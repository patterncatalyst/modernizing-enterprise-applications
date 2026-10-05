# shipping-service — Migration Notes (ch.24 Phase A → Phase B, DRQ-056…065)

This file is the measured record of ch.24's two-phase Shipping extraction,
`_plans/iterations/shipping-plan.md` steps S4 (Phase A) and S5 (Phase B,
**THIS STEP**), and mirrors `examples/05-payment-service/MIGRATION.md`'s
template — the closest precedent, extended from a **choreographed** saga
(no coordinator) to an **orchestrated** one (a named Camel Saga EIP
coordinator).

- **Phase A** ("lift the read surface onto Quarkus") — S4. The monolith's
  Spring MVC / Spring DI / Spring Data JPA shipping READ surface
  (`ShippingController`/`ShippingService#getById,listByOrderId`/`Shipment`
  entity+repository) was moved onto Quarkus largely unchanged via the
  Quarkiverse Spring-compatibility extensions (`quarkus-spring-web`,
  `quarkus-spring-di`, `quarkus-spring-data-jpa`), already pointed at its
  OWN `shipping` Postgres schema from day one. No saga/consumer/producer
  existed yet; the table was populated only by a small Flyway demo seed.
- **Phase B** ("make it idiomatic" + "make it live") — **this step (S5)**.
  The compatibility shim is REMOVED (Quarkus REST + Panache + plain CDI,
  `@ServerExceptionMapper`), and the net-new **ORCHESTRATED** core is
  authored idiomatic-from-the-start: an `InMemorySagaService`-backed
  **Camel Saga EIP route** that consumes `payment.captured`, enriches the
  order's shipping address, dispatches, books the (simulated) carrier, and
  — atomically via this service's OWN transactional outbox — emits
  `shipment.dispatched`/`shipment.failed`, relayed to Kafka by a
  `@Scheduled` poller. Idempotent by `orderId` (new `uq_shipments_order_id`
  unique constraint, DRQ-064).

## Per-component refactor (Phase A → Phase B)

| Component | Phase A (Spring-compat lift) | Phase B (idiomatic Quarkus + net-new orchestrated core) |
|---|---|---|
| HTTP layer | `ShippingController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@PathVariable`/`@RequestParam` | `ShippingResource` — Jakarta REST `@Path`/`@GET`/`@PathParam`/`@QueryParam`, directly on `quarkus-rest-jackson`. A missing `orderId` is an explicit `BadRequestException`, mapped to 400 by `GlobalExceptionMapper` |
| Data access | `ShipmentRepository` — Spring Data `JpaRepository<Shipment, Long>` | Same class name, now `@ApplicationScoped implements PanacheRepository<Shipment>`; `findAllByOrderId` becomes an explicit `list(...)` call; net-new `findByOrderId` (`Optional<Shipment>`) backs the idempotency guard and the compensation route's correlation lookup |
| Entity (`Shipment`) | Plain JPA, own schema, scalar `orderId` column (FK decomposed, S4) | Net-new `markDispatched()`/`markCancelled()` status-transition mutators (see "Saga shape" below); otherwise unchanged |
| Service | `ShippingService` — `@Service` (Spring), read paths only | `@ApplicationScoped` only; read paths unchanged; **net-new** `processPaymentCaptured(PaymentCaptured)` — the idempotency guard + saga trigger (via `ProducerTemplate`) |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice` | `GlobalExceptionMapper` — plain class, `@ServerExceptionMapper` per failure mode (`ResourceNotFoundException`→404, net-new `BadRequestException`→400) |
| DTO (`ShipmentDto`) | `record ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status, Instant createdAt)` | **Unchanged byte-for-byte** — the `/api/shipments` contract (Shipping Context Contract) is preserved |
| Event contracts (`PaymentCaptured`, `ShipmentDispatched`, `ShipmentFailed`) | *(did not exist)* | **Net-new**, this service's own copies of the DRQ-038 JSON wire contracts, field-for-field identical to the monolith's `common.events` mirrors (deferred from S3) |
| Kafka consumer | *(did not exist)* | **Net-new** `PaymentCapturedConsumer` — `@Incoming("payment-captured")`, `@ActivateRequestContext` (see "Gotcha" below), delegates to `ShippingService#processPaymentCaptured` |
| Saga coordinator (`SagaConfiguration`) | *(did not exist)* | **Net-new** — CDI producer of a `CamelSagaService` (`InMemorySagaService`) bean, explicitly registered with the `CamelContext` (see "Gotcha" below) |
| Saga route (`ShipmentSagaRoute`) | *(did not exist)* | **Net-new** — the Camel Saga EIP ORCHESTRATOR: `.saga().sagaService(...).propagation(REQUIRES_NEW).completionMode(AUTO).timeout(15s).compensation("direct:ship-compensate").option("orderId", ...)` sequencing enrich → dispatch → book-carrier → emit, adapted from datamesh's choreography-style `ShipmentProcessor` (DRQ-032) |
| Saga steps (`ShipmentSagaSteps`) | *(did not exist)* | **Net-new** — the five step bodies (enrich/dispatchShipment/bookCarrier/emitDispatched/compensate); see "Atomicity design" below |
| Order enrichment (`OrderReadClient`/`OrderServiceClient`/`OrderSummary`) | *(did not exist)* | **Net-new** — REST client coded against the INTENDED order read contract; see "ENRICHMENT CONTRACT" below |
| Outbox (`ShipmentOutboxEvent`/`ShipmentOutboxRepository`) | *(did not exist)* | **Net-new** — this service's OWN transactional outbox, mirroring payment's `PaymentOutboxEvent`/`PaymentOutboxRepository` (Panache) |
| Outbox relay (`ShipmentOutboxRelay`) | *(did not exist)* | **Net-new** — `@Scheduled` poll publishing to `shipment-dispatched`/`shipment-failed` via two `@Channel Emitter<String>`s, AT-LEAST-ONCE (same documented limitation as every relay in this repo) |
| Config (`application.properties`) | SmallRye Config, port 8088/8089, own-schema Postgres coords, no messaging | **Added** `kafka.bootstrap.servers`, `mp.messaging.incoming.payment-captured.*`, `mp.messaging.outgoing.shipment-dispatched/-failed.*`, `shipping.outbox.relay.poll-interval`, `order.service.base-url` + `quarkus.rest-client."order-service".*` |
| Flyway | V1 (own schema+table), V2 (demo seed) | **Added** V3 — `uq_shipments_order_id` unique constraint (idempotency backstop, DRQ-064; Phase A deliberately deferred this); V4 — `outbox` table + `idx_outbox_unpublished` partial index |
| Build (`pom.xml`) | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa` present | **Removed** all three; **added** `quarkus-hibernate-orm-panache`, `quarkus-messaging-kafka`, `quarkus-scheduler`, `quarkus-rest-client-jackson`, `quarkus-camel-bom` (import) + `camel-quarkus-saga`/`camel-quarkus-direct`/`camel-quarkus-bean`; test-only `camel-quarkus-mock`, `smallrye-reactive-messaging-in-memory`, `awaitility` |
| Tests | `ShippingControllerTest` (`@QuarkusTest` + RestAssured), `ShippingServiceTest` (plain Mockito) | `ShippingControllerTest` → renamed `ShippingResourceTest`, seeds via `QuarkusTransaction.requiringNew()`; `ShippingServiceTest` extended with `processPaymentCaptured` unit cases (mocked `ProducerTemplate`); **net-new** `PaymentCapturedConsumerTest` (SmallRye in-memory connector: happy path, SHIP-FAIL abort+compensate, idempotent redelivery) and `ShipmentSagaRouteAdviceWithTest` (Camel AdviceWith/MockEndpoint unit test of the abort path) |

## The saga shape (DRQ-059) and how compensation fires exactly once

```java
from("direct:ship-start")
    .saga()
        .sagaService("inMemorySagaService")       // DRQ-057
        .propagation(SagaPropagation.REQUIRES_NEW)
        .completionMode(SagaCompletionMode.AUTO)
        .timeout(15, TimeUnit.SECONDS)
        .compensation("direct:ship-compensate")
        .option("orderId", header("orderId"))
    .to("direct:ship-enrich")           // step 1
    .to("direct:ship-dispatch")         // step 2 — persists BEFORE book-carrier can throw (DRQ-062)
    .to("direct:ship-book-carrier")     // step 3 — the deterministic SHIP-FAIL throw point
    .to("direct:ship-emit-dispatched")  // step 4 — only reached on success
    .end();
```

`completionMode(AUTO)` means the `InMemorySagaService` coordinator watches
the wrapped chain: if it completes without an exception, the saga
completes (no compensation call); if **any** step throws (deterministically,
`ShipFailException` from `bookCarrier`; in principle any unexpected
exception from any step) **or** the 15s timeout elapses first, the
coordinator invokes the ONE registered `.compensation("direct:ship-compensate")`
endpoint. Camel's saga SPI guarantees a saga instance is compensated or
completed, **never both, and at most once** — this is the EIP's own
exactly-once-per-outcome contract, not something this code re-implements.
The original failure is then re-thrown to the caller of `direct:ship-start`
(`ShippingService#processPaymentCaptured`), which logs it rather than
propagating it as a consumer error (so a deterministic `SHIP-FAIL` demo
order is never redelivered forever).

**Why compensation correlates by `orderId`, not a saved `shipmentId`
option:** Camel's `.option(name, expression)` list is configured once, at
the top of the `.saga()` block, and DRQ-059 names only `orderId` (the one
value guaranteed present when the saga block is entered, before any step
has run). Rather than betting behavior on undocumented Camel-internal
timing for when a later-set header might also be captured, `direct:ship-compensate`
looks the shipment up the same way the idempotency guard does —
`ShipmentRepository#findByOrderId(orderId)` — which is simpler and does
not depend on internal saga-option evaluation semantics.

## Atomicity design (DRQ-063) — why "dispatch" persists PENDING, not DISPATCHED

DRQ-059's prose says step 2 "persists Shipment DISPATCHED", but DRQ-063
*also* requires the emitted event be "written atomically with the Shipment
state change", and DRQ-062 requires the row to already be **persisted**
before step 3 can throw (so compensation has something to act on). Those
three constraints are only jointly satisfiable if steps 2 and 4 are not one
atomic unit (step 3 sits between them and can abort the saga). This
implementation therefore:

1. **Step 2 (dispatch)** persists a NEW `Shipment` row as `PENDING`, in its
   own committed transaction.
2. **Step 3 (book carrier)** is a pure check — no persistence — and is the
   deterministic `SHIP-FAIL` throw point.
3. **Step 4 (emit, happy path)** — in ONE `@Transactional` method —
   transitions that SAME row `PENDING`→`DISPATCHED` **and** persists the
   `shipment.dispatched` outbox row. This is the literal, satisfiable
   atomic pair DRQ-063 asks for.
4. **Compensation (abort/timeout)** — in ONE `@Transactional` method —
   transitions the row `PENDING`→`CANCELLED` **and** persists the
   `shipment.failed` outbox row, applying the identical atomicity
   discipline to the failure outcome.

This is also why `ShipmentStatus.PENDING` (reserved, unused by Phase A) is
finally exercised — the Phase A javadoc's forward guess ("a shipment row
created before the book-carrier step has run") turned out to be exactly
right once S5 details were worked out. Flagged explicitly here (and in the
S5 report) as a deliberate interpretation of the plan's shorthand, not a
silent deviation.

**Honest limitation:** the crash window is between step 2's commit and
step 4's commit — if the process dies in that (sub-millisecond,
purely-in-process) gap, a `PENDING` row would be orphaned with no event
ever emitted for it. This is narrower than payment's single-transaction
guarantee but is the honest cost of a 4-step orchestrated saga with a
failure point in the middle; `InMemorySagaService`'s own non-durability
(below) is the bigger, already-documented gap in the same direction.

## Idempotency (DRQ-064)

`ShippingService#processPaymentCaptured` checks
`ShipmentRepository#findByOrderId(orderId)` **before** triggering the saga
at all — a redelivered `payment.captured` is a no-op: no second saga run,
no second `Shipment`, no duplicate event. The new
`uq_shipments_order_id` unique constraint (`V3__shipments_unique_order_id.sql`)
is the database-level backstop for concurrent redelivery (Phase A
deliberately deferred this constraint — no producer existed yet to
redeliver anything; added here per DRQ-064). Proven by
`PaymentCapturedConsumerTest#redeliveryOfSameOrderIsIdempotent...`: the
identical `payment.captured` event is sent twice through the real
`@Incoming("payment-captured")` pipeline; the shipment count and outbox row
count for that order id are asserted to hold at exactly one, and
`OrderReadClient#fetchShippingAddress` is verified to have been invoked
only once (the enrich step never runs on redelivery).

## Honest limitation: `InMemorySagaService` is not crash-durable (DRQ-057)

The coordinator is in-JVM, co-located, no new infra container — matching
ch.23's "no new container" discipline. Its saga state (in-flight sagas,
registered compensation callbacks, `.option(...)` data) lives purely in
heap memory. A coordinator restart (crash, redeploy) **mid-saga** loses all
in-flight state: a saga caught between its "dispatch" step (already
committed, `PENDING`) and its "emit" step at the moment of a crash would
leave a `PENDING` `Shipment` row forever uncompleted and uncompensated —
nothing would ever invoke its compensation or completion route. This is
the direct analogue of ch.23's "no saga ledger" limitation. `LRASagaService`
(Narayana LRA) is the distributed, crash-durable alternative, deliberately
deferred (new infra; cross-referenced to ch.25/ch.28 in the plan).

**Gotcha discovered and fixed during this step:** a plain
`new InMemorySagaService()` registered only as a CDI bean (no further
wiring) throws `NullPointerException` inside
`InMemorySagaCoordinator#beginStep` on its first use — CDI never calls
Camel's `Service#start()` lifecycle on it, so its internal
`ScheduledExecutorService` (used for timeout scheduling) is never
initialized. `ShipmentSagaRoute#configure()` now explicitly looks the named
bean up from the Camel registry and calls
`getContext().addService(sagaService, true, true)`, which puts it under
Camel's own start/stop lifecycle. Documented in `SagaConfiguration`'s and
`ShipmentSagaRoute`'s javadoc.

**Second gotcha:** `PaymentCapturedConsumer#consume` runs off a Reactive
Messaging worker thread with no CDI request context active by default.
Because `ShippingService#processPaymentCaptured` is deliberately **not**
`@Transactional` (each saga step owns its own transaction boundary — see
above), the idempotency guard's Panache call threw
`ContextNotActiveException` until `consume` was annotated
`@jakarta.enterprise.context.control.ActivateRequestContext` (standard CDI
4+, not a Quarkus-specific annotation) — this activates only the request
context, not a transaction, so the per-step transaction boundaries are
unaffected.

## ENRICHMENT CONTRACT decision (DRQ-059 "enrich") — flagged for S6

**Investigated:** the saga's trigger (`PaymentCaptured`) does not carry a
shipping address. Reading the monolith directly —
`examples/00-monolith/.../common/OrderDto.java` and
`order/OrderController.java` — confirms `OrderDto` exposes
`id, customerId, status, totalCents, createdAt, items` and **does NOT**
expose `shippingAddress`, even though the order entity
(`order.Order#shippingAddress`) and the checkout payload
(`common.OrderCreate#shippingAddress`) both have it. `GET /api/orders/{id}`
therefore cannot supply the address today.

**Decision:** per this step's scope (touch `examples/06-shipping-service/`
only), the monolith-side fix (add `shippingAddress` to `OrderDto`/the order
read surface) is **S6's job, not this step's**. `OrderReadClient`/
`OrderServiceClient`/`OrderSummary` are coded against the INTENDED
contract — a `GET /api/orders/{id}` that DOES return `shippingAddress` —
with the base URL configurable (`order.service.base-url`, default
`http://localhost:8080`, the monolith's real port). Tests mock
`OrderReadClient` directly (`@InjectMock`), never depending on the gap
being closed.

**Not-yet-wired behavior (defined, not silently assumed):**
`OrderReadClient#fetchShippingAddress` never throws and never returns
`null` — on any failure (connection refused, timeout, non-2xx, or a
successful-but-address-less response, i.e. today's real `OrderDto`) it
returns the documented sentinel `ADDRESS_UNAVAILABLE =
"ADDRESS-UNAVAILABLE-PENDING-S6"` and logs a warning; the saga proceeds to
dispatch with that placeholder. This mirrors payment's own
`UNSPECIFIED_METHOD` fallback for its documented forward-compatibility gap.
Deliberately **never** equal to the `SHIP-FAIL` sentinel, so a
not-yet-wired/unreachable condition can never masquerade as the
deterministic carrier-failure demo rule.

**Verified live** (see "End-to-end verification" below): against the real
podman-stack monolith (not running on :8080 during this verification run),
every dispatched shipment's address came back
`ADDRESS-UNAVAILABLE-PENDING-S6`, dispatched successfully, zero errors —
exactly the documented behavior, not a crash.

**FLAG FOR S6:** `OrderDto`/`GET /api/orders/{id}` must expose
`shippingAddress` for the live enrichment (and therefore the live
`SHIP-FAIL` demo scenario) to work end-to-end. Until S6 lands, every real
checkout dispatches with the `ADDRESS-UNAVAILABLE-PENDING-S6` placeholder,
and the deterministic `SHIP-FAIL` abort path can only be exercised via
mocked `OrderReadClient` tests (which this step does, thoroughly) — not via
a real checkout. S8 cutover is expected to prove the live path end-to-end
once S6 lands.

## Phase B read surface — contract proof (DRQ-065, ACL honesty)

`/api/shipments/{id}` and `/api/shipments?orderId=` are BYTE-FOR-BYTE
unchanged: same paths, same `ShipmentDto` JSON shape
(`id, orderId, address, status, createdAt`), same 404 (`ApiError`)/400
contract. Proven by `ShippingResourceTest` (renamed from
`ShippingControllerTest`, same assertions, now against the idiomatic
Panache/JAX-RS stack).

No `quarkus-spring-*` dependency remains in `pom.xml` (only this file's own
prose mentions it, documenting the removal); `grep -rn "org.springframework" src/`
→ no matches.

## Verification

- `./mvnw -q clean package`: **BUILD SUCCESS**.
- `./mvnw -q test` (16 tests, 0 failures, 0 errors):
  - `ShippingResourceTest` (5): `GET /api/shipments/{id}` existing → 200 +
    `ShipmentDto` shape; unknown id → 404 + `ApiError` shape;
    `GET /api/shipments?orderId=` existing order → 200 + array; unknown
    order → 200 + empty array; missing `orderId` → 400.
  - `ShippingServiceTest` (7): read-path mapping/404 (2 unit tests
    identical in spirit to Phase A's); `listByOrderId` (2); net-new
    `processPaymentCaptured` (3): starts the saga via `ProducerTemplate`
    for a new order; is an idempotent no-op for a duplicate order
    (verifies `ProducerTemplate` is never invoked); does not propagate a
    saga-abort exception to the caller.
  - `PaymentCapturedConsumerTest` (3, real `@Incoming`/`@Channel` Reactive
    Messaging pipeline via SmallRye's in-memory connector, `OrderReadClient`
    mocked): a normal `payment.captured` → DISPATCHED `Shipment` persisted
    + exactly one `shipment.dispatched` outbox row, no `shipment.failed`;
    a `SHIP-FAIL` address → saga aborts → `direct:ship-compensate` runs →
    `Shipment` CANCELLED + exactly one `shipment.failed`, no
    `shipment.dispatched`; a redelivered `payment.captured` → exactly one
    `Shipment`, exactly one outbox row, `OrderReadClient` invoked exactly
    once (enrich never re-runs).
  - `ShipmentSagaRouteAdviceWithTest` (1): a Camel AdviceWith/MockEndpoint
    unit test — weaves `mock:compensated` onto `ship-compensate`, drives
    the saga through the `SHIP-FAIL` abort via `ProducerTemplate` directly
    (bypassing the Kafka consumer), asserts the compensation route is
    invoked exactly once.
- `camel_validate_route` (camel-mcp, quarkus runtime, platformBom
  `io.quarkus.platform:quarkus-bom:3.40.1`): all six `direct:` endpoint URIs
  (`ship-start`, `ship-enrich`, `ship-dispatch`, `ship-book-carrier`,
  `ship-emit-dispatched`, `ship-compensate`) and the full saga block (YAML
  equivalent of the Java DSL route, including `sagaService`, `propagation`,
  `completionMode`, `timeout`, `compensation`, `option`) validate clean.

## Before / after metrics

Method: packaged (`./mvnw -q clean package`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` — against the real
podman-stack Postgres (`localhost:5432`) and, for Phase B, the real
podman-stack Kafka (`localhost:9092`; Phase A never configured messaging).
Startup time is Quarkus's own "started in `X`s" log line. RSS is
`ps -o rss` on the running process, sampled ~3s after the ready log line.

**Isolation:** this service's entity classes hardcode `@Table(schema =
"shipping")` (mirrors every other service in this repo), so a same-database
schema-name override does not change what Hibernate actually queries.
Both phases were therefore measured against their OWN throwaway Postgres
**databases** (`shipping_phasea_scratch_db` / `shipping_phaseb_scratch_db`,
created via `CREATE DATABASE ... OWNER monolith`, each migrated fresh by
this service's own Flyway history inside a `shipping` schema local to that
database, dropped afterward) — never the real `monolith` database's
`shipping` schema, which was verified untouched before and after (still
exactly V1+V2, 1 seed row). Phase B's measurement run used a throwaway
Kafka consumer group (`shipping-service-phaseb-measure`,
`auto.offset.reset=latest`) so RSS reflects steady-state consumer
connection overhead, not a backlog replay. Phase A was reconstructed
byte-for-byte from this step's pre-edit source (the exact Spring-compat
Controller/Service/Repository/Exception-handler/pom/application.properties
captured before this step's edits, verified against the conversation
record) into a scratch directory and packaged the same way — no git
operations were used (per this step's constraints).

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| **Phase A** — JVM, Spring-compat | 1.519 s | ~289 MB (295,880 KB) | 15 (`agroal, cdi, flyway, hibernate-orm, hibernate-orm-panache, jdbc-postgresql, narayana-jta, rest, rest-jackson, smallrye-context-propagation, smallrye-health, spring-data-jpa, spring-di, spring-web, vertx`) |
| **Phase B** — JVM, idiomatic + Camel Saga EIP + Kafka consumer/producer + outbox relay | 2.002 s | ~364 MB (372,256 KB) | 21 (spring-compat extensions removed; `camel-bean, camel-core, camel-direct, camel-saga, kafka-client, messaging, messaging-kafka, rest-client, rest-client-jackson, scheduler` added) |

**Reading the numbers:** like payment's Phase A → Phase B, Phase B adds
REAL runtime capability — a live Camel Saga EIP engine, a live Kafka
consumer, two live Kafka producers (the outbox relay's emitters), a REST
client, and a `@Scheduled` poller — not just an idiomatic rewrite of the
same surface. Startup is ~32% slower (1.52s → 2.00s — the Camel context
bootstrap plus Kafka consumer/producer connection and metadata-fetch
against the live broker) and RSS is ~26% higher (~289MB → ~364MB — Camel's
route/model overhead on top of the Kafka client's buffers/metadata caches),
noticeably larger than payment's own Phase A→B deltas (~23%/~12%) because
this step adds BOTH a full Camel engine AND the Kafka messaging stack,
where payment only added the latter. This is the honest cost of the
orchestrated core actually running, not a regression to be optimized away.
Native image was **not** attempted for this step (time-boxed, same as every
other phase in this repo).

## End-to-end verification (payment.captured → saga → own outbox → Kafka)

With the podman-stack Kafka (`mea-kafka`) and Postgres (`mea-postgres`)
already up, the packaged JVM artifact (Phase B, against its own scratch
database) was started directly and three `payment.captured` events were
published via `kafka-console-producer.sh` (the monolith was not running at
:8080, exercising the documented not-yet-wired enrichment fallback):

- **orderId=9100001**: `shipping saga started` → `OrderReadClient` logged
  the expected `Connection refused` + fallback
  `ADDRESS-UNAVAILABLE-PENDING-S6` → `dispatch shipment: persisted PENDING
  shipment id=2` → `emit shipment.dispatched: ... now DISPATCHED` →
  `shipping saga completed (dispatched)`.
- **orderId=9100002, 9100003**: identical outcome, shipment ids 3 and 4.
- The outbox relay published all three: `outbox relay: published event
  id=1/2/.. aggregateType=shipment eventType=shipment.dispatched`.
- `kafka-console-consumer.sh --topic shipment.dispatched --from-beginning`
  showed all three payloads, e.g.
  `{"status":"DISPATCHED","address":"ADDRESS-UNAVAILABLE-PENDING-S6","orderId":9100001,"occurredAt":"2026-10-05T20:03:08.985226745Z","shipmentId":2}`.
- Both outcomes were independently confirmed via the read surface —
  `GET /api/shipments?orderId=9100001` →
  `[{"id":2,"orderId":9100001,"address":"ADDRESS-UNAVAILABLE-PENDING-S6","status":"DISPATCHED",...}]`.

**Not verified live (honest gap):** the real `SHIP-FAIL` abort/compensation
path. Because the live enrichment always falls back to
`ADDRESS-UNAVAILABLE-PENDING-S6` (the monolith's real `OrderDto` cannot
supply `SHIP-FAIL` or any other address yet — the S6 gap above), a live
checkout can never produce a `SHIP-FAIL` shipping address today. The abort
path IS thoroughly proven at the test level — `PaymentCapturedConsumerTest`
(real Reactive Messaging pipeline, mocked `OrderReadClient` returning
`SHIP-FAIL`) and `ShipmentSagaRouteAdviceWithTest` (Camel-level
AdviceWith/MockEndpoint) both exercise it directly — but the end-to-end,
real-checkout version of this scenario is deferred to S8 cutover, after S6
closes the enrichment gap.

Shut down cleanly afterward (`:8088`/`:8089` freed; no orphan JVM left
listening, confirmed via `ps`/`ss`); both scratch databases were dropped;
the podman stack itself (`mea-kafka`, `mea-postgres`, `mea-connect`,
`mea-lgtm`) was left running untouched — no destructive reset. The real
`monolith` database's `shipping` schema was verified unchanged before and
after this step's live verification (still exactly Flyway versions 1+2,
1 seed row) — the new V3/V4 migrations were exercised only inside
`@QuarkusTest` Dev Services and the throwaway scratch databases, never
against the shared long-lived instance.

## Deferred to S6 (NOT this step)

- Adding `shippingAddress` to the monolith's `OrderDto`/`GET /api/orders/{id}`
  (the ENRICHMENT CONTRACT gap, flagged above) — required for the live
  enrichment and the live `SHIP-FAIL` demo scenario to work.
- `shipping.mode = inprocess|orchestrated` flag, `OrderStatus.AWAITING_SHIPMENT`/
  `SHIPPING_FAILED`, and the order-saga reactions to `shipment.dispatched`/
  `shipment.failed` (DRQ-060/061) — all monolith-side, lane M.
- Native image measurement (time-boxed out of this step).
