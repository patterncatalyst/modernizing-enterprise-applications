---
title: "Distributed Transactions, the Saga & Extraction 4 — Payment (choreographed)"
order: 23
part: "Coordinating Across Services"
description: "ACID gives way to ACD; checkout's synchronous 201/402 contract becomes an asynchronous 202/PENDING handoff; a choreographed saga captures payment, confirms or compensates, and the equivalence suite is taught to verify an outcome it can no longer observe on the same request."
duration: 35 minutes
---

Chapter 19 took the smallest possible bite out of saga compensation: one
reservation, one compensating `Release`, fired from an in-line `catch` block
on the same request thread that discovered the failure. That was a narrow
scope, documented in its own javadoc. This chapter removes the request thread
entirely. Once payment is captured by a process the checkout call never waits
for, there is no `catch` left to put the compensation in — the decline has to
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
This is Part 7, "Coordinating Across Services" — the part of the book where
extractions stop moving one context at a time and start coordinating several
of them across a boundary neither side fully controls.

The code is in `examples/05-payment-service/` and `examples/00-monolith/`.
The run script in each directory builds/sets up and runs it; each
`README.md` covers what it does and how to drive it.

{% include excalidraw.html file="payment-choreographed-saga-sequence" alt="The full choreographed saga sequence: a client posts to the strangler proxy, which forwards to the monolith; OrderService reserves stock synchronously over gRPC, persists the order PENDING, writes an order.placed outbox row in the same transaction, and returns 202 Accepted. The monolith's OutboxRelay later publishes order.placed to Kafka. The payment service's OrderPlacedConsumer reacts, charges idempotently, and writes CAPTURED or DECLINED to its own outbox in the same transaction as the Payment row; its own PaymentOutboxRelay publishes payment.captured or payment.declined to Kafka. The monolith's OrderSagaListener consumes that topic: on capture it confirms the order and dispatches shipping; on decline it marks the order PAYMENT_DECLINED and issues a compensating gRPC Release against the inventory service." caption="Figure 23.1 — The choreographed saga, end to end: one synchronous reservation, two asynchronous outboxes, no central coordinator" %}

## ACID gives way to ACD: SMELL[ch.22] paid off, for payment

Chapter 22 gave this project's central consistency smell a name and a
trajectory without curing it: the monolith's single ACID transaction spans
order, inventory, payment, and shipping, and every extraction before this one
has carved a piece off that transaction while finding some other mechanism to
hold the illusion of atomicity together. Review needed nothing, because it
never wrote. Notification needed an outbox, because it wrote but nothing else
depended on its write succeeding. Inventory needed a compensating `Release`,
because it both wrote and sat upstream of a payment decision that might still
undo it — but the *trigger* for that compensation was still a request-thread
`catch`, because payment was still called in-process at that point in the
book's history.

Payment's extraction is the step where that last crutch goes away. Once
`OrderService#placeOrder` stops calling payment at all — not "calls it, but
over a slower seam," but stops calling it, full stop — there is no exception
for a `catch` block to intercept, because the method that used to throw it no
longer executes inside `placeOrder`'s stack at all. `examples/00-monolith`'s
`SMELLS.md` records the result in its own ledger discipline, the same one
Chapter 19 used to mark its own partial progress:

```
Captured — examples/00-monolith/SMELLS.md, SMELL[ch.22] excerpt

Realized for payment in r06/ch.23 S9 (DRQ-047/DRQ-049) -- SMELL[ch.22]'s
ACID -> ACD story cashed in for this context. OrderService#placeOrder no
longer calls payment at all ... What Postgres used to give "for free" --
roll back the inventory decrement when payment declines, inside one local
transaction -- is now rebuilt EXPLICITLY, asynchronously, cross-process ...
The smell is therefore NOT struck through above: it is cured for payment,
not yet for shipping/order, which is exactly the resume boundary ch.24/ch.26
pick up.
```

That entry is worth reading slowly, because the qualification in its last
sentence is the actual lesson, not a hedge. **ACD** — atomic, consistent,
durable, with isolation dropped from the acronym — names a saga's
real ceiling: each local step is still atomic and durable in its own
database, the aggregate still ends up consistent once the saga completes or
compensates, but there is no isolation spanning the whole sequence the way a
single transaction's locks provide. A reader could, in principle, observe an
order sitting `PENDING` for several seconds while its payment is still in
flight — a state the old synchronous checkout could never expose, because the
whole operation was invisible to any other transaction until it committed or
rolled back as one unit. This chapter does not eliminate that window. It
makes the window explicit, bounded, and provably reversible, which is a
different and more accurate claim than "the inconsistency is gone" would be —
and it is also exactly as far as this one extraction's smell closure goes.
Shipping and order still live inside the smell Chapter 22 named; this chapter
only closes the payment slice of it.

## Choreography, not orchestration — and the contrast with Chapter 24

Before the mechanics: this chapter chooses one control style, and Chapter 24
chooses the opposite one. A **saga** is a sequence of local transactions coordinated across
services, each with a compensating action for when a later step fails. There
are two ways to run one. In an **orchestrated** saga, a central coordinator
holds the sequence — it calls step one, waits for the result, decides whether
to call step two or trigger a compensation, and the participants themselves
know nothing about the sequence they're part of. In a **choreographed** saga,
there is no coordinator at all: every participant reacts to events it
subscribes to and emits the next event in the chain, and the sequence exists
only as the sum of those independent reactions.

This chapter's saga is choreographed. The monolith's `OrderService` publishes
`order.placed` and moves on — it has no idea that a payment service exists,
let alone what that service decides. The payment service reacts to
`order.placed`, decides, and publishes `payment.captured` or
`payment.declined` — it has no idea that a monolith is listening, or that an
`OrderSagaListener` is about to confirm an order or issue a compensating
`Release` because of what it just published. Nobody holds the whole sequence
in one place; it only exists as an emergent property of three independent
reactions wired together by topic names. This design has real tradeoffs:
choreography scales cleanly
as participants are added (a fourth reactor to `payment.captured` needs no
change to the first three), but the overall sequence is nowhere written
down in code — a reader has to reconstruct it by tracing event names across
services, exactly the exercise this chapter's diagrams exist to shortcut.
Chapter 24's shipping extraction is the foil: it coordinates a
comparable multi-step sequence through Camel's **Saga EIP**, an explicit
in-process orchestrator that knows the steps and their compensations by
name. Reading the two chapters back to back is the point — the same
coordination problem, solved with opposite control styles, so that the
tradeoff is felt rather than just asserted.

## The sync→async checkout contract: why `202`, not `201`-with-`PENDING`

The externally-observable change this chapter makes is the sharpest one any
extraction in this book has made so far. Every prior extraction kept
`POST /api/orders`'s contract fixed — `201 Created` with a terminal
`CONFIRMED`/`402 Payment Required` outcome, decided before the response was
written — and moved everything else around it. Payment's extraction cannot
keep that promise, because the fact the response used to report — did the
charge succeed — is no longer known at response time. `OrderController`'s own
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

`202 Accepted` is the correct status code for exactly this situation — RFC
9110's own wording is "the request has been accepted for processing, but the
processing has not been completed" — and the order resource is still created
synchronously and pollable: `GET /api/orders/{id}` works the instant the
`202` returns, same as before, just carrying `status: PENDING` until the saga
reaches a terminal state. This project's own plan considered, and rejected,
an alternative that looks tempting from a distance: keep returning `201
Created` immediately, with the order's status field set to `PENDING`. The
reason that alternative was rejected is about what the status code itself
asserts, not about the body. A `201` response is a specific claim — "the
requested resource now exists, in the state described" — and REST clients,
caches, and monitoring systems are entitled to treat it that way. An order
that might still silently flip to `PAYMENT_DECLINED` seconds later is not
fully described by the response that announced its creation; `202` is the
status code built for precisely this case, where "accepted" and "finished"
are different moments, and choosing it over a `201` that oversells what is
actually known at response time is what keeps the contract accurate rather
than merely convenient. `examples/00-monolith`'s `common.OrderStatus` enum —
`PENDING`, `CONFIRMED`, `PAYMENT_DECLINED` — is the same three-value
vocabulary a client already polls against; only the moment the terminal value
settles has moved off the request thread.

{% include excalidraw.html file="checkout-sync-async-contract" alt="Side-by-side before and after of the checkout HTTP contract. Before: placeOrder reserves stock, charges payment in-process, confirms the order, and dispatches shipping, all before the response; the POST returns 201 Created with status CONFIRMED, or 402 Payment Required with nothing persisted. After: placeOrder still reserves stock synchronously, but persists the order PENDING and returns 202 Accepted plus a Location header immediately; the terminal status, CONFIRMED or PAYMENT_DECLINED, is reached later over the choreography, and the client polls GET /api/orders/{id} to observe it." caption="Figure 23.2 — The checkout contract, before and after: the same three-value OrderStatus vocabulary, decided synchronously versus observed by polling" %}

{% include codetabs.html langs="Before — synchronous checkout, payment in-line (removed)|After — PENDING + handoff, payment out-of-process (current)" %}

```java
// order/OrderService.java#placeOrder — payment.mode=synchronous (removed at S9)
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
// in-line. order.placed (carrying paymentMethod, DRQ-048) is the handoff
// to the payment service, which reacts over Kafka and emits
// payment.captured|declined. OrderSagaListener reacts to either outcome.
writeOrderPlacedOutboxEvent(customer, order, command.paymentMethod());
return toDto(order);
```

The two panels are almost the same shape as Chapter 17's own before/after —
again, curing a coupling problem removes a blocking call rather than adding
code — but notice what disappeared entirely this time, not just moved: the
`paymentService.charge(...)` call, the `order.confirm()` call, and the
`shippingService.dispatch(...)` call are all gone from `placeOrder` itself.
Chapter 17 moved *one* synchronous call to an outbox write. This chapter
moves *three* — because confirming the order and dispatching shipping both
used to happen only after payment succeeded, and now none of the three can
happen until an event neither side of this method controls arrives back.

## The event topology: two outboxes, no coordinator

DRQ-048 is the decision that fixes the choreography's vocabulary before a
line of either side's code existed: `order.placed` (produced by the
monolith, carrying the order's line items and, now, its `paymentMethod`)
reaches the payment service, which captures or declines and produces
`payment.captured` or `payment.declined`; the monolith consumes whichever one
arrives and reacts. Two services, two topics each carrying one direction of
the conversation, and — this is the detail worth sitting with — **both
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
earns its own section below for idempotency — but the one-sentence summary
that matters here is that it captures or declines, persists the `Payment`
row, and writes a `PaymentOutboxEvent` row, all inside the *same*
`@Transactional` method, exactly mirroring the discipline
`OutboxRelay`/`OutboxEvent` established for the monolith back in Chapter 17.
A second, independent `@Scheduled` relay — `PaymentOutboxRelay` — reads those
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
`publishedAt` — the identical at-least-once shape Chapter 17's relay uses,
reused rather than reinvented for this service. The monolith's side of the
topology needed something new, though: until this chapter, the monolith only
ever *produced* to Kafka (the `order.placed` outbox relay). `OrderSagaListener`
is its first Kafka **consumer**, and its own class javadoc names that
milestone directly: "the monolith only talks to Kafka one direction" was a
standing architectural fact for five chapters, and this is the chapter where
it stops being true.

## Compensation via choreography: the catch block retires for this path

Chapter 19 left a documented gap in `RemoteInventoryClient#release`'s own
javadoc: no idempotency key yet, a limitation "deliberately deferred to
Chapter 23's full saga, not speculative compensation infrastructure this
extraction does not need yet." This is that chapter, and DRQ-049 is the
decision that spends it: the compensating `Release` for a payment decline
moves from `OrderService#placeOrder`'s in-line `catch` block to
`OrderSagaListener#onPaymentDeclined`'s reaction to the `payment.declined`
event. The two compensation paths are not duplicated — they are made
disjoint by construction, and `OrderService#placeOrder`'s own current javadoc
explains exactly why neither can double-fire:

```java
// examples/00-monolith/.../order/OrderService.java — current catch
} catch (RuntimeException ex) {
    // r06/ch.23 S9 (DRQ-049): this compensates ONLY a pre-handoff failure
    // now (a later line out of stock, or the save/outbox write itself
    // throwing) — never a payment decline, since payment is no longer
    // called from this method at all.
    compensateRemoteReservations(remoteReservations);
    throw ex;
}
```

The catch can still fire — a multi-line order whose second line is out of
stock still has to release the first line's reservation — but it can no
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
catch only fires for a checkout that never reached the saga at all — no
`order.placed` row was ever committed for it — and the listener's reaction
only fires for an order that *did* reach the saga, because it needs a
`PENDING` row to transition out of. Neither can observe the other's order, so
neither can release the same reservation twice. This is the "the database
undid it for me" versus "I had to write the undo myself" distinction Chapter
19 introduced, pushed one more step: Chapter 19 still had a request thread to
write the undo *on*; this chapter's undo has to survive across two
asynchronous hops — the payment service's own outbox publish, then the
monolith's consumer reacting to it — with no thread left connecting the
original request to the eventual compensation at all.

{% include excalidraw.html file="payment-compensation-choreography" alt="Two stacked diagrams contrasting compensation styles. Top, the ch.19 shape: OrderService's own try/catch calls the compensating gRPC Release directly on the same request thread the moment an in-process payment charge throws. Bottom, the ch.23 shape: there is no request-thread catch for a payment decline anymore; the payment service emits payment.declined via its own transactional outbox, the monolith's OrderSagaListener consumes it across two asynchronous hops, and the reaction issues the same compensating gRPC Release, converging to net-zero stock either way." caption="Figure 23.3 — Compensation, before and after: from a request-thread catch to a two-hop choreographed reaction, same compensating call, same net-zero result" %}

## Idempotency: at-least-once delivery, exactly-once effect

Every outbox relay in this project is at-least-once, never exactly-once, and
this chapter has two of them chained together — the monolith's `order.placed`
relay, then the payment service's own `payment.captured`/`payment.declined`
relay — which means a single logical event can arrive at its consumer more
than once for entirely mundane reasons (a crash between a broker ack and a
`publishedAt` stamp, a consumer-group rebalance replaying an uncommitted
offset). DRQ-051 is the decision that makes redelivery safe on both ends of
the chain, by the same two-layer pattern Chapter 17 established for
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

A redelivered `order.placed` for an order already charged is a safe no-op —
no second charge, no second outbox row — and `uq_payments_order_id`, a
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
side-effect — a card charge, a stock release — is allowed to happen no matter
how many times the event that triggers it arrives.

## Keeping the equivalence suite sound across a contract it can no longer observe synchronously

DRQ-055 is the decision Chapter 17's bounded-wait idiom (DRQ-037) had to be
extended to cover, and the stakes are higher here than they were for
notification, because this suite's two critical payment assertions —
Scenario 1's happy path, Scenario 3's decline — are exactly the reason this
chapter exists. A suite that weakened either one to pass would make Scenario
3 prove nothing. The approach is the same shape Chapter 17 taught, applied to
a harder case: the `POST` assertion becomes transport-tolerant (`201` from
the still-synchronous monolith, or `202` from the choreographed saga, either
is acceptable), while the *outcome* assertion stays strict and is re-expressed
as a bounded-wait poll of `GET /api/orders/{id}` for the exact terminal
status — `CONFIRMED` for Scenario 1, `PAYMENT_DECLINED` for Scenario 3 — with
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
Captured — examples/05-payment-service/CUTOVER.md, negative check (DRQ-055)

1. AssertionError  Order reaches CONFIRMED within the bounded-wait budget (10 x 500ms)
                   order 177 status after 10 attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'

RED — newman exit code 1, 1 of 23 assertions failed.
```

With no consumer left to react to `order.placed`, the order is stuck
`PENDING` forever, and the suite reports exactly that rather than passing by
coincidence. Restarting the payment service and re-running the identical
folder on a fresh checkout produced **GREEN — 20/20 assertions, newman exit
code 0** within seconds, Kafka's at-least-once redelivery draining the
backlog exactly as the design predicts. This project's own CI gate re-proved
the same discipline on the compensation path specifically, not just the
confirmation path: with the full suite green at **108/108** through the
proxy, the compensating `Release` loop inside `OrderSagaListener.onPaymentDeclined`
was deliberately commented out in a local working copy, the monolith rebuilt
and restarted, and Scenario 3 alone re-run — RED, **1 of 22 assertions**
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

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 19 demonstrated for
> Inventory, at its hardest rung yet. Frame fixed nine decisions up front in
> `decisions.md` (DRQ-047 through DRQ-055 — the synchronous-to-asynchronous
> checkout contract, the choreographed event topology, compensation re-plumbed
> from a catch block to a `payment.declined` reaction, saga state on the order
> aggregate, idempotency by `orderId`, the payment service's two-phase read
> surface with an idiomatic-from-day-one consumer/producer, its own
> transactional outbox, two-flag reversibility, and the bounded-wait
> equivalence discipline extended to the checkout path). Plan laid out
> fourteen steps across two parallel lanes — the event contract and the new
> service's scaffold — with six Opus validation gates named before a line of
> consumer or outbox code existed, most pointedly at the cutover step where a
> forced decline had to converge to net-zero stock across two asynchronous
> hops with no request-thread catch to lean on. Generate produced the service,
> the event contract, the monolith's first Kafka consumer, and a proxy route
> that turned out not to need the translator the plan had assumed, under
> quarkus-agent and lgtm-quarkus tooling, following `migrate-spring-to-quarkus`
> for the lifted read surface. Verify is this chapter's negative check and its
> real stock numbers — 96→95→96, 88→87→88, 72→71→72 — plus a suite gap found
> and closed before decommission rather than left standing. Operate is the
> two-flag cutover `CUTOVER.md` records end to end, reversibility proven at
> 79/79 with zero retries, and the committed-default flip at decommission.
> Reconcile is `SMELLS.md` marking Smell[ch.22] cured for payment — not yet
> for shipping or order — with the orchestrated-saga contrast in Chapter 24 as
> the named resume boundary.

## What you learned

- **ACID gives way to ACD** the moment a transaction's scope stops spanning a
  consistency guarantee — each local step stays atomic and durable, but
  isolation across the whole sequence is gone, and a saga rebuilds
  consistency explicitly where the database used to provide it as a side
  effect of one write-ahead log.
- **Choreography and orchestration are opposite control styles for the same
  coordination problem** — no participant in this chapter's saga knows the
  sequence it's part of, only the event it reacts to and the event it emits;
  Chapter 24's orchestrated shipping saga is the deliberate contrast, not an
  alternative implementation of the same design.
- **`202 Accepted` is the status code this contract change needs** —
  a `201` whose state can still silently change afterward claims more at
  response time than the system knows, and the order resource stays
  synchronously created and pollable either way.
- **Compensation that used to live in a catch block has to become a reaction**
  once there is no request thread left connecting the original failure to its
  undo — and the two compensation paths this chapter leaves behind (pre-handoff
  catch, post-handoff reaction) are disjoint by construction, not by
  convention.
- **Idempotent by `orderId`, at two separate hops, is what makes at-least-once
  delivery safe** — a redelivered event is a no-op on both the payment
  service's capture and the monolith's reaction, and the same guard bounds
  the compensating `Release` to firing at most once per order.
- **A negative check is still the only proof a bounded-wait assertion is
  testing the real pipeline** — this chapter's suite went red on a dead
  consumer and red on a disabled compensation, in both cases for
  the specific reason expected and nothing else, before it was trusted green.

Chapter 24 picks up the coordination problem this chapter introduced and
solves it the other way: shipping's saga runs through Camel's explicit Saga
EIP, an orchestrator that knows its own steps and compensations by name,
the direct contrast to this chapter's choreography. Chapter 26, extracting
order itself last, inherits both patterns at once — it is the aggregate this
chapter's saga state already lives on, and the context every remaining
reaction-and-compensation machinery in this part of the book ultimately
answers to.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is real, runnable code and real, dated evidence
already in this repository: `examples/05-payment-service/` (`OrderPlacedConsumer`,
`PaymentService#processOrderPlaced`/`charge`, `PaymentOutboxEvent`/`PaymentOutboxRelay`,
and `MIGRATION.md`'s measured Phase A→B record — 1.494s/~303MB/15 features to
1.833s/~338MB/16 features, the cost of a live Kafka consumer, two producers,
and a scheduled relay, not a regression), `examples/00-monolith/`
(`OrderService#placeOrder`'s current form, `OrderSagaListener`, `OrderController`,
and `SMELLS.md`'s SMELL[ch.22] entry), `examples/01-strangler-proxy/`
(`StranglerProxyRoute.java`'s payment branch and `CUTOVER.md`'s full
cutover/reversibility/negative-check timeline), `tooling/newman/mea.postman_collection.json`'s
Scenario 1, Scenario 3, and Payment Context Contract folders, and
`.github/workflows/code-ci.yml`'s `payment-equivalence-gate` job. Tests and
demos actually run, per the captured records above: the full behavior-equivalence
suite through the proxy with both async Scenario 1 (bounded-wait to
`CONFIRMED`) and Scenario 3 (bounded-wait to `PAYMENT_DECLINED` plus
bounded-wait to net-zero stock) included; the payment service's own
capture/decline/idempotent-redelivery round-trip tests (`PaymentServiceTest`,
`OrderPlacedConsumerTest`, 18 tests total, 0 failures); the negative check
with the payment consumer killed (RED, 1/23, order stuck `PENDING`; restarted,
GREEN, 20/20); Dev Services-backed `PaymentResourceTest`/`PaymentServiceTest`
runs under `./mvnw -q test`; and the `payment-equivalence-gate` CI job's
local red-then-green proof — **108/108** green baseline, a
disabled compensation loop driving Scenario 3 to **RED (1/22 failed)**
isolated to the net-zero inventory assertion, and **99/99** green again after
the revert, plus a cheap re-confirmation of the consumer-down negative check
(RED 1/23, then GREEN 19/19). The captured outputs quoted above are taken
verbatim from `CUTOVER.md`, `MIGRATION.md`, `code-ci.yml`'s own commentary,
and the ADLC trace document rather than re-run live here, per this project's
own DRQ-025 discipline. What a reader's own run should confirm independently:
the exact startup/RSS figures in the Phase A→B table will differ on
different hardware even if the qualitative heavier-not-regressed reading
holds; the observed bounded-wait retry counts (Scenario 1 around six
attempts, Scenario 3 around eight) are a function of the outbox relays' 2-second
poll intervals and will vary with load; and the GitHub Actions run of
`payment-equivalence-gate` itself was validated locally against the same
topology rather than inside Actions, per the job's own committed comment —
a reader watching the real workflow run is the remaining independent
confirmation this chapter does not itself supply.*
