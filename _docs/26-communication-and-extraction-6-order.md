---
title: "Communication Styles & Extraction 6: Order + GraphQL Gateway"
order: 26
part: "Communication & Contracts"
description: "REST, gRPC, async sagas and GraphQL aggregation side by side; the order aggregate extracted last into a CQRS write/read split; the strangler completes and the monolith is decommissioned."
duration: 45 minutes
---

## Learning objectives

By the end of this chapter you can:

- Explain why order is extracted last and name the five decisions that shape the extraction.
- Trace what disappeared from `placeOrder` (direct calls to payment, shipping, notification) and what remains (gRPC reservation, persist, outbox write), and why what remains is the CQRS write model.
- Explain the same-schema `order_view` projection, how `OrderViewProjector` keeps it in the same transaction as every write, and what read-after-write consistency it does and does not buy.
- Prove with a negative check that reads come only from the projection and never fall back to the write model.
- State the distinct problem REST, gRPC, asynchronous sagas and GraphQL aggregation each solve.
- Explain how `examples/08-graphql-gateway` aggregates five services through lazy `@Source` resolvers while owning no data, and why bounded query depth and fixed downstream targets matter.
- Explain why freezing the decommissioned monolith, not deleting it, lets the equivalence suite become a contract suite with zero assertions changed, and why reversibility is proven right before the one irreversible step.

## Why this matters

Each earlier extraction carved one slice off `OrderService` and left the rest standing: review, notification, inventory behind a gRPC seam, payment into a choreographed saga, shipping into an orchestrated one. Order goes last because the only way to extract a service that calls four others is to remove the four others first. What remains is `OrderService` itself: the checkout command, the four reactions to payment and shipping outcomes, and the `Order`/`OrderItem`/`Customer` aggregate those reactions mutate.

This extraction moves that core into a service with a CQRS write/read split, adds a SmallRye GraphQL gateway that aggregates five services into one query, and turns off the monolith. The code is in `examples/07-order-service/` (write model, the four lifted saga reactions, the `order_view` projection), `examples/08-graphql-gateway/` (aggregation) and `examples/01-strangler-proxy/` (now a flagless REST edge router).

{% include excalidraw.html file="strangler-completes-final-topology" alt="The final end-state topology. The Camel strangler proxy on :8888 has shed its strangler role and become a permanent REST and GraphQL edge router: the content-based routing collapses to straight per-context forwarding, with every strangler.*.enabled cutover flag retired. One client entry point reaches all six extracted Quarkus services: review :8081, notification :8083, inventory :8084/:9004, payment :8085, order :8087, shipping :8088, plus the GraphQL gateway at :8090, itself a pure aggregation layer over five of those six. The Spring monolith at :8080 stands alone, unwired, removed from the running topology but kept frozen in the repository as the golden-baseline referent the contract suite was built from." caption="Figure 26.1: The final topology: six independently deployable services, a GraphQL gateway, a flagless edge router, and a frozen, unwired monolith" %}

## Why order goes last

Two smells live on `OrderService`. It holds `SMELL[ch.22]`, the one in-process `@Transactional` that spans order, inventory, payment and shipping, held together by Postgres rollback rather than explicit design. It is also the god service, reaching directly into every other context's services, repositories and entities. Both need the same precondition: every other context leaves first. `SMELLS.md` in `examples/00-monolith` records the sequence entry by entry as each extraction closed one more direct dependency.

Five decisions shape the extraction, each with a rejected alternative on record:

| Decision | Chosen | Rejected |
|---|---|---|
| CQRS shape | Two tables in the order service's own Postgres schema, `order_view` projected from lifecycle events | A separate read datastore, which adds infrastructure the pattern doesn't need |
| GraphQL gateway | Additive, with its own front door beside the edge router | Apollo-style federation router |
| Monolith end state | Decommissioned but frozen | Deleted |
| Test suite | Equivalence converts to contract once no monolith remains to match | Staying anchored to a system about to disappear |
| Last cutover | Reversibility proven once more before the irreversible step | Assuming it |

## Pattern: CQRS

### The write model: `placeOrder` as the command handler

The god-service `placeOrder` spans five contexts in one method, inside one transaction a single database commits or rolls back:

{% include codetabs.html langs="Before: the god-service placeOrder, five contexts, one transaction (frozen on reference/monolith-before)|After: the CQRS command handler, one context, its own outbox (current)" %}

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
// examples/07-order-service/.../OrderService.java#placeOrder, current
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    Customer customer = customerRepository.findByIdOptional(command.customerId())...
    Order order = new Order(customer.getId(), customer.getEmail(), command.shippingAddress());
    try {
        for (OrderCreate.Line line : command.items()) {
            // reserve + snapshot sku/name/price over gRPC, unchanged since the inventory extraction
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

Four direct calls disappeared. `inventoryService` became a gRPC client in the inventory extraction; `paymentService.charge`, `order.confirm()`, `shippingService.dispatch` and `notificationService.sendOrderConfirmation` are gone from this class. What remains is one context's work: validate the customer, reserve stock over gRPC, persist the order `PENDING`, and write `order.placed` to the service's own transactional outbox, all inside one `@Transactional` boundary. The order row and the outbox row commit or roll back together, with no dual write and no second datastore. This is the CQRS write model, the only path that mutates the `Order` aggregate. `OrderOutboxRelay` publishes the committed row to Kafka on a scheduled poll, replacing the monolith's relay as the producer of that topic.

The four saga reactions from the monolith's `OrderSagaListener` move into this service in unchanged shape:

- `onPaymentCaptured` and `onShipmentDispatched` advance the aggregate toward `CONFIRMED`.
- `onPaymentDeclined` and `onShipmentFailed` each guard on their one reachable pre-transition status and issue the compensating gRPC `Release` for every reserved sku.

`onPaymentDeclined` fires only from `PENDING`, `onShipmentFailed` only from `AWAITING_SHIPMENT`, and an order reaches `AWAITING_SHIPMENT` only by surviving a captured payment. The two compensations are mutually exclusive by construction: neither can release the same reservation twice, and neither collides with the pre-handoff catch in `placeOrder`, which fires only for a checkout that never committed `order.placed`. The reactions were lifted, not re-derived: their idempotency guards, at-most-once compensation and mutual exclusion were already proven on the monolith.

Four live consumers show in startup and memory. Phase A (the Spring-compatibility lift, no messaging yet) started in 1.721 seconds at about 308 MB RSS with 17 Quarkus features. Phase B, the idiomatic CQRS core with four Kafka consumers, one producer and a scheduled outbox relay, started in 2.050 seconds at about 407 MB with 18 features: about 19% slower startup and 32% higher RSS, the largest Phase A to Phase B jump of any extraction, because this is the first service to hold four consumer connections instead of one.

{% include excalidraw.html file="order-cqrs-split" alt="The CQRS write/read split inside the order service. Top band, the command path: POST /api/orders drives placeOrder, which validates the customer, reserves every line over synchronous gRPC, persists the Order/OrderItem write-model aggregate PENDING, and writes order.placed to the service's own transactional outbox, all in one transaction; OrderOutboxRelay later relays the row to Kafka. Middle band, the projection: placeOrder itself, and each of OrderSagaListener's four lifecycle reactions, call the same OrderViewProjector inside their own transaction to upsert the denormalized order_view read model: two distinct tables in one schema, never joined at read time. Bottom band, the query path: getById and listAll read exclusively from the order_view repository; the write-model repository is never consulted for a read, and the GraphQL gateway's order(id) query reaches this same REST read path like any other client." caption="Figure 26.2: The CQRS split: one command path, one projection, one query path that never touches the write model" %}

### The read model: `order_view`

The two models are not a write database and a read database but two tables in the same `order_service` Postgres schema. `order_view` is a denormalized projection, one row per order with status, total, shipping address and a JSON summary of line items, maintained by `OrderViewProjector`. It is called from five places: `placeOrder` for the initial `PENDING` row and each of the four saga reactions for every later transition. Each call runs inside the transaction that holds the aggregate write, so the `Order` row and its `order_view` row commit or roll back together. There is no dual-write window because there is no second write path: the projection is a direct method call inside the same unit of work, not a consumer of its own events.

`OrderService#getById` and `#listAll` read only from `order_view`. `OrderRepository` appears once in the class, inside `placeOrder`, and `OrderResource` never references it. A read that fell back to the aggregate would hide a broken projection behind green checks, so a negative check closes that loophole. With the projector suppressed and `payment.captured` sent, the write-model repository shows the aggregate at `AWAITING_SHIPMENT` (so the event was processed), while a `GET` for the same order stays at `PENDING` for the whole held window. The live cutover repeated this: with the projection skipped on one transition, the write-model table showed order 406 `CONFIRMED` while `order_view` showed `AWAITING_SHIPMENT`, and twenty straight polls of `GET /api/orders/406` returned the stale value. Reverting the edit produced 21 of 21 assertions green.

What this buys is read-after-write consistency within the order service: a `GET` right after `placeOrder` sees the new `PENDING` row with no delay and no polling. It does not buy consistency across the saga. A client polling `GET /api/orders/{id}` after checkout observes `PENDING`, then `AWAITING_SHIPMENT`, then `CONFIRMED`, each a real intermediate state, because the transition is only as fresh as the Kafka event that triggers it. CQRS here closes the gap between a write and its own service's read, not between one service's write and another service's reaction.

## Four communication styles, one system

| Style | Problem it solves | Where it is used |
|---|---|---|
| REST | Request-reply a user is waiting on, one service's own read/write surface | `GET /api/orders/{id}`, `POST /api/payments` |
| gRPC | A call the caller cannot proceed without, where strong typing matters more than readable payloads | Inventory `Reserve`, `Release`, `GetStock` from order-service |
| Asynchronous events | Independent reactions with no caller waiting; choreography has no coordinator, orchestration has one that names its steps | The payment saga (choreographed), the shipping saga (orchestrated), and the four lifted reactions (choreographed again) |
| GraphQL aggregation | One client needs an order plus its payment, shipment, line items' live stock and reviews in one round trip, without five calls stitched by hand | `examples/08-graphql-gateway` |

## Pattern: API Gateway (GraphQL aggregation)

`examples/08-graphql-gateway` owns no data. It answers `order(id)` by calling the services that own each piece: order-service, payment-service, shipping-service and review-service over REST, and inventory-service over gRPC for each line item's live stock. `GatewayApi` is the only resolver class. Every field below the root query is a `@Source` resolver, a MicroProfile GraphQL method that runs only when a client selects that field, so a query that never asks for `reviews` never calls review-service.

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

A 404 from order-service becomes a thrown `OrderNotFoundException`, not a silent `null`, so the client sees `data.order: null` alongside a populated `errors[]` array and can tell a missing order from one with no interesting fields.

A layer that fans one request out across five backends can amplify a hostile request fivefold, or into a query nested deep enough to explode combinatorially across joins the client never sees. Query depth and complexity are bounded in operator configuration, not left to each resolver: `smallrye.graphql.instrumentation.queryDepth=8` against a deepest legitimate query of 3 (`order`, `items`, `stock` or `reviews`), and `queryComplexity=200`, so a recursive or alias-padded query is rejected before any backend is called. Every downstream target (the REST base URLs, the inventory gRPC host and port) is fixed operator configuration that the incoming request cannot influence.

{% include excalidraw.html file="graphql-aggregation-gateway" alt="One order(id) GraphQL query, fanned out by the gateway on :8090 to five already-extracted services. GatewayApi.order(id) resolves the order itself over REST from order-service at :8087. The fields nested on that result are each a lazy @Source resolver, evaluated only when selected: payments to payment service over REST at :8085, shipments to shipping service over REST at :8088, and per line item, reviews to review service over REST at :8081 and stock to inventory service over gRPC at :9004, the one non-REST hop. The gateway holds no state and owns no data; every field is fetched live from the service that owns it, never cached locally, and every downstream target is fixed operator configuration with query depth and complexity bounded." caption="Figure 26.3: One query, five backends, no local data: the gateway as a pure aggregation layer" %}

During cutover, one query against a real order returned the order from order-service, a captured payment, a cancelled shipment, a line item's live stock over gRPC and ninety-five reviews for that sku: one request, five backends, zero data owned locally.

## Pattern: Strangler Fig, completed

Every earlier extraction left a `strangler.*.enabled` flag in the proxy and a default path to the monolith. This cutover flips the sixth and last, `strangler.order.enabled`. The whole order context (command and reads) moves together, so it is the first flag to route both at once. Afterward six `.choice()` branches plus a monolith default collapse into one unconditional forwarding rule per context, and an unmatched path returns `404` directly instead of falling through to a monolith with nothing to serve it.

The monolith is frozen, not deleted. `examples/00-monolith` still builds, as a shell of three configuration-only classes and the original Flyway migrations, and its smoke test now asserts `404` on every context's old endpoint. The complete runnable "before" state, every context live and no extracted services required, is preserved on the `reference/monolith-before` branch, tagged `v0-monolith`. The tags `stage/01-review-extracted` through `stage/06-order-extracted` mark each extraction's completed state, so any point in the sequence can be checked out and run.

## From equivalence to contract

Every earlier run asserted that the extracted service behaves like the monolith. After decommission no monolith runs, and CI cannot boot a second full copy of the replaced system to keep asserting a match. The fix is to freeze one last monolith-anchored run as the golden baseline and re-designate the checks from equivalence to contract without touching an assertion. The record states it in one line: not one assertion or negative check changed. The strict terminal checks (`CONFIRMED`, `409`, `PAYMENT_DECLINED` plus net-zero stock, `SHIPPING_FAILED` plus net-zero stock, the read-model and GraphQL response shapes) are word for word the ones that ran against the live monolith. So are three negative checks:

- Disable the saga consumer's reaction and Scenario 1 goes red.
- Disable the read-model projection on one transition and the write and read models diverge while `GET` serves the stale value.
- Disable the compensating `Release` and stock stays decremented instead of returning to its starting value.

Four environmental findings surfaced before the cutover evidence could be trusted, none a defect in the order service's logic. This was the first time all six services, the gateway, the monolith and the proxy ran together against a Postgres and Kafka instance with months of accumulated history:

- The order service's `customers` table starts empty by design, so the fixtures needed one seed row.
- A new Kafka consumer group had to drain hundreds of historical messages on the shared broker before its first checkout settled within budget.
- The order id sequence, starting at 1, collided with a historical high-water mark of 322 held in payment-service's and shipping-service's idempotency guards. One checkout landed on an id an old payment row already claimed and stayed `PENDING`. It was isolated as not a saga defect, because every order placed moments later behaved correctly.
- Shipping-service's base URL for order data still defaulted to the monolith, so once the order flag cut over, shipping enrichment silently fell back to a stub address instead of failing. That one would have produced a false-green suite unnoticed.

The final cutover run produced 167 assertions with one failure, a documented pre-existing content-type string mismatch in the GraphQL contract item, in 16.6 seconds. The payment-decline and shipping-failure scenarios each converged to net-zero stock: 490 to 489 back to 490, in both cases. The same order queried through the proxy and directly against the order service returned byte-identical JSON, while the same id queried against the monolith returned a plain `404`. That is the strongest proxy-reaches-the-service proof possible, because the monolith never minted the order.

## Reversibility, then the irreversible step

Every earlier cutover could be undone by flipping a flag back to a live monolith. This is the last point where that is true, so it was proven before the decommission, not assumed. With `strangler.order.enabled` back at its default and shipping-service restarted without its cutover override, a direct checkout was served by the monolith again (`202`, `PENDING`, a bounded-wait climb to `CONFIRMED`), and the order service had no record of that order. The collection re-ran at 164 assertions with 8 failed, all in one GraphQL contract item whose fixture assumed an order placed in the current run. The gateway has no monolith client, so it correctly could not see an order the monolith just placed, and still resolved an earlier order-service order on request.

The decommission then deleted the monolith's order context, its copy of the inventory gRPC client and its outbox relay (thirty-one files), leaving the shell building with its smoke test green at six assertions for six retired endpoints. The proxy's six cutover flags and monolith base-URL configuration were removed with the code path they chose between. All six extractions are now independently deployable.

## ACID gives way to ACD

A saga replaces a single transaction's guarantees with ACD: atomic, consistent and durable, with isolation dropped. Each local step still commits atomically in its own database, but nothing holds the whole cross-service sequence under one set of locks. The order service's one remaining `@Transactional` boundary spans exactly its own schema: the order row, its outbox row and its read-model projection. No in-process transaction anywhere in the system reaches across more than one bounded context's data. Every cross-context guarantee left is a saga with an explicit compensation, shown by a check to break when the compensation is removed, never an implicit rollback a database provided as a side effect.

> **ADLC in Action:** This is the sixth and final extraction, the hardest shape of the Frame, Map, Plan, Generate, Verify, Operate, Reconcile loop. Frame settled the five decisions above before any code existed. Map named six reasons this extraction outranks the others: it is the god aggregate and the hub every other extraction left standing; after decommission nothing remains to be equivalent to; there is no fallback to revert to; two named smells finally had to be cured with evidence; two new teaching surfaces, CQRS and GraphQL, arrive in one step; and the strangler runs out of host tree. Generate produced the order service in two phases (Phase A an own-schema lift, Phase B the CQRS write model with the four lifted reactions and their own outbox), the `order_view` projection, the GraphQL gateway and the last strangler flag. Verify carried the heaviest proof load: four environmental findings closed before the evidence counted, net-zero stock numbers twice, the monolith's plain `404`, and three negative checks each shown red then green. Operate was the final flag flip, reversibility proven once more, and the single irreversible decommission with the checks re-designated from equivalence to contract. Reconcile struck the god service and the cross-context transaction from `SMELLS.md` for good.

> **Decision**
>
> Extract the god aggregate last, once the other contexts are gone, and split it into a command side and an `order_view` projection written in the same transaction. Prove the read path with a negative check, prove reversibility before the irreversible decommission, and freeze the monolith instead of deleting it.

> **🤖 Explore with your agent**
>
> "In `examples/07-order-service`, find every call to `OrderViewProjector` and confirm each sits inside the transaction that writes the `Order`. Then suppress the projection on the `onShipmentDispatched` transition, run the order service's tests, and tell me which one fails and why `GET /api/orders/{id}` stays stale. Revert the change afterward."
>
> Check: the failing run shows a read stuck one status behind the write model, and `git diff examples/07-order-service` is empty after the revert.

## Build, run, observe

`demos/demo-final-topology.sh` brings up the finished topology with no monolith: review :8081, notification :8083, inventory :8084 (gRPC :9004), payment :8085, order :8087, shipping :8088 and the GraphQL gateway :8090, behind the edge router on :8888. It then runs the full contract suite through the router and passes green.

The intermediate cutover is reconstructed by `demos/demo-order-cutover.sh`. The proxy there still honors the `strangler.*.enabled` flags, so run it from `git checkout stage/06-order-extracted`; on `main` the flag flips are no-ops and the reversibility step cannot route back to a monolith.

- Query a real order through `:8888/api/orders/{id}` and directly on `:8087`: the JSON is identical. Query the same id on the monolith at `:8080` at the cutover snapshot: `404`.
- Send a GraphQL `order(id)` query to `:8090` that selects no `reviews`: review-service receives no call.
- Suppress `OrderViewProjector` on one transition: the write model advances and `GET` serves the stale status.
- Disable the compensating `Release`: stock stays decremented after a payment decline instead of returning to its starting value.

## For deeper coverage

- Chris Richardson, *Microservices Patterns* (Manning, 2018): the CQRS and API composition patterns central to the order extraction and the gateway; see the [Reading List](/bibliography/).
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the derived-data and materialized-view framing behind the same-transaction `order_view` projection.
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the pattern the cutover completes.
- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): guidance on a final extraction and a decommissioned system worth preserving.
- Cloud Native Design Patterns, [Communications](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/02-communications.md): the REST, gRPC, event-driven and aggregation styles side by side.
