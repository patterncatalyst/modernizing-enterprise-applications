---
title: "Extraction 2 — Notification Service (Going Event-Driven)"
order: 17
part: "The Strangler Fig in Practice"
description: "Introduces the Kafka backbone, the outbox in the monolith for reliable publication, and content-based event routing — the first taste of eventual consistency."
---

Chapter 15 proved the seam machinery on the easiest possible case: a context
with zero synchronous collaborators, extracted behind a flag, verified by a
suite that only ever had to check a status code and a response body. Chapter
16 hardened that seam against a different failure — a raw entity leaking
across a boundary that hadn't been cut yet. Neither chapter had to deal with
the thing this one deals with: a call that happens *inside* another
context's transaction, whose caller does not wait for an answer it actually
needs, and whose extraction therefore cannot just move code from one runtime
to another — it has to change *when* the work happens, not just *where*.
Notification is this book's first event-driven extraction, and everything in
it is real, running code from this project's own r04 iteration:
`examples/00-monolith/` (the transactional outbox and its polling relay),
`examples/03-notification-service/` (the Quarkus consumer, its own schema,
and its WebSocket push), `examples/01-strangler-proxy/` (the second cutover
flag), and `tooling/newman/` (the Notification Context Contract folder that
had to learn a new way to assert "the same" against an asynchronous backend).

The code is in `examples/00-monolith/` and `examples/03-notification-service/`.
The run script in each directory builds/sets up and runs it; each `README.md`
covers what it does and how to drive it.

{% include excalidraw.html file="notification-async-topology" alt="The end-to-end async notification pipeline: a client through the Camel strangler proxy to the Spring monolith, whose checkout transaction writes an order row and an order.placed outbox row atomically; a scheduled relay polls the outbox and publishes to Kafka; the Quarkus notification service runs two SmallRye consumers off that topic, one persisting idempotently into its own schema, one pushing over a WebSocket; the proxy's read route forwards /api/notifications straight to the new service." caption="Figure 17.1 — The notification extraction's async topology: outbox write, polling relay, two Kafka consumers, own schema, WebSocket push" %}

## Why notification leaves second, and why it's harder than it looks

Chapter 9 tagged this as Smell 4 the moment the monolith existed, and the
language it used was deliberately clinical: `OrderService#placeOrder` called
`NotificationService#sendOrderConfirmation(customer, order)` as an ordinary
Java method, on the same call stack, inside the same `@Transactional` that
also reserved inventory, persisted the order, and charged a card. Two
separate costs hide inside that one line, and it's worth pulling them apart
because the extraction below cures them by two different mechanisms.

The first cost is latency coupling: whatever "sending a confirmation" takes —
building a message, writing a row, in a more realistic system maybe calling
an email provider's API — that time is now checkout's time, even though a
customer whose card was just charged and whose order was just confirmed does
not need to wait for an email to be queued before getting their response. The
second cost is failure-domain coupling, and it's the sharper one:
`PaymentDeclinedException`'s own javadoc, quoted in Chapter 9, names it
directly — because everything from inventory decrement through notification
sits inside one `@Transactional`, an exception *anywhere* in that chain,
including inside the notification step, rolls back *everything*, including a
successfully reserved stock item and a successfully charged payment. A
transient failure in a concern the business doesn't even consider part of
"did checkout succeed" is capable of undoing a checkout that, by every
customer-facing measure, already succeeded.

This is why notification earns the "raises the bar" framing this part of the
book gives it. Review's extraction only had to move code and prove a response
shape didn't change. Notification's extraction has to change *when* a fact
gets recorded — from "synchronously, before the customer's response returns"
to "eventually, on a schedule the caller doesn't control and doesn't wait
for" — without changing what a client seeing `GET /api/notifications` is
entitled to expect. That's a harder claim to verify, and the back half of
this chapter is about exactly how the behavior-equivalence suite was taught
to verify it without lying to itself.

## The transactional outbox: atomicity now, delivery later

The fix for the failure-domain half of Smell 4 is the **transactional
outbox** pattern, and the one sentence that matters most about it is this:
the event row and the business change it describes are written by the *same*
`@Transactional`, so either both commit or neither does. Here is the entity
that row becomes, `OutboxEvent`, and the javadoc on it states the guarantee
plainly:

```java
// examples/00-monolith/.../common/outbox/OutboxEvent.java
/**
 * ch.17 ... a row in the transactional outbox. Written by
 * {@code order.OrderService#placeOrder} in the SAME {@code @Transactional}
 * as the order/payment/shipment writes, every time, so the event is atomic
 * with the business change it describes: either both the order and this row
 * commit, or neither does (a payment decline rolls both back together, same
 * as every other write in that transaction).
 *
 * <p>{@link OutboxRelay} is a separate, asynchronous reader of this table —
 * it polls for rows where {@code publishedAt IS NULL}, publishes each to
 * Kafka, and stamps {@code publishedAt} on success. That stamp is an
 * AT-LEAST-ONCE guarantee, not exactly-once...
 */
```

That javadoc is also an honest admission of where the atomicity guarantee
*stops*. The write into Postgres is atomic with checkout; the publish to
Kafka is not, and cannot be, because it is a second system reached over a
second network hop on a separate schedule. `OutboxRelay` is a plain Spring
`@Scheduled` method, polling every two seconds for up to fifty unpublished
rows, oldest first:

```java
// examples/00-monolith/.../common/outbox/OutboxRelay.java
@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:2000}")
public void publishUnpublishedEvents() {
    List<OutboxEvent> batch = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
    for (OutboxEvent event : batch) {
        publishOne(event);
    }
}

private void publishOne(OutboxEvent event) {
    try {
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                .get(5, TimeUnit.SECONDS);
        event.markPublished();
        outboxRepository.save(event);
        // ...
    } catch (ExecutionException | TimeoutException e) {
        // left unpublished; picked up again next tick
    }
}
```

Notice what the relay actually blocks on: `kafkaTemplate.send(...).get(5,
SECONDS)` waits for the broker's acknowledgment *before* the code stamps
`publishedAt`. That ordering is the whole at-least-once guarantee in one
line. If the process dies after the broker has acked the send but before the
`save(event)` call commits the stamp, the row still reads as unpublished on
the next poll, and the relay republishes the exact same payload. That is a
duplicate delivery by design, not a bug — the tradeoff this project's
decision log (DRQ-034) states explicitly: a polling relay is the simplest
mechanism that teaches the outbox's real guarantee (atomic write,
at-least-once publish) without pulling a CDC connector and a replication slot
into the stack a chapter early. CDC — reading the Postgres write-ahead log
directly instead of polling a table — is deliberately deferred to Chapter 19,
where the inventory extraction needs its lower latency and its freedom from a
periodic table scan badly enough to justify the added operational
complexity; this chapter's poll interval and steady read load are the
honestly-stated cost of choosing the simpler mechanism first.

The reason this beats a naive "call Kafka directly after the transaction
commits" dual-write is worth stating precisely, because it's the exact trap
an inexperienced reader of this pattern falls into. A post-commit publish has
a gap no amount of careful code can close: the transaction can commit and the
process can crash (or the publish call can simply fail) *before* the Kafka
send happens, and now the order exists with no corresponding event ever
published — silently, permanently, with nothing in the system positioned to
notice. The outbox closes that gap by moving the "did this happen" question
entirely inside the database transaction: either the order and its outbox
row both exist, or neither does, and `OrderService`'s own in-code comment
states the negative case this buys directly — a payment decline, which
throws before `placeOrder` reaches the outbox write, rolls back the
inventory reservation *and* leaves no orphan outbox row behind, because the
write that would have created one is on the same transactional ledger as the
reservation itself. There is no window, however small, in which the business
fact exists and the event row does not, or vice versa — which is precisely
the property a dual-write can never give you, no matter how it's sequenced.

{% include excalidraw.html file="transactional-outbox-sequence" alt="Two stacked sequences. Top: the old flow, checkout running inventory-reserve, order-persist, payment-charge, shipping-dispatch, then calling NotificationService#sendOrderConfirmation synchronously inside the same @Transactional before commit. Bottom: the new flow, the same checkout steps but the last write before commit is an order.placed outbox row in the same transaction; a separate, later, asynchronous OutboxRelay polls for unpublished rows, publishes to Kafka, and stamps published_at only after the broker acks, with the idempotent consumer absorbing at-least-once redelivery." caption="Figure 17.2 — The transactional outbox sequence: one atomic commit, then a decoupled, at-least-once publish" %}

Here is the actual diff, not a reconstruction — the synchronous call Chapter
9 tagged as Smell 4, quoted verbatim from that chapter before this
extraction removed it, next to the outbox write that replaced it:

{% include codetabs.html langs="Before — synchronous, in-transaction (removed)|After — the outbox write (current)" %}

```java
// order/OrderService.java — removed in r04/S8
// SMELL[ch.17]: notification sent synchronously inside the checkout
// transaction instead of via an outbox + async consumer.
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());
notificationService.sendOrderConfirmation(customer, order);
return toDto(order);
```

```java
// order/OrderService.java#placeOrder — current
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());

// ch.17 cure (r04/S8): write the event to the outbox, atomically, in
// THIS transaction. No Kafka client is touched here — the @Scheduled
// OutboxRelay is the only thing that talks to Kafka, on its own
// schedule, after this transaction has committed (or not).
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

The two panels are almost the same length, and that similarity is the point:
curing a coupling smell doesn't require more code, it requires the *same*
amount of code doing a structurally different thing — a local method call
that blocks and can fail the whole transaction, replaced by a local
persistence write that only ever adds a row the transaction already knows how
to roll back.

## Consuming it: SmallRye Reactive Messaging and the idempotency it demands

On the far side of Kafka, `examples/03-notification-service` consumes
`order.placed` with SmallRye Reactive Messaging's declarative `@Incoming`:

```java
// examples/03-notification-service/.../OrderPlacedConsumer.java
@ApplicationScoped
public class OrderPlacedConsumer {
    private final NotificationService service;

    @Incoming("order-placed")
    public void consume(OrderPlacedEvent event) {
        service.recordOrderPlaced(event);
    }
}
```

That method signature is the entire integration surface — no manual Kafka
client, no offset bookkeeping in application code, no polling loop of its
own. The channel configuration (`mp.messaging.incoming.order-placed.*`,
bound to the same `order.placed` topic the monolith's relay publishes to)
lives entirely in `application.properties`, which is what makes this
"idiomatic-from-the-start" rather than lifted: the monolith never consumed
its own event in the first place, so there is no Spring original to carry
across — this is net-new Quarkus code from the day it was written (DRQ-035).

The at-least-once guarantee the outbox relay documented above is a promise,
not a courtesy, and `NotificationService#recordOrderPlaced` is where that
promise gets honored rather than ignored:

```java
// examples/03-notification-service/.../NotificationService.java
@Transactional
public void recordOrderPlaced(OrderPlacedEvent event) {
    if (repository.findByOrderId(event.orderId()) != null) {
        return;
    }
    Notification notification =
            new Notification(event.customerId(), event.orderId(), "EMAIL", event.confirmationMessage());
    repository.persist(notification);
}
```

That's layer one: check-then-insert, deduped by the order id the event
already carries. It is sufficient for the common case — a single consumer
replica processing redeliveries one at a time — but it has an honest gap:
two deliveries racing concurrently (two replicas, or a redelivery landing
while the first write is still in flight) can both pass the `findByOrderId`
check before either commits. Layer two closes that gap at the only place a
race condition can actually be closed — the database itself:

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

A partial unique index, scoped to `order_id IS NOT NULL` (so a future
notification type that doesn't originate from an order isn't forced into the
same uniqueness constraint), is the backstop: even if the application-level
check races and loses, Postgres itself refuses the second row. The two
layers are not redundant — they answer different questions. The application
check is the cheap, common-case no-op that avoids a thrown constraint
violation on every ordinary redelivery; the unique index is the guarantee
that holds even when the application check's own logic is beaten by timing.
This is proven, not asserted: `OrderPlacedConsumerTest#redeliveryOfSameOrderIsIdempotent`
sends the identical event twice through the real `@Incoming` pipeline (via
SmallRye's in-memory test connector) and asserts the notification count for
that order id holds at exactly one for a continuous three-second window —

```
Captured — examples/03-notification-service, OrderPlacedConsumerTest
(MIGRATION.md, "Idempotency mechanism" section)

redeliveryOfSameOrderIsIdempotent: send order.placed for orderId=42 twice
through the real @Incoming("order-placed") pipeline (SmallRye in-memory
connector) -> notification count for orderId=42 asserted == 1, held for a
continuous 3-second window.

PASSED.
```

— which is exactly the shape of evidence a reader should expect before
trusting an at-least-once producer paired with a consumer that claims to
absorb it.

## Own-schema ownership: the deliberate contrast with Review

Review's Phase A extraction, back in Chapter 15, stayed in the monolith's
shared Postgres schema — a defensible, explicitly scoped deferral, because
Review served a synchronously-consistent REST contract against data the
monolith also wrote. Notification cannot make that same choice, and the
reason is structural, not a matter of taste. `examples/03-notification-service/README.md`
states it directly: an event-driven read model that shared the upstream
aggregate's table would have *two independent writers* — the monolith's
(now-removed) synchronous path and this service's asynchronous consumer —
racing on the same rows with no protocol to reconcile them. Owning the table
from Phase A onward avoids that split-brain by construction rather than by
discipline.

Concretely, this service's `Notification` entity lives in its own
`notification` Postgres schema (same shared instance, different namespace,
migrated by its own Flyway history from day one — DRQ-035), and its
`customerId`/`orderId` fields are plain `Long` columns, not JPA
`@ManyToOne` relations into the monolith's `customers`/`orders` tables,
because those tables don't exist in a schema this service owns. That
one-line adaptation — a foreign-key relationship becomes a plain scalar,
populated once from the event payload rather than looked up live — is the
smallest possible illustration of a much larger idea Chapter 18 develops in
full: a service that owns its data cannot lean on the database to enforce
relationships across a boundary it no longer shares.

## The Spring relay and the Quarkus consumer, side by side

The publishing half and the consuming half of this pipeline are not the same
code moved to a different place — they are two different programming models,
chosen because each side is the natural idiom of its own framework, and
seeing them side by side is the clearest way to feel that difference:

{% include codetabs.html langs="Monolith — Spring @Scheduled polling relay (publisher)|notification-service — Quarkus SmallRye @Incoming (consumer)" %}

```java
// examples/00-monolith/.../common/outbox/OutboxRelay.java
@Component
public class OutboxRelay {

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:2000}")
    public void publishUnpublishedEvents() {
        List<OutboxEvent> batch =
                outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                .get(5, TimeUnit.SECONDS);
        event.markPublished();
        outboxRepository.save(event);
    }
}
```

```java
// examples/03-notification-service/.../OrderPlacedConsumer.java
@ApplicationScoped
public class OrderPlacedConsumer {

    private final NotificationService service;

    public OrderPlacedConsumer(NotificationService service) {
        this.service = service;
    }

    @Incoming("order-placed")
    public void consume(OrderPlacedEvent event) {
        service.recordOrderPlaced(event);
    }
}
```

The relay is imperative and self-scheduled: it owns a loop, a batch size,
and an explicit, blocking call to the broker, because Spring gives it no
declarative abstraction over "read unpublished rows and publish them." The
consumer is declarative and reactive: SmallRye's `@Incoming` annotation is
the entire subscription — deserialization, offset management, and delivery
all happen beneath that one method signature, driven by configuration rather
than code. Both are correct for the job they do. The relay has to reach
*into* a table it owns and *out* to a broker it doesn't, on its own clock;
the consumer only has to react to what arrives, which is exactly the shape
Reactive Messaging is built to minimize boilerplate for.

The other net-new piece worth naming briefly, because it's the extraction's
non-trivial showcase rather than a second trip through the same idea, is
`OrderPlacedPushConsumer` — a second, independent consumer of the *same*
`order.placed` topic, bound to a per-replica unique consumer group instead
of the shared group `OrderPlacedConsumer` uses, so every replica (not just
one, as a shared group would balance to) gets its own copy of every event and
fans it out over `quarkus-websockets-next` to whatever clients are connected
to `/ws/notifications` in that JVM. It never persists and is never
`@Transactional` — deliberately kept thin, because the persistence
consumer's idempotency story is the one this chapter needs readers to trust,
and a second consumer with its own write path would only dilute that
argument.

## The measured cost of Phase B — and why it's honest, not a regression

Phase A lifted the read surface — `NotificationController` →
`NotificationService.listByCustomerId` → `Notification` — onto Quarkus via
the Spring-compatibility extensions, already pointed at its own schema from
day one, with no consumer yet. Phase B removed the compatibility shim,
rewrote the surface in idiomatic Quarkus REST and Panache, and — because
there was no Spring original to lift — added the Kafka consumer and
WebSocket push as net-new code in the same step. `MIGRATION.md` records the
before/after, measured against packaged artifacts on the same machine:

```
Captured — examples/03-notification-service/MIGRATION.md,
"Before / after metrics"

| Build                                        | Startup | RSS     | Features |
|-----------------------------------------------|--------:|--------:|---------:|
| Phase A — JVM, Spring-compat                  | 1.486 s | ~296 MB | 14       |
| Phase B — JVM, idiomatic + Kafka + WebSocket  | 1.765 s | ~372 MB | 16       |
```

Read against Review's Phase A→B table (Chapter 15's near-wash: a few percent
of RSS recovered simply by no longer loading three translation extensions),
this table runs the other way: Phase B is **~19% slower to start and ~26%
heavier** than Phase A. `MIGRATION.md` is explicit about why, and the
explanation matters more than the number: unlike Review, Notification's
Phase B is not a pure refactor — it adds two live Kafka consumers, each with
its own background poll thread and broker connection, plus a Netty/Vert.x
WebSocket server, none of which existed in Phase A at all. That is real
runtime capability added in the same step as the idiomatic rewrite, and the
honest reading is that the idiomatic-rewrite effect (a modest win, the same
direction as Review's) is simply masked by the larger net-new footprint
added alongside it. A reader who expected every Phase A→B table in this book
to show the same improvement would be generalizing from one data point;
this table is here specifically to correct that generalization before it
calcifies, not to apologize for a result that doesn't fit the pattern.

## The cutover: two flags, flipped together

Reversibility here needed two flags, not one, because this extraction has a
write side and a read side that live in different processes. The monolith's
own `notification.mode` (`synchronous` default, `outbox` once cut over)
decided whether checkout sent a confirmation in-process or wrote the outbox
row; the proxy's `strangler.notification.enabled` decided whether
`/api/notifications` reads were served by the monolith or the new service.
`StranglerProxyRoute` added the second flag with the identical shape as
Review's — content-based routing on a path prefix, read once per request,
resolving only to one of two fixed, operator-configured base URLs — learning
Chapter 15's own routing-predicate lesson directly into its own in-code
comment: match the *full* `/api/notifications` path, never a route-relative
`/notifications`, or the predicate silently never fires.

`CUTOVER.md` records the cutover as a progression of runs through the proxy —
three against the full behavior-equivalence suite (baseline, cutover, and the
post-flip sanity run) plus the negative check's Notification-folder-only
red→green pair — and that progression is the chapter's clearest evidence
that the suite was actually exercising what it claimed to:

```
Captured — examples/01-strangler-proxy/CUTOVER.md,
"Summary of the notification-cutover runs"

1. flag OFF,  mode=synchronous -> monolith            : 58/58 green (reversibility baseline)
2. flag ON,   mode=outbox      -> notification-service: 70/70 green (poll genuinely retried)
3a. flag ON,  consumer DOWN                            : RED  (negative check)
3b. flag ON,  consumer restarted                       : 11/11 green
4. flag ON (committed default), mode=outbox            : 64/64 green (final sanity)
```

Run 2's note — "the bounded-wait poll in the Notification folder *genuinely
retried*" — is worth dwelling on for a sentence: against the synchronous
monolith (run 1), the notification was already there on the very first GET,
so the poll loop never had cause to retry at all. Against the async service,
several attempts came back `200` with no matching notification yet before
the match finally appeared — real, observed outbox→Kafka→consumer latency,
not an artifact of the test. Decommission — removing the monolith's
synchronous call site and its `/api/notifications` read surface entirely,
leaving only the outbox as the sole notification path — followed only after
this evidence existed, exactly mirroring Chapter 15's sequencing: prove the
cutover is real before making it permanent.

## The key lesson: defeating the async false-equivalence trap

Chapter 7 already told you, in the abstract, what a green suite run is and
isn't evidence of: a behavior-equivalence suite verifies the *response*, not
the *route* a request took to produce it, and when two backends can agree by
coincidence, 49 green assertions prove nothing about which one actually
answered. That lesson was discovered on Review by accident — a routing
predicate bug that happened to be invisible because both backends shared one
table. This chapter's job was to make sure the *same* trap, in its async
form, couldn't repeat by accident a second time, and DRQ-037 is the decision
that did it on purpose rather than waiting to get lucky again.

The async version of the trap is sharper than Review's. A synchronous
assertion — "checkout returns 201, now immediately `GET
/api/notifications`" — would be correct against the old monolith and
*flaky* against the new service, failing exactly often enough to look like a
test-infrastructure problem rather than a design one, and a team under
deadline pressure has every incentive to "fix" that flakiness by loosening
the assertion rather than by understanding it. DRQ-037's answer is a
bounded-wait eventual-consistency poll: on a decoupled retry delay (never
delaying before the first attempt), it checks for the notification, and on a
miss with budget remaining, it retries up to ten times at 500ms. Written
this way, the same collection is correct against *both* backends without
being edited for either: synchronous backends satisfy it on attempt zero and
the loop never engages; asynchronous backends get a bounded window to catch
up. That symmetry — one assertion, unedited, correct against two genuinely
different timing models — is what makes it reusable rather than a
one-off hack for this chapter.

But a bounded-wait poll only proves the suite *can* wait long enough. It does
not, by itself, prove the suite is waiting on the *right thing* — exactly
the gap Review's routing bug lived in. The negative check is what closes
that second, harder gap: stop the consumer, so there is no process left on
the other end of the pipeline the suite claims to be testing, and the
assertion has to fail, loudly and specifically, or the suite was never
really testing the async path at all. Here is what that failure looked like,
captured exactly as recorded:

```
Captured — examples/01-strangler-proxy/CUTOVER.md,
"Negative check — the consumer stopped, the assertion MUST go RED"

notification-service process killed (Kafka consumer AND /api/notifications
read surface are the same process); re-run through the proxy:

newman run mea.postman_collection.json \
    --folder "Notification Context Contract" --env-var baseUrl=http://localhost:8888

1. AssertionError  Notification read surface returns 200
                    expected response to have status code 200 but got 500
2. AssertionError  Content-Type is application/json
                    expected 'text/plain; charset=utf-8' to include 'application/json'
3. JSONError        No data, empty input at 1:1

RED — newman exit code 1.
```

That is a *stronger* failure than a soft budget exhaustion would have been.
A suite that merely ran out of retries and reported "no matching
notification after 10 attempts" could, in principle, be excused as slow
infrastructure under load. A suite that fails with a 500 and a JSON parse
error because the backend process is gone entirely admits no such excuse —
it is the suite discovering, immediately and unambiguously, that the thing
it depends on to make its claim true no longer exists. Restarting the
consumer and re-running the identical folder produced the other half of the
proof:

```
Captured — examples/01-strangler-proxy/CUTOVER.md,
"GREEN — 11/11 assertions, newman exit code 0"

notification-service restarted; backlog drained immediately
("consumed order.placed for order 50 (customer 1)" in its logs — the event
published by the relay while the consumer was down, delivered at-least-once
once a consumer was listening again); the freshly-created order from this
re-run was also observed within budget.

GREEN — 11/11 assertions, newman exit code 0.
```

RED on kill, GREEN on restart, with the exact same folder and the exact same
collection, unedited across both runs — that symmetric pair is the proof the
bounded-wait assertion is correct and the proof it is not a false positive,
together. Either one alone would be weaker evidence than both: a RED-only
result could mean the suite is simply broken; a GREEN-only result is the
Review-style trap all over again. This is the single most important idea in
this chapter, and the reason it earns that weight is structural, not
rhetorical: every later event-driven extraction in this book — payment's
choreographed saga in Part 7, shipping's orchestrated saga beside it —
inherits this exact discipline rather than rediscovering it. A bounded-wait
poll without a negative check is a suite that *looks* like it tests
asynchrony and might not; a negative check is what turns "looks like" into
"proven to."

## In GitHub Actions: the gate runs the async path, in CI, green

This isn't a local-only proof. `.github/workflows/code-ci.yml`'s
`notification-equivalence-gate` job brings up disposable Postgres *and*
single-broker KRaft Kafka service containers, builds and starts the
monolith, the notification service, and the strangler proxy exactly as the
local runs above did, and runs the Notification Context Contract folder
against the proxy — a non-zero `newman` exit code fails the build. Before
that workflow file was committed, it was validated red-then-green the same
way the negative check was: green at 17/17 with the poll genuinely retrying
across a handful of attempts (not an instant hit); then `OrderPlacedConsumer`'s
persist call commented out, which exhausted the full ten-attempt bounded-wait
budget and failed with exit code `1`; then the call restored and the suite
green again at 17/17 before the job was trusted. The async notification path
is not a special local-machine ritual in this project — it is exercised,
end to end, on every push and pull request, by a CI job that genuinely waits
for and asserts eventual consistency rather than racing a hopeful green.

> **ADLC in Action** — This extraction ran the identical Frame → Map → Plan →
> Generate → Verify → Operate → Reconcile loop Chapter 7 demonstrated on
> Review, at the next rung of difficulty. Frame fixed five decisions up front
> in `decisions.md` (DRQ-034 through DRQ-038 — polling over CDC, the
> two-phase-plus-net-new-consumer split, two-flag reversibility, the
> bounded-wait-plus-negative-check testing discipline, and JSON deferred to
> Avro at Chapter 28). Plan laid out thirteen steps with two explicit
> parallel lanes (the monolith's outbox versus the new service's scaffold)
> and named every Opus validation gate before a line of outbox or consumer
> code existed. Generate produced the outbox, the relay, the two-phase
> service, and the consumers under quarkus-agent and lgtm-quarkus tooling.
> Verify is this chapter's negative-check story, run for real, not narrated.
> Operate is the two-flag cutover recorded in `CUTOVER.md`. Reconcile is
> `SMELLS.md` marking Smell 4 cured, in the same ledger discipline Chapter 7
> already showed you reading commit messages that name their own Verify
> results.

## What you learned

- A **transactional outbox** buys atomicity between a business change and the
  fact that describes it by moving "did this happen" entirely inside one
  database transaction — a payment decline rolls back the order *and* leaves
  no orphan outbox row, a guarantee a post-commit dual-write can never give
  you no matter how carefully it's sequenced.
- A polling relay's at-least-once delivery (publish, then stamp — never
  atomic together) is a stated tradeoff, not a hidden flaw, and it is only
  safe because the consumer is idempotent by two independent layers: an
  application-level check-then-insert for the common case, and a database
  partial unique index as the backstop for a genuine race.
- An event-driven read model **must own its data** — this chapter's
  deliberate contrast with Review's shared-schema deferral is the concrete
  argument for why: two independent writers racing on one shared table is a
  split-brain an owned schema avoids by construction, not by discipline.
- A **bounded-wait eventual-consistency poll** lets one unedited assertion
  pass correctly against both a synchronous and an asynchronous backend —
  but only a **negative check** (stop the consumer, force RED) proves that
  assertion is testing the real pipeline rather than quietly agreeing with
  whatever answers first, the exact trap a shared-state coincidence sprang on
  Review in Chapter 7.

Chapter 18 picks up the data-ownership thread this chapter only had to solve
once, for one table, and asks it of every remaining shared row in the
monolith's schema — the full shared-data-to-owned-data argument, with
Chapter 19's CDC backfill as the mechanism for contexts that can't just
start owning their schema from day one the way this service did. Part 7
returns to the asynchrony this chapter introduced and raises its own stakes
again: payment's choreographed saga and shipping's orchestrated saga both
have to coordinate *multiple* events across *multiple* services, with
compensating actions when one step fails partway through — this chapter's
single at-least-once topic and its one idempotent consumer are the smallest
possible instance of a coordination problem Part 7 makes considerably
harder.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is real, runnable code and real, dated evidence
already in this repository: `examples/00-monolith/` (the outbox entity,
relay, and `OrderService#placeOrder`'s current form), `examples/03-notification-service/`
(`OrderPlacedConsumer`, `OrderPlacedPushConsumer`, `NotificationService`,
`Notification`, `OrderNotificationSocket`, and `MIGRATION.md`'s measured
record), `examples/01-strangler-proxy/` (`StranglerProxyRoute.java` and
`CUTOVER.md`'s full notification-cutover timeline, including the negative
check), `tooling/newman/mea.postman_collection.json`'s "Notification Context
Contract" folder, and `.github/workflows/code-ci.yml`'s
`notification-equivalence-gate` job. The captured outputs quoted above are
taken verbatim from `CUTOVER.md` and `MIGRATION.md` rather than re-run live
here, per this project's own DRQ-025 discipline. What a reader's own run
should confirm independently: the exact startup/RSS numbers in the Phase
A→B table will differ on different hardware even if the qualitative
heavier-not-regressed reading holds; and the outbox relay's two-second poll
interval, and therefore the ~5-second observed checkout-to-notification
latency it produces, is a tuned constant worth re-checking against whatever
load the reader's own environment places on it.*
