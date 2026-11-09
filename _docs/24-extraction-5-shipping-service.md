---
title: "Orchestrated Sagas & Extraction 5: Shipping (Camel Saga EIP)"
order: 24
part: "Coordinating Across Services"
description: "Shipping becomes a bounded, orchestrated saga: a Camel Saga EIP coordinator sequences dispatch and owns the compensation decision, and the order context issues the cross-context undo."
duration: 40 minutes
---

## Learning objectives

By the end of this chapter you can:

- Explain how shipping's extraction removes in-process dispatch from every checkout and payment transaction.
- Contrast an orchestrated saga (one Camel Saga EIP coordinator owning the compensation decision) with a choreographed one, and say why re-expressing the whole flow as one orchestrated saga, or letting shipping call inventory's `Release` directly, were both rejected.
- Read the `.saga()` clauses (`sagaService`, `propagation`, `completionMode`, `timeout`, `compensation`, `option`) and say what each contributes to "compensated or completed, never both, at most once".
- Explain why compensation is delegated across contexts: shipping emits `shipment.failed`, and the order context, which owns the reserved-line snapshot, issues the gRPC `Release`.
- Trace the order states `AWAITING_SHIPMENT` and `SHIPPING_FAILED` and how they make a previously invisible window observable without changing the `202` contract.
- Name the limits of `InMemorySagaService` (bookkeeping lost on a crash mid-saga, a captured payment never refunded on a shipping failure) and why `LRASagaService` is deferred.
- Use negative checks (disabled compensation, a killed consumer) to prove the compensation fires exactly once.

## Why this matters

The Payment extraction removed the request thread from compensation: a decline travels as an event and lands on a listener. Shipping keeps that discipline (no request thread, a real network seam, a compensation that must provably fire) and changes one thing: a single route holds the whole fulfilment sequence in one place, decides whether to proceed or compensate, and says so in its own source.

The code is in `examples/06-shipping-service/` (the Quarkus shipping service and its Camel Saga EIP orchestrator), `examples/00-monolith/` (`OrderSagaListener#onPaymentCaptured`, `onShipmentDispatched`, `onShipmentFailed`, and `OrderDto.shippingAddress`) and `examples/01-strangler-proxy/` (the `/api/shipments` route). The order package later left the monolith, so the monolith listings below are as of `git checkout stage/05-shipping-extracted`. The run script in each directory builds and runs it; each `README.md` covers how to drive it.

{% include excalidraw.html file="shipping-orchestrated-saga-sequence" alt="The full happy-and-failure flow for the orchestrated shipping saga. Bands A and B are unchanged: a synchronous gRPC Reserve, an order persisted PENDING with an order.placed outbox row, and the payment service's own choreography emitting payment.captured or payment.declined through its own transactional outbox. Band C is new. Two independent consumer groups react to payment.captured at once: the monolith's OrderSagaListener moves the order to AWAITING_SHIPMENT and does not dispatch in-process, while the shipping service's Camel Saga EIP coordinator starts, sequencing enrich, dispatch, book-carrier, and emit. Band D, success: the coordinator emits shipment.dispatched through its own outbox and the monolith's onShipmentDispatched reaction confirms the order. Band E, failure: the deterministic SHIP-FAIL sentinel makes book-carrier throw, the same coordinator invokes its one registered compensation route exactly once, cancelling the shipment and emitting shipment.failed, and the monolith's onShipmentFailed reaction marks the order SHIPPING_FAILED and issues the compensating gRPC Release for every reserved sku." caption="Figure 24.1: The orchestrated shipping saga, end to end: one coordinator, two consumer groups reacting to the same event, one compensating action invoked exactly once" %}

## ACD for shipping

Payment's extraction replaced what Postgres gave inside one transaction with explicit, asynchronous, cross-process machinery. Shipping owed the same debt. `SMELLS.md` now records the consistency smell, `SMELL[ch.22]`, as cured for payment and shipping, not yet for order. Before this step the order reached `CONFIRMED` the moment payment captured, because `onPaymentCaptured` dispatched shipping in-process on the same Kafka-consumer thread. That coupling is gone: fulfilment is a saga of its own, with its own failure mode, terminal state and compensating action, none of which the order context performs directly. The external contract doesn't move: `POST /api/orders` still returns `202 Accepted` with a pollable `Location`.

## Pattern: Saga, orchestrated

Payment is choreographed: no coordinator, three independent reactions, a sequence a reader reconstructs by tracing topic names. Shipping is the opposite: a new bounded *orchestrated* saga triggered on `payment.captured`, whose coordinator is a Camel Saga EIP route in the new shipping service. It sequences the fulfilment steps and registers the compensating action. On success it emits `shipment.dispatched` (order `CONFIRMED`). On abort or timeout it cancels the shipment, emits `shipment.failed` (order `SHIPPING_FAILED`), and the inventory is released.

Two alternatives were rejected:

- **One orchestrated saga for the whole order, payment and shipping flow.** It would tear out the accepted choreographed payment saga and erase the contrast. A modernization program doesn't retroactively make every earlier decision match the newest one.
- **Shipping calling inventory's gRPC `Release` directly.** The reserved-line snapshot (which skus, which quantities) lives on the order aggregate, not the shipment. A coordinator that reached around the order context would be reasoning about data it doesn't own.

`ShipmentSagaRoute` is a single Camel route that names every step, in order, in one file, and owns the one decision (proceed or compensate) that choreography spread across three reactions.

{% include excalidraw.html file="orchestration-vs-choreography" alt="Side by side on one codebase. Left, the choreographed payment saga: no central box; the payment service's OrderPlacedConsumer and PaymentService, and the monolith's OrderSagaListener, each independently subscribe to Kafka topics and decide on their own what their reaction means for the order; control is emergent. Right, the orchestrated shipping saga: a single Camel Saga EIP coordinator explicitly sequences enrich, dispatch, book-carrier, and emit, and owns the one registered compensating action; control is centralized and visible in one route that reads top to bottom, and the monolith reacts only to the outcome, never deciding what fulfilment step runs next." caption="Figure 24.2: Orchestration versus choreography on the same codebase: a coordinator that decides, versus three reactions that only add up to a sequence" %}

Neither style is simply better:

| | Choreography | Orchestration |
|---|---|---|
| Adding a reactor | No change to existing participants | Change the coordinator |
| Where the sequence lives | Nowhere; reconstruct it from topic names | One route, top to bottom |
| Compensation decision | Emergent from subscriptions | One testable decision |
| Cost | Hard to see the whole flow | A coordinator that is infrastructure with its own failure modes |

### The Camel Saga EIP, mechanically

The whole coordinator reads as one route:

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

| Clause | Job |
|---|---|
| `.sagaService(...)` | Names the `CamelSagaService` bean the route delegates coordination to. `SagaConfiguration` produces an `InMemorySagaService`, Camel's in-JVM implementation, under the name the route looks up |
| `.propagation(REQUIRES_NEW)` | Starts a fresh saga instance for every `payment.captured`, never joining one in progress |
| `.completionMode(AUTO)` | Camel watches the four chained steps: all complete without an exception and the saga completes with no compensation call; any throws, or `.timeout(15, SECONDS)` elapses first, and the coordinator invokes the one route in `.compensation(...)` |
| `.option("orderId", header("orderId"))` | Carries the correlation key into the compensation, evaluated once as the exchange enters the saga scope |

An unexpected exception from any step is treated like `bookCarrier`'s deterministic `ShipFailException`.

Registration isn't free. `InMemorySagaService` is a plain CDI bean, and CDI never calls Camel's `Service#start()` on what it produces. The first saga invocation against an unregistered instance threw a bare `NullPointerException` deep in Camel's coordinator, because the scheduled executor its timeout logic uses was never initialized. The route fixes this by registering the bean with the `CamelContext` before wiring finishes:

```java
// examples/06-shipping-service/.../ShipmentSagaRoute.java#configure
CamelSagaService sagaService =
        getContext().getRegistry().lookupByNameAndType(SAGA_SERVICE_BEAN_NAME, CamelSagaService.class);
getContext().addService(sagaService, true, true);
```

"Compensated or completed, never both, at most once per instance" is Camel's own saga SPI contract. The implementation's job is making sure the compensation route can find the row to act on. `.option(...)` is evaluated at saga entry, before `dispatchShipment` runs, so a `shipmentId` set mid-route isn't reliably available to it. `ShipmentSagaSteps#compensate` therefore looks the shipment up by `orderId`, the one value `.option(...)` guarantees, the same way the idempotency guard does.

## Compensation that spans a boundary

The shipping service never calls inventory's gRPC `Release`. It cancels its own `Shipment` row locally and emits `shipment.failed`. The order context, which holds the reserved-line snapshot from checkout, issues the `Release`, reusing the machinery `onPaymentDeclined` built for the choreographed decline.

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
        LOG.error("compensation FAILED for order={} sku={} qty={} — stock NOT restored",
                order.getId(), item.getSku(), item.getQuantity(), releaseFailure);
    }
}
```

Both styles hand off the cross-context part of the undo. What differs is who decides that a handoff is needed. In the choreographed saga nobody decided: the order context reacted to `payment.declined` because its subscription said to. Here the coordinator ran the step that threw, knows compensation is required, and triggers it in-process before any event leaves the shipping service. Only the part it can't perform locally travels onward as `shipment.failed`.

{% include excalidraw.html file="shipping-saga-compensation-flow" alt="Three stacked bands, each converging to net-zero stock, showing how the trigger for compensation changes while the inventory undo stays the same gRPC call. Top, the Inventory extraction: compensation runs from an in-line catch, on the same request thread, the instant an in-process payment charge throws. Middle, the choreographed payment saga: no request-thread catch exists; a payment decline is discovered by reacting to the payment.declined event across two asynchronous hops, with no coordinator. Bottom, the orchestrated shipping saga: the Camel Saga EIP coordinator is coordinator-initiated: it is the process running the step that throws, so it invokes its registered compensation route immediately, in-process; only the cross-context part, releasing inventory the shipping service does not own the snapshot for, is delegated onward via shipment.failed to the order context, which performs the actual compensating Release." caption="Figure 24.3: Compensation across three extractions: a request-thread catch, a choreographed reaction, and a coordinator-initiated compensation that delegates only the part it can't perform locally" %}

## The order lifecycle: `AWAITING_SHIPMENT`, and `CONFIRMED` one hop later

An order that has been paid for but not shipped is now a real state a client's poll can land on, where it used to be invisible because one method confirmed and dispatched in one breath. `common.OrderStatus` gains two values:

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

{% include codetabs.html langs="Before: onPaymentCaptured confirms + dispatches in-process (removed)|After: onPaymentCaptured only awaits shipment; the saga owns the rest (current)" %}

```java
// order/OrderSagaListener.java#onPaymentCaptured: shipping.mode=inprocess (removed)
Order order = orderRepository.findById(event.orderId()).orElse(null);
if (order.getStatus() != OrderStatus.PENDING) {
    return;
}
order.confirm();
orderRepository.save(order);
shippingService.dispatch(order, order.getShippingAddress());
```

```java
// examples/00-monolith/.../order/OrderSagaListener.java#onPaymentCaptured: current
Order order = orderRepository.findById(event.orderId()).orElse(null);
if (order.getStatus() != OrderStatus.PENDING) {
    return; // idempotency guard
}
order.awaitShipment();
orderRepository.save(order);
// Fulfilment and the eventual CONFIRMED transition are owned entirely by
// onShipmentDispatched/onShipmentFailed below, reacting to the shipping
// service's own saga outcome. This method never confirms directly.
```

`CONFIRMED` now happens one hop further down, only in reaction to the shipping outcome:

```java
// examples/00-monolith/.../order/OrderSagaListener.java#onShipmentDispatched
if (order.getStatus() != OrderStatus.AWAITING_SHIPMENT) {
    return; // idempotency guard
}
order.confirm();
orderRepository.save(order);
```

Two consumer groups now react to the same `payment.captured` independently: the monolith's `OrderSagaListener` and the shipping service's `PaymentCapturedConsumer`. Neither knows the other exists. A client polling `GET /api/orders/{id}` sees the same vocabulary as before plus one more intermediate value. One additive change to the read model, `OrderDto.shippingAddress`, lets the saga's enrichment step read a real address over `GET /api/orders/{id}` instead of a placeholder.

## Idempotency, once more

The shipping service's `shipment.dispatched` and `shipment.failed` relay is a third at-least-once relay chained after the monolith's `order.placed` relay and the payment service's relay, so a redelivered event anywhere has to be a safe no-op. `ShippingService#processPaymentCaptured` checks for an existing `Shipment` by `orderId` before starting the saga, backed by the unique constraint `uq_shipments_order_id` for concurrent deliveries. In the monolith, all three shipping-related reactions guard on the order's status: `onPaymentCaptured` transitions only a `PENDING` order, and `onShipmentDispatched` and `onShipmentFailed` only an `AWAITING_SHIPMENT` one.

The guard absorbs redelivery and bounds the compensating `Release` to once per order, because `AWAITING_SHIPMENT` to `SHIPPING_FAILED` is reachable by one transition. It also keeps a payment decline and a shipping failure from double-compensating a reservation: an order reaches `AWAITING_SHIPMENT` only after a successful payment, so the two failure reactions are mutually exclusive by construction.

## What an in-memory coordinator doesn't promise

`InMemorySagaService` holds which sagas are in flight, and their compensation and completion callbacks, only on the JVM heap. A restart mid-saga, or a crash between the dispatch step's commit and the emit step's atomic pair, loses that bookkeeping. A `Shipment` row persisted `PENDING` at that moment sits there uncompensated and unconfirmed, because nothing remembers a saga was running. `LRASagaService`, built on a crash-durable distributed coordinator, is the production alternative, deferred for the same reason earlier extractions deferred CDC or a schema registry: new infrastructure the pattern doesn't need to be demonstrated.

`onShipmentFailed` also compensates inventory only. The payment that `payment.captured` already captured isn't refunded; requesting a refund would extend the saga into a second, payment-aware compensation that isn't built. Both limits are recorded in the code that would change to close them, and hardening a coordinator that keeps no ledger belongs with the resilience work.

## Keeping the equivalence suite sound

A longer chain needs the same bounded-wait budget widened without touching the assertion it bounds. Scenario 1's confirm poll grew to `20×750ms` to cover the extra `shipping saga → shipment.dispatched` hop, and its terminal `eql('CONFIRMED')` assertion was left alone. A new "Scenario 4: Shipping-Failure" folder sits behind a `shippingSagaEnabled` flag. A terminal outcome the in-process monolith can't exercise has to be present, inert and marked inert, rather than silently absent. Baselined against the in-process monolith: **121 assertions, 0 failed, Scenario 4 reported as pending**.

Cutover first exposed a tooling gap. newman's `--env-var` and `--global-var` populate a different variable scope than the collection's own `variable` array, so flipping `shippingSagaEnabled=true` from the command line did nothing: Scenario 4 reported pending and the run still passed **127/127**, an easy false green. The fix loads the collection through newman's Node API and patches the in-memory variable, packaged as `demos/lib/run-shipping-newman.js` without rewriting the committed collection.

With the saga engaged, stock for a forced `SHIP-FAIL` checkout was read directly from the inventory service:

```
Captured: examples/06-shipping-service/CUTOVER.md, forced-SHIP-FAIL stock trace

Before checkout: 496 -- Immediately after POST (sync Reserve committed): 495
-- After the bounded-wait observes SHIPPING_FAILED: 496

Net-zero: 496 -> 495 -> 496. Repeated inside the scripted run, a different
order: 478 -> 477 -> 478.
```

A green suite can't tell a firing compensation from one that would pass regardless, so two negative checks forced it red:

- **Compensation disabled.** With the `.compensation(...)` registration commented out and the service rebuilt, a forced `SHIP-FAIL` order went red: **2 of 163 assertions** failed (manual) and **2 of 47** (scripted), the predicted pair. The order stuck at `AWAITING_SHIPMENT` instead of reaching `SHIPPING_FAILED`, and stock stayed decremented. After reverting, Scenario 4 went green: **127/127** (manual) and **16/16** (scripted).
- **Consumer killed.** With the shipping service's only `payment.captured` consumer stopped, a fresh checkout through the proxy went red, **1 of 33**, the order stuck at `AWAITING_SHIPMENT`. After restart, Kafka's at-least-once redelivery drained the backlog within seconds and a fresh checkout went green, **19/19**.

The scripted cutover also exposed a Notification-folder bounded-wait budget that was never widened alongside Scenario 1's and flaked under the longer chain. The fix was budget-only, assertion untouched.

Reversibility was proven as for every extraction: both overrides removed, a direct checkout confirmed the in-process contract, and the full suite re-ran **112/112** (manual) and **114/114** (scripted) with Scenario 4 pending again. Decommission then deleted the monolith's in-process shipping module, `GET :8080/api/shipments` returns `404`, and `strangler.shipping.enabled=true` became the committed proxy default. `.github/workflows/code-ci.yml` gained a fifth job, `shipping-equivalence-gate`, which starts the shipping service and waits for its `payment.captured` consumer group to connect before any checkout runs, because every later job's terminal `CONFIRMED` assertion now depends on it.

> **ADLC in Action**
>
> Frame fixed the orchestration decision first (a bounded saga on `payment.captured` coordinated by a Camel Saga EIP route, with the whole-flow re-expression and the direct shipping-to-inventory shortcut both rejected) plus nine more: the coordinator choice (`InMemorySagaService`, no new infrastructure container), the event topology, step shape and compensation, delegated cross-context compensation, two new order states, deterministic failure injection, the two-phase service, idempotency and two-flag reversibility. Map named four reasons this is harder than choreography: a coordinator now exists, its compensation is coordinator-initiated and crosses services, `CONFIRMED` moves one hop later, and a new failure outcome had to be proven net-zero. Plan sequenced fourteen steps in two parallel lanes with validation gates at nearly every rung, naming a silently failing or diverging compensation as the biggest risk. Generate produced the orchestrator, the new order lifecycle and a proxy route that again didn't need the planned translator. Verify produced the negative checks, the stock traces and the newman scope finding. Operate was the two-flag cutover in `CUTOVER.md`, reversibility at 112/112 then 114/114, and the default flip at decommission. Reconcile marked the smell cured for payment and shipping, not yet for order.

> **Decision**
>
> Orchestrate when the sequence has steps that must run in order and a failure at any of them needs a compensation you can read in one place. Choreograph when participants are independent and the flow can emerge from subscriptions. Whichever you pick, the service that owns the data performs the undo: delegate compensation across a context boundary instead of reaching around it.

> **🤖 Explore with your agent**
>
> "In `examples/06-shipping-service`, read `ShipmentSagaRoute` and `ShipmentSagaSteps`. Add a fifth step, `direct:ship-notify-warehouse`, after `direct:ship-book-carrier` that throws for the sentinel `WH-FAIL`, so that a failure there triggers the existing `direct:ship-compensate` route. Keep compensation delegated: shipping cancels its own row and emits `shipment.failed`, and `OrderSagaListener#onShipmentFailed` issues the inventory `Release`. Don't call inventory from the shipping service."
>
> Check: place a checkout with the `WH-FAIL` sentinel through the proxy on `:8888`, poll `GET /api/orders/{id}` until it reports `SHIPPING_FAILED`, and confirm `GET /api/inventory/{sku}` returns to its starting quantity.

## Build, run, observe

Each example has a run script that builds and starts it. The shipping service listens on `:8088`, the monolith on `:8080` and the proxy on `:8888`. `demos/demo-shipping-cutover.sh` brings up the topology, flips both cutover flags, runs the equivalence suite with Scenario 4 and the Shipping Context Contract folders enabled, flips back and reruns, then runs both negative checks. It reconstructs an intermediate state, so run it from the matching snapshot: `git checkout stage/05-shipping-extracted`. On `main` the proxy is a flagless edge router and the flag flips are no-ops.

A passing run polls a normal order through `AWAITING_SHIPMENT` to `CONFIRMED`, and a `SHIP-FAIL` order to `SHIPPING_FAILED` with stock back at its starting value.

- **Comment out `.compensation(...)` in `ShipmentSagaRoute` and rebuild.** The `SHIP-FAIL` order stays `AWAITING_SHIPMENT` and stock stays decremented.
- **Stop the shipping service.** A fresh checkout stays `AWAITING_SHIPMENT`; restart it and the backlog drains.
- **Pass `--env-var shippingSagaEnabled=true` to plain newman.** Scenario 4 still reports pending; use `demos/lib/run-shipping-newman.js`.

## For deeper coverage

- Chris Richardson, *Microservices Patterns* (Manning, 2018): the orchestration-based saga style; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the process-manager role the Camel Saga EIP route plays in one file.
- Michael Nygard, *Release It!* (2nd ed., Pragmatic Bookshelf, 2018): stability under partial failure, relevant to the in-memory coordinator's crash window.
- [Apache Camel: Saga EIP](https://camel.apache.org/components/next/eips/saga-eip.html): the `.saga()`, `.compensation()` and `.option()` clauses.
- Cloud Native Design Patterns, [Appendix D: Sagas](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/17-appendix-d-sagas.md): the companion book's saga appendix, read against an orchestrated implementation.
