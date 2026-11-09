---
title: "Extraction 2: Notification Service"
order: 17
part: "The Strangler Fig in Practice"
description: "A transactional outbox in the monolith, a Kafka backbone and an idempotent Quarkus consumer move notification out of the checkout transaction."
---

## Learning objectives

By the end of this chapter you can:

- Name the two costs of a synchronous, in-transaction notification call (latency coupling and failure-domain coupling) and see why curing them changes when the work happens, not only where it runs.
- Write an outbox entry in the same `@Transactional` as the business change, and state where that atomicity stops.
- Build a SmallRye Reactive Messaging `@Incoming` consumer with two layers of idempotency: a check-then-insert and a partial unique index.
- Justify why an event-driven read model owns its schema from the first day.
- Write an eventual-consistency poll that passes unedited against a synchronous and an asynchronous backend, and prove with a negative check that it tests the real pipeline.

## Why this matters

Review had no synchronous collaborators, so extracting it meant moving code and proving a response shape didn't change. Notification is different. The monolith calls it inside the checkout transaction and doesn't wait for an answer it needs, so the extraction can't just move code between runtimes. It has to change when the work happens.

The code is in `examples/00-monolith/` (the transactional outbox and its polling relay), `examples/03-notification-service/` (the Quarkus consumer, its own schema and a WebSocket push) and `examples/01-strangler-proxy/` (the second cutover flag). The order extraction later deleted the monolith's `OrderService` and its outbox classes from the current tree; the monolith code quoted below is at the `stage/02-notification-extracted` tag (`git checkout stage/02-notification-extracted`).

{% include excalidraw.html file="notification-async-topology" alt="The end-to-end async notification pipeline: a client through the Camel strangler proxy to the Spring monolith, whose checkout transaction writes an order row and an order.placed outbox row atomically; a scheduled relay polls the outbox and publishes to Kafka; the Quarkus notification service runs two SmallRye consumers off that topic, one persisting idempotently into its own schema, one pushing over a WebSocket; the proxy's read route forwards /api/notifications straight to the new service." caption="Figure 17.1: The notification extraction's async topology: outbox write, polling relay, two Kafka consumers, own schema, WebSocket push" %}

## The smell: notification inside the checkout transaction

`OrderService#placeOrder` calls `NotificationService#sendOrderConfirmation(customer, order)` as an ordinary method, on the same call stack and inside the same `@Transactional` that reserves inventory, persists the order and charges a card. That one line hides two costs:

| Cost | Effect |
|---|---|
| Latency coupling | Whatever sending a confirmation takes (building a message, writing a row, calling an email provider) is checkout's time. |
| Failure-domain coupling | An exception anywhere in the chain, including the notification step, rolls back the reserved stock and the charged payment. A transient failure in a concern the business doesn't count as part of "did checkout succeed" can undo a checkout that succeeded. |

Curing them takes two mechanisms: an outbox for atomicity, and an idempotent consumer for the at-least-once delivery the outbox implies.

## Pattern: Transactional Outbox

The event row and the business change are written by the same `@Transactional`, so both commit or neither does. `OutboxEvent` is the entity; its javadoc states the guarantee:

```java
// examples/00-monolith/.../common/outbox/OutboxEvent.java
/**
 * A row in the transactional outbox. Written by
 * {@code order.OrderService#placeOrder} in the SAME {@code @Transactional}
 * as the order/payment/shipment writes, every time, so the event is atomic
 * with the business change it describes: either both the order and this row
 * commit, or neither does (a payment decline rolls both back together, same
 * as every other write in that transaction).
 *
 * <p>{@link OutboxRelay} is a separate, asynchronous reader of this table:
 * it polls for rows where {@code publishedAt IS NULL}, publishes each to
 * Kafka, and stamps {@code publishedAt} on success. That stamp is an
 * AT-LEAST-ONCE guarantee, not exactly-once...
 */
```

The write into Postgres is atomic with checkout. The publish to Kafka isn't, because it's a second system reached over a second network hop on a separate schedule. `OutboxRelay` is a Spring `@Scheduled` method that polls every two seconds for up to fifty unpublished rows, oldest first:

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

The relay blocks on the broker's acknowledgment before it stamps `publishedAt`. If the process dies after the ack but before the stamp commits, the row still reads as unpublished on the next poll and the relay republishes the same payload. That duplicate is by design. A polling relay is the simplest mechanism that gives the outbox's real guarantee (atomic write, at-least-once publish) without a CDC connector and a replication slot. Change data capture, which reads the Postgres write-ahead log instead of polling a table, comes with the inventory extraction, where its lower latency justifies the operational cost. The poll interval and its steady read load are the cost of starting simple.

It beats the alternative of calling Kafka after the commit. A post-commit publish has a gap no careful code can close: the transaction commits, the process crashes or the publish fails, and the order exists with no event, silently and permanently. The outbox moves the "did this happen" question inside the transaction. A payment decline throws before `placeOrder` reaches the outbox write, so it rolls back the inventory reservation and leaves no orphan outbox row. No window exists in which the business fact exists and the event row doesn't, or the reverse.

{% include excalidraw.html file="transactional-outbox-sequence" alt="Two stacked sequences. Top: the old flow, checkout running inventory-reserve, order-persist, payment-charge, shipping-dispatch, then calling NotificationService#sendOrderConfirmation synchronously inside the same @Transactional before commit. Bottom: the new flow, the same checkout steps but the last write before commit is an order.placed outbox row in the same transaction; a separate, later, asynchronous OutboxRelay polls for unpublished rows, publishes to Kafka, and stamps published_at only after the broker acks, with the idempotent consumer absorbing at-least-once redelivery." caption="Figure 17.2: The transactional outbox sequence: one atomic commit, then a decoupled, at-least-once publish" %}

The diff against the old code is small:

{% include codetabs.html langs="Before: synchronous, in-transaction (removed)|After: the outbox write (current)" %}

```java
// order/OrderService.java: removed
// SMELL[ch.17]: notification sent synchronously inside the checkout
// transaction instead of via an outbox + async consumer.
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());
notificationService.sendOrderConfirmation(customer, order);
return toDto(order);
```

```java
// order/OrderService.java#placeOrder: current
paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
order.confirm();
shippingService.dispatch(order, order.getShippingAddress());

// The cure: write the event to the outbox, atomically, in
// THIS transaction. No Kafka client is touched here: the @Scheduled
// OutboxRelay is the only thing that talks to Kafka, on its own
// schedule, after this transaction has committed (or not).
writeOrderPlacedOutboxEvent(customer, order);
return toDto(order);
```

The panels are about the same length. Curing a coupling smell takes the same amount of code doing a structurally different thing: a blocking call that can fail the whole transaction becomes a persistence write the transaction already knows how to roll back.

## Consuming it: SmallRye Reactive Messaging and idempotency

`examples/03-notification-service` consumes `order.placed` with the declarative `@Incoming`:

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

There's no Kafka client, offset bookkeeping or polling loop in application code. The channel configuration (`mp.messaging.incoming.order-placed.*`, bound to the topic the relay publishes to) lives in `application.properties`. The monolith never consumed its own event, so this is new Quarkus code, not a port.

The relay's at-least-once promise is honored here, in two layers. Layer one is check-then-insert, deduplicated by the order id the event carries:

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

That covers a single consumer replica processing redeliveries one at a time. Two deliveries racing concurrently, from two replicas or a redelivery landing while the first write is in flight, can both pass the check before either commits. Layer two closes the gap in the only place a race can be closed, the database:

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

The partial index leaves a future notification type that doesn't come from an order free of the constraint. The layers answer different questions. The check is the cheap common-case no-op that avoids a constraint violation on every ordinary redelivery. The index holds even when the check loses a race. Sending the same `order.placed` event twice through the real pipeline leaves exactly one notification for the order, held for a continuous three-second window (`MIGRATION.md`, "Idempotency mechanism").

### A second consumer: WebSocket push

`OrderPlacedPushConsumer` reads the same topic in a per-replica unique consumer group instead of the shared group, so every replica receives every event and fans it out over `quarkus-websockets-next` to clients connected to `/ws/notifications` in that JVM. It never persists and isn't `@Transactional`.

### Spring publisher, Quarkus consumer

The two halves are different programming models, each the natural idiom of its framework:

{% include codetabs.html langs="Monolith: Spring @Scheduled polling relay (publisher)|notification-service: Quarkus SmallRye @Incoming (consumer)" %}

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

The relay is imperative: it owns a loop, a batch size and a blocking call to the broker, because Spring has no declarative abstraction over "read unpublished rows and publish them". The consumer is declarative: `@Incoming` is the whole subscription, and deserialization, offsets and delivery sit beneath it, driven by configuration. The relay reaches into a table it owns and out to a broker it doesn't, on its own clock. The consumer only reacts.

## Own-schema ownership

Review's Phase A stayed in the monolith's shared Postgres schema, a defensible, scoped deferral, because Review served a synchronously consistent REST contract against data the monolith also wrote. Notification can't defer. An event-driven read model that shared the upstream aggregate's table would have two independent writers, the monolith's synchronous path and the service's consumer, racing on the same rows with no protocol to reconcile them. Owning the table from Phase A onward avoids that split-brain by construction, not by discipline.

The `Notification` entity lives in its own `notification` schema (same Postgres instance, different namespace, its own Flyway history). Its `customerId` and `orderId` are plain `Long` columns, not `@ManyToOne` relations, because the monolith's `customers` and `orders` tables aren't in a schema the service owns. A foreign key becomes a scalar populated once from the event payload. A service that owns its data can't lean on the database to enforce relationships across a boundary it no longer shares.

## The measured cost of Phase B

Phase A moved the read surface (`NotificationController`, `NotificationService.listByCustomerId`, `Notification`) onto Quarkus through the Spring-compatibility extensions, already on its own schema. Phase B removed the shim, rewrote the surface in Quarkus REST and Panache, and, because there was no Spring original to lift, added the Kafka consumer and WebSocket push as new code in the same step. `MIGRATION.md` records the comparison, measured on packaged artifacts on the same machine:

| Build | Startup | RSS | Features |
|---|---:|---:|---:|
| Phase A, JVM, Spring-compat | 1.486 s | ~296 MB | 14 |
| Phase B, JVM, idiomatic + Kafka + WebSocket | 1.765 s | ~372 MB | 16 |

Phase B starts about 19% slower and uses about 26% more memory. Unlike Review's near-wash, Notification's Phase B isn't a pure refactor. It adds two live Kafka consumers, each with a background poll thread and broker connection, plus a Netty/Vert.x WebSocket server. The idiomatic-rewrite win is masked by the capability added in the same step, so one phase-to-phase table doesn't generalize to the next.

## The cutover: two flags

The extraction has a write side and a read side in different processes, so reversibility needed two flags. The monolith's `notification.mode` (`synchronous` by default, `outbox` after cutover) decides whether checkout sends in-process or writes the outbox row. The proxy's `strangler.notification.enabled` decides whether `/api/notifications` is served by the monolith or the new service. The proxy flag has Review's shape: content-based routing on a path prefix, read once per request, resolving to one of two fixed base URLs. Match the full `/api/notifications` path, never a route-relative `/notifications`, or the predicate never fires.

`CUTOVER.md` records the runs through the proxy:

| Run | State | Result |
|---|---|---|
| 1 | flag off, `mode=synchronous` (monolith) | 58/58 green, reversibility baseline |
| 2 | flag on, `mode=outbox` (notification-service) | 70/70 green, poll retried |
| 3a | flag on, consumer down | RED, negative check |
| 3b | flag on, consumer restarted | 11/11 green |
| 4 | flag on (committed default), `mode=outbox` | 64/64 green, final sanity |

Against the synchronous monolith in run 1 the notification was present on the first GET, so the poll never retried. Against the async service in run 2, several attempts returned `200` with no matching notification before the match appeared. That's the observed outbox, Kafka and consumer latency. Decommissioning the monolith's synchronous call site and its `/api/notifications` read surface followed only after this evidence existed.

## Testing an async path without fooling yourself

An equivalence suite verifies the response, not the route that produced it. When two backends can agree by coincidence, green assertions don't show which one answered. On Review that happened by accident: a routing bug stayed invisible because both backends shared one table. The async version is sharper. "Checkout returns 201, then immediately `GET /api/notifications`" is correct against the monolith and flaky against the new service, and a team under deadline is tempted to loosen the assertion.

The fix is a bounded-wait eventual-consistency poll: it checks, and on a miss with budget remaining it retries up to ten times at 500 ms, never delaying before the first attempt. One collection is correct against both backends without edits. A synchronous backend satisfies it on attempt zero, and an asynchronous backend gets a bounded window to catch up.

A poll proves only that a suite can wait long enough, not that it waits on the right thing. The negative check closes that gap: stop the consumer and the assertion has to fail. It did:

```
Negative check: notification-service killed (the Kafka consumer and the
/api/notifications read surface are the same process); re-run through the proxy

newman run mea.postman_collection.json \
    --folder "Notification Context Contract" --env-var baseUrl=http://localhost:8888

1. AssertionError  Notification read surface returns 200
                    expected response to have status code 200 but got 500
2. AssertionError  Content-Type is application/json
                    expected 'text/plain; charset=utf-8' to include 'application/json'
3. JSONError        No data, empty input at 1:1

RED, newman exit code 1.
```

That's a stronger failure than a soft budget exhaustion, which could be excused as slow infrastructure. A 500 and a JSON parse error mean the dependency is gone. After a restart, the identical folder went green at 11/11 (newman exit code 0): the service drained the backlog immediately, including the event the relay published while the consumer was down, delivered at-least-once once a consumer was listening. RED on kill and GREEN on restart, with the collection unedited, show together that the assertion is correct and not a false positive. A poll without a negative check looks like it tests asynchrony and might not.

In GitHub Actions, the `notification-equivalence-gate` job in `.github/workflows/code-ci.yml` starts disposable Postgres and single-broker KRaft Kafka containers, builds and starts the monolith, the notification service and the proxy, and runs the Notification Context Contract folder against the proxy. A non-zero `newman` exit fails the build. The job was validated red then green before it was trusted: green at 17/17 with the poll retrying across several attempts, then red with exit code `1` after the `OrderPlacedConsumer` persist call was commented out and the full ten-attempt budget ran out, then green at 17/17 with the call restored.

> **ADLC in Action.** The extraction ran the same Frame, Map, Plan, Generate, Verify, Operate, Reconcile loop as Review, one rung up in difficulty. Frame fixed five decisions: polling over CDC, a two-phase plan plus a new consumer, two-flag reversibility, bounded-wait polling with a negative check, and JSON deferred to Avro. Plan set out thirteen steps with two parallel lanes (the monolith's outbox and the new service's scaffold) and named every reasoning-model validation gate before any outbox or consumer code existed. Generate produced the outbox, relay, two-phase service and consumers under quarkus-agent and a scaffolding skill. Verify is the negative check above. Operate is the two-flag cutover in `CUTOVER.md`. Reconcile marks Smell 4 cured.

> **Decision**
>
> When a call inside a transaction is not needed for the response, write an outbox row in that transaction and publish it asynchronously. Make the consumer idempotent twice (application check, database unique index), give an event-driven read model its own schema, and don't trust an async equivalence suite until a negative check has turned it red.

> **🤖 Explore with your agent**
>
> "In `examples/03-notification-service`, add a second event type, `order.cancelled`, with its own `@Incoming` channel and a `recordOrderCancelled` method on `NotificationService`. Keep the two idempotency layers: a lookup by order id and a partial unique index in a new Flyway migration. Don't touch `examples/00-monolith`."
>
> Check: send the same `order.cancelled` event twice through the in-memory connector and confirm one row exists; run `./mvnw test` in `examples/03-notification-service`.

## Build, run, observe

```bash
demos/demo-notification-cutover.sh
```

The script runs `scripts/stack-up.sh` if the compose stack (Postgres, Kafka) isn't up, starts the checkout chain (inventory on `:8084`, payment on `:8085`, notification on `:8083`, shipping on `:8088`, order on `:8087`), then runs the Notification Context Contract through the edge router on `:8888` and the consumer-down negative check. On the current tree the proxy has no cutover flags and routes `/api/notifications` straight to the service; to replay the flag flip and reversibility window, check out `stage/02-notification-extracted`.

- Kill the notification service and re-run the Notification folder: it fails with the 500 shown above. Restart it and the same folder passes.
- Comment out the `OrderPlacedConsumer` persist call: the poll exhausts its ten attempts and newman exits `1`.
- Publish the same `order.placed` event twice: one notification row remains.

## For deeper coverage

- Chris Richardson, *Microservices Patterns* (Manning, 2018): the transactional outbox pattern, from the write through the relay to the idempotent consumer.
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the at-least-once delivery and derived-data reasoning behind why the consumer, not the relay, owns idempotency.
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the idempotent receiver the consumer's two-layer dedupe implements.
- Michael Nygard, *Release It!*, 2nd ed. (Pragmatic Bookshelf, 2018): the stability argument for decoupling a request path from a slower downstream concern.
- [Quarkus: Using Apache Kafka with Reactive Messaging](https://quarkus.io/guides/kafka): the declarative `@Incoming` consumer model `OrderPlacedConsumer` uses.
- Cloud Native Design Patterns, [Event-Driven](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/05-event-driven.md): the companion book's treatment of event-driven architecture.
