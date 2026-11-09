---
title: "Distributed Transactions, the Saga and Extraction 4: Payment (choreographed)"
order: 23
part: "Coordinating Across Services"
description: "Checkout's synchronous 201/402 contract becomes an asynchronous 202/PENDING handoff, and a choreographed saga captures payment, confirms or compensates, with idempotency at both hops."
duration: 35 minutes
---

## Learning objectives

By the end of this chapter you can:

- Explain how payment's extraction retires the last request-thread catch: `OrderService#placeOrder` stops calling payment, so no `catch` block is left to compensate from.
- Define choreography and orchestration, and say why payment is choreographed: no coordinator, every participant reacting to an event and emitting the next.
- Explain why checkout moves from `201 Created` with a terminal status to `202 Accepted` with a pollable `Location`.
- Trace the two-outbox topology (`order.placed` to `payment.captured` or `payment.declined`) and identify `OrderSagaListener` as the monolith's first Kafka consumer.
- Move compensation from an in-line `catch` to `OrderSagaListener#onPaymentDeclined`, and show that the pre-handoff and post-handoff paths are disjoint by construction.
- Apply two-layer idempotency (application check plus a database unique constraint) at both hops and bound the compensating `Release` to once per order.
- Kill the payment consumer and disable the compensation loop to prove a bounded-wait assertion exercises the real pipeline.

## Why this matters

The Inventory extraction compensated with one `Release` fired from an in-line `catch` on the request thread that discovered the failure. Payment removes the request thread. When a process the checkout call never waits for captures the payment, the decline has to travel as an event, land on another service's consumer and trigger a stock release two network hops away, with nothing synchronous holding the two sides together. The monolith's single `@Transactional` used to buy that consistency as a side effect, and moving payment out of the transaction ends it.

The code is in `examples/05-payment-service/` (the Quarkus saga participant with its own schema and transactional outbox), `examples/00-monolith/` (`OrderService#placeOrder` in its asynchronous form and `OrderSagaListener`, the monolith's first Kafka consumer) and `examples/01-strangler-proxy/` (the `/api/payments` route). The order package later left the monolith, so the monolith code below lives at the snapshot taken after this extraction: `git checkout stage/04-payment-extracted`. The run script in each directory builds and runs it; each `README.md` covers how to drive it.

{% include excalidraw.html file="payment-choreographed-saga-sequence" alt="The full choreographed saga sequence: a client posts to the strangler proxy, which forwards to the monolith; OrderService reserves stock synchronously over gRPC, persists the order PENDING, writes an order.placed outbox row in the same transaction, and returns 202 Accepted. The monolith's OutboxRelay later publishes order.placed to Kafka. The payment service's OrderPlacedConsumer reacts, charges idempotently, and writes CAPTURED or DECLINED to its own outbox in the same transaction as the Payment row; its own PaymentOutboxRelay publishes payment.captured or payment.declined to Kafka. The monolith's OrderSagaListener consumes that topic: on capture it confirms the order and dispatches shipping; on decline it marks the order PAYMENT_DECLINED and issues a compensating gRPC Release against the inventory service." caption="Figure 23.1: The choreographed saga end to end: one synchronous reservation, two asynchronous outboxes, no central coordinator" %}

## Pattern: Saga, choreographed

A **saga** is a sequence of local transactions across services, each with a compensating action for when a later step fails. Two control styles run one:

| Style | Who holds the sequence | Adding a participant | Weakness |
|---|---|---|---|
| Orchestration | A central coordinator calls each step and decides whether to proceed or compensate; participants don't know the sequence | Change the coordinator | The coordinator is infrastructure with its own failure modes |
| Choreography | Nobody; each participant reacts to events it subscribes to and emits the next | A fourth reactor to `payment.captured` needs no change to the first three | The sequence is written down nowhere; you reconstruct it by tracing event names across services |

Payment is choreographed. The monolith's `OrderService` publishes `order.placed` and moves on, unaware a payment service exists. The payment service reacts, decides and publishes `payment.captured` or `payment.declined`, unaware that `OrderSagaListener` will confirm an order or issue a compensating `Release` because of it. The shipping extraction takes the opposite style on the same codebase: Camel's Saga EIP, an explicit in-process orchestrator that knows each step and its compensation by name.

### What the consistency smell looks like after this step

`placeOrder` no longer calls payment at all, not over a slower seam but not at all, so there is no exception for a `catch` to intercept. What Postgres provided automatically, rolling back the inventory decrement when payment declines, is rebuilt explicitly, asynchronously and across processes. `SMELLS.md` records `SMELL[ch.22]` (one ACID transaction spanning order, inventory, payment and shipping) as cured for payment, not yet for shipping or order.

ACD (atomic, consistent, durable, with isolation dropped) names the ceiling: each local step stays atomic and durable, the aggregate ends up consistent once the saga completes or compensates, but nothing isolates the whole sequence. A client can see an order `PENDING` for several seconds while payment is in flight, a state the old checkout could never expose. The window is explicit, bounded and provably reversible. It isn't gone.

## The checkout contract: `202`, not `201` with `PENDING`

Every earlier extraction kept `POST /api/orders` fixed at `201 Created` with a terminal `CONFIRMED`, or `402 Payment Required`, decided before the response. Payment can't keep that promise, because whether the charge succeeded is unknown at response time:

```java
// examples/00-monolith/.../order/OrderController.java
/**
 * OrderService#placeOrder} now always returns the order {@code PENDING}
 * (payment is captured out-of-process, over the choreographed saga), so
 * this always returns {@code 202 Accepted} with a {@code Location}
 * header. The synchronous {@code 201 Created}/{@code 402} contract this
 * endpoint used to also support ... was removed along with that flag.
 */
public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody OrderCreate command) {
    OrderDto dto = service.placeOrder(command);
    URI location = URI.create("/api/orders/" + dto.id());
    return ResponseEntity.accepted().location(location).body(dto);
}
```

RFC 9110 defines `202` as "the request has been accepted for processing, but the processing has not been completed." The order is still created synchronously and pollable at `GET /api/orders/{id}` the instant the `202` returns, carrying `status: PENDING` until the saga settles. Returning `201` with `PENDING` was considered and rejected because `201` asserts the resource now exists in the state described, and clients, caches and monitors may rely on that. An order that can flip to `PAYMENT_DECLINED` seconds later isn't described by the response that announced it. The `OrderStatus` vocabulary (`PENDING`, `CONFIRMED`, `PAYMENT_DECLINED`) is unchanged; only the moment the terminal value settles moved off the request thread.

{% include excalidraw.html file="checkout-sync-async-contract" alt="Side-by-side before and after of the checkout HTTP contract. Before: placeOrder reserves stock, charges payment in-process, confirms the order, and dispatches shipping, all before the response; the POST returns 201 Created with status CONFIRMED, or 402 Payment Required with nothing persisted. After: placeOrder still reserves stock synchronously, but persists the order PENDING and returns 202 Accepted plus a Location header immediately; the terminal status, CONFIRMED or PAYMENT_DECLINED, is reached later over the choreography, and the client polls GET /api/orders/{id} to observe it." caption="Figure 23.2: The checkout contract, before and after: the same three-value OrderStatus vocabulary, decided synchronously versus observed by polling" %}

{% include codetabs.html langs="Before: synchronous checkout, payment in-line (removed)|After: PENDING + handoff, payment out-of-process (current)" %}

```java
// order/OrderService.java#placeOrder: payment.mode=synchronous (removed)
// ... reserve each line via RemoteInventoryClient, track remoteReservations ...
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

```java
// examples/00-monolith/.../order/OrderService.java#placeOrder: current
// ... reserve each line via RemoteInventoryClient, track remoteReservations ...
// The order is persisted PENDING; payment and shipping no longer run
// in-line. order.placed (carrying paymentMethod) is the handoff
// to the payment service, which reacts over Kafka and emits
// payment.captured|declined. OrderSagaListener reacts to either outcome.
writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
return toDto(order);
```

The Notification extraction moved one synchronous call to an outbox write. This one removes three: `charge`, `confirm` and `dispatch` all disappear from `placeOrder`, and none of them can happen until an event neither side controls arrives back.

## Pattern: Transactional Outbox, twice

`order.placed` (from the monolith, carrying the line items and `paymentMethod`) reaches the payment service, which produces `payment.captured` or `payment.declined`. Both producers write through their own transactional outbox. The payment service's outbox is new code, because the monolith never consumed its own events and there is no Spring original to lift:

```java
// examples/05-payment-service/.../OrderPlacedConsumer.java
@ApplicationScoped
public class OrderPlacedConsumer {

    private final PaymentService service;

    public OrderPlacedConsumer(PaymentService service) {
        this.service = service;
    }

    @Incoming("order-placed")
    public void consume(OrderPlacedEvent event) {
        service.processOrderPlaced(event);
        LOG.infof("consumed order.placed for order %d (amount=%d)", event.orderId(), event.totalCents());
    }
}
```

`processOrderPlaced` captures or declines, persists the `Payment` row and writes a `PaymentOutboxEvent` row in one `@Transactional` method. A second `@Scheduled` relay, `PaymentOutboxRelay`, publishes unpublished rows to the topic matching each row's `eventType`, through a programmatic `Emitter` because the topic is a runtime decision:

```java
// examples/05-payment-service/.../PaymentOutboxRelay.java
@Scheduled(every = "${payment.outbox.relay.poll-interval:2s}")
@Transactional
void publishUnpublishedEvents() {
    List<PaymentOutboxEvent> batch = outboxRepository.findUnpublished(BATCH_SIZE);
    for (PaymentOutboxEvent event : batch) {
        publishOne(event);
    }
}
```

The relay blocks on the broker's acknowledgment before stamping `publishedAt`, the same at-least-once shape as the monolith's relay. `OrderSagaListener` is the monolith's first Kafka consumer; until now it only produced.

## Compensation as a reaction

The compensating `Release` for a decline moves from `placeOrder`'s in-line `catch` to `OrderSagaListener#onPaymentDeclined`. The inventory client's `release` has no idempotency key yet, a limit deferred to this saga. The two compensation paths are disjoint by construction. The catch now compensates only a pre-handoff failure:

```java
// examples/00-monolith/.../order/OrderService.java: current catch
} catch (RuntimeException ex) {
    // this compensates ONLY a pre-handoff failure
    // now (a later line out of stock, or the save/outbox write itself
    // throwing), never a payment decline, since payment is no longer
    // called from this method at all.
    compensateRemoteReservations(remoteReservations);
    throw ex;
}
```

A multi-line order whose second line is out of stock still releases the first line's reservation. The decline path lives in the reaction:

```java
// examples/00-monolith/.../order/OrderSagaListener.java
@KafkaListener(topics = Topics.PAYMENT_DECLINED)
@Transactional
public void onPaymentDeclined(String payload) {
    PaymentDeclined event = deserialize(payload, PaymentDeclined.class);
    Order order = orderRepository.findById(event.orderId()).orElse(null);
    if (order.getStatus() != OrderStatus.PENDING) {
        return; // idempotency guard, see below
    }
    order.declinePayment();
    orderRepository.save(order);
    for (OrderItem item : order.getItems()) {
        try {
            remoteInventoryClient.release(item.getSku(), item.getQuantity());
        } catch (RuntimeException releaseFailure) {
            LOG.error("compensation FAILED for order={} sku={} qty={} — stock NOT restored",
                    order.getId(), item.getSku(), item.getQuantity(), releaseFailure);
        }
    }
}
```

The catch fires only for a checkout that never reached the saga (no `order.placed` row was committed). The listener fires only for an order that did, because it needs a `PENDING` row to transition out of. Neither sees the other's orders, so neither releases the same reservation twice. The undo now survives two asynchronous hops with no thread connecting the request to the eventual compensation.

{% include excalidraw.html file="payment-compensation-choreography" alt="Two stacked diagrams contrasting compensation styles. Top, the earlier shape: OrderService's own try/catch calls the compensating gRPC Release directly on the same request thread the moment an in-process payment charge throws. Bottom, the choreographed shape: there is no request-thread catch for a payment decline anymore; the payment service emits payment.declined via its own transactional outbox, the monolith's OrderSagaListener consumes it across two asynchronous hops, and the reaction issues the same compensating gRPC Release, converging to net-zero stock either way." caption="Figure 23.3: Compensation before and after, from a request-thread catch to a two-hop choreographed reaction with the same compensating call and the same net-zero result" %}

## Idempotency: at-least-once delivery, exactly-once effect

Both outbox relays are at-least-once. A crash between a broker ack and the `publishedAt` stamp, or a consumer-group rebalance replaying an uncommitted offset, delivers an event twice. Each hop uses the same two layers: an application check first, a database constraint as the backstop.

The payment service looks for an existing `Payment` by `orderId` before doing anything:

```java
// examples/05-payment-service/.../PaymentService.java
@Transactional
public void processOrderPlaced(OrderPlacedEvent event) {
    if (repository.findByOrderId(event.orderId()) != null) {
        LOG.infof("skipping duplicate order.placed for order %d (payment already recorded)", event.orderId());
        return;
    }
    Payment payment = charge(event.orderId(), event.totalCents(), event.paymentMethod());
    repository.persist(payment);
    PaymentOutboxEvent outboxEvent = buildOutboxEvent(payment);
    outboxRepository.persist(outboxEvent);
}
```

A redelivered `order.placed` is a no-op: no second charge, no second outbox row. The unique index `uq_payments_order_id` covers two deliveries racing concurrently. On the monolith side the guard is a status check. It absorbs redelivered `payment.captured` and `payment.declined` events, and it bounds the compensating `Release` to at most once per order, because the only reachable transition is `PENDING` to `PAYMENT_DECLINED` and the guard refuses to repeat it. Idempotency by `orderId` bounds the physical side effects (a card charge, a stock release) regardless of how many times the triggering event arrives.

> **ADLC in Action**
>
> Frame fixed nine decisions: the synchronous-to-asynchronous checkout contract, the choreographed topology, compensation moved from a catch to a `payment.declined` reaction, saga state on the order aggregate, idempotency by `orderId`, the payment service's read surface, its own outbox, two-flag reversibility, and the bounded-wait equivalence discipline extended to checkout. Plan sequenced fourteen steps in two parallel lanes (the event contract and the new service's scaffold), with six validation gates named before any consumer or outbox code existed, most pointedly at the cutover where a forced decline had to converge to net-zero stock across two asynchronous hops. Generate produced the service, the event contract, the monolith's first consumer and a proxy route that didn't need the translator the plan had assumed, following `migrate-spring-to-quarkus` for the lifted read surface. Verify produced the negative checks and stock traces below, plus a suite gap found and closed before decommission. Operate was the two-flag cutover recorded in `CUTOVER.md`, reversibility at 79/79 with zero retries, and the committed-default flip at decommission. Reconcile marked the smell cured for payment, not yet for shipping or order.

## Keeping the equivalence suite sound

The equivalence suite's two critical payment assertions, the happy path in Scenario 1 and the decline in Scenario 3, are why this extraction exists, and weakening either to pass would make Scenario 3 prove nothing. The `POST` assertion became transport-tolerant (`201` from the synchronous monolith or `202` from the saga). The outcome assertion stays strict: a bounded-wait poll of `GET /api/orders/{id}` for the exact terminal status (`CONFIRMED` for Scenario 1, `PAYMENT_DECLINED` for Scenario 3). Scenario 3 adds a second poll of `GET /api/inventory/{sku}` until stock returns to its pre-checkout value, the only way to confirm that the choreographed `Release` ran and not merely that the status field changed. The recorded forced `CARD-DECLINE` checkouts show stock going 96, 95, 96, and on other runs 88, 87, 88 and 72, 71, 72.

A bounded-wait assertion proves nothing until it is forced red. With the payment service's consumer stopped and a fresh checkout placed, Scenario 1 fails:

```
Captured: examples/05-payment-service/CUTOVER.md, negative check

1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (10 x 500ms)
                   order 177 status after 10 attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'

RED: newman exit code 1, 1 of 23 assertions failed.
```

With no consumer to react to `order.placed`, the order stays `PENDING`. Restarting the service and re-running the folder on a fresh checkout went green, 20/20 assertions, as Kafka redelivered the backlog. The compensation path got the same treatment. With the full suite green at 108/108 through the proxy, the `Release` loop inside `onPaymentDeclined` was commented out locally and the monolith restarted. Scenario 3 went red, 1 of 22 assertions, isolated to the net-zero inventory check while the decline-status assertion still passed. After reverting, the full suite returned to 99/99.

The cutover also found a gap: the collection had no "Payment Context Contract" folder yet, and a Notification folder's checkout step still asserted the retired synchronous `201` and `CONFIRMED`. Both were closed before decommission.

> **Decision**
>
> Choreograph a saga when each participant can act on an event without knowing the sequence and the sequence has few steps. Return `202` with a pollable resource when the outcome isn't known at response time. Make every consumer idempotent by a business key, with a unique constraint as the backstop. Orchestrate instead when the sequence must be readable in one place.

> **🤖 Explore with your agent**
>
> "In `examples/05-payment-service` and `examples/07-order-service` (where `OrderSagaListener` lives now that order is extracted), add a `payment.refunded` event that the payment service emits through its own outbox, and an `OrderSagaListener` reaction that moves a `CONFIRMED` order to a new `REFUNDED` status. Keep the reaction idempotent by `orderId` with a status guard, back the payment side with a unique constraint, and don't call the payment service from `placeOrder`."
>
> Check: replay the same `payment.refunded` message twice, then query `GET /api/orders/{id}` and confirm one transition. Run `./mvnw test` in both services and confirm they stay green.

## Build, run, observe

Each example has a run script that builds and starts it. The monolith listens on `:8080`, the payment service on `:8085` and the proxy on `:8888`. `demos/demo-payment-cutover.sh` brings up the whole topology, flips both cutover flags, runs the equivalence suite through the proxy, flips them back and runs it again, then runs the negative check. It reconstructs an intermediate state, so run it from the matching snapshot: `git checkout stage/04-payment-extracted`. On `main` the proxy is a flagless edge router and the flag flips are no-ops.

A passing run shows `POST /api/orders` returning `202` with a `Location` header, the order polling to `CONFIRMED`, and a `CARD-DECLINE` checkout polling to `PAYMENT_DECLINED` with stock back at its starting value.

- **Stop the payment service's consumer.** A fresh checkout stays `PENDING` and the bounded-wait check goes red. Restart it and Kafka redelivers the backlog until the check goes green.
- **Comment out the `Release` loop in `onPaymentDeclined`.** The decline status still passes but stock stays decremented, so the net-zero check goes red.
- **Flip both cutover flags back.** Checkout returns to the synchronous `201`/`402` contract and the full suite stays green.

## For deeper coverage

- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga pattern and its choreography-based style; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the publish-subscribe and event-message vocabulary the Kafka choreography uses.
- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the incremental extraction playbook, including the synchronous-to-asynchronous contract shift.
- [RFC 9110, HTTP Semantics, section 15.3.3, 202 Accepted](https://www.rfc-editor.org/rfc/rfc9110.html#name-202-accepted): the definition of the status code the checkout contract relies on.
- Cloud Native Design Patterns, [Appendix D: Sagas](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/17-appendix-d-sagas.md): the companion book's saga appendix.
