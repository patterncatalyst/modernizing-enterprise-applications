---
title: "Orchestrated Sagas & Extraction 5 — Shipping (Camel Saga EIP)"
order: 24
part: "Coordinating Across Services"
description: "Shipping becomes a bounded, orchestrated saga: a single Camel Saga EIP coordinator sequences dispatch and owns the compensation decision, in contrast to Chapter 23's choreography; the cross-context undo is delegated to the order context that owns the data it must reverse, and the equivalence suite is extended to prove it net-zero across an even longer chain."
duration: 40 minutes
---

Chapter 23 took the request thread out of payment's compensation entirely —
once `OrderService#placeOrder` stopped calling payment at all, there was no
`catch` block left to put an undo in, so the decline had to travel as an
event and land on a listener that had never consumed Kafka before. This
chapter keeps that same discipline — no request thread, a real network seam,
a compensating action that has to provably fire — and changes exactly one
thing about how the sequence is controlled: instead of three independent
services each reacting to an event and emitting the next one, a single route
now holds the whole fulfilment sequence in one place, decides whether to
proceed or compensate, and says so explicitly in its own source. Everything
below is running code already committed to this repository:
`examples/06-shipping-service/` (the Quarkus shipping service and its Camel
Saga EIP orchestrator), `examples/00-monolith/`
(`order/OrderSagaListener#onPaymentCaptured`/`onShipmentDispatched`/
`onShipmentFailed`, and `OrderDto.shippingAddress`), and
`examples/01-strangler-proxy/` (the `/api/shipments` route). This is still
Part 7, "Coordinating Across Services" — the same part Chapter 23 opened —
and it closes that part's second extraction with the opposite control style
from the first.

The code is in `examples/06-shipping-service/` and `examples/00-monolith/`.
The run script in each directory builds/sets up and runs it; each
`README.md` covers what it does and how to drive it.

{% include excalidraw.html file="shipping-orchestrated-saga-sequence" alt="The full happy-and-failure flow for the orchestrated shipping saga. Bands A and B are unchanged from chapters 19 and 23: a synchronous gRPC Reserve, an order persisted PENDING with an order.placed outbox row, and the payment service's own choreography emitting payment.captured or payment.declined through its own transactional outbox. Band C is new: two independent consumer groups react to payment.captured at once — the monolith's OrderSagaListener moves the order to AWAITING_SHIPMENT and does not dispatch in-process, while the shipping service's Camel Saga EIP coordinator starts, sequencing enrich, dispatch, book-carrier, and emit. Band D, success: the coordinator emits shipment.dispatched through its own outbox and the monolith's onShipmentDispatched reaction confirms the order. Band E, failure: the deterministic SHIP-FAIL sentinel makes book-carrier throw, the same coordinator invokes its one registered compensation route exactly once, cancelling the shipment and emitting shipment.failed, and the monolith's onShipmentFailed reaction marks the order SHIPPING_FAILED and issues the compensating gRPC Release for every reserved sku." caption="Figure 24.1 — The orchestrated shipping saga, end to end: one coordinator, two consumer groups reacting to the same event, one compensating action invoked exactly once" %}

## ACD realized for shipping: SMELL[ch.22]'s second clause cashed in

Chapter 23 cashed in Chapter 22's named consistency smell for payment —
`OrderService#placeOrder` stopped calling payment in-process, and what
Postgres used to give as a side effect inside one transaction had to be rebuilt
explicitly, asynchronously, across a process boundary. That entry's closing
line named the two contexts still owing the same debt: shipping and order.
This chapter pays shipping's half. `examples/00-monolith`'s `SMELLS.md`
carries the extension of the same row:

```
Captured — examples/00-monolith/SMELLS.md, SMELL[ch.22] excerpt (shipping clause)

Realized for shipping too in r07/ch.24 S9 (DRQ-056/058/060/061) -- the same
ACID -> ACD story cashed in for this context. The in-process shipping
dispatch no longer rides any checkout/payment transaction at all ...
Fulfilment is now owned end-to-end by the shipping service's Camel Saga EIP
coordinator ... The smell is therefore NOT struck through above: it is cured
for payment and shipping, not yet for order, which is exactly the resume
boundary ch.26 picks up.
```

The externally observable contract does not move again here — Chapter 23
already did the hard work of replacing `201`-with-a-terminal-status with
`202 Accepted` plus a pollable resource, and nothing about that changes in
this chapter. What moves is further back: the order used to reach its
terminal `CONFIRMED` state the moment payment captured, because
`onPaymentCaptured` dispatched shipping in-process on the same Kafka-consumer
thread. That coupling is what this chapter removes — fulfilment becomes a
saga of its own, with its own failure mode, its own terminal state, and its
own compensating action, none of which the order context performs directly
anymore.

## Orchestration, not choreography — the contrast this book has been building to

Chapter 23 named the two control styles a saga can run under and picked
choreography: no coordinator, every participant reacting only to
the event it subscribes to and emitting the next one, the sequence existing
only as the sum of three independent reactions. This chapter makes the
opposite choice, and the decision was written down before a line of saga
code existed:

```
Captured — _plans/iterations/shipping-plan.md, DRQ-056 (the load-bearing decision)

DECISION: Shipping is extracted as a new, bounded ORCHESTRATED saga,
triggered on payment.captured, whose COORDINATOR is a Camel Saga EIP route
co-located in the new Quarkus shipping service. It sequences the fulfilment
steps and registers the compensating action; on success it emits
shipment.dispatched (-> order CONFIRMED), on abort/timeout it compensates
(cancel shipment + emit shipment.failed -> order SHIPPING_FAILED + inventory
Release).
```

Two alternatives were considered in the same planning pass and rejected in
writing, not quietly dropped. Re-expressing the *whole* order→payment→
shipping flow as one orchestrated saga was rejected because it would tear
out the already-accepted choreographed payment saga from Chapter 23 and
erase the very contrast this part of the book exists to teach — a
modernization program does not get to retroactively make every earlier
decision agree with the newest one just because it would be tidier.
Shipping calling inventory's gRPC `Release` directly, skipping the order
context entirely, was also rejected, for a reason that matters more than it
first appears: the reserved-line snapshot — which skus, which quantities,
reserved at which point in checkout — lives on the order aggregate, not on
the shipment. A coordinator that reached around the order context to call
`Release` itself would be reasoning about data it does not own.

An **orchestrated** saga has a coordinator: it calls each step, decides
whether to proceed or compensate, and the steps themselves have no idea what
sequence they are part of. A **choreographed** saga has none: every
participant reacts to an event and emits the next one, and nobody holds the
sequence anywhere a reader could point to. Chapter 23's payment service and
the monolith's `OrderSagaListener` are still, today, two independent
reactors — neither one knows the other exists, and the "saga" is a fact a
reader has to reconstruct by tracing topic names across two codebases. This
chapter's shipping service is the opposite shape: `ShipmentSagaRoute` is a
single Camel route that names every step, in order, in one file, and owns
the one decision — proceed or compensate — that choreography spread across
three separate reactions.

{% include excalidraw.html file="orchestration-vs-choreography" alt="Side by side on one codebase. Left, chapter 23's choreographed payment saga: no central box; the payment service's OrderPlacedConsumer and PaymentService, and the monolith's OrderSagaListener, each independently subscribe to Kafka topics and decide on their own what their reaction means for the order; control is emergent. Right, chapter 24's orchestrated shipping saga: a single Camel Saga EIP coordinator explicitly sequences enrich, dispatch, book-carrier, and emit, and owns the one registered compensating action; control is centralized and visible in one route that reads top to bottom, and the monolith reacts only to the outcome, never deciding what fulfilment step runs next." caption="Figure 24.2 — Orchestration vs. choreography, on the same codebase: a coordinator that decides, versus three reactions that only emerge into a sequence" %}

Neither style is simply better. Choreography scales without touching
existing participants — a fourth service that wants to react to
`payment.captured` needs no change to the first three — but the sequence
itself is nowhere in the code; a reader reconstructs it by following topic
names. Orchestration makes the sequence legible in one place and makes the
compensation decision a single, testable thing to get right, at the cost of
a coordinator that is now a piece of infrastructure in its own right, with
its own failure modes. Reading Chapters 23 and 24 back to back is the point
this book has been building to: the same coordination problem — capture or
fulfil, then confirm or compensate — solved with opposite control styles on
one codebase, so the tradeoff is felt in running code rather than asserted
in prose.

## The Camel Saga EIP, mechanically

`examples/06-shipping-service`'s saga is built on Camel's Saga
Enterprise Integration Pattern, and the whole coordinator reads as one
route:

```java
// examples/06-shipping-service/.../ShipmentSagaRoute.java#configure
from(ShippingService.SAGA_START_ENDPOINT)
        .routeId("shipping-saga")
        .saga()
            .sagaService(SAGA_SERVICE_BEAN_NAME)
            .propagation(SagaPropagation.REQUIRES_NEW)
            .completionMode(SagaCompletionMode.AUTO)
            .timeout(SAGA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .compensation("direct:ship-compensate")
            .option(ShipmentSagaSteps.HEADER_ORDER_ID, header(ShipmentSagaSteps.HEADER_ORDER_ID))
        .to("direct:ship-enrich")
        .to("direct:ship-dispatch")
        .to("direct:ship-book-carrier")
        .to("direct:ship-emit-dispatched")
        .end();

from("direct:ship-compensate")
        .routeId("ship-compensate")
        .bean(steps, "compensate");
```

Every clause on `.saga()` is doing a specific job. `.sagaService(...)` names
the `CamelSagaService` bean the route delegates coordination to —
`SagaConfiguration` produces an `InMemorySagaService`, Camel's in-JVM
implementation, registered under exactly the name this route looks up.
`.propagation(REQUIRES_NEW)` starts a fresh saga instance for every
`payment.captured` the saga entry point receives, rather than joining one
already in progress. `.completionMode(AUTO)` is the clause that makes the
coordinator's job automatic rather than manual: Camel watches the outcome of
the four chained `.to(...)` steps, and if they all complete without an
exception, the saga is marked complete with no compensation call at all; if
*any* of them throws — in this route, only `bookCarrier`'s deterministic
`ShipFailException` is exercised deliberately, but an unexpected exception
from any other step would be treated identically — or the `.timeout(15,
SECONDS)` elapses first, the coordinator invokes the one route named in
`.compensation(...)` instead. `.option("orderId", header("orderId"))`
carries the correlation key into that compensation invocation, evaluated
once, as the exchange enters the saga scope.

That registration step is not free. `InMemorySagaService` is a plain CDI
bean, but CDI never calls Camel's own `Service#start()` lifecycle on
anything it produces — the first saga invocation against an unregistered
`InMemorySagaService` threw a bare `NullPointerException` deep inside
Camel's internal coordinator, because the scheduled executor its timeout
logic depends on was never initialized. `ShipmentSagaRoute#configure` fixes
this by looking the bean up and registering it with the `CamelContext`
directly before the route finishes wiring:

```java
// examples/06-shipping-service/.../ShipmentSagaRoute.java#configure
CamelSagaService sagaService =
        getContext().getRegistry().lookupByNameAndType(SAGA_SERVICE_BEAN_NAME, CamelSagaService.class);
getContext().addService(sagaService, true, true);
```

The guarantee this whole clause list buys — a saga instance is compensated
or completed, never both, and at most once per instance — is Camel's own
saga SPI contract, not something this project's code re-implements. The
implementation's job is narrower and more mundane: making sure the
compensation route can always find the right row to act on. `.option(...)`
is evaluated once, at saga-entry time, before `dispatchShipment` has even
run — so a `shipmentId` set mid-route by step 2 is not reliably available to
a saga `.option(...)` clause by the time compensation runs. Rather than
betting behavior on Camel-internal timing that is not part of the
documented contract, `ShipmentSagaSteps#compensate` looks the shipment up
the same way the idempotency guard does, by `orderId` — the one value
`.option(...)` actually guarantees is present.

## Compensation that spans a boundary: delegated, not direct

The hardest property this chapter's saga has to prove is not that it can
retry a step — it is that a compensation decided inside one service can
reliably undo a side effect that lives in a different one, without the
coordinator reaching into data it does not own. DRQ-060 is the decision that
answers this: the shipping service never calls inventory's gRPC `Release`
itself. It cancels its own `Shipment` row locally and emits
`shipment.failed`; the order context — which already holds the reserved-line
snapshot from checkout — is the one that issues the `Release`, reusing the
exact machinery Chapter 23's `onPaymentDeclined` built for the choreographed
decline path.

```java
// examples/06-shipping-service/.../ShipmentSagaSteps.java#compensate
@Transactional
public void compensate(Exchange exchange) {
    Long orderId = exchange.getIn().getHeader(HEADER_ORDER_ID, Long.class);
    Optional<Shipment> maybeShipment = repository.findByOrderId(orderId);
    // ... marks the shipment CANCELLED if one was persisted, classifies the
    // reason (the SHIP-FAIL sentinel vs. an unexplained abort/timeout) ...
    ShipmentFailed payload = new ShipmentFailed(
            orderId, shipmentId, address, ShipmentFailed.STATUS, Instant.now(), reason);
    outboxRepository.persist(buildOutboxEvent(
            ShipmentOutboxRelay.SHIPMENT_FAILED_EVENT_TYPE, orderId, payload));
}
```

```java
// examples/00-monolith/.../order/OrderSagaListener.java#onShipmentFailed
order.failShipping();
orderRepository.save(order);
for (OrderItem item : order.getItems()) {
    try {
        remoteInventoryClient.release(item.getSku(), item.getQuantity());
    } catch (RuntimeException releaseFailure) {
        LOG.error("compensation FAILED for order={} sku={} qty={} -- stock NOT restored",
                order.getId(), item.getSku(), item.getQuantity(), releaseFailure);
    }
}
```

This is the one place this chapter's compensation is *not* purely
coordinator-decided — the coordinator still owns the decision to compensate
and the ordering of its own local undo, but the cross-context part of the
undo is handed off, the same shape Chapter 23's choreography used for the
cross-context part of its own compensation. The difference between the two
chapters is not whether a hand-off happens — it is who decides *that* a
hand-off should happen in the first place. In Chapter 23 nobody decided;
the order context reacted to `payment.declined` because that is what its
own subscription said to do. Here, the Camel Saga EIP coordinator is the one
that ran the step that threw, knows immediately that compensation is
required, and triggers it in-process before any event ever leaves the
shipping service — only the part of the undo it cannot perform locally
travels onward as `shipment.failed`.

{% include excalidraw.html file="shipping-saga-compensation-flow" alt="Three stacked bands, each converging to net-zero stock, showing how the trigger for compensation changes while the inventory undo stays the same gRPC call. Top, chapter 19: compensation runs from an in-line catch, on the same request thread, the instant an in-process payment charge throws. Middle, chapter 23: no request-thread catch exists; a payment decline is discovered by reacting to the payment.declined event across two asynchronous hops, with no coordinator. Bottom, chapter 24: the Camel Saga EIP coordinator is coordinator-initiated — it is the process running the step that throws, so it invokes its registered compensation route immediately, in-process; only the cross-context part, releasing inventory the shipping service does not own the snapshot for, is delegated onward via shipment.failed to the order context, which performs the actual compensating Release." caption="Figure 24.3 — Compensation across three chapters: a request-thread catch, a choreographed reaction, and a coordinator-initiated compensation that only delegates the part it cannot perform locally" %}

## The new order lifecycle: `AWAITING_SHIPMENT`, and `CONFIRMED` one hop later

Moving fulfilment out of `onPaymentCaptured` required a new intermediate
state on the order aggregate — DRQ-061's `AWAITING_SHIPMENT` — because an
order that has been paid for but not yet shipped is now a real, observable
state a client's poll can land on, where it used to be invisible because the
same method confirmed and dispatched in one breath. `common.OrderStatus`
grows two values to carry this:

```java
// examples/00-monolith/.../common/OrderStatus.java
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PAYMENT_DECLINED,
    AWAITING_SHIPMENT,
    SHIPPING_FAILED
}
```

{% include codetabs.html langs="Before — onPaymentCaptured confirms + dispatches in-process (removed at S9)|After — onPaymentCaptured only awaits shipment; the saga owns the rest (current)" %}

```java
// order/OrderSagaListener.java#onPaymentCaptured — shipping.mode=inprocess (removed at S9)
Order order = orderRepository.findById(event.orderId()).orElse(null);
if (order.getStatus() != OrderStatus.PENDING) {
    return;
}
order.confirm();
orderRepository.save(order);
shippingService.dispatch(order, order.getShippingAddress());
```

```java
// examples/00-monolith/.../order/OrderSagaListener.java#onPaymentCaptured — current
Order order = orderRepository.findById(event.orderId()).orElse(null);
if (order.getStatus() != OrderStatus.PENDING) {
    return; // idempotency guard
}
order.awaitShipment();
orderRepository.save(order);
// Fulfilment and the eventual CONFIRMED transition are owned entirely by
// onShipmentDispatched/onShipmentFailed below, reacting to the shipping
// service's own saga outcome — this method never confirms directly.
```

The order's `CONFIRMED` transition now happens exactly once more hop down
the chain than it did at the end of Chapter 23, and only in reaction to the
shipping service's own outcome:

```java
// examples/00-monolith/.../order/OrderSagaListener.java#onShipmentDispatched
if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
    return; // idempotency guard
}
order.confirm();
orderRepository.save(order);
```

Two consumer groups now react to the same `payment.captured` event
independently — the monolith's `OrderSagaListener` and the shipping
service's `PaymentCapturedConsumer` — and neither knows the other exists;
this is not a hand-off between them, it is the same event landing on two
unrelated subscriptions. Nothing about this touches the external contract
Chapter 23 fixed: `POST /api/orders` still returns `202 Accepted` with a
pollable `Location`, and a client watching `GET /api/orders/{id}` still only
ever observes the same three-plus-two-value `OrderStatus` vocabulary it did
before, just with one more intermediate value it might catch mid-flight.
Reaching that address required one small, additive change to the order
read model, `OrderDto.shippingAddress`, so the saga's enrichment step could
read a real address over `GET /api/orders/{id}` instead of a documented
placeholder — a gap Chapter 24's own Generate step flagged explicitly in its
own commit message for the monolith wiring to close, which it then did.

## Idempotency and at-least-once, once more

Every outbox relay in this project is at-least-once, and this chapter adds
a second independent relay to the two Chapter 23 already chained — the
monolith's `order.placed` relay, the payment service's own
`payment.captured`/`payment.declined` relay, and now the shipping service's
`shipment.dispatched`/`shipment.failed` relay — so a redelivered event
anywhere along that chain has to be a safe no-op. `ShippingService
#processPaymentCaptured` checks for an existing `Shipment` by `orderId`
before starting the saga at all, backed by a database-level
`uq_shipments_order_id` constraint for the case of two deliveries racing
concurrently — the same two-layer pattern Chapter 23's `uq_payments_order_id`
established. On the monolith's side, all three of `OrderSagaListener`'s
shipping-related reactions guard on the order's current status before doing
anything: `onPaymentCaptured` only transitions an order that is still
`PENDING`, and `onShipmentDispatched`/`onShipmentFailed` only transition one
that is still `AWAITING_SHIPMENT`. That guard does two jobs at once, exactly
as it did for payment's decline path — it absorbs a redelivered event as a
no-op, and it bounds the compensating `Release` to firing at most once per
order, because `AWAITING_SHIPMENT → SHIPPING_FAILED` is reachable by exactly
one transition. The same guard is also what keeps a payment decline and a
shipping failure from ever double-compensating the same reservation: an
order can only reach `AWAITING_SHIPMENT` after a successful (non-declined)
payment, so the two failure reactions are mutually exclusive by
construction, not by convention.

## What an in-memory coordinator does not promise

A Camel Saga EIP coordinator that keeps all of its in-flight state on the
JVM heap is a bounded choice for this example, and its
limits matter as much as its mechanics. `InMemorySagaService`
remembers which sagas are in flight, and their registered compensation and
completion callbacks, purely in memory — a coordinator restart mid-saga, a
crash between the dispatch step's commit and the emit step's atomic pair,
loses that bookkeeping entirely. A `Shipment` row persisted `PENDING` at
exactly that moment would sit there forever, uncompensated and
unconfirmed, because nothing remembers a saga was ever running for that
order. `LRASagaService`, built on a distributed, crash-durable coordinator,
is the production-grade alternative — deferred here for the same reason
earlier extractions deferred CDC or a schema registry: new
infrastructure this example does not need to demonstrate the pattern, a
scoped decision rather than an oversight. The same bounded-saga
discipline shows up in what `onShipmentFailed` does *not* do: it
compensates inventory only. The payment this order's `payment.captured`
already captured is not refunded — reaching back into the payment service
to request a refund would extend this saga's scope into a second,
payment-aware compensation this chapter does not build. Both
limits are recorded directly in the code that would need to change to close
them, and both are named here as real engineering tradeoffs this extraction
accepted rather than solved, with Chapter 25's resilience work as the
forward reference for hardening a coordinator that currently keeps no
ledger at all.

## Keeping the equivalence suite sound across a longer chain and a new outcome

Chapter 23's suite had to learn that an asynchronous terminal status needs a
bounded-wait poll instead of a synchronous assertion. This chapter's suite
had to learn that an even longer chain needs that same budget widened
without touching the assertion it bounds — and that a terminal outcome the
in-process monolith cannot exercise at all needs to be present, inert, and
*marked* inert, rather than silently absent. Scenario 1's confirm poll was
widened from its payment-era budget to `20×750ms` to cover the extra
`shipping saga → shipment.dispatched` hop; the terminal
`eql('CONFIRMED')` assertion itself was never touched. A new "Scenario 4 —
Shipping-Failure" folder was added behind a `shippingSagaEnabled` flag,
baselined green against the still-in-process monolith with the new folder
explicitly reporting itself pending rather than passing on an unexercised
path — **121 assertions, 0 failed, Scenario 4 correctly skipped** at
baseline, before any saga code existed.

Proving the scenario non-vacuous once the saga was real took more than a
green run. Cutover first surfaced a tooling gap rather than a success:
newman's `--env-var`/`--global-var` flags populate a different variable
scope than the one a Postman collection's own `variable` array defines, so
the first attempt to flip `shippingSagaEnabled=true` from the command line
silently did nothing — the collection quietly reported Scenario 4 as
pending and still passed **127/127**, which would have been an easy false
green to miss entirely. The fix loaded the collection through newman's Node
API and patched the in-memory variable directly, packaged into
`demos/lib/run-shipping-newman.js` without ever rewriting the committed
collection file. With the saga now engaged, a forced `SHIP-FAIL`
checkout's stock was captured directly against the inventory service, twice:

```
Captured — examples/06-shipping-service/CUTOVER.md, forced-SHIP-FAIL stock trace

Before checkout: 496 -- Immediately after POST (sync Reserve committed): 495
-- After the bounded-wait observes SHIPPING_FAILED: 496

Net-zero: 496 -> 495 -> 496. Repeated inside the scripted run, a different
order: 478 -> 477 -> 478.
```

The sharper proof is the two negative checks, because a green suite alone
cannot tell a firing compensation from one that would pass
regardless. With the saga's `.compensation(...)` registration commented
out and the service rebuilt, a forced `SHIP-FAIL` order went **RED — 2 of
163 assertions failed** (manual) and **2 of 47** (scripted), both isolated
to exactly the predicted pair: the order stuck at `AWAITING_SHIPMENT`
instead of reaching `SHIPPING_FAILED`, and stock left decremented instead
of net-zero. The edit was reverted and the service rebuilt; Scenario 4
re-ran **GREEN — 127/127** (manual) and **16/16** (scripted). A second
check killed the shipping service's sole `payment.captured` consumer
entirely and placed a fresh checkout through the proxy: **RED — 1 of 33**
assertions failed, the order stuck at `AWAITING_SHIPMENT` with no
coordinator left to react; restarting the service let Kafka's
at-least-once redelivery drain the backlog within seconds, and a fresh
checkout went **GREEN — 19/19**. One further, unrelated finding surfaced
during the scripted cutover run and was closed immediately rather than
left standing: a pre-existing Notification-folder bounded-wait budget had
never been widened alongside Scenario 1's own, and flaked intermittently
under the now-longer chain — a budget-only fix, landed the same day, with
the assertion itself untouched.

Reversibility was proven the same way every prior extraction proved it:
both overrides removed, a direct checkout confirmed the in-process contract
was back, and the full suite re-ran **112/112** (manual) and **114/114**
(scripted), with Scenario 4 correctly reporting pending again. Decommission
then closed that window for good — the monolith's in-process shipping
module was deleted entirely, `GET :8080/api/shipments` now returns `404`,
and `strangler.shipping.enabled=true` became the committed proxy default.
The same discipline reaches CI: `.github/workflows/code-ci.yml` gained a
fifth job, `shipping-equivalence-gate`, extending the existing workflow to
start the shipping service and wait for its `payment.captured` consumer
group to connect before any checkout runs, because every later job's own
terminal `CONFIRMED` assertion now depends on it.

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 23 demonstrated for
> Payment, at a different shape. Frame fixed the orchestration
> decision (DRQ-056) before anything was built — a bounded saga on
> `payment.captured`, coordinated by a Camel Saga EIP route, with a whole-flow
> re-expression and a direct shipping-calls-inventory shortcut both considered
> and rejected — plus nine more decisions (DRQ-057 through DRQ-065) covering
> the coordinator choice (`InMemorySagaService`, no new infra container), the
> event topology, the saga's step shape and compensation, delegated
> cross-context compensation, two new order states, deterministic failure
> injection, the two-phase service, idempotency, and two-flag reversibility.
> Map named four reasons this rung is harder than choreography: a
> coordinator now has to exist, its compensation is coordinator-initiated and
> crosses service boundaries, the order's `CONFIRMED` moves one hop later, and
> a new failure-bearing terminal outcome had to be proven net-zero. Plan laid
> out fourteen steps across two parallel lanes with Opus gates at nearly every
> rung, naming the orchestrated compensation silently failing or diverging as
> the single biggest risk before a line of saga code existed. Generate
> produced the Camel Saga EIP orchestrator (`.saga()/.compensation()/.option()/.timeout()`,
> `InMemorySagaService`), the monolith's new order lifecycle
> (`AWAITING_SHIPMENT`/`SHIPPING_FAILED`), and a proxy route that again did not
> need the translator the plan had anticipated — under quarkus-agent,
> lgtm-quarkus, lgtm-camel, and camel-mcp tooling, following
> `migrate-spring-to-quarkus` for the lifted read surface. Verify is this
> chapter's sharpest negative checks and its real stock numbers —
> 496→495→496, 478→477→478 — plus a CLI-limitation finding (newman's
> `--env-var` cannot set a collection-scoped variable) found, diagnosed, and
> worked around before the cutover evidence could even begin, and a
> downstream timing flake found and closed immediately after. Operate is the
> two-flag cutover `CUTOVER.md` records end to end, reversibility proven at
> 112/112 then 114/114, and the committed-default flip at decommission.
> Reconcile is `SMELLS.md` marking Smell[ch.22] cured for payment **and**
> shipping — not yet for order — with both saga styles now standing side by
> side on one codebase, and ch.26's order+CQRS+GraphQL extraction as the named
> resume boundary.

## What you learned

- **Orchestration and choreography are the same coordination problem, solved
  with opposite control styles** — Chapter 23's payment saga has no
  coordinator anywhere in the code; this chapter's shipping saga has exactly
  one, a Camel route that sequences its steps and owns its compensation
  decision in one place a reader can follow top to bottom.
- **The Camel Saga EIP's `.saga()` clauses map directly to coordinator
  behavior** — `propagation`, `completionMode(AUTO)`, `timeout`,
  `compensation`, and `option` are not decoration; together they are what
  makes an exception anywhere in the step chain, or a timeout, invoke the one
  registered compensating route exactly once.
- **A coordinator-decided compensation can still require a cross-context
  hand-off** — the shipping service never calls inventory's `Release`
  directly; it emits `shipment.failed` and lets the order context, which owns
  the reserved-line snapshot, perform the undo, the same delegation shape
  choreography used, triggered by a decision instead of a subscription.
- **A new intermediate state makes an invisible window observable** —
  `AWAITING_SHIPMENT` names exactly the span between a captured payment and a
  confirmed shipment that `onPaymentCaptured` used to collapse into nothing,
  without changing what a client's poll was ever allowed to assume about
  `202`/`PENDING`.
- **An in-memory coordinator is a bounded choice, not an oversight** — its
  non-durability, the crash-window orphan it can leave behind, and the
  payment it does not refund on a shipping failure are named directly in the
  code and in this chapter, with distributed alternatives deferred rather
  than silently assumed away.
- **A negative check is still the only proof a bounded-wait assertion tests
  the real pipeline** — this suite went red on a disabled compensation and
  red on a dead consumer, in both cases for exactly the reason predicted,
  before either result was trusted.

Chapter 25 picks up the limitation this chapter named rather than solved —
an in-memory coordinator with no durable ledger, a payment that stays
captured after a shipping failure — and builds the resilience chassis that
earlier chapters deferred one extraction at a time. Chapter 26, extracting
order itself last, inherits both saga styles this book now has running
side by side: it is the aggregate choreography's reactions and
orchestration's delegated compensations both already answer to, and the
context whose own extraction finally empties the monolith out.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is real, runnable code and real, dated evidence
already in this repository: `examples/06-shipping-service/`
(`ShipmentSagaRoute`, `ShipmentSagaSteps`, `SagaConfiguration`,
`ShippingService#processPaymentCaptured`, and `MIGRATION.md`'s measured
Phase A→B record — 1.519s/~289MB/15 features to 2.002s/~364MB/21 features,
the cost of adding both a full Camel engine and the Kafka messaging stack in
one step, not a regression), `examples/00-monolith/`
(`order/OrderSagaListener`'s `onPaymentCaptured`/`onShipmentDispatched`/
`onShipmentFailed`, `OrderStatus`, `OrderDto.shippingAddress`, and
`SMELLS.md`'s SMELL[ch.22] shipping clause), `examples/01-strangler-proxy/`
(`StranglerProxyRoute.java`'s shipping branch), `tooling/newman/mea.postman_collection.json`'s
widened Scenario 1, new Scenario 4, and Shipping Context Contract folders,
and `.github/workflows/code-ci.yml`'s `shipping-equivalence-gate` job. Tests
and demos actually run, per the captured records above: the shipping
service's own saga-correctness suite (`ShippingServiceTest`,
`ShipmentSagaRouteAdviceWithTest`, `PaymentCapturedConsumerTest`,
`ShippingResourceTest` — 16 tests total, including the forced-`SHIP-FAIL`
abort-and-compensate path, idempotent redelivery through the real
`@Incoming` pipeline, and an `AdviceWith` proof that compensation fires
exactly once); the full behavior-equivalence suite through the proxy with
the widened Scenario 1 and the new Scenario 4 (forced `SHIP-FAIL` to
`SHIPPING_FAILED` plus bounded-wait to net-zero stock) included, baselined
at **121/121** green against the in-process monolith before cutover; the
forced-`SHIP-FAIL` stock trace captured twice directly against the
inventory service (**496→495→496**, then **478→477→478**); the
compensation-disabled negative check (**RED, 2/163** manual and **2/47**
scripted, isolated to the predicted stuck-status and stock assertions;
reverted and rebuilt to **GREEN, 127/127** and **16/16**); the
consumer-down negative check (**RED, 1/33**; restarted to **GREEN, 19/19**);
reversibility re-proven at **112/112** then **114/114**; and the
`shipping-equivalence-gate` CI job's own red-then-green discipline,
mirroring the payment gate's. The captured outputs quoted above are taken
verbatim from `CUTOVER.md`, `MIGRATION.md`, `SMELLS.md`, `decisions.md`, and
the chapter's own pre-captured ADLC trace rather than re-run live here, per
this project's standing discipline of citing dated evidence over re-running
it for every chapter. What a reader's own run should confirm
independently: the exact startup/RSS figures in the Phase A→B table will
differ on different hardware even if the qualitative heavier-not-regressed
reading holds; the observed bounded-wait retry counts are a function of two
chained outbox relays' poll intervals and will vary with load; and the
GitHub Actions run of `shipping-equivalence-gate` itself was validated
locally against the same topology before being trusted in Actions, per the
job's own committed comments — a reader watching the real workflow run is
the remaining independent confirmation this chapter does not itself
supply.*
