---
title: "The Outbox Pattern, Done Right"
order: 20
part: "Data Across the Seam"
description: "Transactional outbox versus dual write: idempotent consumers, ordering by aggregate key, relay latency, and when to move from polling to log-based CDC."
---

## Learning objectives

By the end of this chapter you can:

- Explain the dual-write problem and why "exactly-once" means at-least-once delivery plus idempotent processing.
- Trace the four pieces of an outbox pipeline (row, transactional write, polling relay, idempotent consumer) and say why `published_at` is stamped only after the broker acknowledges.
- Implement two layers of consumer idempotency, an application check-then-insert and a partial unique index, and say why neither is enough alone.
- Key outbox events by aggregate id to get per-aggregate ordering while the broker still parallelizes across aggregates.
- Tune a relay's poll interval as a latency versus read-load trade-off, and name the retention and poison-message gaps that remain.
- Choose between a polling relay and a log-based CDC relay from a named latency or load requirement.

## Why this matters

The Notification extraction needed the outbox to stop running a side effect inside checkout's own `@Transactional`. The subject here is what the outbox costs to run correctly once checkout depends on it: what happens when delivery duplicates (it will), when two events for one order arrive out of order (they can, if you key wrong), how fast "eventually" really is, and when to stop polling a table and tail a log. The code is the `examples/00-monolith/` outbox and the `examples/03-notification-service/` consumer, read at the depth you need before trusting the pattern with your own checkout. The order extraction later moved the outbox into `examples/07-order-service` (`OrderOutboxRelay`) and deleted the monolith's copy; the monolith code quoted below is at the `stage/02-notification-extracted` tag (`git checkout stage/02-notification-extracted`).

{% include excalidraw.html file="dual-write-vs-outbox" alt="The dual-write hazard versus the transactional outbox, stacked for contrast: on top, saving to the database and publishing to Kafka as two independent writes with a gap nothing closes; on the bottom, the business write and the outbox row committing together in the same transaction, with the second hop to Kafka made explicit and owned by the relay." caption="Figure 20.1: Dual-write versus the transactional outbox" %}

## Pattern: Transactional Outbox

### The dual-write problem

This version of "save to the database, then publish to Kafka" looks correct and isn't:

```java
// NOT what this project does: the pattern the outbox is the cure for
orderRepository.save(order);                      // write 1: Postgres
kafkaTemplate.send("order.placed", event);         // write 2: Kafka
return toDto(order);
```

These are two independent writes to two systems over two network paths, and nothing makes them succeed or fail together. Kleppmann calls this the **dual-write problem**: there is a window in which one write has landed and the other hasn't, and careful code around it can't close it. The process can crash between the lines, the Kafka client can time out after Postgres committed, the pod can be rescheduled mid-method. Each leaves the order persisted with no event, and nothing downstream notices, because it watches for events that failed to send, not for orders with no event. Publishing first only relocates the hole: a customer is notified about an order whose save then failed. Retries, logging and circuit breakers don't change that the two writes are not one operation.

No distributed system over an unreliable network delivers a message exactly once end to end. The sender can't be sure the receiver got it without an acknowledgment, the acknowledgment can be lost, and that forces a retry, which is a duplicate. What a pipeline can promise is **at-least-once delivery** with **idempotent processing**, which Kleppmann calls effectively-once: duplicates may arrive, but processing them twice leaves the same state as once. The outbox supplies the first half and the consumer the second. The pattern is the pair.

### Moving the guarantee inside one transaction

The outbox makes the second write a write to the same system as the first: the same Postgres database, transaction and commit. `OrderService` writes the `OutboxEvent` row in the same `@Transactional` method that persists the order, charges the card and dispatches the shipment:

```java
// examples/00-monolith/.../order/OrderService.java#placeOrder (excerpt)
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());

// write the event to the outbox, atomically, in THIS transaction. No Kafka
// client is touched here. The @Scheduled OutboxRelay is the only thing
// that talks to Kafka, on its own schedule, after this transaction has
// committed (or not).
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

`writeOrderPlacedOutboxEvent` is an ordinary `outboxRepository.save(...)`, one more row under the same commit-or-rollback as every other row. A payment decline that throws from `charge` unwinds the order row, the inventory reservation and the outbox write together. There is no interval in which the order exists and the outbox row doesn't, which no ordering of a dual write can offer.

The second hop, outbox row to Kafka, is not atomic with anything. It belongs to a separate asynchronous `OutboxRelay`, so the gap between "the fact is durable" and "the world has been told" is bounded and owned by one component instead of hidden in application code. You close the business-fact gap by accepting a second, smaller, well-understood one: publish latency.

{% include excalidraw.html file="outbox-four-piece-pipeline" alt="The outbox pattern's four pieces as one labeled flow: the write that persists the outbox row inside placeOrder's transaction, the relay that polls for unpublished rows and publishes them to Kafka, the broker topic the relay blocks on for acknowledgment, and the idempotent consumer that checks before inserting." caption="Figure 20.2: The outbox pattern's four pieces: write, relay, broker, consumer" %}

### The four pieces

**The row: `OutboxEvent`.** Its columns answer the relay's question ("what is unpublished, and in what order?") and the consumer's ("what happened, to which aggregate?"):

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

`aggregateId` drives two behaviors: it becomes the Kafka message key (ordering) and the field the consumer dedupes on (idempotency). `payload` is `jsonb` and the relay forwards it byte for byte without parsing, so a new event type never touches the relay. `createdAt` and `publishedAt` reduce "is this row done" to `WHERE published_at IS NULL`, a state machine with two states.

**The write: `OrderService#writeOrderPlacedOutboxEvent`.** A plain method called inside `placeOrder`'s transaction:

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

Two decisions matter. The order's own primary key becomes the `aggregateId`, not a synthetic event id, so the consumer dedupes by business entity instead of tracking an opaque message id. And a serialization failure rethrows unchecked and rolls back the whole transaction. A JSON bug now fails checkout, which is the correct failure mode: swallowing it would commit an order with no event recorded, the failure the pattern exists to prevent.

**The relay: `OutboxRelay`.** A `@Scheduled` method polls on a fixed delay, pulls a bounded batch of unpublished rows oldest first and publishes each:

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

The query is backed by the partial index `idx_outbox_unpublished` from `V3__outbox.sql` (`CREATE INDEX ... ON outbox (created_at) WHERE published_at IS NULL`), so it stays a cheap scan over unpublished rows however many published rows pile up. The cap of fifty bounds a tick's work: a backlog of ten thousand drains across two hundred ticks. The aggregate id goes in as the Kafka record key, not `null` and not the row's surrogate `id`. `.get(5, TimeUnit.SECONDS)` turns the asynchronous send into a blocking call, so `markPublished()` runs only after the broker has acknowledged. The `catch` logs and continues, without rethrowing or marking the row failed, leaving it for the next tick.

**The consumer: `NotificationService#recordOrderPlaced`.** It must assume the relay's at-least-once guarantee means the same event occasionally arrives twice:

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

Check, then insert, keyed on the `aggregateId` that crossed the wire as `orderId`. With one consumer replica and redeliveries arriving one at a time, a redelivery costs one indexed `SELECT` and a no-op return. The weakness is that check and insert are two statements: two deliveries of the same event arriving concurrently (two replicas, or a redelivery racing an in-flight first attempt) can both pass the check before either insert commits. Only the database can close that window.

{% include excalidraw.html file="transactional-outbox-sequence" alt="The transactional-outbox guarantee contrasted with the smell it replaces: the old flow where checkout called notification synchronously inside the same transaction, any failure rolling back every other write; and the new flow where the last write before commit is an outbox row, published later by the relay to a consumer that is idempotent by construction." caption="Figure 20.3: The old synchronous call versus the new outbox-and-relay flow" %}

## Done right, concern by concern

### Two layers of idempotency

The relay's own class comment says it outright: the Kafka send and the `published_at` stamp are two operations, not one atomic unit. If the process dies in between, the broker has acked but the stamp never committed, so the row reads as unpublished on the next poll and is sent again, byte for byte. A log-tailing relay has the same property, because Postgres and Kafka remain two systems after the outbox makes the first write atomic. At-least-once is safe only because the consumer is idempotent twice over:

- **Layer one: the application check-then-insert** above. The cheap common-case path; a redelivery costs a read, never an exception.
- **Layer two: a database constraint that holds when layer one loses a race.**

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

The partial index is scoped to rows with an `order_id`, so a future notification type that doesn't originate from an order isn't forced into the constraint. The layers answer different questions. The check keeps ordinary redeliveries from ever hitting a constraint violation; the index guarantees correctness when two writers race past the same read. The check alone has the race; the index alone works but charges every ordinary redelivery a thrown-and-caught violation.

### Ordering: partition by aggregate key

Kafka orders messages within a partition, not across a topic. `OutboxRelay` passes `event.getAggregateId()` as the record key, and the default partitioner hashes it, so every event for one order lands in the same partition and reaches a consumer in the order the relay sent it. That is all the ordering this pattern needs. Order 41's events never need to precede order 42's, but if one order ever emits more than one event (a future `order.cancelled` beside `order.placed`), those must arrive in write order.

| Key | Effect |
|---|---|
| `null` | One order's events scatter across partitions at random; per-order ordering is lost. |
| A constant | Every event goes through one partition; consumption can't parallelize. |
| Aggregate id | Ordering exactly where it's needed, parallelism everywhere else. |

In development and CI the `order.placed` topic auto-creates with a single partition, so everything is trivially ordered. The key is what makes it safe to add partitions later without breaking per-order ordering.

### Poll interval and the latency it buys

`outbox.relay.poll-interval-ms` defaults to 2000, and that one number is the latency versus load trade-off. Shorten it and confirmations arrive sooner, at the cost of the unpublished-rows query running more often against a table the application is also writing. Lengthen it and read load drops, but an event waits on average half the interval and up to the whole interval before it is picked up, before broker and consumer time. The Notification cutover evidence recorded roughly five seconds checkout-to-notification end to end at the two-second interval. Two seconds is a tuned constant, not a law; retune it against your own table size, write volume and tolerance for "eventually."

### Stamp `published_at` only after the ack

`.get(5, TimeUnit.SECONDS)` blocks until the broker acknowledges, and only then does `markPublished()` run and the `save` commit the stamp. Reverse them and a crash between the two leaves a row stamped as published whose event was never sent. The relay's query excludes published rows, so nothing ever retries it. A duplicate is redundant work an idempotent consumer absorbs; a falsely stamped row is a silently lost event, the failure the outbox exists to rule out. "Publish, then stamp" decides whether the failure mode is occasionally redundant or occasionally silent.

### Retention: a gap left open

Nothing in `examples/00-monolith/` deletes, archives or partitions published outbox rows. The partial index keeps the relay's hot query fast as they accumulate, but the table grows forever, which eventually costs autovacuum, backup size and any unindexed diagnostic query. A production deployment needs a second, far less frequent scheduled job that deletes or archives rows whose `published_at` is older than a retention window, long enough to outlive any plausible redelivery or audit need and short enough to keep the table bounded. This code does not implement it.

### Poison messages: retries the relay doesn't bound

`publishOne` leaves a failed row unpublished for any `ExecutionException` or `TimeoutException`, with no retry counter and no circuit breaker. For a transient failure, a briefly unreachable broker or a timeout under load, that is right: the next tick succeeds. A permanently failing event is treated the same way. A payload the broker always rejects (an oversized message past `max.request.size`) is retried on every poll, and because it sorts to the front of "oldest unpublished first" it takes one of the batch's fifty slots each tick. It never blocks other rows, since the loop continues, but a handful of such rows would crowd fresh events out. A hardened relay would add an attempt-count column, cap retries and move a row over the cap to a dead-letter table or topic for inspection. That hardening is not present in `examples/00-monolith/`.

## Polling relay or log-based CDC

A **polling relay** is a scheduled job that issues an ordinary `SELECT` for unpublished rows. It adds a recurring read against a table the application also writes, and its latency is bounded below by the poll interval. Its operational footprint is near zero: a method in the application's process.

A **log-based CDC relay**, such as Debezium, attaches to the database's write-ahead log and is pushed every row change in commit order, with no interval to wait out. Latency drops to roughly the time the WAL record takes to reach the connector, commonly tens to a few hundred milliseconds, and no periodic scan competes with application traffic. The costs:

- A **replication slot**, a durable bookmark that makes the database retain WAL until the connector consumes it. A connector that falls behind or disappears lets retained WAL grow without bound, a failure a polling relay can't produce.
- **Kafka Connect** (or an equivalent CDC runtime) as new infrastructure with its own deployment, monitoring, upgrades and connector configuration.

| | Polling relay | Log-based CDC relay |
|---|---|---|
| Latency floor | The poll interval | Tens to hundreds of ms |
| Read load | Recurring query on the outbox | None on the table |
| New infrastructure | None | Kafka Connect, replication slot |
| Failure mode | Lag | Unbounded retained WAL |

Notification's volume and latency tolerance don't demand sub-second delivery, so polling teaches the outbox's real guarantee, atomic write and at-least-once publish, without a connector and a slot. The calculus changes for Inventory. Stock is read on nearly every product page and checkout, and multi-second lag between a reservation committing and becoming visible downstream costs far more than a delayed confirmation email. That extraction adopts Debezium-style CDC, not because CDC is categorically better but because its requirements justify the connector and the slot.

> **Decision**
>
> Start with a polling relay. Move to log-based CDC only when a named latency or read-load requirement can't be met by shortening the poll interval, because the connector is infrastructure you operate permanently. Whichever relay you use, deliver at least once, stamp after the ack, key by aggregate id, and make the consumer idempotent in the application and in the database.

> **🤖 Explore with your agent**
>
> "In `examples/03-notification-service`, write a test that delivers the same `order.placed` event to `NotificationService#recordOrderPlaced` from two threads at once. Show which of the check-then-insert and the `uq_notifications_order_id` index each outcome depends on, then add an attempt-count column to `examples/07-order-service`'s `OrderOutboxRelay` that stops retrying a row after five failures."
>
> Check: run `./mvnw test` in both modules; exactly one `notifications` row exists for the order, and a row that fails five times stops being selected.

## Build, run, observe

On the current tree the order service's `OrderOutboxRelay` feeds the notification service, and the notification demo exercises the whole pipeline through the edge router:

```bash
demos/demo-notification-cutover.sh
```

To run the monolith's own relay, check out `stage/02-notification-extracted`, bring up the compose stack (Postgres, Kafka), start the monolith on `:8080` (the outbox is its only notification path there) and the notification service on `:8083`. A checkout (`POST :8080/api/orders`) writes an `order.placed` outbox row, the `OutboxRelay` publishes it to Kafka, and `GET :8083/api/notifications?customerId=1` shows the notification within about five seconds, consistent with the two-second poll. Each example's `README.md` covers driving them by hand.

Experiments:

- Reset an outbox row's `published_at` to null to simulate a redelivery: the relay publishes a second Kafka message and the service consumes it, but the notification count stays at one.

## For deeper coverage

- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the dual-write problem and the effectively-once (at-least-once plus idempotent) framing the whole pattern is built on.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the transactional outbox pattern covering the pattern in production depth.
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the idempotent-receiver pattern and the message-key-driven ordering discipline behind two-layer dedupe and aggregate-id keying.
- [Debezium: Connector documentation](https://debezium.io/documentation/): the canonical reference for the log-based CDC relay compared here against the polling relay.
- [Apache Kafka: Message Delivery Semantics](https://kafka.apache.org/documentation/#semantics): the canonical definition of at-least-once and exactly-once delivery that the effectively-once framing refines.
