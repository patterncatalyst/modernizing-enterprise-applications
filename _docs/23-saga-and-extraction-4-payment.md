---
title: "Distributed Transactions, the Saga and Extraction 4: Payment (choreographed)"
order: 23
part: "Coordinating Across Services"
description: "ACID gives way to ACD; checkout's synchronous 201/402 contract becomes an asynchronous 202/PENDING handoff; a choreographed saga captures payment, confirms or compensates, and the equivalence suite is taught to verify an outcome it can no longer observe on the same request."
duration: 35 minutes
---

## Learning objectives

By the end of this chapter you can:

- Explain how payment's extraction retires the last request-thread crutch from SMELL[ch.22]: `OrderService#placeOrder` stops calling payment at all, so there is no `catch` block left to compensate from.
- Define choreography versus orchestration as two saga control styles, and explain why this chapter picks choreography: no coordinator, every participant reacting to an event and emitting the next one.
- Explain why the checkout contract changes from `201 Created` with a terminal status to `202 Accepted` with a pollable `Location`, and why `202` is the accurate status code for a decision not yet known at response time.
- Trace the two-outbox event topology (`order.placed` to `payment.captured`/`payment.declined`) and identify `OrderSagaListener` as the monolith's first Kafka consumer.
- Explain how compensation moves from an in-line `catch` to `OrderSagaListener#onPaymentDeclined`, and why the pre-handoff and post-handoff compensation paths are disjoint by construction, not convention.
- Apply the two-layer idempotency pattern (application check plus a database unique constraint) at both hops of the chain, and explain how it bounds the compensating `Release` to firing at most once per order.
- Use a negative check (killing the payment consumer, disabling the compensation loop) to prove a bounded-wait assertion is testing the real pipeline rather than passing by coincidence.

## Why this matters

Chapter 19 took the smallest possible bite out of saga compensation: one
reservation, one compensating `Release`, fired from an in-line `catch` block
on the same request thread that discovered the failure. That was a narrow
scope, documented in its own javadoc. This chapter removes the request thread
entirely. Once payment is captured by a process the checkout call never waits
for, there is no `catch` left to put the compensation in; the decline has to
travel as an event, land on a different service's consumer, and trigger a
release of stock from across two network hops, with nothing synchronous
holding the two sides together. Chapter 22 named the realization this chapter
cashes in: the monolith's one in-process `@Transactional` used to buy
checkout's cross-context consistency as a side effect, and once payment moves
out of that transaction, that side effect is gone. Everything below is
running code already committed to this repository: `examples/05-payment-service/` (the
Quarkus choreographed-saga participant, its own schema, its own transactional
outbox), `examples/00-monolith/` (`OrderService#placeOrder`'s current
asynchronous form and `OrderSagaListener`, the monolith's first Kafka
*consumer*), and `examples/01-strangler-proxy/` (the `/api/payments` route).
This is Part 7, "Coordinating Across Services": the part of the book where
extractions stop moving one context at a time and start coordinating several
of them across a boundary neither side fully controls.

The code is in `examples/05-payment-service/` and `examples/00-monolith/`.
The run script in each directory builds/sets up and runs it; each
`README.md` covers what it does and how to drive it.

{% include excalidraw.html file="payment-choreographed-saga-sequence" alt="The full choreographed saga sequence: a client posts to the strangler proxy, which forwards to the monolith; OrderService reserves stock synchronously over gRPC, persists the order PENDING, writes an order.placed outbox row in the same transaction, and returns 202 Accepted. The monolith's OutboxRelay later publishes order.placed to Kafka. The payment service's OrderPlacedConsumer reacts, charges idempotently, and writes CAPTURED or DECLINED to its own outbox in the same transaction as the Payment row; its own PaymentOutboxRelay publishes payment.captured or payment.declined to Kafka. The monolith's OrderSagaListener consumes that topic: on capture it confirms the order and dispatches shipping; on decline it marks the order PAYMENT_DECLINED and issues a compensating gRPC Release against the inventory service." caption="Figure 23.1: The choreographed saga, end to end: one synchronous reservation, two asynchronous outboxes, no central coordinator" %}

## ACID gives way to ACD: SMELL[ch.22] paid off, for payment

Chapter 22 gave this project's central consistency smell a name and a
trajectory without curing it: the monolith's single ACID transaction spans
order, inventory, payment, and shipping, and every extraction before this one
has carved a piece off that transaction while finding some other mechanism to
hold the illusion of atomicity together. Review needed nothing, because it
never wrote. Notification needed an outbox, because it wrote but nothing else
depended on its write succeeding. Inventory needed a compensating `Release`,
because it both wrote and sat upstream of a payment decision that might still
undo it. But the *trigger* for that compensation was still a request-thread
`catch`, because payment was still called in-process at that point in the
book's history.

Payment's extraction is the step where that last crutch goes away. Once
`OrderService#placeOrder` stops calling payment at all (not "calls it, but
over a slower seam," but stops calling it outright), there is no exception
for a `catch` block to intercept, because the method that used to throw it no
longer executes inside `placeOrder`'s stack at all. `examples/00-monolith`'s
`SMELLS.md` records the result in its own ledger discipline, the same one
Chapter 19 used to mark its own partial progress:

```
Captured — examples/00-monolith/SMELLS.md, SMELL[ch.22] excerpt

Realized for payment in ch.23 -- SMELL[ch.22]'s
ACID -> ACD story cashed in for this context. OrderService#placeOrder no
longer calls payment at all ... What Postgres used to give "for free" --
roll back the inventory decrement when payment declines, inside one local
transaction -- is now rebuilt EXPLICITLY, asynchronously, cross-process ...
The smell is therefore NOT struck through above: it is cured for payment,
not yet for shipping/order, which is exactly the resume boundary ch.24/ch.26
pick up.
```

That entry is worth reading slowly, because the qualification in its last
sentence is the actual lesson, not a hedge. **ACD** (atomic, consistent,
durable, with isolation dropped from the acronym) names a saga's
real ceiling: each local step is still atomic and durable in its own
database, the aggregate still ends up consistent once the saga completes or
compensates, but there is no isolation spanning the whole sequence the way a
single transaction's locks provide. A reader could, in principle, observe an
order sitting `PENDING` for several seconds while its payment is still in
flight: a state the old synchronous checkout could never expose, because the
whole operation was invisible to any other transaction until it committed or
rolled back as one unit. This chapter does not eliminate that window. It
makes the window explicit, bounded, and provably reversible, which is a
different and more accurate claim than "the inconsistency is gone" would be.
It is also exactly as far as this one extraction's smell closure goes.
Shipping and order still live inside the smell Chapter 22 named; this chapter
only closes the payment slice of it.

## Choreography, not orchestration: the contrast with Chapter 24

Before the mechanics: this chapter chooses one control style, and Chapter 24
chooses the opposite one. A **saga** is a sequence of local transactions coordinated across
services, each with a compensating action for when a later step fails. There
are two ways to run one. In an **orchestrated** saga, a central coordinator
holds the sequence: it calls step one, waits for the result, decides whether
to call step two or trigger a compensation, and the participants themselves
know nothing about the sequence they're part of. In a **choreographed** saga,
there is no coordinator at all: every participant reacts to events it
subscribes to and emits the next event in the chain, and the sequence exists
only as the sum of those independent reactions.

This chapter's saga is choreographed. The monolith's `OrderService` publishes
`order.placed` and moves on: it has no idea that a payment service exists,
let alone what that service decides. The payment service reacts to
`order.placed`, decides, and publishes `payment.captured` or
`payment.declined`: it has no idea that a monolith is listening, or that an
`OrderSagaListener` is about to confirm an order or issue a compensating
`Release` because of what it just published. Nobody holds the whole sequence
in one place; it only exists as an emergent property of three independent
reactions wired together by topic names. This design has real tradeoffs:
choreography scales cleanly
as participants are added (a fourth reactor to `payment.captured` needs no
change to the first three), but the overall sequence is nowhere written
down in code: a reader has to reconstruct it by tracing event names across
services, exactly the exercise this chapter's diagrams exist to shortcut.
Chapter 24's shipping extraction is the foil: it coordinates a
comparable multi-step sequence through Camel's **Saga EIP**, an explicit
in-process orchestrator that knows the steps and their compensations by
name. Reading the two chapters back to back is the point: the same
coordination problem, solved with opposite control styles, so that the
tradeoff is felt rather than just asserted.

## The sync→async checkout contract: why `202`, not `201`-with-`PENDING`

The externally-observable change this chapter makes is the sharpest one any
extraction in this book has made so far. Every prior extraction kept
`POST /api/orders`'s contract fixed (`201 Created` with a terminal
`CONFIRMED`/`402 Payment Required` outcome, decided before the response was
written) and moved everything else around it. Payment's extraction cannot
keep that promise, because the fact the response used to report (did the
charge succeed) is no longer known at response time. `OrderController`'s own
javadoc states the new contract:

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

`202 Accepted` is the correct status code for exactly this situation: RFC
9110's own wording is "the request has been accepted for processing, but the
processing has not been completed." The order resource is still created
synchronously and pollable: `GET /api/orders/{id}` works the instant the
`202` returns, same as before, just carrying `status: PENDING` until the saga
reaches a terminal state. This project's own plan considered, and rejected,
an alternative that looks tempting from a distance: keep returning `201
Created` immediately, with the order's status field set to `PENDING`. The
reason that alternative was rejected is about what the status code itself
asserts, not about the body. A `201` response is a specific claim: "the
requested resource now exists, in the state described." REST clients,
caches, and monitoring systems are entitled to treat it that way. An order
that might still silently flip to `PAYMENT_DECLINED` seconds later is not
fully described by the response that announced its creation; `202` is the
status code built for precisely this case, where "accepted" and "finished"
are different moments, and choosing it over a `201` that oversells what is
actually known at response time is what keeps the contract accurate rather
than merely convenient. `examples/00-monolith`'s `common.OrderStatus` enum
(`PENDING`, `CONFIRMED`, `PAYMENT_DECLINED`) is the same three-value
vocabulary a client already polls against; only the moment the terminal value
settles has moved off the request thread.

{% include excalidraw.html file="checkout-sync-async-contract" alt="Side-by-side before and after of the checkout HTTP contract. Before: placeOrder reserves stock, charges payment in-process, confirms the order, and dispatches shipping, all before the response; the POST returns 201 Created with status CONFIRMED, or 402 Payment Required with nothing persisted. After: placeOrder still reserves stock synchronously, but persists the order PENDING and returns 202 Accepted plus a Location header immediately; the terminal status, CONFIRMED or PAYMENT_DECLINED, is reached later over the choreography, and the client polls GET /api/orders/{id} to observe it." caption="Figure 23.2: The checkout contract, before and after: the same three-value OrderStatus vocabulary, decided synchronously versus observed by polling" %}

{% include codetabs.html langs="Before: synchronous checkout, payment in-line (removed)|After: PENDING + handoff, payment out-of-process (current)" %}

```java
// order/OrderService.java#placeOrder — payment.mode=synchronous (removed)
// ... reserve each line via RemoteInventoryClient, track remoteReservations ...
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

```java
// examples/00-monolith/.../order/OrderService.java#placeOrder — current
// ... reserve each line via RemoteInventoryClient, track remoteReservations ...
// The order is persisted PENDING; payment and shipping no longer run
// in-line. order.placed (carrying paymentMethod) is the handoff
// to the payment service, which reacts over Kafka and emits
// payment.captured|declined. OrderSagaListener reacts to either outcome.
writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
return toDto(order);
```

The two panels are almost the same shape as Chapter 17's own before/after
(again, curing a coupling problem removes a blocking call rather than adding
code), but notice what disappeared entirely this time, not just moved: the
`paymentService.charge(...)` call, the `order.confirm()` call, and the
`shippingService.dispatch(...)` call are all gone from `placeOrder` itself.
Chapter 17 moved *one* synchronous call to an outbox write. This chapter
moves *three*: confirming the order and dispatching shipping both
used to happen only after payment succeeded, and now none of the three can
happen until an event neither side of this method controls arrives back.

## The event topology: two outboxes, no coordinator

The choreography's event vocabulary is fixed before a line of either side's
code exists: `order.placed` (produced by the
monolith, carrying the order's line items and, now, its `paymentMethod`)
reaches the payment service, which captures or declines and produces
`payment.captured` or `payment.declined`; the monolith consumes whichever one
arrives and reacts. Two services, two topics each carrying one direction of
the conversation; **both
producers write through their own transactional outbox**, the same pattern
Chapter 17 introduced for notification and Chapter 19 reused for nothing
(inventory's `Reserve`/`Release` are synchronous RPCs, not outbox events).
The payment service's own outbox is net-new code, because the monolith never
consumed its own events before this chapter and there is no Spring original
to lift it from:

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

`processOrderPlaced` is where the actual choreography core lives, and it
earns its own section below for idempotency; the one-sentence summary
that matters here is that it captures or declines, persists the `Payment`
row, and writes a `PaymentOutboxEvent` row, all inside the *same*
`@Transactional` method, exactly mirroring the discipline
`OutboxRelay`/`OutboxEvent` established for the monolith back in Chapter 17.
A second, independent `@Scheduled` relay, `PaymentOutboxRelay`, reads those
rows and publishes to whichever topic matches each row's `eventType`, via a
programmatic `Emitter` rather than a static `@Outgoing` method, because the
topic a given row needs is a runtime decision, not a compile-time one:

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

That relay blocks on the broker's acknowledgment before stamping
`publishedAt`: the identical at-least-once shape Chapter 17's relay uses,
reused rather than reinvented for this service. The monolith's side of the
topology needed something new, though: until this chapter, the monolith only
ever *produced* to Kafka (the `order.placed` outbox relay). `OrderSagaListener`
is its first Kafka **consumer**, and its own class javadoc names that
milestone directly: "the monolith only talks to Kafka one direction" was a
standing architectural fact for five chapters, and this is the chapter where
it stops being true.

## Compensation via choreography: the catch block retires for this path

Chapter 19 left a documented gap in `RemoteInventoryClient#release`'s own
javadoc: no idempotency key yet, a limitation deferred to
Chapter 23's full saga, not speculative compensation infrastructure this
extraction does not need yet. This is that chapter, and the compensating `Release` for a payment decline
now moves from `OrderService#placeOrder`'s in-line `catch` block to
`OrderSagaListener#onPaymentDeclined`'s reaction to the `payment.declined`
event. The two compensation paths are not duplicated: they are made
disjoint by construction, and `OrderService#placeOrder`'s own current javadoc
explains exactly why neither can double-fire:

```java
// examples/00-monolith/.../order/OrderService.java — current catch
} catch (RuntimeException ex) {
    // this compensates ONLY a pre-handoff failure
    // now (a later line out of stock, or the save/outbox write itself
    // throwing) — never a payment decline, since payment is no longer
    // called from this method at all.
    compensateRemoteReservations(remoteReservations);
    throw ex;
}
```

The catch can still fire (a multi-line order whose second line is out of
stock still has to release the first line's reservation), but it can no
longer be reached by a payment decline, because `placeOrder` never calls
payment. The decline-path compensation now lives entirely in the reaction:

```java
// examples/00-monolith/.../order/OrderSagaListener.java
@KafkaListener(topics = Topics.PAYMENT_DECLINED)
@Transactional
public void onPaymentDeclined(String payload) {
    PaymentDeclined event = deserialize(payload, PaymentDeclined.class);
    Order order = orderRepository.findById(event.orderId()).orElse(null);
    if (order.getStatus() != OrderStatus.PENDING) {
        return; // idempotency guard — see below
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

The two paths are mutually exclusive, not just conventionally separated: the
catch only fires for a checkout that never reached the saga at all (no
`order.placed` row was ever committed for it), and the listener's reaction
only fires for an order that *did* reach the saga, because it needs a
`PENDING` row to transition out of. Neither can observe the other's order, so
neither can release the same reservation twice. This is the "the database
undid it for me" versus "I had to write the undo myself" distinction Chapter
19 introduced, pushed one more step: Chapter 19 still had a request thread to
write the undo *on*; this chapter's undo has to survive across two
asynchronous hops (the payment service's own outbox publish, then the
monolith's consumer reacting to it), with no thread left connecting the
original request to the eventual compensation at all.

{% include excalidraw.html file="payment-compensation-choreography" alt="Two stacked diagrams contrasting compensation styles. Top, the ch.19 shape: OrderService's own try/catch calls the compensating gRPC Release directly on the same request thread the moment an in-process payment charge throws. Bottom, the ch.23 shape: there is no request-thread catch for a payment decline anymore; the payment service emits payment.declined via its own transactional outbox, the monolith's OrderSagaListener consumes it across two asynchronous hops, and the reaction issues the same compensating gRPC Release, converging to net-zero stock either way." caption="Figure 23.3: Compensation, before and after: from a request-thread catch to a two-hop choreographed reaction, same compensating call, same net-zero result" %}

## Idempotency: at-least-once delivery, exactly-once effect

Every outbox relay in this project is at-least-once, never exactly-once, and
this chapter has two of them chained together (the monolith's `order.placed`
relay, then the payment service's own `payment.captured`/`payment.declined`
relay), which means a single logical event can arrive at its consumer more
than once for entirely mundane reasons (a crash between a broker ack and a
`publishedAt` stamp, a consumer-group rebalance replaying an uncommitted
offset). Redelivery is made safe on both ends of
the chain by the same two-layer pattern Chapter 17 established for
notification's consumer: an application-level check first, a database
constraint as the backstop.

On the payment service's side, `processOrderPlaced` checks for an existing
`Payment` by `orderId` before doing anything else:

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

A redelivered `order.placed` for an order already charged is a safe no-op
(no second charge, no second outbox row), and `uq_payments_order_id`, a
database-level unique index, is the backstop for two deliveries racing
concurrently rather than arriving one after another. On the monolith's side,
`OrderSagaListener`'s idempotency guard is a status check rather than a
lookup, and it does double duty: it makes a redelivered `payment.captured` or
`payment.declined` a no-op, *and* it is the mechanism that guarantees the
compensating `Release` fires **at most once** per order, because the only
reachable transition is `PENDING` → `PAYMENT_DECLINED`, and once an order has
made that transition the guard refuses to make it again. "Idempotent by
`orderId`" is doing two jobs in this chapter that it only had to do one of in
Chapter 17: absorbing redelivery, and bounding how many times a physical
side-effect (a card charge, a stock release) is allowed to happen no matter
how many times the event that triggers it arrives.

## Keeping the equivalence suite sound across a contract it can no longer observe synchronously

This is where Chapter 17's bounded-wait idiom had to be
extended, and the stakes are higher here than they were for
notification, because this suite's two critical payment assertions
(Scenario 1's happy path, Scenario 3's decline) are exactly the reason this
chapter exists. A suite that weakened either one to pass would make Scenario
3 prove nothing. The approach is the same shape Chapter 17 taught, applied to
a harder case: the `POST` assertion becomes transport-tolerant (`201` from
the still-synchronous monolith, or `202` from the choreographed saga, either
is acceptable), while the *outcome* assertion stays strict and is re-expressed
as a bounded-wait poll of `GET /api/orders/{id}` for the exact terminal
status (`CONFIRMED` for Scenario 1, `PAYMENT_DECLINED` for Scenario 3), with
no relaxation of what "declined" has to mean. Scenario 3 adds a second
bounded-wait poll on top of the first: once the decline is observed, the
suite polls `GET /api/inventory/{sku}` until stock returns to its pre-checkout
value, which is the suite's only way to confirm that the choreographed
`Release` actually ran, not merely that the order's status field changed.

Non-vacuity is the harder half of this claim, and it is proven the same way
Chapter 17 proved it: by killing the one process the choreography depends on
and forcing the suite red. With the payment service's consumer stopped, a
fresh checkout was placed and Scenario 1 re-run:

```
Captured — examples/05-payment-service/CUTOVER.md, negative check

1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (10 x 500ms)
                   order 177 status after 10 attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'

RED — newman exit code 1, 1 of 23 assertions failed.
```

With no consumer left to react to `order.placed`, the order is stuck
`PENDING` forever, and the suite reports exactly that rather than passing by
coincidence. Restarting the payment service and re-running the identical
folder on a fresh checkout produced **GREEN: 20/20 assertions, newman exit
code 0** within seconds, Kafka's at-least-once redelivery draining the
backlog exactly as the design predicts. This project's own CI gate re-proved
the same discipline on the compensation path specifically, not just the
confirmation path: with the full suite green at **108/108** through the
proxy, the compensating `Release` loop inside `OrderSagaListener.onPaymentDeclined`
was commented out in a local working copy to force the failure, the monolith rebuilt
and restarted, and Scenario 3 alone re-run: RED, **1 of 22 assertions**
failed, isolated precisely to the net-zero inventory check while the decline
status assertion still passed, proving the failure was the missing
compensation and nothing else. The edit was reverted and the full suite
returned to **99/99** green before the job was trusted. A cutover run also
found a gap: the first pass through this chapter's acceptance criteria found
that the Newman collection had no "Payment Context Contract" folder yet, and
that an unrelated Notification folder's own checkout step still asserted the
retired synchronous `201`/`CONFIRMED` contract directly. Both were closed in
a follow-up step before decommission, and both now run as part of the same
unedited collection every later run exercises.

> **ADLC in Action:** This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 19 demonstrated for
> Inventory, at its hardest rung yet. Frame fixed nine decisions up front in
> `decisions.md` (the synchronous-to-asynchronous
> checkout contract, the choreographed event topology, compensation re-plumbed
> from a catch block to a `payment.declined` reaction, saga state on the order
> aggregate, idempotency by `orderId`, the payment service's two-phase read
> surface with an idiomatic-from-day-one consumer/producer, its own
> transactional outbox, two-flag reversibility, and the bounded-wait
> equivalence discipline extended to the checkout path). Plan laid out
> fourteen steps across two parallel lanes (the event contract and the new
> service's scaffold), with six reasoning-model validation gates named before a line of
> consumer or outbox code existed, most pointedly at the cutover step where a
> forced decline had to converge to net-zero stock across two asynchronous
> hops with no request-thread catch to lean on. Generate produced the service,
> the event contract, the monolith's first Kafka consumer, and a proxy route
> that turned out not to need the translator the plan had assumed, under
> quarkus-agent and a scaffolding skill, following `migrate-spring-to-quarkus`
> for the lifted read surface. Verify is this chapter's negative check and its
> real stock numbers (96→95→96, 88→87→88, 72→71→72), plus a suite gap found
> and closed before decommission rather than left standing. Operate is the
> two-flag cutover `CUTOVER.md` records end to end, reversibility proven at
> 79/79 with zero retries, and the committed-default flip at decommission.
> Reconcile is `SMELLS.md` marking Smell[ch.22] cured for payment, not yet
> for shipping or order, with the orchestrated-saga contrast in Chapter 24 as
> the named resume boundary.

## What you learned

- **ACID gives way to ACD** the moment a transaction's scope stops spanning a
  consistency guarantee: each local step stays atomic and durable, but
  isolation across the whole sequence is gone, and a saga rebuilds
  consistency explicitly where the database used to provide it as a side
  effect of one write-ahead log.
- **Choreography and orchestration are opposite control styles for the same
  coordination problem.** No participant in this chapter's saga knows the
  sequence it's part of, only the event it reacts to and the event it emits;
  Chapter 24's orchestrated shipping saga is the deliberate contrast, not an
  alternative implementation of the same design.
- **`202 Accepted` is the status code this contract change needs.**
  A `201` whose state can still silently change afterward claims more at
  response time than the system knows, and the order resource stays
  synchronously created and pollable either way.
- **Compensation that used to live in a catch block has to become a reaction**
  once there is no request thread left connecting the original failure to its
  undo. The two compensation paths this chapter leaves behind (pre-handoff
  catch, post-handoff reaction) are disjoint by construction, not by
  convention.
- **Idempotent by `orderId`, at two separate hops, is what makes at-least-once
  delivery safe.** A redelivered event is a no-op on both the payment
  service's capture and the monolith's reaction, and the same guard bounds
  the compensating `Release` to firing at most once per order.
- **A negative check is still the only proof a bounded-wait assertion is
  testing the real pipeline.** This chapter's suite went red on a dead
  consumer and red on a disabled compensation, in both cases for
  the specific reason expected and nothing else, before it was trusted green.

Chapter 24 picks up the coordination problem this chapter introduced and
solves it the other way: shipping's saga runs through Camel's explicit Saga
EIP, an orchestrator that knows its own steps and compensations by name,
the direct contrast to this chapter's choreography. Chapter 26, extracting
order itself last, inherits both patterns at once: it is the aggregate this
chapter's saga state already lives on, and the context every remaining
reaction-and-compensation machinery in this part of the book ultimately
answers to.

## For deeper coverage

- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga pattern and its choreography-based coordination style, which this chapter implements end to end; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the publish-subscribe and event-message vocabulary the Kafka choreography is built from.
- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the incremental extraction playbook, including the synchronous-to-asynchronous contract shift this chapter makes.
- [RFC 9110, HTTP Semantics, §15.3.3, 202 Accepted](https://www.rfc-editor.org/rfc/rfc9110.html#name-202-accepted): the canonical definition of the status code this chapter's checkout contract change relies on.
- Cloud Native Design Patterns, [Appendix D: Sagas](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/17-appendix-d-sagas.md): the companion book's saga appendix, matched directly to this chapter's choreographed implementation.
