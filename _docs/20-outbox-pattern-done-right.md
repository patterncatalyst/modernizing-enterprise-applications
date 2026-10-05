---
title: "The Outbox Pattern, Done Right"
order: 20
part: "Data Across the Seam"
description: "Transactional outbox vs. dual-write; idempotent consumers; ordering and deduplication."
---

Chapter 17 introduced the transactional outbox at the exact moment the
monolith needed it: the Notification extraction had to stop running a
third-party-adjacent side effect inside checkout's own `@Transactional`, and
the outbox was the mechanism that let the write survive without smuggling a
second system into that transaction's blast radius. That was the right
amount of outbox for a chapter whose real subject was event-driven
extraction. This chapter's subject is the outbox itself — not what it is, but
what it costs to run correctly once it's load-bearing infrastructure rather
than a one-paragraph aside. Every concern below is a concern a team actually
hits running this exact mechanism in production: what happens when delivery
duplicates (it will), what happens when two events for the same order arrive
out of order (they can, if you key wrong), how fast "eventually" really is,
when to stop polling a table and start tailing a log instead. None of this is
new code. It is the same `examples/00-monolith/` outbox and the same
`examples/03-notification-service/` consumer Chapter 17 already built,
read again at the depth a reader needs before they'd trust this pattern with
their own checkout flow.

The code is in `examples/00-monolith/` and `examples/03-notification-service/`
— the same two example directories Chapter 17 built. There is no new run
script for this chapter; the run scripts already there build/set up and run
the outbox write, the relay, and the consumer exactly as described below,
and each directory's `README.md` covers how to drive them.

## The dual-write problem: two systems, one guarantee you don't have

Here is the version of "save to the database, then publish to Kafka" that
looks correct and isn't:

```java
// NOT what this project does — the pattern this chapter explains the cure for
orderRepository.save(order);                      // write 1: Postgres
kafkaTemplate.send("order.placed", event);         // write 2: Kafka
return toDto(order);
```

It looks correct because, read top to bottom on the happy path, it does
exactly what the business wants: persist the order, then tell the world
about it. The problem is that these are two independent writes to two
independent systems over two independent network paths, and nothing anywhere
in that snippet makes them succeed or fail together. Kleppmann's *Designing
Data-Intensive Applications* names this precisely as the **dual-write
problem**: whenever an application needs to keep two systems in sync by
issuing two separate writes from its own code, there is a window — however
small — in which one write has landed and the other has not, and the
application has no way to make that window disappear just by writing careful
code around it. The failure modes are not exotic. The process can crash
between the two lines. The Kafka client can time out while Postgres already
committed. The pod can be rescheduled by the orchestrator mid-method. Every
one of these leaves the order persisted with no event ever published for it
— silently, because nothing downstream is watching for an order that exists
with no corresponding event, only for an event that failed to send. The
inverse ordering (publish first, then save) is not a fix, only a relocation
of the same hole: now a customer can receive a notification for an order
that was never actually persisted, because the save failed after the publish
already succeeded. There is no sequencing of two independent writes to two
independent systems that closes this gap. You can retry, you can log, you
can add a circuit breaker — none of it changes that the two writes are not
one operation, and "the two writes are not one operation" is the entire
problem.

It is worth being precise about the vocabulary here, because marketing copy
for message brokers throws around "exactly-once" in a way Kleppmann's
treatment of stream processing is careful to puncture. No distributed system
spanning an unreliable network can deliver a message *exactly* once end to
end — the sender can never be certain the receiver got it without an
acknowledgment, and the acknowledgment itself can be lost, which forces a
retry, which is a duplicate. What a well-built pipeline can actually promise
is **at-least-once delivery** paired with **idempotent processing** on the
receiving end, and the combination of the two is what the literature calls
*effectively-once*: duplicates may arrive, but processing them twice produces
the same observable state as processing them once. That phrase —
at-least-once plus idempotent — is this chapter's spine. The outbox supplies
the first half. The consumer you'll see below supplies the second. Neither
half alone is the pattern; the pattern is the pair.

## The transactional outbox: moving the guarantee inside one transaction

The outbox's fix for the dual-write problem is to stop treating "persist the
order" and "record that an event needs publishing" as two writes to two
systems, and instead make the second write a write to the *same* system as
the first — the same Postgres database, the same transaction, the same
commit. `OutboxEvent` is the entity that row becomes, and `OrderService`
writes it in the identical `@Transactional` method that persists the order,
charges the card, and dispatches the shipment:

```java
// examples/00-monolith/.../order/OrderService.java#placeOrder (excerpt)
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());

// write the event to the outbox, atomically, in THIS transaction. No Kafka
// client is touched here — the @Scheduled OutboxRelay is the only thing
// that talks to Kafka, on its own schedule, after this transaction has
// committed (or not).
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

Nothing about this is exotic — `writeOrderPlacedOutboxEvent` is an ordinary
`outboxRepository.save(...)` call, the same kind of write `orderRepository`
already made two lines earlier. That ordinariness is exactly the point: the
outbox row is not infrastructure glued on beside the business transaction,
it is one more row written by that transaction, subject to the identical
commit-or-rollback behavior as every other row it touches. A payment decline
that throws from `paymentService.charge(...)` unwinds the whole method —
the order row, the inventory reservation, *and* the outbox write, because
none of them has committed yet. There is no interval, however narrow, in
which the order exists and the outbox row doesn't, or the reverse — which is
precisely the guarantee a dual-write across two systems structurally cannot
offer, no matter how its two calls are ordered or wrapped.

What the outbox does **not** do is make the second hop — outbox row to
Kafka — atomic with anything. That hop is a separate, asynchronous
`OutboxRelay`, and the gap between "the business fact is durable" and "the
world has been told" is now explicit, bounded, and owned by one piece of
infrastructure instead of hidden inside application code that pretends it
isn't there. Closing the first gap (business-fact atomicity) by deliberately
accepting a second, smaller, well-understood gap (publish latency) is the
trade this pattern makes, and the remainder of this chapter is about running
that second gap correctly.

## How the code works

Four pieces carry this pattern end to end: the row, the relay that publishes
it, the write that creates it, and the consumer that absorbs its
at-least-once delivery. Each is real, already-running code.

**The row — `OutboxEvent`.** Its columns are the minimum needed to answer
the relay's one question ("what is unpublished, and in what order should I
send it?") and the consumer's one question ("what happened, and to which
aggregate?"):

```java
// examples/00-monolith/.../common/outbox/OutboxEvent.java
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "aggregate_type", nullable = false) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false)   private String aggregateId;
    @Column(name = "event_type", nullable = false)     private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)        private String payload;
    @Column(name = "created_at", nullable = false)      private Instant createdAt;
    @Column(name = "published_at")                      private Instant publishedAt;
    // ...
    public void markPublished() { this.publishedAt = Instant.now(); }
}
```

`aggregateId` exists for one reason only, and it drives two different
downstream behaviors you'll meet below: it becomes the Kafka message key
(ordering), and it's the field the consumer dedupes on (idempotency).
`payload` is stored as `jsonb` and the relay never parses it — it only ever
forwards the string byte-for-byte to Kafka, which is deliberate: the relay's
job is "move bytes reliably," not "understand bytes," and keeping it ignorant
of the payload's shape means a new event type never requires touching the
relay. `createdAt`/`publishedAt` are the two timestamps that turn "is this
row done" into a one-column `WHERE` clause (`published_at IS NULL`) instead
of a join or a separate status table — the simplest state machine that
could possibly work, with exactly two states.

**The write — `OrderService#writeOrderPlacedOutboxEvent`.** This is a plain
method, called from inside `placeOrder`'s `@Transactional`, that builds the
JSON payload and persists the row through the same `OutboxRepository` the
relay reads from:

```java
// examples/00-monolith/.../order/OrderService.java (excerpt)
private void writeOrderPlacedOutboxEvent(Customer customer, Order order) {
    String confirmationMessage =
            "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
    OrderPlacedEvent event = new OrderPlacedEvent(
            order.getId(), customer.getId(), customer.getEmail(),
            order.getTotalCents(), confirmationMessage, Instant.now());
    try {
        String payload = objectMapper.writeValueAsString(event);
        outboxRepository.save(new OutboxEvent(
                "Order", String.valueOf(order.getId()), Topics.ORDER_PLACED, payload));
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to serialize OrderPlacedEvent for order " + order.getId(), e);
    }
}
```

Two decisions here matter more than they look. First, `String.valueOf(order.getId())`
becomes the outbox row's `aggregateId` — the order's own primary key, not a
synthetic event id, which is what lets the consumer dedupe by the business
entity rather than by an opaque message identifier it would otherwise have
to invent and track. Second, the `catch` block does not swallow a
serialization failure and move on — it rethrows as an unchecked exception,
which rolls back the whole transaction. That looks harsh (a JSON
serialization bug now fails checkout, not just the notification), but it's
the correct failure mode for an outbox: silently dropping the write would
mean the order commits with *no* event ever recorded for it, the exact
failure the entire pattern exists to prevent. A loud failure here is strictly
better than a quiet one.

**The relay — `OutboxRelay`.** A `@Scheduled` method polls on a fixed delay,
pulls a bounded batch of unpublished rows oldest-first, and publishes each:

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
    } catch (ExecutionException | TimeoutException e) {
        // left unpublished; picked up again next tick
    }
}
```

Walk this call by call. `findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()`
is backed by the partial index `idx_outbox_unpublished` from
`V3__outbox.sql` (`CREATE INDEX ... ON outbox (created_at) WHERE
published_at IS NULL`), so this query stays a cheap index scan over the
*unpublished* rows only, regardless of how many published rows have piled up
— the published rows simply never enter the index. The cap at fifty exists
so one scheduled tick can never hold an unbounded amount of work if the
relay falls behind; a backlog of ten thousand unpublished rows gets drained
across two hundred ticks instead of one enormous one. `kafkaTemplate.send(topic,
event.getAggregateId(), event.getPayload())` passes the aggregate id as the
Kafka record key — not `null`, not the outbox row's own surrogate `id` —
and that one argument is what makes partitioning-by-aggregate work at all,
covered in its own section below. `.get(5, TimeUnit.SECONDS)` is the single
most consequential line in the method: it turns an asynchronous Kafka send
into a blocking call the relay waits on, and it is what makes
`event.markPublished()` on the next line trustworthy — by the time that line
runs, the broker has already acknowledged the write. The `catch` block
deliberately does nothing but log: it does not rethrow, it does not mark the
row in any special failed state, it just lets the loop continue to the next
event, and leaves this one for the next tick to retry.

**The consumer — `NotificationService#recordOrderPlaced`.** On the far side
of Kafka, this is the method that has to assume the relay's promise will
occasionally be broken on purpose:

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

`findByOrderId` first, insert second — check-then-insert, keyed on the exact
`aggregateId` the outbox row carried across the wire as `orderId`. For the
ordinary case (a single consumer replica, redeliveries arriving one at a
time, which is the common case for a batch size of fifty and a two-second
poll) this is the entire idempotency story, and it is cheap: a redelivered
event costs one indexed `SELECT` and a no-op return, not a thrown exception
caught somewhere upstream.

**The fragile bit this walkthrough has to name plainly:** the check and the
insert above are two separate statements, not one atomic operation, so two
deliveries of the same event landing concurrently — two consumer replicas,
or a redelivery racing a still-in-flight first attempt — can both pass the
`findByOrderId` check before either one's insert commits. The application
code alone cannot close that window; only the database can, which is exactly
why the next section exists as a second, independent layer rather than a
nice-to-have.

## Done right, concern by concern

### At-least-once delivery means the consumer must own idempotency, in two layers

The relay's own class-level comment states its guarantee without softening
it: "the Kafka send and the `published_at` stamp are two separate
operations, not one atomic unit." If the process dies in the gap between
them — broker acked, stamp never committed — the row still reads as
unpublished on the next poll and gets sent again, byte-for-byte identical.
This is not a bug to be engineered away; it is the one guarantee a polling
relay (or, as the next section shows, a log-tailing one) cannot give you for
free, because "write to Postgres" and "write to Kafka" remain two different
systems even after the outbox has made the *first* write — order plus event
row — atomic. Accepting at-least-once at the relay is only safe because the
consumer is idempotent, and this project's consumer is idempotent by two
independent layers, not one:

- **Layer one — the application check-then-insert** shown above. This is
  the cheap, common-case path: an ordinary redelivery costs a read and a
  no-op, never a thrown exception.
- **Layer two — a database constraint that holds even when layer one
  races and loses:**

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

A partial unique index, scoped to rows where `order_id IS NOT NULL` so a
future notification type that doesn't originate from an order is never
forced into the same constraint, is the backstop Postgres itself enforces
regardless of what the application code believed when it ran its own check.
The two layers answer genuinely different questions rather than duplicating
one answer: the application check is the fast path that keeps the common
case from ever touching a constraint violation; the unique index is the
guarantee that holds in the one case application logic structurally cannot
close on its own — two writers racing past the same read at nearly the same
instant. Either layer alone leaves a gap: the application check alone has
the race condition above; the unique index alone would work, but every
ordinary redelivery would pay for a thrown-and-caught constraint violation
instead of a cheap no-op read. Running both is not belt-and-suspenders
paranoia — it's matching each layer to the specific failure it, and only it,
actually prevents.

### Ordering: partition by aggregate key, not by none and not by everything

A Kafka topic's ordering guarantee is per-partition, not per-topic — the
broker promises that messages within one partition are delivered to a
consumer in the order they were produced, and promises nothing about
ordering *across* partitions. That single fact decides how an outbox relay
must choose its message key. `OutboxRelay` calls
`kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())` —
the second argument is the record key, and Kafka's default partitioner
hashes that key to select a partition, meaning every event sharing one
`aggregateId` (one order) lands in the same partition every time and is
therefore delivered to any given consumer in the exact order the relay sent
them. That is the only ordering guarantee this pattern needs: a reader of
the notification service never requires that order #41's event be processed
before order #42's — those are independent aggregates whose events can
interleave freely — but it does require that, if a single order ever
produces more than one event in its lifetime (this chapter's single
`order.placed` topic doesn't yet, but a future `order.cancelled` alongside it
would), those events arrive in the sequence they were written. Keying by
`null` would scatter even one order's own events across every partition at
random, destroying that guarantee for no benefit; keying by a constant
would force every event through one partition regardless of which order it
describes, destroying Kafka's ability to parallelize consumption across
partitions for no benefit either. Keying by the aggregate id is the one
choice that buys ordering exactly where it's needed and nowhere it isn't,
and it's a design decision made once, at the relay, rather than something
each future consumer has to reconstruct. In this project's development and
CI setup the `order.placed` topic auto-creates with a single partition, so
every event is trivially ordered regardless of key — the keying choice is
what makes it safe to grow the topic to many partitions later without
silently breaking per-order ordering the day someone bumps the partition
count for throughput.

### The relay's poll-interval and the latency it buys

`outbox.relay.poll-interval-ms` defaults to two thousand milliseconds, and
that single number is the whole latency-versus-load trade-off in one place.
Shorten it and a customer's order confirmation arrives sooner after
checkout, at the direct cost of the relay running its unpublished-rows query
more often against the same table every other request is also writing to.
Lengthen it and that read load drops, at the direct cost of a customer
waiting longer, on average half the interval and up to the full interval in
the worst case, before their event is even picked up for publishing — before
any of the broker round-trip or consumer processing time is added on top.
Chapter 17's own cutover evidence puts a number on the composed effect:
observed checkout-to-notification latency around five seconds end to end
against this exact two-second poll interval, batch size, and broker
round-trip. Nothing about two seconds is a law of nature — it's a tuned
constant, chosen to be fast enough to feel reasonably responsive in a demo
without hammering Postgres with a sub-second poll loop, and it's exactly the
kind of fragile, environment-specific number the depth standard asks a
chapter to name rather than bury: a reader deploying this relay under
real load should expect to retune it against their own table size, their own
write volume, and their own tolerance for "eventually."

### `published_at` is stamped only after the broker acknowledges

It bears restating as its own concern, because getting the order of these
two lines wrong is the single easiest way to quietly break the at-least-once
guarantee into something worse: `.get(5, TimeUnit.SECONDS)` blocks until the
broker has acknowledged the send, and only then does `event.markPublished()`
run, followed by the `save` that commits the stamp. If those two lines were
reversed — mark published first, send second — a crash between them would
leave a row permanently stamped as published whose event was never actually
sent, and nothing in the system would ever notice or retry it, because the
relay's own query explicitly excludes published rows. That failure mode is
strictly worse than a duplicate: a duplicate is merely redundant work for an
idempotent consumer to absorb, while a falsely-stamped row is a silently
lost event, which is the exact failure the entire outbox pattern exists to
rule out. "Publish, then stamp" is not a stylistic preference; it's the line
that decides whether this mechanism's failure mode is "occasionally
redundant" or "occasionally silent," and those are not close to equally bad.

### Cleanup and retention — the gap this project leaves open

Here is a fragile bit worth naming rather than glossing over: nothing in
`examples/00-monolith/` deletes, archives, or partitions published outbox
rows. `idx_outbox_unpublished`'s partial condition means the relay's own hot
query never slows down as published rows accumulate — that index simply
never indexes them — but the table itself still grows forever, and an
unbounded table is eventually a problem for autovacuum, for backup size, and
for anyone who has to run an unindexed diagnostic query against `outbox`
someday. A production deployment of this exact pattern needs a retention
policy this codebase does not yet implement: a second scheduled job, running
far less often than the publishing relay, that deletes (or moves to cold
storage) rows where `published_at` is older than some retention window —
long enough to outlive any plausible redelivery or audit need, short enough
to keep the table bounded. This chapter is naming the gap rather than
closing it, in keeping with the standing of every example in this book:
credit the code with exactly what it does, and no more.

### Poison messages — the retry this relay doesn't yet bound

`publishOne`'s `catch` block leaves a failed row unpublished unconditionally,
for *any* `ExecutionException` or `TimeoutException`, with no retry counter
and no circuit breaker. For a transient failure — the broker briefly
unreachable, a timeout under load — that is exactly the right behavior: the
next tick tries again, and it eventually succeeds once the broker recovers.
But the same code path handles a permanently failing event identically to a
transiently failing one: a payload the broker consistently rejects (an
oversized message past `max.request.size`, say) will be re-attempted,
fail, and get re-attempted again, forever, on every single poll, since it
keeps sorting to the front of "oldest unpublished first." It never blocks
other events in that batch — the `for` loop continues past a caught
exception to the next row — but it does consume one of the batch's fifty
slots on every tick indefinitely, and a handful of such rows would
eventually crowd out genuinely fresh events from ever being selected. A
hardened version of this relay would track an attempt count per row (one
more column), cap retries, and move a row that exceeds the cap into a
separate dead-letter table or topic for a human or an automated remediation
process to inspect — the event is never silently lost, but it also stops
competing with healthy events for the relay's limited per-tick attention.
That hardening is not present in `examples/00-monolith/` today; naming it
here is this chapter doing exactly what the depth standard asks of a
fragile bit, not waving it away as solved.

## Two relay strategies: polling versus log-based CDC

Everything above describes one way to read an outbox table — have a
scheduled process poll it. That is not the only way, and it is worth being
precise about the alternative before deciding which one a given extraction
deserves.

A **polling relay**, which is what this project's notification pipeline
uses, is a scheduled job that periodically issues an ordinary `SELECT`
against the outbox table, looking for rows matching some "not yet
published" predicate. Its cost model is straightforward: it adds a
recurring read against a table your application is also writing to, and its
latency is bounded below by the poll interval — an event can never be
published faster than the next scheduled tick notices it, no matter how
fast the broker itself responds. Its operational footprint is close to
zero: it is a method in the application's own process, with no new
infrastructure to run, patch, or monitor beyond the application itself.

A **log-based CDC relay** — the kind a connector like Debezium implements —
takes a structurally different approach: instead of asking the table "what's
new" on a schedule, it attaches to the database's write-ahead log (the same
log Postgres uses internally for crash recovery and streaming replication)
and is pushed every row-level change the instant it's written, in commit
order, with no polling interval to wait out. Latency drops to roughly the
time it takes the WAL record to reach the connector — commonly tens to a
few hundred milliseconds rather than a multi-second poll cadence — and there
is no periodic table scan competing with application traffic at all,
because the mechanism is push-based rather than pull-based. That latency
and read-load win is not free. Reading the WAL requires a **replication
slot** — a durable bookmark the database keeps so it knows how much WAL
history it must retain until the connector has consumed it, and a connector
that falls behind or disappears can cause that retained WAL to grow
unboundedly, a failure mode a polling relay simply cannot produce because it
never asks the database to retain anything on its behalf. It also typically
means standing up **Kafka Connect** (or an equivalent CDC runtime) as a new
piece of infrastructure, with its own deployment, its own monitoring, its
own upgrade cadence, and its own connector-specific configuration surface —
real, ongoing operational weight that a two-line `@Scheduled` method inside
an existing Spring process does not carry.

The decision this project made (DRQ-034, recorded in `decisions.md`) was
deliberate about not paying that weight on the very first extraction to need
an outbox: Notification's event volume and latency tolerance don't demand
sub-second delivery, so a polling relay teaches the outbox's real
guarantee — atomic write, at-least-once publish — plainly, without pulling a
connector and a replication slot into the stack a chapter early, purely to
look more sophisticated than the problem requires. That calculus changes for
the Inventory extraction. Stock levels are read on nearly every product page
and checkout attempt across the whole system; a multi-second lag between a
reservation committing and that fact becoming visible downstream is a much
more direct cost there than a few extra seconds before a confirmation email
is queued. The Inventory extraction (covered in *Transaction Log Tailing,
CDC & Extraction 3 — Inventory*) is exactly where this project adopts
Debezium-style CDC — not because CDC is categorically superior to polling,
but because that extraction's latency and read-load requirements are
different enough from Notification's to justify the connector and the
replication slot the polling relay above got to avoid. The general rule this
pair of chapters is teaching: reach for a polling relay first, because it is
the plain, low-ceremony default; reach for log-based CDC only once a
specific, named latency or load requirement can't be met by shortening the
poll interval, because that connector is infrastructure you now operate
forever, not a line of configuration you tune once.

## What you learned

- The **dual-write problem** is structural, not a matter of careless
  sequencing: two independent writes to two independent systems can never be
  made atomic by application code alone, and Kleppmann's framing of
  "effectively-once" (at-least-once delivery plus idempotent processing) is
  the realistic target, not the marketing fiction of "exactly-once."
- The **transactional outbox** closes that gap for the *first* hop only — the
  business write and the event row commit or roll back together, because
  they're the same transaction on the same database — and deliberately
  leaves the second hop (table to broker) at-least-once, a trade this
  project's relay states plainly rather than hides.
- At-least-once delivery is only safe paired with a consumer that is
  idempotent on **two independent layers**: a cheap application-level
  check-then-insert for the common case, and a database constraint as the
  backstop for the race the application layer alone cannot close.
- **Keying by aggregate id**, not by nothing and not by a constant, is what
  gives an outbox event stream the one ordering guarantee it actually needs
  — in order within an aggregate — without sacrificing a broker's ability to
  parallelize across aggregates.
- A **polling relay** is the right default — simple, low-ceremony, predictably
  bounded by its poll interval — right up until a specific latency or
  read-load requirement can't be met by tuning that interval, at which point
  **log-based CDC** (Debezium reading the WAL via a replication slot) trades
  a lower latency floor for a connector you now operate permanently; this
  project makes that trade for the first time in the Inventory extraction.

*Event Sourcing & CQRS* picks up directly from the event stream this
chapter hardened: once `order.placed` (and the events the Inventory and
Payment extractions add beside it) can be trusted to arrive at-least-once,
in order per aggregate, and to be processed exactly-effectively-once, that
stream becomes a credible source from which a read model can be built and
kept current — which is precisely the CQRS read side that chapter
constructs. *Transaction Log Tailing, CDC & Extraction 3 — Inventory*
is the chapter that actually puts log-based CDC into production in this
project, the alternative relay strategy previewed above.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above is real, already-running code already verified by
Chapter 17's own cutover evidence: `examples/00-monolith/`'s `OutboxEvent`,
`OutboxRelay`, `OutboxRepository`, `OrderPlacedEvent`, `V3__outbox.sql`, and
`OrderService#placeOrder`/`#writeOrderPlacedOutboxEvent`; and
`examples/03-notification-service/`'s `OrderPlacedConsumer`,
`NotificationService#recordOrderPlaced`, and `V3__idempotent_order_id.sql`.
This chapter adds no new code and reruns no new demo; its claims are a
deeper reading of exactly the mechanism Chapter 17 already exercised
end-to-end (including the negative check proving the consumer's idempotency
under redelivery). What a reader's own run should confirm independently: the
observed checkout-to-notification latency against the two-second poll
interval on their own hardware and load; and that the cleanup/retention and
poison-message gaps named above remain genuinely unaddressed in the current
codebase rather than quietly fixed since this chapter was written — both are
explicitly scoped as future hardening, not implemented here.*
