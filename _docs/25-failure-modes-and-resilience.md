---
title: "Failure Modes & the Resilience Chassis"
order: 25
part: "Coordinating Across Services"
description: "Partial failure, timeouts, retries that need idempotency, circuit breakers, bulkheads, compensation and health checks, with SmallRye Fault Tolerance as the Quarkus chassis."
---

## Learning objectives

By the end of this chapter you can:

- Name the three outcomes of a network call (returns, throws, never returns) and why a bare gRPC call inherits an ambiguity an in-process call never had.
- Explain what the `withDeadlineAfter` deadline on `RemoteInventoryClient` buys, and why `DEADLINE_EXCEEDED` maps to a `500`, never to the `409` of `InsufficientStockException`.
- Apply the rule "a retry is only as safe as the idempotency behind it" to the outbox relay and its two-layer idempotent consumers.
- Say where a circuit breaker and a bulkhead would wrap in this codebase and why both are deferred.
- Distinguish a compensating action from a database rollback.
- Decide which failures may degrade gracefully and which must fail hard.
- Use a negative check (kill the dependency, confirm RED, restart, confirm GREEN) to turn a resilience claim into a demonstrated one.

## Why this matters

Decomposition took away one process, one write-ahead log and one atomic commit spanning four bounded contexts. The extractions each rebuilt a slice of what that guarantee gave automatically: a gRPC call that fails on a clock instead of hanging, a compensating `Release` for a reservation no database transaction spans anymore, an outbox with at-least-once publish, a strangler proxy that passes failures through. Together they are one discipline. Every mechanism below either runs in the code (`RemoteInventoryClient` and its deadline, the `placeOrder` compensation loop, the outbox relay with idempotent consumers, the `throwExceptionOnFailure=false` routing in `examples/01-strangler-proxy/`, `quarkus-smallrye-health` in `examples/03-notification-service/` and `examples/04-inventory-service/`) or is named with the reason it is deferred. The monolith listings are as they stood after the Inventory extraction, when the monolith was still the gRPC caller: `git checkout stage/03-inventory-extracted`.

## Partial failure is the normal case

Martin Kleppmann's *Designing Data-Intensive Applications* ("The Trouble with Distributed Systems") makes the point that a network can lose a request, lose a response, or take unbounded time to deliver either, and the caller cannot tell from inside its own process which happened. A node cannot reliably tell its own slowness from a slow peer, a slow network or a crashed peer. The data side of this argument is the CAP and PACELC choice between availability and consistency once a network sits between two databases. This is the caller's side of the same fact.

The monolith's `inventoryService.reserve(sku, qty)` was an ordinary method call: it returned or threw, in microseconds, on the same heap. Behind a gRPC stub a third outcome exists: the call never comes back, because inventory is slow, overloaded, restarting or partitioned. An undecorated call does not say which, or how long to wait. Sam Newman's *Building Microservices* calls a synchronous call with no bound on its duration a latent outage. Each checkout thread blocked on `reserve` holds a pool slot for as long as the far side takes, and enough of them exhaust the monolith's own pool. A healthy caller goes down because of a dependency it never stopped waiting on. Michael Nygard's *Release It!* calls this a cascading failure, and the first mechanism the project built against it is a clock.

## Timeouts and deadlines

`RemoteInventoryClient#reserve` turns "wait indefinitely" into "wait at most this long, then fail loudly":

```java
// examples/00-monolith/.../inventory/RemoteInventoryClient.java
public ReserveResult reserve(String sku, int quantity) {
    ReserveReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            .reserve(ReserveRequest.newBuilder()
                    .setStockKeepingUnit(sku)
                    .setRequestedQty(quantity)
                    .build());
    return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
}
```

The extracted order service's own `RemoteInventoryClient` carries the same `withDeadlineAfter(inventoryGrpcTimeoutMs, TimeUnit.MILLISECONDS)` forward, so the bound is live in the finished system. The value is bound to `inventory.grpc.timeout-ms` and defaults to 5,000 ms: long enough to absorb a GC pause or brief connection-pool contention on a healthy inventory service, short enough that a hung one fails a checkout in a window a customer tolerates. It is a tuned constant. Retune it against a slower network or a heavier-loaded service.

What happens when the deadline fires matters as much as its value. The blocking stub throws an unchecked `StatusRuntimeException`: `DEADLINE_EXCEEDED` when the clock ran out, `UNAVAILABLE` when the channel never connected. `reserve` does not catch it. A deadline exceeded is not a logical "insufficient stock" result, so it never maps to the `409`; Spring turns the unmapped `RuntimeException` into a `500`. That is the accurate answer. A checkout that cannot tell whether a reservation succeeded must not report success and must not retry into a state nobody can audit. A timeout is a fast failure that trades "maybe eventually correct" for "promptly explicit about not knowing", and the anti-corruption layer and the typed proto follow the same rule: an ambiguous outcome never masquerades as a known one.

A deadline bounds one call. It does not say what to do next, and it does not protect the caller from a dependency that is reliably slow rather than occasionally hung.

{% include excalidraw.html file="timeout-three-outcomes" alt="A checkout thread's call to RemoteInventoryClient#reserve branches into three outcomes once it crosses the gRPC boundary: returns a ReserveReply, throws a StatusRuntimeException mapped to a 500, or never returns at all. The never-returns branch leads to a blocked thread holding its pool slot and, without a bound in place, cascading failure as enough blocked threads exhaust the pool. A ghost box shows the deadline that bounds this outcome in this project's own code today." caption="Figure 25.1: A gRPC call's third outcome, and the cascading-failure path a deadline cuts off" %}

## Retries need idempotency

A retry is only safe if the thing retried is idempotent. Every retry in this project is paired with an idempotency mechanism on the receiving end.

`OutboxRelay` retries by omission. The `catch` block leaves the row unpublished, so the next tick (two seconds later by default) sends the identical payload again:

```java
// examples/00-monolith/.../common/outbox/OutboxRelay.java (excerpt)
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

That retry has no backoff, no jitter and no cap: the hundredth attempt at a permanently failing row uses the same five-second timeout and two-second interval as the first. A message the broker will never accept, an oversized payload for example, takes one of the relay's fifty per-tick slots on every poll indefinitely. That is the general concern of unbounded retry, deferred below with the circuit breaker.

The retry is safe to attempt because consumers expect duplicates, on two independent layers. `NotificationService#recordOrderPlaced` checks first, and a partial unique index backs the check:

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

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

The check-then-insert is the cheap common path: a redelivery costs one indexed read and a no-op return. The index closes the case application code cannot, two deliveries racing so that both pass `findByOrderId` before either commit lands. Sending the same `OrderPlacedEvent` twice through the real `@Incoming` pipeline holds the notification count at one for a continuous three-second window. The CDC consumer applies the same principle with an `ON CONFLICT` upsert keyed by the source row's primary key, so Kafka's at-least-once redelivery never double-applies a stock update. Kleppmann's name for at-least-once delivery with idempotent processing is effectively-once, the only version of "exactly-once" that survives a real network. A retry without the idempotency half is a second way to corrupt state.

## Pattern: Circuit Breaker and Bulkhead

A deadline answers how long to wait on one call. It does not answer whether to keep calling a dependency that has failed its last several calls. Nygard's circuit breaker wraps the call in a small state machine:

| State | Behavior |
|---|---|
| Closed | Calls flow through normally. |
| Open | Calls fail immediately, without the network round trip, once a failure threshold trips. |
| Half-open | A trial call checks whether the dependency recovered; success closes the breaker, failure re-opens it. |

The gain over a bare deadline is load. With a dependency known to be down, every caller still pays the full five seconds on every attempt, adding load to a struggling service and holding caller resources for the whole window. An open breaker fails in microseconds and stops hammering a dependency that is trying to recover. A bulkhead caps concurrent calls to one dependency, so a slow inventory service starves only the checkout paths that call it instead of a thread pool shared with every other request.

{% include excalidraw.html file="circuit-breaker-states" alt="The circuit breaker state machine: closed, where calls flow through normally; open, where calls fail immediately without attempting the network round-trip once a failure threshold trips; and half-open, where a trial call checks whether the dependency has recovered before the breaker closes again or a failed trial re-opens it. A dashed callout marks RemoteInventoryClient#reserve and #release as where this would wrap, and notes that no @CircuitBreaker annotation exists in this codebase today." caption="Figure 25.2: The circuit breaker's closed/open/half-open state machine, not yet built" %}

The wrap point is unambiguous: the `reserve` and `release` calls in `RemoteInventoryClient`, the one synchronous, mutating, hot-path collaborator. MicroProfile Fault Tolerance, implemented by SmallRye Fault Tolerance and available to every Quarkus service here, supplies the chassis: `@Timeout` states the deadline declaratively instead of by hand at each call site, `@CircuitBreaker` adds the state machine, `@Bulkhead` caps in-flight calls. None of the three appears in any `pom.xml` or source file in the repository. Two reasons:

- The call site lives in the Spring Boot monolith, not in a Quarkus service. SmallRye Fault Tolerance is a Quarkus-side chassis; the monolith's equivalent would be a library such as Resilience4j, also not added. A framework capability is a service capability only when someone wires it in. Once order is extracted, the caller is the Quarkus order service and the breaker is one annotation away.
- A breaker pays for its complexity when a dependency's failure rate, not its occasional slowness, is high enough that short-circuiting beats retrying. A demo-scale system with one inventory service and no production load pattern has no such measurement. Microservices are not the goal, and neither is a completed pattern catalog. Name the gap, the fix location and the chassis, and let production traffic justify the addition.

A bulkhead follows the same logic: one gRPC channel to inventory shares no dedicated thread or connection pool with the rest of the monolith's I/O, and nothing yet shows one dependency starving an unrelated path. When it does, the fix is an annotation, not a redesign.

## Pattern: Compensating Transaction

The monolith's checkout got atomic rollback across four bounded contexts as a side effect of one `@Transactional` boundary and one write-ahead log. That cannot survive a decrement that commits in another service's database. `OrderService#placeOrder` tracks every sku it reserved remotely and issues a compensating `Release` for each on any later failure:

```java
// examples/00-monolith/.../order/OrderService.java (excerpt)
try {
    // ... reserve each line via RemoteInventoryClient, track remoteReservations ...
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    // ...
} catch (RuntimeException ex) {
    compensateRemoteReservations(remoteReservations);
    throw ex;
}
```

A compensating action is not a rollback. A rollback is a property the database gives atomically when you declare a transaction. A compensating action is a second, forward-moving operation that someone wrote and named separately from what it undoes. `release` is its own RPC with its own failure modes, and the caller invokes it at exactly the right moment.

`compensateRemoteReservations` stays small: best-effort, logged loudly on failure rather than swallowed, with no idempotency key and no saga ledger, because one reservation with one compensating call does not yet need them. The choreographed saga for Payment (`order.placed`, then `payment.captured` or `payment.declined`) and the orchestrated saga for Shipping on the Camel Saga EIP scale this up to several steps across several services. Both inherit the open gap: with no idempotency key on `Release`, a retried call whose effect already landed would over-restore stock.

## Dead letters and poison messages

A poison message is one a consumer, or here a relay publishing to one, can never process however often it is retried: an oversized payload, a schema the deployed version cannot parse, a business invariant the message violates. `OutboxRelay` treats every failure alike, transient or permanent, so a poisoned row takes a per-tick slot on every poll and never gets out of the way of healthy events. The textbook fix is a dead-letter queue, a separate topic or table the message moves to after a bounded number of attempts, where a human or an automated process can inspect it. None exists. The relay and both Quarkus consumers (`OrderPlacedConsumer`, `InventoryCdcConsumer`) keep no per-message attempt count and route a failure only back into the same retry loop. Build it the day real traffic produces a poisoned message.

## Graceful degradation, and where it does not belong

Graceful degradation means serving a reduced but useful response when a dependency fails. The strangler proxy refuses it. In `StranglerProxyRoute`, `throwExceptionOnFailure=false` stops Camel from turning a backend's 4xx or 5xx into a thrown exception, so the real status code and body reach the caller. The behavior-equivalence suite must see what a backend actually produced; a proxy that turned a `500` into something friendlier would falsify the instrument that proves two backends behave alike.

Which failures tolerate degradation is decided invariant by invariant:

| Failure | Degrade? | Why |
|---|---|---|
| Stock reservation | No | A confirmed order against stock never secured is a wrong answer, not a reduced one. You can refund a card; you cannot produce a physical widget that doesn't exist. |
| Product page stock count | Yes | Read from the CDC-replicated copy a few hundred milliseconds behind the source of truth; staleness is acceptable. |

Ask whether a degraded answer is accurate or merely convenient, and never fail soft where failing hard is the only answer a customer can trust.

## Health checks and readiness

Health checks are the one chassis piece already in the dependency tree, on the Quarkus side. Both `examples/03-notification-service/pom.xml` and `examples/04-inventory-service/pom.xml` declare `quarkus-smallrye-health`, which gives each service `/q/health/live` (the process is running) and `/q/health/ready` (it can accept traffic) with no hand-written check. SmallRye Reactive Messaging adds its own health integration to the same surface, so notification-service's Kafka connection state becomes part of `/q/health/ready` as soon as the extension is on the classpath. Readiness then reflects whether the service can consume `order.placed`, not just whether its HTTP port is open.

At the Inventory extraction two things were missing. Nothing in `examples/01-strangler-proxy/` or the compose and CI definitions wires a platform's liveness and readiness probes to these endpoints; that arrives with the minikube deployment. And `examples/00-monolith/pom.xml` has no actuator or health dependency, while the proxy has no health check of its own. The health chassis existed on the two newest services and nowhere else; every Quarkus service extracted after them declares the same dependency.

## Negative checks

A resilience claim is as credible as a code comment until something breaks the dependency and the response is watched. A green equivalence run verifies the response, not the path, and a run against a healthy system only proves nothing about resilience. A negative check kills the dependency the claim rests on, confirms the run goes RED, restarts it and confirms GREEN again with nothing edited in between. Only that combination rules out both "the check is broken" and "the check never meant anything."

- Killing the notification-service process and re-running the Notification Context Contract folder through the proxy produced a `500` and a JSON parse error. That is stronger than a soft budget exhaustion: the bounded-wait poll was waiting on a real pipeline, not a lenient timeout.
- Killing the inventory service with both cutover flags set failed 34 of 56 assertions, every one on the inventory seam, while the Review and Notification folders stayed green. The failure was scoped to the dependency that was down.
- `CUTOVER.md` Scenario 3: stock reserved, payment declined, `Release` fired, stock restored to its exact pre-checkout value, verified by reading two independent databases that converged.
- The equivalence-gate CI jobs for Notification and Inventory (`.github/workflows/code-ci.yml`) were each proven red then green on a deliberate break before being trusted as permanent gates.

## The resilience scorecard

| Built, and proven by a negative check | Deferred, with a reason |
|---|---|
| Per-call gRPC deadline on `RemoteInventoryClient` (`inventory.grpc.timeout-ms`) | Circuit breaker on the inventory call: no measured failure rate, and the call site is in the Spring monolith |
| At-least-once delivery with two-layer idempotent consumption (outbox relay, check-then-insert plus partial unique index, `ON CONFLICT` upsert) | Bulkhead around the same call: no observed starvation |
| Explicit compensating action instead of rollback (`compensateRemoteReservations`) | Backoff, jitter and a retry cap on the outbox relay |
| Un-degraded failure propagation at the proxy (`throwExceptionOnFailure=false`) | Dead-letter path for messages that can never process |
| MicroProfile Health, with broker connectivity, on both Quarkus services | Platform use of the health endpoints; idempotency key on `Release` |

{% include excalidraw.html file="resilience-scorecard" alt="A two-column scorecard. Left, implemented and proven by a negative check: the per-call gRPC deadline, at-least-once delivery paired with two-layer idempotent consumption, the explicit compensating action in place of rollback, transparent un-degraded failure propagation at the proxy, and MicroProfile Health endpoints on both Quarkus services. Right, deferred by design with a stated reason: a circuit breaker, a bulkhead, retry backoff/jitter/cap on the outbox relay, a dead-letter path, platform-level consumption of the health endpoints, and an idempotency key on the compensating Release call." caption="Figure 25.3: The resilience scorecard: what is built, and what waits for measured traffic to justify it" %}

Each built item exists because a specific extraction's failure mode demanded it and a negative check showed the fix worked. Each deferred item waits for traffic that produces the failure. The chassis a system needs is the one its measured failure modes demand, not the one a diagram says every microservice carries.

> **Decision**
>
> Put a deadline on every synchronous remote call and pair every retry with idempotency on the receiver. Add a circuit breaker or bulkhead when a measured failure rate or observed starvation justifies it. Fail hard wherever a degraded answer would be wrong, and treat a resilience claim as true only after a kill-and-restart check.

> **🤖 Explore with your agent**
>
> "In `examples/07-order-service`, the gRPC caller of inventory now that order is extracted, find the call sites of `RemoteInventoryClient#reserve` and `#release` and list which failures reach `placeOrder`. Then sketch where `@Timeout`, `@CircuitBreaker` and `@Bulkhead` would wrap that client, apply the rule that a breaker needs a measured failure rate, and tell me what evidence would justify adding each one. Change no code."
>
> Check: the reply names `RemoteInventoryClient`, `compensateRemoteReservations` and the `inventory.grpc.timeout-ms` property, and `grep -rE '@CircuitBreaker|@Bulkhead' examples/` still returns nothing.

## For deeper coverage

- Michael Nygard, *Release It!* (2nd ed., Pragmatic Bookshelf, 2018): the circuit breaker, bulkhead and cascading-failure vocabulary; see the [Reading List](/bibliography/).
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): "The Trouble with Distributed Systems," the source for the partial-failure taxonomy.
- Sam Newman, *Building Microservices* (2nd ed., O'Reilly, 2021): the risk of an unbounded synchronous call at a service boundary.
- [Quarkus: SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance): the official guide to `@Timeout`, `@CircuitBreaker` and `@Bulkhead`.
- Cloud Native Design Patterns, [Observability](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/11-observability.md): health checks and the readiness surface `quarkus-smallrye-health` provides.
