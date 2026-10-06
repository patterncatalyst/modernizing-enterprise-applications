---
title: "Communication Styles & Extraction 6 — Order + GraphQL Gateway"
order: 26
part: "Communication & Contracts"
description: "REST, gRPC, async sagas, and GraphQL aggregation side by side; the god order aggregate extracted last into a CQRS write/read split; the strangler completes and the monolith is decommissioned."
duration: 45 minutes
---

Every extraction before this one carved a single slice off `OrderService` and
left the rest of it standing. Chapter 15 removed review. Chapter 17 removed
notification. Chapter 19 removed inventory behind a gRPC seam. Chapter 23
removed payment into a choreographed saga. Chapter 24 removed shipping into
an orchestrated one. Each of those five extractions left `OrderService` with
one fewer collaborator to reach into — and by design: order was always going
to move last, because the only way to extract a service that calls four
others is to remove the four others first. What is left standing at the
start of this chapter is `OrderService` itself, the checkout command, the
four reactions that listen for payment and shipping outcomes, and the
`Order`/`OrderItem`/`Customer` aggregate those reactions mutate. This chapter
extracts that core, reshapes it as a service with a CQRS write/read
split, adds a SmallRye GraphQL gateway that aggregates five services into one
query, and then does something no prior chapter could: it turns off the
monolith. Everything below is running code already committed to this
repository: `examples/07-order-service/` (the CQRS write model, the four
lifted saga reactions, the `order_view` read-model projection),
`examples/08-graphql-gateway/` (the aggregation layer), and
`examples/01-strangler-proxy/` (now a flagless REST edge router). This is
Part 8, "Communication & Contracts" — the part of the book that steps back
from any one extraction and asks how services should talk to each other at
all, with four communication styles now standing side by side in one system
to compare directly.

{% include excalidraw.html file="strangler-completes-final-topology" alt="The final end-state topology. The Camel strangler proxy on :8888 has shed its strangler role and become a permanent REST and GraphQL edge router: the content-based routing collapses to straight per-context forwarding, with every strangler.*.enabled cutover flag retired. One client entry point reaches all six extracted Quarkus services — review :8081, notification :8083, inventory :8084/:9004, payment :8085, order :8087, shipping :8088 — plus the GraphQL gateway at :8090, itself a pure aggregation layer over five of those six. The Spring monolith at :8080 stands alone, unwired, removed from the running topology but kept frozen in the repository as the golden-baseline referent the contract suite was built from." caption="Figure 26.1 — The final topology: six independently deployable services, a GraphQL gateway, a flagless edge router, and a frozen, unwired monolith" %}

## Why order had to go last

Chapter 22 named this project's central consistency smell and gave it a
trajectory without curing it: one in-process `@Transactional` spanning order,
inventory, payment, and shipping, held together by Postgres's own rollback
rather than any explicit design. `OrderService` was also, at the same time,
this project's god service — the single point that reached directly into
every other context's services, repositories, and entities rather than
treating them as independently deployable collaborators reached over a
stable contract. Both smells live on the same class, and both required the
same precondition before they could be cured: every other context had to
leave first. `examples/00-monolith`'s `SMELLS.md` named the sequencing
directly, entry by entry, as each extraction closed off one more direct
dependency — review's removal, then notification's, then inventory's, then
payment's, then shipping's — until this chapter's entry was the only one left
with something to cure.

Five architectural decisions were confirmed before any of this chapter's code
existed, because this is the one extraction whose planning questions do not
have an obvious prior-chapter answer to reuse. The CQRS shape is a same-
database, two-table split — a denormalized `order_view` projected from
lifecycle events inside the order service's own Postgres schema — rejected in
favor of a separate read datastore, which would have introduced
infrastructure this teaching system does not need to demonstrate the
pattern. The GraphQL gateway is additive, with its own front door alongside
the edge router, rejected in favor of an Apollo-style federation router for
the same reason. The monolith's end state is fully decommissioned but frozen,
not deleted, so the book's "before" picture survives. The equivalence suite
converts to a contract suite once there is no monolith left to be equivalent
to. And reversibility is proven one last time before the one decommission
step that cannot be undone. Each of those calls has a rejected alternative on
record, confirmed the same sitting the questions were raised — the same
discipline every earlier extraction's planning phase used, applied here to
five decisions instead of one.

## The CQRS write model: `placeOrder` as the command handler

The clearest way to see what moved is to put the god-service version of
`placeOrder` next to the version that replaced it. The original spans five
contexts in one method, inside one transaction a single database commits or
rolls back as an indivisible unit:

{% include codetabs.html langs="Before — the god-service placeOrder, five contexts, one transaction (frozen on reference/monolith-before)|After — the CQRS command handler, one context, its own outbox (current)" %}

```java
// reference/monolith-before, order/OrderService.java#placeOrder
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    Customer customer = customerRepository.findById(command.customerId())...
    Order order = new Order(customer, command.shippingAddress());
    for (OrderCreate.Line line : command.items()) {
        InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
        inventoryService.reserve(line.sku(), line.quantity());
        order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
    }
    order = orderRepository.save(order);
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    order.confirm();
    shippingService.dispatch(order, order.getShippingAddress());
    notificationService.sendOrderConfirmation(customer, order);
    return toDto(order);
}
```

```java
// examples/07-order-service/.../OrderService.java#placeOrder — current
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    Customer customer = customerRepository.findByIdOptional(command.customerId())...
    Order order = new Order(customer.getId(), customer.getEmail(), command.shippingAddress());
    try {
        for (OrderCreate.Line line : command.items()) {
            // reserve + snapshot sku/name/price over gRPC, unchanged since ch.19
        }
        orderRepository.persist(order);
        orderViewProjector.project(order, null, null);       // CQRS read-model row, same transaction
        writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
        return toDto(order);
    } catch (RuntimeException ex) {
        compensateRemoteReservations(remoteReservations);
        throw ex;
    }
}
```

Four direct calls disappeared from the method body: `inventoryService` became
a gRPC client back in Chapter 19, and `paymentService.charge`,
`order.confirm()`, `shippingService.dispatch`, and
`notificationService.sendOrderConfirmation` are simply gone, not moved
elsewhere in this class. What remains is one context's own work: validate the
customer, reserve stock over gRPC, persist the order `PENDING`, and write
`order.placed` to this service's own transactional outbox — all inside the
same `@Transactional` boundary, so the order row and the outbox row commit or
roll back together, with no dual-write and no second datastore. This is the
CQRS **write model** — the command side, the only path that ever mutates the
`Order` aggregate. `OrderOutboxRelay`'s separate scheduled poll then
publishes the committed row to Kafka asynchronously, replacing the
monolith's own outbox relay as the external producer of this topic. The
four saga reactions that used to live on the monolith's `OrderSagaListener`
move into this same service, in shape unchanged: `onPaymentCaptured` and
`onShipmentDispatched` advance the aggregate toward `CONFIRMED`;
`onPaymentDeclined` and `onShipmentFailed` each guard on their one reachable
pre-transition status and issue the compensating gRPC `Release` for every
reserved sku. Because `onPaymentDeclined` fires only from `PENDING` and
`onShipmentFailed` fires only from `AWAITING_SHIPMENT` — and an order reaches
`AWAITING_SHIPMENT` only by surviving a captured payment — the two
compensations are mutually exclusive by construction: neither reaction can
ever release the same reservation twice, and neither can collide with
`placeOrder`'s own pre-handoff catch, which only fires for a checkout that
never reached the point of committing `order.placed` at all.

{% include excalidraw.html file="order-cqrs-split" alt="The CQRS write/read split inside the order service. Top band, the command path: POST /api/orders drives placeOrder, which validates the customer, reserves every line over synchronous gRPC, persists the Order/OrderItem write-model aggregate PENDING, and writes order.placed to the service's own transactional outbox, all in one transaction; OrderOutboxRelay later relays the row to Kafka. Middle band, the projection: placeOrder itself, and each of OrderSagaListener's four lifecycle reactions, call the same OrderViewProjector inside their own transaction to upsert the denormalized order_view read model — two distinct tables in one schema, never joined at read time. Bottom band, the query path: getById and listAll read exclusively from the order_view repository; the write-model repository is never consulted for a read, and the GraphQL gateway's order(id) query reaches this same REST read path like any other client." caption="Figure 26.2 — The CQRS split: one command path, one projection, one query path that never touches the write model" %}

Lifting the four reactions unmodified in shape — not re-derived from scratch
— was the point: their idempotency guards, their at-most-once compensation,
and the mutual exclusion between the two failure paths were already proven
correct on the monolith, and the risk in moving them was losing one of those
three properties in translation. `OrderSagaListenerTest`'s own matrix proves
each property survived the move, including the compensation tests, run with
at least two skus each rather than a single-item case that could hide an
off-by-one. Startup and memory tell the same story a lift of four live
consumers should tell: Phase A (the Spring-compatibility lift, no messaging
yet) started in 1.721 seconds at roughly 308 MB RSS with 17 Quarkus features
installed; Phase B, with the idiomatic CQRS core, four live Kafka consumers,
one producer, and a scheduled outbox relay, started in 2.050 seconds at
roughly 407 MB with 18 features. Startup is about 19% slower and RSS about
32% higher — proportionally the largest jump of any extraction's Phase A to
B in this book, because this is the first service to run four independent
consumer connections at once rather than one trigger consumer. That is the
cost of four live reactions, not a regression to chase down.

## The read model: `order_view`, and what "read-after-write" actually promises

CQRS separates the model that accepts writes from the model that serves
reads, and the two that matter for this chapter are not a write database and
a read database — they are two tables in the same `order_service` Postgres
schema. `order_view` is a denormalized projection: one row per order,
carrying the status, total, shipping address, and a JSON summary of line
items, kept in sync by `OrderViewProjector`, a single component called from
five places — `placeOrder` itself, for the initial `PENDING` row, and each of
the four saga reactions, for every subsequent transition. Every one of those
five calls runs inside the transaction that already holds the aggregate
write, so the `Order` row and its `order_view` row commit or roll back
together. There is no dual-write window, because there is no second write
path: the projection is not a consumer of its own events, it is a direct
method call made from inside the same unit of work.

`OrderService#getById` and `#listAll` read exclusively from `order_view` —
`OrderRepository`, the write-model repository, appears exactly once in the
whole class, inside `placeOrder` itself, and `OrderResource` never references
it at all. That single fact is what makes the read side of CQRS a teaching
example rather than an assertion: a read that silently fell back to the
aggregate whenever the projection lagged would hide a broken projection
completely, and the suite would stay green while the pattern did nothing.
The project closed that loophole with a negative check rather than a comment.
`OrderViewProjectionDisabledTest` suppresses the projector, sends
`payment.captured`, confirms directly against the write-model repository
that the aggregate reached `AWAITING_SHIPMENT` — ruling out "the
event was never processed" as an innocent explanation — and then asserts
that a `GET` against the same order stays stuck at `PENDING` for the
duration of a held window. The same check was repeated live during cutover
against a real order: with the projection skipped on one transition, the
write-model table showed order 406 as `CONFIRMED` while `order_view` showed
it as `AWAITING_SHIPMENT`, and twenty straight polls of `GET /api/orders/406`
returned the stale value. Reverting the edit and re-running produced 21 of 21
assertions green. That gap, shown and then closed, is the proof that reads
come from the projection and nowhere else.

What this buys, precisely, is read-after-write consistency *within* the
order service: because the projection commits in the same transaction as the
write, a `GET` issued immediately after `placeOrder` returns sees the new
`PENDING` row with no delay and no polling. What it does not buy is
consistency across the saga. The transition to `CONFIRMED` is still only as
fresh as the Kafka event that eventually triggers it — a client polling
`GET /api/orders/{id}` moments after checkout can and will observe
`PENDING`, then `AWAITING_SHIPMENT`, then `CONFIRMED`, each one a real
intermediate state, not a glitch. `OrderViewProjector`'s own documentation
draws that line explicitly, because claiming whole-system strong consistency
from a same-transaction projection would overstate what the pattern
delivers. CQRS here closes the gap between the write and its own service's
read; it does nothing to close the gap between one service's write and
another service's reaction to it, which remains exactly the eventually
consistent world Chapters 23 and 24 already established.

## Four communication styles, one system

Part 8 opens by stepping back from any single extraction to ask how services
should talk to each other, and this chapter is where every style this book
has used ends up standing side by side in one running topology. REST is the
contract between a client and any one service's own read/write surface —
`GET /api/orders/{id}`, `POST /api/payments`, every endpoint this book has
built since Chapter 1 — a request-reply exchange where the caller waits for
an answer before moving on, well suited to anything a user is directly
waiting on. gRPC is the choice this book made for inventory's reservation
calls, where a checkout cannot proceed without knowing stock was actually
decremented, and strong typing across the wire matters more than
human-readable payloads — order-service still calls `Reserve`/`Release`/
`GetStock` exactly the way it did the moment Chapter 19 cut that seam.
Asynchronous events are the backbone of every saga in this book: Chapter 23's
choreography has no coordinator anywhere, just independent reactions wired
by topic name; Chapter 24's orchestration has one explicit coordinator that
names its own steps; this chapter's four lifted reactions are choreography
again, now running inside the order service itself rather than the
monolith. None of the first three styles fit a fourth problem this chapter
introduces: a client that wants one order's data plus its payment, its
shipment, its line items' live stock, and their reviews, in one round trip,
without calling five endpoints and stitching the responses by hand. That is
what GraphQL aggregation is for, and it is the one style this book has not
used until now.

## The GraphQL aggregation gateway

`examples/08-graphql-gateway` owns no data of its own. It answers one query
— `order(id)` — by calling the five services that each own a piece of the
answer: order-service over REST for the order itself, payment-service and
shipping-service over REST for the payment and shipment history, review-
service over REST for each line item's reviews, and inventory-service over
gRPC for each line item's live stock level. `GatewayApi` is the only
resolver class, and every field beyond the root query is a `@Source`
resolver — a MicroProfile GraphQL method that runs only when a client
actually selects that field, so a query that never asks for `reviews` never
calls review-service at all:

```java
// examples/08-graphql-gateway/.../GatewayApi.java
@Query("order")
public OrderView order(@Id @Name("id") String id) {
    try {
        OrderDto dto = orderRestClient.getById(id);
        return OrderView.from(dto);
    } catch (WebApplicationException e) {
        if (e.getResponse().getStatus() == 404) {
            throw new OrderNotFoundException("order " + id + " not found");
        }
        throw e;
    }
}

public StockView stock(@Source OrderItemView item) {
    StockReply reply = inventoryClient.withDeadlineAfter(3, TimeUnit.SECONDS)
            .getStock(GetStockRequest.newBuilder().setStockKeepingUnit(item.sku()).build());
    return new StockView(reply.getStockKeepingUnit(), reply.getOnHandQty(), reply.getAvailable());
}
```

The unknown-id path is the gateway's error-envelope contract: a 404 from
order-service is translated into a thrown `OrderNotFoundException` rather
than a silently returned `null`, so a client sees `data.order: null`
alongside a populated `errors[]` array — a response a caller can distinguish
from "the order has no interesting fields" rather than one that looks
identical to it. An aggregation layer that fans a single request out across
five backends is also a layer that could amplify a hostile request into five
times the load, or into a query nested deep enough to explode combinatorially
across joins the client never has to see — so query depth and complexity are
bounded in configuration, not left to the resolver code to enforce case by
case, and every downstream target — the four REST base URLs, the inventory
gRPC host and port — is fixed operator configuration, never a value the
incoming request can influence. `GatewayDepthComplexityLimitTest` proves the
bound is actually wired, under a deliberately small test-only budget, rather
than resting on the configuration file alone.

{% include excalidraw.html file="graphql-aggregation-gateway" alt="One order(id) GraphQL query, fanned out by the gateway on :8090 to five already-extracted services. GatewayApi.order(id) resolves the order itself over REST from order-service at :8087. The fields nested on that result are each a lazy @Source resolver, evaluated only when selected: payments to payment service over REST at :8085, shipments to shipping service over REST at :8088, and per line item, reviews to review service over REST at :8081 and stock to inventory service over gRPC at :9004 — the one non-REST hop. The gateway holds no state and owns no data; every field is fetched live from the service that owns it, never cached locally, and every downstream target is fixed operator configuration with query depth and complexity bounded." caption="Figure 26.3 — One query, five backends, no local data: the gateway as a pure aggregation layer" %}

During cutover, a single query against a real order stitched all four other
extracted services' data in one round trip: the order itself from
order-service, a captured payment from payment-service, a cancelled shipment
from shipping-service, and a line item's live stock level from
inventory-service over gRPC — alongside ninety-five reviews for that sku from
review-service. That is the read-aggregation surface this gateway exists to
demonstrate: one client request, five backends resolved, zero data owned
locally.

## The strangler completes

Every extraction before this one left at least one `strangler.*.enabled`
flag in the proxy, and at least one path still forwarding to the monolith by
default. This chapter's cutover flips the sixth and last flag,
`strangler.order.enabled`, and because the whole order context — command and
every read — moves together, it is the first flag in this project to route
both at once rather than splitting a read surface from a write surface. Once
it flips, `examples/01-strangler-proxy`'s routing logic has nowhere left to
be conditional: six `.choice()` branches plus a monolith default collapse
into one unconditional forwarding rule per context, with an unmatched path
returning `404` directly rather than falling through to a monolith that has
nothing left to serve it. `EdgeRouterRoutingTest` proves the collapsed route
against the actual compiled class — eight tests, one unconditional routing
assertion per context plus the fallback — not a description of what the
route is supposed to do.

The monolith's end state answers a question every reader of a strangler fig
pattern eventually asks: what happens to the host tree? This project's
answer is frozen, not deleted. `examples/00-monolith` keeps building after
decommission, but as a shell — three configuration-only classes and the
original Flyway migrations, with its own smoke test now asserting `404` on
every context's old endpoint rather than a working response. The complete,
runnable "before" state — every context still live, no extracted services
required — is preserved on the `reference/monolith-before` branch, tagged
`v0-monolith`; a `stage/01-review-extracted` through
`stage/06-order-extracted` sequence tags each extraction's completed state in
turn, so a reader can check out any point in this book's six-extraction
sequence and run it. Freezing the monolith rather than deleting it is what
makes the decommission survivable as a teaching artifact: the book's "before"
picture does not disappear the moment its "after" picture is complete, and a
reader who wants to see what checkout looked like with five contexts sharing
one transaction can still build and run that exact code.

## From equivalence to contract: proving the suite still means something

Every suite run before this chapter asserted the same claim: the extracted
service behaves like the monolith did. That claim needs a monolith to
compare against, and after this chapter's decommission step there is no
monolith left in the running topology to compare against — it still exists
on `reference/monolith-before`, but a CI job cannot boot a second full
instance of the system it is supposed to be replacing just to keep asserting
"replacement matches original" forever. The plan's own answer is to freeze
one last monolith-anchored run as the golden baseline, then re-designate the
suite's own self-description from an equivalence check to a contract check —
without touching a single assertion. The decommission commit states the
result in one line that is the whole argument for why the conversion is not
vacuous: "not one assertion or negative check changed." The strict terminal
checks — `CONFIRMED`, `409`, `PAYMENT_DECLINED` plus net-zero stock,
`SHIPPING_FAILED` plus net-zero stock, the read-model and GraphQL response
shapes — are the same checks, word for word, that ran against the live
monolith. So are the three negative checks this chapter's own cutover
exercised: disable the saga consumer's reaction and Scenario 1 goes red;
disable the read-model projection on one transition and a direct database
comparison shows the write model and the read model diverging while `GET`
serves the stale value; disable the compensating `Release` and stock stays
decremented instead of returning to its starting value. A suite that would
pass identically whether or not the system underneath it actually worked is
a suite that proves nothing — these three checks, preserved and still
running, are what keeps the contract suite from becoming exactly that.

Cutover itself needed more than a green run before any of those checks could
be trusted, because this was the first time all six extracted services, the
gateway, the monolith, and the proxy ran together against a Postgres and
Kafka instance that had accumulated months of this book's own dev and test
history. Four environmental findings surfaced before the real evidence could
begin, none of them a defect in the order service's own logic. The order
service's own `customers` table starts empty by design — no backfill from
the monolith — so the suite's fixtures needed one seed row. A brand-new
Kafka consumer group had to drain hundreds of historical messages
accumulated on the shared broker since Chapters 23 and 24 before its first
real checkout settled within budget. The order service's own id sequence,
starting fresh at 1, collided with a historical order-id high-water mark of
322 shared across payment-service's and shipping-service's own idempotency
guards — one checkout landed on an id an old payment row already claimed and
stayed permanently stuck `PENDING`, isolated and proven not a saga defect
because every order placed moments later behaved correctly. And
shipping-service's own base URL for reading order data still defaulted to
the monolith, which meant that once the order flag cut over, shipping's
enrichment step silently fell back to a stub address instead of failing —
the one finding that would have produced a quietly false-green suite if it
had gone unnoticed. Each was isolated, fixed, and documented before the
cutover evidence that follows was trusted.

With the topology healthy, the final cutover run produced 167 assertions
with one failure — a documented, pre-existing content-type string mismatch
in the GraphQL contract item, unrelated to any of this chapter's claims — in
16.6 seconds. Scenario 3's payment-decline and Scenario 4's shipping-failure
each converged to net-zero stock with explicit numbers: 490 to 489 back to
490, in both cases. A three-way proof confirmed the proxy reaches
the order service rather than a stale monolith copy: the same order queried
through the proxy and directly against the order service returned byte-
identical JSON, while the same id queried directly against the monolith
returned a plain `404` — the strongest version of this proof any chapter in
this book has produced, because here the monolith cannot even answer with
stale data. It never minted the order in the first place.

## Reversibility, one last time, then the one move that cannot be undone

Every cutover before this one could be undone by flipping a flag back to a
live monolith. This chapter's cutover is the last point in the book where
that is still true, and the plan's own risk analysis names exactly why
proving it mattered here more than anywhere else: once the decommission step
runs, there is nothing left to revert to if the extracted order service
turns out to be subtly wrong. So reversibility was proven exhaustively
before that step, not assumed. With `strangler.order.enabled` flipped back
to its default and shipping-service restarted without its cutover override,
a direct checkout confirmed the monolith served checkout again — `202`,
`PENDING`, a bounded-wait climb to `CONFIRMED` — and a direct query against
the order service confirmed it had no record of that order at all. The full
suite re-ran at 164 assertions with 8 failed, every failure isolated to one
GraphQL contract item whose own test fixture assumed an order placed in the
current run — the gateway, built with no monolith client at all by design,
correctly could not see an order the monolith had just placed, and correctly
could still resolve a real order-service order from the earlier cutover run
on request. That gap is the gateway behaving exactly as its additive design
promises, not a defect in it.

Only after that proof did the decommission run — the one step in this
project's six extractions with no flag to flip back. It deleted the
monolith's order context, its copy of the inventory gRPC client, and its
outbox relay — thirty-one files — while leaving the monolith shell building
and its smoke test green at six assertions for six now-retired endpoints.
The proxy's six cutover flags and its monolith base-url configuration were
removed along with the code path they used to choose between, because there
was nothing left for them to choose. Every one of this project's six
extractions is now independently deployable, and the sixth one's own
removal from the monolith is what finally closes the smell every one of the
other five had been chipping away at since Chapter 15.

## ACID gives way to ACD, for every context that's left

Chapter 22 described ACD — atomic, consistent, durable, with isolation
dropped — as the ceiling a saga replaces a single transaction's guarantees
with: each local step still commits atomically in its own database, but
nothing holds the whole cross-service sequence under one set of locks the
way Postgres used to. Chapters 23 and 24 each cashed in one slice of that
story, for payment and for shipping respectively, and each one's own ledger
entry named the same two contexts still carrying the debt: shipping and
order, then just order. This chapter closes the last slice. The order
service's one remaining `@Transactional` boundary spans exactly its own
schema — the order row, its outbox row, and its read-model projection — and
nothing else. There is no longer a single in-process transaction anywhere in
this system, monolith or extracted, that reaches across more than one
bounded context's own data. Every cross-context consistency guarantee left
in this book is a saga with an explicit compensation, proven by a test that
shows what breaks when the compensation is removed — never an implicit
rollback a database used to provide as a side effect.

> **ADLC in Action** — This is the sixth and final extraction, and it ran
> the same Frame → Map → Plan → Generate → Verify → Operate → Reconcile loop
> every prior chapter demonstrated, at its hardest shape yet. Frame brought
> five architectural decisions to the user in one sitting — same-database
> CQRS over a separate read datastore, an additive GraphQL gateway with its
> own front door over a federation router, a frozen-not-deleted monolith
> over deletion, an equivalence-to-contract conversion over a suite anchored
> to a system about to disappear, and reversibility proven one more time
> before the one irreversible move — all confirmed before a line of code
> existed. Map named six reasons this extraction outranks the other five:
> it is the god aggregate and the hub every other extraction left standing;
> after decommission there is no monolith left to be equivalent to; there is
> no fallback to revert to once that happens; two named smells finally had
> to be cured with evidence, not just description; two new teaching surfaces
> — CQRS and GraphQL — land in the same step; and the strangler itself runs
> out of host tree to strangle. Generate produced the two-phase order
> service (an own-schema lift, then the idiomatic CQRS write model, the four
> lifted saga reactions, and their own outbox), the `order_view` projection,
> the GraphQL gateway, and the sixth and final strangler flag. Verify is
> this chapter's sharpest proof load: four environmental findings surfaced
> and closed before any cutover evidence could be trusted, real net-zero
> numbers (490 to 489 back to 490, twice), the strongest proxy-reaches-the-
> service proof in the book (the monolith returns a plain 404, having never
> minted the order at all), and three negative checks — saga consumer
> disabled, projection disabled, compensation disabled — each shown red,
> then reverted green. Operate is the final flag flip, reversibility proven
> once more, and then the single irreversible decommission: thirty-one
> files removed from the monolith, the suite's own self-description
> re-designated from equivalence to contract with not one assertion changed,
> and the complete pre-decommission system preserved on
> `reference/monolith-before` and the `stage/01` through `stage/06-order-
> extracted` tags. Reconcile is `SMELLS.md` striking through the god service
> and the last clause of the cross-context transaction for good — ACID gives
> way to ACD for every context this book extracted, with nothing left to
> extract.

## What you learned

- **CQRS splits the model that accepts writes from the model that serves
  reads**, and the simplest version of that split is two tables in one
  schema, kept in sync by a projection that runs inside the same transaction
  as the write it reacts to — not a second datastore, not event sourcing,
  and not a separate team of infrastructure to operate.
- **Read-after-write consistency is a within-service promise, not a
  whole-system one** — a same-transaction projection makes a service's own
  `GET` see its own `POST` immediately, while the cross-service outcome that
  transition represents is still only as fresh as the event that eventually
  triggers it.
- **A read path that could silently fall back to the write model would hide
  a broken projection completely** — the only proof CQRS is doing real work
  is a negative check that disables the projection and watches the read
  stay stale while the write moves on.
- **REST, gRPC, asynchronous sagas, and GraphQL aggregation solve four
  different problems**, not four versions of the same problem — a synchronous
  request a caller waits on, a strongly typed call on a hot path, a sequence
  of independent reactions with no central coordinator, and one client
  query that needs five services' data in a single round trip.
- **An aggregation gateway that owns no data is still an attack surface** —
  bounding query depth and complexity, and fixing every downstream target in
  configuration rather than deriving it from the request, are what keep a
  stitching layer from becoming an amplifier.
- **Freezing a decommissioned system, rather than deleting it, is what lets
  an equivalence suite convert into a contract suite without going vacuous**
  — the frozen baseline is the referent the contract was built from, the
  negative checks are what prove the contract still tests something real,
  and a system with no fallback left gets the most reversibility testing of
  any cutover in the book, precisely because it gets the least margin for
  error afterward.

This is the last extraction this book makes. Six bounded contexts are now
independently deployable services, the strangler proxy is a permanent edge
router with no flags left to flip, and a GraphQL gateway stands alongside it
aggregating five of those six into one query surface. Chapter 27 turns from
what moved to what it moved onto — the Quarkus and MicroProfile building
blocks (configuration, fault tolerance, health, metrics, the REST client)
that every one of these six services has been using since the moment each
one was scaffolded, named and explained in their own right for the first
time.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is real, runnable code and real, dated evidence
already in this repository: `examples/07-order-service/` (`OrderService`,
`OrderSagaListener`, `OrderViewProjector`, and `MIGRATION.md`'s measured
Phase A→B record — 1.721s/~308MB/17 features to 2.050s/~407MB/18 features),
`examples/07-order-service/CUTOVER.md` (the four environmental findings, the
cutover and reversibility runs, and the three negative checks),
`examples/08-graphql-gateway/` (`GatewayApi` and its README's stitching
shape, error envelope, and security bounds), `examples/01-strangler-proxy/`
(`StranglerProxyRoute.java`'s collapsed edge-router routing and
`EdgeRouterRoutingTest`), `examples/00-monolith/SMELLS.md` (SMELL #2 and
SMELL #3 struck through) and `README.md` (the `reference/monolith-before`
branch and the `v0-monolith`/`stage/01`…`stage/06-order-extracted` tags),
and `.github/workflows/code-ci.yml`'s `order-gateway-contract-gate` job.
Tests and demos actually run, per the captured records above: the order
service's own test suite — `OrderResourceTest` (6), `OrderServiceTest` (6),
`OrderSagaListenerTest` (15), `OrderSagaListenerIntegrationTest` (3),
`CheckoutOutboxTest` (2), `OrderViewProjectionTest` (7), and
`OrderViewProjectionDisabledTest` (1), 40 tests total, 0 failures; the
gateway's own test suite — `GatewayApiTest` (3) and
`GatewayDepthComplexityLimitTest` (1), 4 tests total, 0 failures; the final
cutover run through the proxy at 167 assertions, 1 failure (a documented,
pre-existing GraphQL content-type string mismatch, unrelated to any claim
made above), and a subsequent full-suite sanity run at 171 assertions, 1
failure, the same single gap; the explicit net-zero stock traces for both
Scenario 3 and Scenario 4 (490→489→490 in each case); the reversibility
re-run at 164 assertions with 8 failed, every failure isolated to one GraphQL
contract item's own test-fixture assumption, not a defect; and all three
negative checks — saga-consumer-disabled (RED, order stuck
`AWAITING_SHIPMENT`; reverted, GREEN 19/19), read-model-projection-disabled
(RED, write and read diverging on direct database inspection; reverted,
GREEN 21/21), and compensating-`Release`-disabled (RED, stock stuck at
494→493→493; reverted, GREEN 14/14, net-zero reconfirmed at 492→491→492).
Dev Services-backed tests ran throughout via `./mvnw -q test` for both
services. Native image was not attempted for either service, the same
time-boxed choice payment-service's and shipping-service's own Phase B steps
made. The captured outputs quoted above are taken verbatim from
`CUTOVER.md`, `MIGRATION.md`, `SMELLS.md`, and this project's own
pre-captured ADLC trace document, rather than re-run live here, per this
project's standing discipline of citing dated evidence over re-running it
for every chapter. What a reader's own run should confirm independently:
the exact startup/RSS figures in the Phase A→B table will differ on
different hardware even if the qualitative heavier-not-regressed reading
holds; the order-id and Kafka-backlog figures in the four environmental
findings are specific to one evidence session on one long-lived shared
Postgres/Kafka instance and will be different, and larger, on any later
run; and the GitHub Actions run of `order-gateway-contract-gate` itself was
validated locally against the same topology before being trusted in
Actions, per the job's own committed comments — a reader watching the real
workflow run is the remaining independent confirmation this chapter does
not itself supply.*
