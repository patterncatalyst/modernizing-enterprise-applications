# payment-service — Migration Notes (ch.23 Phase A → Phase B, DRQ-052/DRQ-053)

This file is the measured record of ch.23's two-phase Payment extraction,
`_plans/iterations/payment-plan.md` steps S4 (Phase A) and S5 (Phase B, THIS
STEP), and mirrors `examples/03-notification-service/MIGRATION.md`'s /
`examples/04-inventory-service/MIGRATION.md`'s template.

- **Phase A** ("lift the read surface onto Quarkus") — S4. The monolith's
  Spring MVC / Spring DI / Spring Data JPA payment READ surface
  (`PaymentController`/`PaymentService#getById,listByOrderId`/
  `Payment` entity+repository) **plus** the `charge()` capture logic
  (including the CARD-DECLINE demo rule) was moved onto Quarkus largely
  unchanged via the Quarkiverse Spring-compatibility extensions
  (`quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`),
  already pointed at its OWN `payment` Postgres schema from day one
  (DRQ-052 — contrast with review-service, which stayed in the monolith's
  shared schema). No consumer/producer existed yet; the table was populated
  only by a small Flyway demo seed.
- **Phase B** ("make it idiomatic" + "make it live") — **this step (S5)**.
  The compatibility shim is REMOVED (Quarkus REST + Panache + plain CDI,
  `@ServerExceptionMapper`), and the net-new choreography core is authored
  idiomatic-from-the-start (DRQ-052: no Spring original to lift, the
  monolith never consumed its own `order.placed` event): a SmallRye Reactive
  Messaging consumer of `order.placed` that captures/declines the payment via
  the SAME deterministic demo rule, and — atomically, via this service's OWN
  transactional outbox (DRQ-053) — emits `payment.captured`/
  `payment.declined`, relayed to Kafka by a `@Scheduled` poller. Idempotent
  by `orderId` (DRQ-051).

## Per-component refactor (Phase A → Phase B)

| Component | Phase A (Spring-compat lift) | Phase B (idiomatic Quarkus + net-new) |
|---|---|---|
| HTTP layer | `PaymentController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@PathVariable`/`@RequestParam` (via `quarkus-spring-web`) | `PaymentResource` — Jakarta REST `@Path`/`@GET`/`@PathParam`/`@QueryParam`, directly on `quarkus-rest-jackson`. A missing `orderId` is no longer a framework-level 400 (JAX-RS `@QueryParam` has no "required" concept) — an explicit `null` check throws `BadRequestException`, mapped to the same 400 by `GlobalExceptionMapper` |
| Data access | `PaymentRepository` — Spring Data `JpaRepository<Payment, Long>` interface (via `quarkus-spring-data-jpa`) | Same class name, now a concrete `@ApplicationScoped` class implementing `PanacheRepository<Payment>`; derived queries become explicit simplified-HQL `find(...)`/`list(...)` calls |
| Entity (`Payment`) | Plain JPA, own schema, scalar `orderId` column (FK decomposed, S4) | **Unchanged** — zero entity edits needed; Panache's repository pattern works against ordinary `@Entity` classes |
| Service | `PaymentService` — `@Service` (Spring stereotype), `charge(Long, long, String)` throws `PaymentDeclinedException` on decline and never persists | `@ApplicationScoped` only; `charge` REFACTORED (not merely relocated) to return a transient CAPTURED/DECLINED `Payment` and never throw (see "Behavior change" below); **net-new** `processOrderPlaced(OrderPlacedEvent)` — the idempotent capture-then-emit transaction, authored idiomatic-from-the-start |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice`/`@ExceptionHandler`, `ResourceNotFoundException`→404 only | `GlobalExceptionMapper` — plain class, `@ServerExceptionMapper` per failure mode: `ResourceNotFoundException`→404 (unchanged) + net-new `BadRequestException`→400 |
| `PaymentDeclinedException` | Thrown by `charge` on decline (never caught by anything — no caller yet) | **Deleted** — the choreography emits a `payment.declined` event instead of propagating an exception to an HTTP caller (DRQ-047: the monolith's synchronous 402 does not carry over) |
| DTO (`PaymentDto`) | `record PaymentDto(Long id, Long orderId, long amountCents, String method, PaymentStatus status, Instant createdAt)` | **Unchanged byte-for-byte** |
| `ApiError` | Local copy, `@RegisterForReflection` from the start | **Unchanged** |
| Event contracts (`OrderPlacedEvent`, `PaymentCaptured`, `PaymentDeclined`) | *(did not exist)* | **Net-new**, this service's own copies of the DRQ-038 JSON wire contracts (field-compatible with the monolith's `common.outbox.OrderPlacedEvent`/`common.events.PaymentCaptured`/`PaymentDeclined`) — see "Known forward-compatibility gap" below for one deliberate field addition |
| Kafka consumer | *(did not exist)* | **Net-new** `OrderPlacedConsumer` — `@Incoming("order-placed")`, delegates to `PaymentService#processOrderPlaced`, idempotent (dedupe-by-orderId, check-then-act, DB unique-index backstop) |
| Outbox (`PaymentOutboxEvent`/`PaymentOutboxRepository`) | *(did not exist)* | **Net-new** — this service's OWN transactional outbox, mirroring the monolith's proven `common.outbox.OutboxEvent`/`OutboxRepository` (ch.17/S3) idiomatically (Panache instead of Spring Data JPA) |
| Outbox relay (`PaymentOutboxRelay`) | *(did not exist)* | **Net-new** — `io.quarkus.scheduler.Scheduled` poll (mirrors the monolith's Spring `@Scheduled` `OutboxRelay`) that publishes unpublished rows to `payment.captured`/`payment.declined` via two `@Channel` `Emitter<String>`s (the topic choice is a runtime decision per row, not a static `@Outgoing` return type), blocking on the broker ack before stamping `publishedAt` (AT-LEAST-ONCE, same documented limitation as the monolith's relay) |
| Config (`application.properties`) | SmallRye Config, port 8085/8087, own-schema Postgres coords, no messaging | **Added** `kafka.bootstrap.servers` (default `localhost:9092`, overridable via `KAFKA_BOOTSTRAP_SERVERS`), `mp.messaging.incoming.order-placed.*` (connector=smallrye-kafka, topic=`order.placed`, `auto.offset.reset=earliest`), `mp.messaging.outgoing.payment-captured/-declined.*` (topics `payment.captured`/`payment.declined`), `payment.outbox.relay.poll-interval` (default `2s`) |
| Flyway | V1 (own schema+table incl. `uq_payments_order_id`), V2 (demo seed) | **Added** V3 — `outbox` table (own `payment` schema) + `idx_outbox_unpublished` partial index, mirroring the monolith's `V3__outbox.sql` |
| Build (`pom.xml`) | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa` present | **Removed** all three; added `quarkus-hibernate-orm-panache`, `quarkus-messaging-kafka`, `quarkus-scheduler`; test-only `smallrye-reactive-messaging-in-memory` + `awaitility` |
| Tests | `PaymentControllerTest` (`@QuarkusTest` + RestAssured), `PaymentServiceTest` (plain Mockito) | `PaymentControllerTest` → renamed `PaymentResourceTest`, seeds via `QuarkusTransaction.requiringNew()` (Panache's `persist()` needs an active transaction, unlike Spring Data's implicitly-transactional `save()`); `PaymentServiceTest` updated for `charge`'s revised (non-throwing) contract + net-new `processOrderPlaced` unit cases (mocked collaborators); **net-new** `OrderPlacedConsumerTest` (SmallRye in-memory connector: captured round-trip, declined round-trip, idempotent-redelivery) |

## Behavior change: `charge()` no longer throws on decline

Phase A's `charge` threw `PaymentDeclinedException` on the CARD-DECLINE demo
rule and never built a `Payment` at all — there was no caller yet, and the
monolith's synchronous HTTP 402 mapping was the (never-lifted) target
behavior. Phase B's choreography needs a DECLINED `Payment` row to exist too
(so `/api/payments` can show the declined attempt, and so
`processOrderPlaced` has a `Payment` to build the outbox event from) — so
`charge` now returns a transient `Payment` with CAPTURED or DECLINED status
decided by the SAME deterministic rule, and never throws.
`PaymentDeclinedException` is deleted: a decline is now an emitted
`payment.declined` event, not an exception propagated to an HTTP caller
(DRQ-047).

## Choreography consumer + idempotency mechanism (DRQ-051)

`OrderPlacedConsumer#consume` (`@Incoming("order-placed")`) delegates to
`PaymentService#processOrderPlaced`, which — in ONE `@Transactional` method —
does all of:

1. **Check-then-act idempotency**: `repository.findByOrderId(orderId)` first;
   a non-null result is a no-op (no re-charge, no second outbox row). The
   database-level `uq_payments_order_id` unique index
   (`V1__create_payments_table.sql`, already present from S4) is the backstop
   for two deliveries racing concurrently.
2. **Capture/decline**: `charge(orderId, totalCents, paymentMethod)` — same
   deterministic CARD-DECLINE rule.
3. **Persist the `Payment` row.**
4. **Build and persist the matching `PaymentOutboxEvent`** (`payment.captured`
   or `payment.declined`, JSON-serialized via the CDI-managed `ObjectMapper`)
   — in the SAME transaction as step 3, so the event is atomic with the
   business change (DRQ-053): either both commit, or neither does.

Proven by `OrderPlacedConsumerTest#redeliveryOfSameOrderIsIdempotent...`: the
identical `order.placed` event is sent twice through the real
`@Incoming("order-placed")` pipeline (SmallRye in-memory connector); the
payment count AND the outbox row count for that order id are asserted to
hold at exactly one.

## Outbox + relay (DRQ-053)

`PaymentOutboxEvent`/`PaymentOutboxRepository` mirror the monolith's proven
`common.outbox.OutboxEvent`/`OutboxRepository` (ch.17/S3) in this service's
OWN `payment` schema (`V3__payment_outbox.sql`). `PaymentOutboxRelay`
(`@Scheduled`, default every 2s) reads unpublished rows oldest-first,
publishes each to the Kafka topic matching its `eventType` via a
programmatic `@Channel` `Emitter<String>` (not `@Outgoing`, since the topic
is a per-row runtime decision), blocks on the broker ack
(`CompletionStage.get(5, SECONDS)`), and stamps `publishedAt` only on
confirmed delivery — **AT LEAST ONCE**, the same documented limitation as the
monolith's relay: a crash between the ack and the stamp commit republishes
the identical payload, which is safe only because the future order-saga
consumer (S6) is expected to be idempotent by `orderId`.

## Known forward-compatibility gap (flagged for S6)

This step's scope is constrained to `examples/05-payment-service/` only — the
monolith's `common.outbox.OrderPlacedEvent` could not be touched. That event
(as it exists today, r06/S3) does **not** carry a payment method: in the
still-synchronous monolith, `OrderService#placeOrder` charges in-process via
`command.paymentMethod()` (the checkout request) and writes the
`order.placed` outbox row **only on the success path** — a decline today
never produces an `order.placed` event at all. This service's own consumer-
side `OrderPlacedEvent` record therefore adds a `paymentMethod` field the
real payload doesn't populate yet (deserializes as `null`); `PaymentService`
substitutes a documented literal (`CARD-UNSPECIFIED`, never a decline) so a
real, method-less event captures safely instead of violating `Payment.method`'s
NOT NULL column or crashing the consumer. Verified live: on first startup
against the real podman-stack Kafka, this consumer replayed the entire
pre-existing `order.placed` history (~160 real historical checkout events,
none carrying a payment method) and captured all of them with
`CARD-UNSPECIFIED`, zero errors. Making the monolith publish `order.placed`
**before** the payment decision, carrying the real payment method, is
r06/S6's job (`payment.mode` flag, the sync→async checkout contract change,
DRQ-047/H1/H4) — this is the honest, explicitly-flagged seam, not a silently
assumed one.

## Verification

- `./mvnw -q package`: **BUILD SUCCESS**.
- No Spring-compat dependencies remain: `grep -n "quarkus-spring-" pom.xml`
  matches only this file's/`pom.xml`'s own prose documenting the removal, not
  a dependency.
- No Spring imports remain anywhere in `src/`: `grep -rn "^import org.springframework" src/` → **no matches**.
- `./mvnw -q test` (18 tests, 0 failures, 0 errors):
  - `PaymentResourceTest` (5): `GET /api/payments/{id}` existing → 200 +
    `PaymentDto` shape; unknown id → 404 + `ApiError` shape; `GET
    /api/payments?orderId=` existing order → 200 + array; unknown order → 200
    + empty array; missing `orderId` → 400.
  - `PaymentServiceTest` (10): `charge` captures on a normal method; `charge`
    returns a transient DECLINED `Payment` (never throws) for 4 CARD-DECLINE
    variants; `charge` with a `null` method substitutes `CARD-UNSPECIFIED`
    and captures; `getById` on an unknown id throws `ResourceNotFoundException`;
    `processOrderPlaced` persists a CAPTURED payment + `payment.captured`
    outbox row for a normal method; persists a DECLINED payment +
    `payment.declined` outbox row for a CARD-DECLINE method; a duplicate
    `orderId` is an idempotent no-op (no persist, no outbox write).
  - `OrderPlacedConsumerTest` (3, real `@Incoming`/`@Channel` Reactive
    Messaging pipeline via SmallRye's in-memory connector): a normal-method
    `order.placed` → CAPTURED `Payment` persisted + a published
    `payment.captured` outbox row; a CARD-DECLINE `order.placed` → DECLINED
    `Payment` persisted + a published `payment.declined` outbox row; the
    identical event redelivered → exactly one `Payment` row and exactly one
    outbox row for that order id (idempotent).

## Before / after metrics

Method: packaged (`./mvnw -q package`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` — against the real
podman-stack Postgres (`localhost:5432`) and, for Phase B, the real
podman-stack Kafka (`localhost:9092`; Phase A never configured messaging).
Startup time is Quarkus's own "started in `X`s" log line. RSS is
`ps -o rss` on the running process, sampled ~3s after the ready log line.
Both phases ran against **isolated scratch Postgres schemas**
(`payment_phasea_scratch`/`payment_phaseb_scratch`, dropped afterward) to
avoid touching this service's real `payment` schema/Flyway history or
skewing RSS with the real schema's accumulated rows during measurement —
same discipline notification-service's MIGRATION.md used. Phase B's
measurement run used a throwaway Kafka consumer group
(`auto.offset.reset=latest`) so RSS reflects steady-state consumer
connection overhead, not a one-time backlog replay. Phase A was
reconstructed byte-for-byte from this step's pre-edit source (the exact
Spring-compat Controller/Service/Repository/Exception-handler/pom/
application.properties captured before this step's edits, verified against
the conversation record, not approximated) into a scratch directory and
packaged the same way — no git operations were used (per this step's
constraints; this repo has no git history to diff against either).

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| **Phase A** — JVM, Spring-compat | 1.494 s | ~303 MB | 15 (`agroal, cdi, flyway, hibernate-orm, hibernate-orm-panache, jdbc-postgresql, narayana-jta, rest, rest-jackson, smallrye-context-propagation, smallrye-health, spring-data-jpa, spring-di, spring-web, vertx`) |
| **Phase B** — JVM, idiomatic + Kafka consumer/producer + outbox relay | 1.833 s | ~338 MB | 16 (spring-compat extensions removed; `kafka-client, messaging, messaging-kafka, scheduler` added) |

**Reading the numbers:** like Notification's Phase A → Phase B (and unlike
Review's pure-refactor near-wash), Payment's Phase B adds REAL runtime
capability — a live Kafka consumer, two live Kafka producers (the outbox
relay's emitters), and a `@Scheduled` poller — not just an idiomatic rewrite
of the same surface. Startup is ~23% slower (1.49s → 1.83s, mostly Kafka
consumer/producer connection + metadata-fetch against the live broker) and
RSS is ~12% higher (303MB → 338MB, the Kafka client's buffers/metadata
caches), which is the honest cost of the choreography actually running, not
a regression to be optimized away. Removing the three Spring-compat
extensions on its own (holding the feature set constant) would have been a
modest win in the same direction as Review's RSS drop; that effect is masked
here by the larger net-new Kafka footprint added in the same step. Native
image was **not** attempted for this step (time-boxed per the plan's "native
optional").

## End-to-end verification (order.placed → capture/decline → own outbox → Kafka)

With the podman-stack Kafka (`mea-kafka`) and Postgres (`mea-postgres`)
already up, the packaged JVM artifact was started directly against the real
stack (`java -jar target/quarkus-app/quarkus-run.jar`, real `payment` schema,
real `order.placed`/`payment.captured`/`payment.declined` topics). On first
connection, the new `payment-service` consumer group (`auto.offset.reset=
earliest`) replayed this repo's entire pre-existing `order.placed` history
(~160 real events from earlier chapters' checkouts, none carrying a payment
method) — all captured with `CARD-UNSPECIFIED`, zero errors (see "Known
forward-compatibility gap" above; this is what surfaced the null-method NOT
NULL bug, fixed before this verification run).

Two fresh events were then published directly to `order.placed` via
`kafka-console-producer.sh` (bypassing the monolith, since real checkouts
don't yet carry/emit a payment method before S6):

- **Normal method** (`orderId=9000001`, `paymentMethod=CARD-VISA`,
  `totalCents=4250`): service log —
  `processed order.placed for order 9000001 -> payment CAPTURED (outbox
  event id=80, type=payment.captured)`, then
  `outbox relay: published event id=80 ... eventType=payment.captured`.
  Consuming `payment.captured` directly via `kafka-console-consumer.sh
  --from-beginning` showed:
  `{"method": "CARD-VISA", "status": "CAPTURED", "orderId": 9000001,
  "paymentId": 81, "capturedAt": "2026-10-05T16:10:14.389579262Z",
  "amountCents": 4250}`.
- **CARD-DECLINE method** (`orderId=9000002`, `paymentMethod=CARD-DECLINE`,
  `totalCents=1500`): service log —
  `processed order.placed for order 9000002 -> payment DECLINED (outbox
  event id=81, type=payment.declined)`, then
  `outbox relay: published event id=81 ... eventType=payment.declined`.
  Consuming `payment.declined` showed:
  `{"method": "CARD-DECLINE", "reason": "Payment method 'CARD-DECLINE' was
  declined", "status": "DECLINED", "orderId": 9000002, "paymentId": 82,
  "declinedAt": "2026-10-05T16:10:15.586781548Z", "amountCents": 1500}`.

Both outcomes were independently confirmed via the read surface —
`GET /api/payments?orderId=9000001` → `[{"...","status":"CAPTURED",...}]`;
`GET /api/payments?orderId=9000002` → `[{"...","status":"DECLINED",...}]` —
closing the loop from Kafka event → own outbox → Kafka publish → own store →
read surface. Latency order.placed → topic-visible outcome was on the order
of the relay's 2s poll interval, the expected eventual-consistency cost of
the polling-relay pattern (same as ch.17's notification extraction).

Shut down cleanly afterward (`:8085`/`:8087` freed; no orphan JVM left
listening, confirmed via `ps`/`ss`); the podman stack itself (`mea-kafka`,
`mea-postgres`, `mea-connect`, `mea-lgtm`) was left running untouched — no
destructive reset.

## Deferred to S6 (NOT this step)

- Flipping the monolith's checkout to the choreographed (`payment.mode`)
  async path, adding `paymentMethod` to the real `order.placed` payload, and
  consuming `payment.captured`/`payment.declined` to confirm/decline orders
  and trigger the compensating inventory `Release` (DRQ-049/DRQ-050).
- Native image measurement (time-boxed out of this step, as with Notification's
  Phase B).
