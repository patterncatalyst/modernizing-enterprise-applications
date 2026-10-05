# notification-service — Migration Notes (ch.17 Phase A → Phase B, DRQ-029/DRQ-035)

This file is the measured record of the two-phase Notification extraction
required by the r04 plan (`_plans/iterations/notification-plan.md`, step S5)
and mirrors `examples/02-review-service/MIGRATION.md`'s template so ch.17 can
cite real numbers instead of placeholders.

- **Phase A** ("lift the read surface onto Quarkus") — S4. The monolith's
  Spring MVC / Spring DI / Spring Data JPA notification READ surface
  (`NotificationController`/`NotificationService#listByCustomerId`/
  `Notification` entity+repository) was moved onto Quarkus largely unchanged
  via the Quarkiverse Spring-compatibility extensions (`quarkus-spring-web`,
  `quarkus-spring-di`, `quarkus-spring-data-jpa`), already pointed at its
  OWN `notification` Postgres schema from day one (DRQ-035 — contrast with
  Review, which stayed in the monolith's shared schema). No consumer existed
  yet; the table was populated only by a Flyway demo-seed row.
- **Phase B** ("make it idiomatic" + "make it live") — this step (r04 S5).
  The compatibility shim is removed; the service runs on Quarkus REST
  (RESTEasy Reactive), Panache repositories, native CDI, and a
  `@ServerExceptionMapper`, with the exact same external HTTP contract — AND
  a net-new SmallRye Reactive Messaging Kafka consumer (idiomatic-from-the-start,
  DRQ-035: there is no Spring original to lift, since the monolith never
  consumed its own `order.placed` event) makes the async outbox→Kafka
  pipeline actually live, plus a `quarkus-websockets-next` push.

## Per-component refactor (A → B)

| Component | Phase A (Spring-compat lift) | Phase B (idiomatic Quarkus + net-new) |
|---|---|---|
| HTTP layer | `NotificationController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@RequestParam` (via `quarkus-spring-web`) | `NotificationResource` — Jakarta REST `@Path`/`@GET`, plain `List<NotificationDto>` return, directly on `quarkus-rest-jackson`. A missing `customerId` is no longer a framework-level 400 (JAX-RS `@QueryParam` has no "required" concept) — an explicit `null` check throws `BadRequestException`, mapped to the same 400 by `GlobalExceptionMapper` |
| Data access | `NotificationRepository` — Spring Data `JpaRepository<Notification, Long>` interface (via `quarkus-spring-data-jpa`) | Same class name, now a concrete `@ApplicationScoped` class implementing `PanacheRepository<Notification>`; the derived query (`findAllByCustomerId`) becomes an explicit simplified-HQL `list(...)` call; net-new `findByOrderId` added for the consumer's idempotency check |
| Entity (`Notification`) | Plain JPA, own schema, scalar `customerId`/`orderId` columns (not `@ManyToOne` — see class javadoc) | **Unchanged** — zero entity edits needed, exactly as review-service found: Panache's repository pattern (vs. active-record) works against ordinary `@Entity` classes |
| Service | `NotificationService` — `@Service` (Spring stereotype) + `@ApplicationScoped` dual-annotated, constructor injection, read-path only | `@ApplicationScoped` only; Quarkus's simplified constructor injection (single constructor ⇒ no `@Inject`); **net-new** `recordOrderPlaced(OrderPlacedEvent)` — the idempotent write path, authored idiomatic-from-the-start (DRQ-035, no Spring original existed to lift) |
| Exception mapping | *(none — Spring MVC's default 400 on a missing `@RequestParam`)* | **Net-new** `GlobalExceptionMapper` — mirrors review-service's: a plain class (no CDI scope, no `@Provider`), one `@ServerExceptionMapper` method, declared outside any `@Path` class so it applies application-wide |
| DTOs (`NotificationDto`) | Plain record | **Unchanged byte-for-byte** — same field shape Phase A served and the same shape the new consumer now produces |
| `ApiError` | *(did not exist)* | **Net-new**, copied from review-service's Phase-B lesson: `@RegisterForReflection` applied from the start (not re-discovered the hard way — see review-service's MIGRATION.md native-image finding) |
| Kafka consumer (persistence) | *(did not exist — no Spring original, the monolith never consumed its own event)* | **Net-new** `OrderPlacedConsumer` — `@Incoming("order-placed")`, delegates to `NotificationService#recordOrderPlaced`, idempotent (dedupe-by-order-id, check-then-insert, DB partial-unique-index backstop) |
| Kafka consumer (WebSocket fan-out) | *(did not exist)* | **Net-new** `OrderPlacedPushConsumer` — `@Incoming("order-placed-push")`, per-replica unique consumer group, never persists, fire-and-forgets a transient `NotificationDto` to every open `/ws/notifications` connection via `OpenConnections` (adapted from datamesh) |
| WebSocket endpoint | *(did not exist)* | **Net-new** `OrderNotificationSocket` — `quarkus-websockets-next` `@WebSocket("/ws/notifications")`, `@OnOpen` handshake ack (adapted from datamesh's `OrderNotificationSocket`) |
| Config (`application.properties`) | SmallRye Config, port 8083/8084, own-schema Postgres coords, no messaging | **Added** `mp.messaging.incoming.order-placed[-push].*` (connector=smallrye-kafka, topic=`order.placed`, `kafka.bootstrap.servers` overridable via `KAFKA_BOOTSTRAP_SERVERS`, default `localhost:9092`) — otherwise unchanged |
| Flyway | V1 (own schema+table), V2 (demo seed) | **Added** V3 — supersedes the Phase-A demo seed (deleted: it collided with real checkout order/customer id 1 and defeated the idempotency dedupe check) and adds a partial unique index on `order_id` as the database-level idempotency backstop |
| Build (`pom.xml`) | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa` present | **Removed** all three; added `quarkus-hibernate-orm-panache`, `quarkus-messaging-kafka`, `quarkus-websockets-next`, `quarkus-smallrye-health`; test-only `smallrye-reactive-messaging-in-memory` + `awaitility` for the async consumer tests |
| Tests | `NotificationResourceTest` (`@QuarkusTest` + RestAssured, seeded via Flyway V2), `NotificationServiceTest` (plain Mockito) | `NotificationResourceTest` updated to seed its own committed row via `QuarkusTransaction.requiringNew()` (Phase-A's Flyway seed is gone in Phase B); `NotificationServiceTest` extended with `recordOrderPlaced` unit cases; **net-new** `OrderPlacedConsumerTest` (SmallRye in-memory connector, happy path + idempotency), `OrderNotificationSocketTest` (WebSocket handshake) |

## Idempotency mechanism (DRQ-034/DRQ-037)

The monolith's `OutboxRelay` is **at-least-once**: a crash between the Kafka
send and the `published_at` stamp republishes the identical event. Two
layers guarantee a redelivered `order.placed` event yields exactly one
`Notification` row:

1. **Application-level check-then-insert** — `NotificationService#recordOrderPlaced`
   looks up `findByOrderId(event.orderId())` first; if a row already exists,
   it is a no-op.
2. **Database-level partial unique index** — `V3__idempotent_order_id.sql`
   adds `uq_notifications_order_id` (`UNIQUE ... WHERE order_id IS NOT NULL`),
   the backstop for the case of two deliveries racing concurrently.

Proven by `OrderPlacedConsumerTest#redeliveryOfSameOrderIsIdempotent`: the
same event is sent twice through the real `@Incoming("order-placed")`
pipeline (via SmallRye's in-memory connector), and the notification count for
that order id is asserted to hold at exactly one continuously for a 3-second
window.

## WebSocket endpoint

`GET`-upgradeable `/ws/notifications` (`quarkus-websockets-next`). On open,
the server immediately sends `{"type":"connected"}` (`OrderNotificationSocket#onOpen`).
Whenever `OrderPlacedPushConsumer` consumes an `order.placed` record (a
second, independent consumer of the same topic, per-replica unique group),
it fire-and-forgets a transient `NotificationDto` to every currently-open
connection via `OpenConnections`. Verified manually during the metrics run
below: `curl --http1.1 -H "Upgrade: websocket" ...` against the packaged JVM
build returned `HTTP/1.1 101 Switching Protocols`.

## Verification

- `./mvnw -q package`: **BUILD SUCCESS**.
- No Spring-compat dependencies remain: `grep -n "quarkus-spring-" pom.xml`
  matches only the `<dependencies>` block's leading comment documenting the
  removal, not a dependency.
- No Spring imports remain anywhere in `src/`: `grep -rn "^import org.springframework" src/` → **no matches**.
- `./mvnw -q test` (podman-stack Postgres + Kafka up, but tests use Dev
  Services' isolated Testcontainers Postgres and SmallRye's in-memory
  connector — no live broker/shared DB touched): **10 tests, 0 failures, 0
  errors**:
  - `NotificationResourceTest` (3): existing-customer 200 + DTO shape,
    unknown-customer 200 + empty array, missing-param 400.
  - `NotificationServiceTest` (4): `listByCustomerId` mapping (populated +
    empty), `recordOrderPlaced` (new order persists, duplicate order is a
    no-op).
  - `OrderPlacedConsumerTest` (2): consuming an `order.placed` event (via
    SmallRye's in-memory connector) persists a `Notification` AND is served
    back by `GET /api/notifications`; redelivering the identical event holds
    the count at exactly one.
  - `OrderNotificationSocketTest` (1): WebSocket handshake returns
    `{"type":"connected"}`.

## Before / after metrics

Method: packaged (`./mvnw -q package`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` — against the podman-stack
Postgres on `localhost:5432` (an isolated scratch Postgres schema per run, to
avoid touching the service's real `notification` schema or its real Flyway
history during measurement) and the podman-stack Kafka on `localhost:9092`
(Phase B only — Phase A never configured messaging). Startup time is
Quarkus's own "started in `X`s" log line. RSS is `ps -o rss` on the running
process, sampled ~4-6s after the ready log line. Phase A was reconstructed
byte-for-byte from the pre-Phase-B source (the exact Spring-compat
Controller/Service/Repository/entity/DTO/pom/application.properties/Flyway
V1+V2 captured before this step's edits) into a scratch directory and
packaged the same way — no git operations were used (per this step's
constraints); the reconstruction is a direct, verified copy, not an
approximation.

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| **Phase A** — JVM, Spring-compat | 1.486 s | ~296 MB | 14 (`agroal, cdi, flyway, hibernate-orm, hibernate-orm-panache, jdbc-postgresql, narayana-jta, rest, rest-jackson, smallrye-context-propagation, spring-data-jpa, spring-di, spring-web, vertx`) |
| **Phase B** — JVM, idiomatic + Kafka consumer + WebSocket | 1.765 s | ~372 MB | 16 (spring-compat extensions removed; `kafka-client, messaging, messaging-kafka, smallrye-health, websockets-next` added) |

**Reading the numbers:** unlike Review's Phase A → Phase B (which was a pure
refactor and so a near-wash on resource usage), Notification's Phase B adds
REAL runtime capability — two live Kafka consumers (each with its own
background poll thread/connection) and a WebSocket server — not just an
idiomatic rewrite of the same surface. Startup is ~19% slower (1.49s → 1.77s,
mostly Kafka consumer group join/metadata-fetch against the live broker) and
RSS is ~26% higher (296MB → 372MB, the Kafka client buffers/metadata caches
and the Netty/Vert.x WebSocket stack), which is the honest cost of the async
pipeline actually running, not a regression to be optimized away. Removing
the three Spring-compat extensions on its own (holding the feature set
constant, as Review's table isolates) would have been a modest win in the
same direction as Review's ~4% RSS drop; that effect is masked here by the
larger net-new Kafka+WebSocket footprint added in the same step. Native
image was **not** attempted for this step (time-boxed per the plan's "native
optional"); Review's MIGRATION.md already demonstrates the ~30x
startup/~4x RSS native payoff pattern for this same service family, which
should transfer directly to Notification's idiomatic Phase B code.

## End-to-end verification (outbox → Kafka → consumer → own store → read surface)

See the ch.17 S5 execution record for the live run: podman-stack Kafka up,
the monolith started with `NOTIFICATION_MODE=outbox`, this service started
on `:8083`, a real checkout performed against the monolith, and
`GET :8083/api/notifications?customerId=<id>` polled until the
order-confirmation notification appeared — then the identical event
redelivered and the count re-confirmed at exactly one. Observed latency and
the idempotency result are reported in that record (checkout → notification
visible on the read surface was on the order of the `OutboxRelay`'s poll
interval, i.e. low single-digit seconds, not immediate — the expected,
documented eventual-consistency cost of the polling-relay pattern, DRQ-034).
