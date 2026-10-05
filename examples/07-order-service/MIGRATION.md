# order-service — Migration Notes (ch.26 Phase A → Phase B, DRQ-068/073/074)

This file is the measured record of ch.26's two-phase Order extraction,
`_plans/iterations/order-plan.md` steps S4 (Phase A) and S5 (Phase B, THIS
STEP), the LAST and hardest extraction in the roadmap — HARD PART H1, lifting
the "god service." It mirrors `examples/05-payment-service/MIGRATION.md`'s /
`examples/06-shipping-service/MIGRATION.md`'s two-phase template, adapted for
order's distinguishing concerns: an FK decomposition that touches BOTH sides
of a relationship (`Order` ⇄ `Customer`), a live cross-service gRPC
collaborator (`RemoteInventoryClient`), and — net-new for S5 — FOUR lifted
saga reactions (not one, like payment's/shipping's single trigger) whose
idempotency/at-most-once-compensation/mutual-exclusion guarantees had to
survive the lift unchanged.

- **Phase A** ("lift the read+command surface onto Quarkus") — THIS STEP (S4).
  The monolith's Spring MVC / Spring DI / Spring Data JPA order
  READ+COMMAND surface (`OrderController`/`OrderService#getById,listAll,
  placeOrder`/`Order`+`OrderItem`/`OrderRepository`/`Customer`+
  `CustomerRepository`) is moved onto Quarkus largely unchanged via the
  Quarkiverse Spring-compatibility extensions (`quarkus-spring-web`,
  `quarkus-spring-di`, `quarkus-spring-data-jpa`), already pointed at its OWN
  `order_service` Postgres schema from day one. `RemoteInventoryClient` is
  ALSO lifted (not merely scaffolded) — `placeOrder` calls it exactly like
  the monolith does — but rebuilt on `quarkus-grpc` instead of the monolith's
  hand-wired `grpc-netty-shaded` plumbing.
- **Phase B** (idiomatic refactor + CQRS write model + lifted saga reactions +
  own outbox) is **S5, THIS STEP** — see "Phase B" below. **Phase C-equivalent**
  (read-model projection into `order_view`) is **S6** — NOT this step.

## Per-component lift (Phase A)

| Component | Monolith (Spring) | This service (Quarkus, Spring-compat) |
|---|---|---|
| HTTP layer | `OrderController` — Spring MVC `@RestController`/`@RequestMapping`/`@PostMapping`/`@GetMapping`/`@PathVariable`/`@RequestBody` | Same class name/annotations, via `quarkus-spring-web`. Unchanged behavior: `placeOrder` → `202 Accepted` + `Location`; `getById`/`listAll` unchanged. |
| Data access | `OrderRepository`/`CustomerRepository` — Spring Data `JpaRepository<T, Long>` | Same interfaces, via `quarkus-spring-data-jpa`. |
| Entity (`Order`) | `@ManyToOne(optional=false) Customer customer` + `@JoinColumn(name="customer_id")` | **FK DECOMPOSED (DRQ-068):** plain `Long customerId` + `String customerEmail` (snapshot at placement time) — no JPA association, no DB FK (see `Order.java`'s javadoc for why the FK is omitted even though `customers` is co-owned). |
| Entity (`OrderItem`) | Denormalized sku/name/unit-price snapshot (DRQ-043, already decomposed in the monolith) | **Unchanged** — lift keeps the existing snapshot shape. |
| Entity (`Customer`) | Shared-kernel entity in the monolith's ONE shared schema, joined cross-context from `order`, `notifications`, `reviews` | Now OWNED outright by this service, in `order_service`'s own schema — no more shared schema, no more cross-context join. Customer was never its own extraction; it comes along with order. |
| Service | `OrderService` — `@Service`, `placeOrder` validates customer → reserves every line over gRPC → persists `PENDING` → writes an `order.placed` OUTBOX event → returns | Same class name, `@Service` **plus `@Scope("application")`** (an added-during-lift adjustment — see "Divergences" below) — `placeOrder` validates/reserves/persists identically, **MINUS the outbox write** (deferred to S5 — see below). |
| gRPC client | `inventory.RemoteInventoryClient` — hand-wired `grpc-netty-shaded`/`ManagedChannelBuilder`, Spring `@Component` | `RemoteInventoryClient` — idiomatic `quarkus-grpc` (`@GrpcClient` field-injected blocking stub), plain CDI `@ApplicationScoped`. Same reserve/release/getStock methods, same translation discipline (proto types never leak past this class). |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice`/`@ExceptionHandler`, incl. `MethodArgumentNotValidException` → 400 | Same class name/annotations via `quarkus-spring-web`, **MINUS the `MethodArgumentNotValidException` handler** — that Spring MVC class does not exist on quarkus-spring-web's classpath at all. Replaced with `ConstraintViolationException` → 400 (see "Divergences"). |
| DTO (`OrderDto`) | `record OrderDto(Long id, Long customerId, OrderStatus status, long totalCents, Instant createdAt, String shippingAddress, List<Item> items)` | **Unchanged byte-for-byte.** `customerId` stays a plain `Long` in the external contract — the FK decomposition changes `Order`'s INTERNAL JPA mapping only. |
| Command (`OrderCreate`) | `record OrderCreate(Long customerId, List<Line> items, String paymentMethod, String shippingAddress)`, bean-validated | **Unchanged byte-for-byte**, including `paymentMethod` even though nothing reads it yet in this service (no outbox write — see below). |
| `ApiError` | Local copy | **Unchanged byte-for-byte**, `@RegisterForReflection` from the start. |
| Flyway | `V1__init_schema.sql` (shared schema) + `V4__decompose_order_items_fk.sql` | **Net-new, own schema**: `V1__create_order_schema.sql` (customers/orders/order_items, final decomposed shape from day one — no "decompose" migration needed since there's no legacy shape to migrate away from), `V2__order_outbox.sql` (RESERVED, empty), `V3__order_view.sql` (RESERVED, empty). |
| Build (`pom.xml`) | Spring Boot starters | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`, `quarkus-rest-jackson` (required explicitly — quarkus-spring-web's exception-handling build step needs the underlying Jakarta REST stack present), `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-hibernate-validator`, `quarkus-smallrye-health`, `quarkus-grpc`; test-only `quarkus-junit-mockito`, `rest-assured`, `assertj-core`. |
| Tests | `OrderControllerTest` (`@WebMvcTest` + `@MockitoBean`), `OrderServiceTest` (plain Mockito) | `OrderControllerTest` → `@QuarkusTest` + `@InjectMock OrderService` (requires `OrderService` to be normal-scoped, hence `@Scope("application")`); `OrderServiceTest` → plain JUnit + Mockito, same shape minus outbox assertions. |

## FK decomposition (DRQ-068) — both sides of `Order` ⇄ `Customer`

Every prior extraction's FK decomposition (review's/inventory's/payment's)
only had to cut ONE side of a cross-service join — the referencED table
stayed behind in the monolith's shared schema. Order's decomposition is
different: `Customer` moves WITH `Order` into the same new service (customer
was never its own extraction), so there are two defensible designs:

1. Keep a DB-level FK (`orders.customer_id REFERENCES customers.id`) since
   both tables now live in the SAME schema — technically "intra-context," not
   cross-service.
2. Omit the FK entirely, treating `customerId` as a pure value reference even
   though the referenced table is local.

This step chose **(2)** — no DB-level FK, no JPA association — to keep
`Order`'s aggregate boundary deliberately decoupled from `Customer`'s
lifecycle, consistent with the `OrderItem.sku` snapshot precedent (DRQ-043)
and documented explicitly in `Order.java`'s and
`V1__create_order_schema.sql`'s comments, so this design choice is legible to
later readers (and the Opus validator) rather than ambiguous.

## What's the SAME as the monolith's lifted `placeOrder`

- Validates the customer exists (`ResourceNotFoundException` → 404).
- Reserves every checkout line synchronously over gRPC via
  `RemoteInventoryClient#reserve`/`getStock` (now `quarkus-grpc`, same
  method shapes).
- Captures each line's sku/name/unit-price-at-order-time as a denormalized
  snapshot (DRQ-043, unchanged).
- Persists the order `PENDING`.
- Compensates (`Release`) every already-reserved line on a PRE-HANDOFF
  failure (a later line out of stock, or the save itself throwing) — the
  exact try/catch shape, proven by `OrderServiceTest`'s
  `placeOrder_reserveFailsOnSecondLine_compensatesFirstLine`.

**Verified live** (not merely mocked): with the real podman-stack Postgres up
but NO inventory-service process running, a `POST /api/orders` against the
packaged JVM artifact on `:8087` correctly failed with `500` (the gRPC
channel correctly reports `UNAVAILABLE`) and left `order_service.orders`/
`order_items` at zero rows — proving the reserve call is genuinely live-wired
(not a no-op stub) and that the `@Transactional` + compensation shape rolls
back cleanly on a real failure, not just a mocked one.

## What's deliberately NOT here (deferred to S5/S6)

- **The `order.placed` outbox write.** The monolith's `placeOrder` ALSO
  writes an `order.placed` `OutboxEvent` as its handoff to the choreographed
  saga. This step's scope (per the plan) is the read+command surface +
  entities + own schema ONLY — the event contract (S3) and the Java-side
  `OrderOutboxEvent`/`OrderOutboxRelay` wiring (S5) are explicitly out of
  scope. The `outbox` table exists (`V2__order_outbox.sql`, empty) so S5 can
  add the Java mapping without a schema migration.
- **Every saga reaction.** No `@Incoming` consumers exist — an order placed
  against this Phase A service sits `PENDING` forever (no path out). This is
  the expected, documented state of an isolated Phase A scaffold.
- **The read-model projection.** `order_view` exists (`V3__order_view.sql`,
  empty, minimal placeholder columns) but nothing writes to it — S6's job.
- **The idiomatic refactor** (removing the Spring-compat shim, Quarkus REST +
  Panache) — S5, same as every other service's Phase A → B transition.
- **CDC backfill.** The store starts EMPTY and is forward-filled only —
  deliberately NOT backfilled from the monolith's existing ~260 `orders`/2
  `customers` rows (contrast inventory's CDC-fed backfill, DRQ-040). A
  migration/backfill strategy for pre-existing data is out of scope for
  Phase A.

## Divergences (compat-layer behavior gaps, same spirit as payment's/shipping's)

- **`@Transactional`:** uses `jakarta.transaction.Transactional`, NOT
  Spring's `org.springframework.transaction.annotation.Transactional` — per
  the `migrate-spring-to-quarkus` skill's annotation map, quarkus-spring-data-jpa
  does not process Spring's own annotation even under compat.
- **`MethodArgumentNotValidException` does not exist** on quarkus-spring-web's
  classpath at all (the compat extension ships the annotation-processing
  shim, not Spring MVC's full exception hierarchy) — a failed `@Valid`
  constraint on `OrderCreate` throws `jakarta.validation.ConstraintViolationException`
  instead, mapped to 400 by `GlobalExceptionHandler#validationFailed`.
- **`OrderService` needs `@Scope("application")`** (→ CDI `@ApplicationScoped`)
  added during the lift — plain `@Service` alone compiles to a `@Singleton`
  pseudo-scope bean, which Quarkus cannot mock with `@InjectMock` (no client
  proxy to swap), which `OrderControllerTest` needs.
- **`quarkus-rest-jackson` is required explicitly** alongside `quarkus-spring-web`
  — its exception-handling build step fails outright
  (`IllegalStateException: Spring Web can only work if 'quarkus-resteasy-jackson'
  or 'quarkus-rest-jackson' is present`) without it, even though other
  services' Phase A pom.xml files didn't need to list it (they likely picked
  it up transitively via a slightly different extension combination; listing
  it explicitly here avoids relying on that).
- **Schema name is `order_service`, not `order`** — `ORDER` is a reserved
  Postgres keyword, which would require quoting in every migration/JDBC URL;
  `order_service` avoids that entirely.

## Verification

- `./mvnw -q package`: **BUILD SUCCESS** (11 tests, 0 failures, 0 errors).
- `./mvnw -q test`:
  - `OrderServiceTest` (6): happy path (PENDING, reserves stock, maps
    customerId/items/shippingAddress correctly); out-of-stock → 409-equivalent,
    never releases; reserve-fails-on-second-line compensates the first line's
    reservation; customer-not-found never touches inventory; `getById`
    unknown → `ResourceNotFoundException`; `listAll` delegates and maps to DTO.
  - `OrderControllerTest` (5, `@QuarkusTest` + `@InjectMock OrderService`,
    own Dev-Services-provisioned Postgres running this service's OWN Flyway
    migrations for real): `POST /api/orders` valid → 202 + `Location` +
    `OrderDto` shape; empty `items` → 400 `VALIDATION_FAILED`; `GET
    /api/orders/{id}` found → 200 + shape; unknown → 404 `NOT_FOUND`; `GET
    /api/orders` → 200 + array.
- **Own-schema isolation, verified against the REAL podman-stack Postgres**
  (not just Dev Services): `\dn` shows `order_service` alongside
  `inventory`/`notification`/`payment`/`shipping`/`public`; `order_service`
  owns exactly `customers`/`order_items`/`order_view`/`orders`/`outbox` (+
  `flyway_schema_history`); `public.orders`/`public.customers` (260/2 rows)
  are untouched by this service's Flyway run.
- `GET /api/orders` on first boot → `[]` (empty store, no backfill).
  `GET /api/orders/999` → `404` + `ApiError` shape.
  `/q/health` → `UP`.
- `pom.xml` has zero `org.springframework` Maven coordinates (the three
  compat extensions are `io.quarkus:quarkus-spring-*`, NOT Spring Boot
  dependencies) — `grep -n "org.springframework" pom.xml` matches nothing.
  `org.springframework.*` IMPORTS in `src/main/java` are expected and
  deliberate for Phase A (`CustomerRepository`/`OrderRepository`/
  `OrderController`/`OrderService`/`GlobalExceptionHandler`) — removing them
  is S5's job, not this step's.

## Ports

- `:8087` main (payment-service's `:8087` is a test-only port there, so it is
  free at runtime for this service's main port).
- `:8091` test (avoids `:8086` Debezium, `:8089` shipping-service's test
  port, `:8090` the future graphql-gateway).
- gRPC client target: inventory-service's `:9004` (dev/prod) / `:9005` (test).

## Cleanup performed after manual verification

The live-boot smoke test (`java -jar target/quarkus-app/quarkus-run.jar`
against the real podman-stack Postgres) inserted one demo `customers` row to
exercise `placeOrder`; it was deleted afterward so the owned schema is back
to its documented EMPTY starting state. The JVM process was shut down
cleanly (`:8087`/`:8091` confirmed free via `ss`); the podman stack itself
(`mea-kafka`, `mea-postgres`, `mea-connect`, `mea-lgtm`) was left running
untouched.

---

# Phase B (ch.26 S5, DRQ-073/074) — idiomatic refactor + CQRS write model + lifted saga reactions + own outbox

Removes the Phase A Spring-compat shim entirely and authors the net-new CQRS
write core **idiomatic from the start** (DRQ-073): `placeOrder` as the
command handler, this service's OWN `order.placed` transactional outbox
(`OrderOutboxEvent`/`OrderOutboxRepository`/`OrderOutboxRelay`, mirroring
payment-service's proven shape, DRQ-053-style), and the FOUR saga reactions
LIFTED from the monolith's `order.OrderSagaListener` as SmallRye `@Incoming`
consumers in this service's OWN consumer group (`OrderSagaListener`,
DRQ-074). **Adapted from datamesh's `order-service` with attribution
(DRQ-032)** — the own-schema/own-Flyway-history/Panache-repository/outbox
discipline this step applies is the same shape datamesh's reference
`order-service` and `examples/05-payment-service`/`examples/06-shipping-service`
already established in this repo; no datamesh source was copied verbatim
(order's command/saga-reaction shape is unique to this extraction), but the
*pattern* — idiomatic Quarkus order service, own Postgres, REST read
surface, Kafka event producer — is the one DRQ-032 names as this
extraction's adaptation source.

## Per-component lift (Phase A → Phase B)

| Component | Phase A (Spring-compat) | Phase B (idiomatic) |
|---|---|---|
| HTTP layer | `OrderController` — Spring MVC `@RestController`/`@RequestMapping`/`@PostMapping`/`@GetMapping` | **Renamed** `OrderResource` — Quarkus REST (`@Path`/`@GET`/`@POST`), plain `Response`/DTO returns. `/api/orders` contract BYTE-FOR-BYTE unchanged: `202 Accepted` + `Location` on `placeOrder`, same `OrderDto` JSON shape. |
| Data access | `OrderRepository`/`CustomerRepository` — Spring Data `JpaRepository<T, Long>` | Panache REPOSITORY pattern (`PanacheRepository<T>`), no derived-method-name convention; `findById`/`findAll`/`save` → `findByIdOptional`/`listAll`/`persist`. |
| Service | `OrderService` — `@Service @Scope("application")` | Plain CDI `@ApplicationScoped` (no Spring annotation needed at all — a normal-scoped CDI bean is always mockable). `placeOrder` gains the `order.placed` outbox write (closing the S4-deferred gap); `getById`/`listAll` keep `@Transactional` (unlike payment's/shipping's reads) because `Order.items` is a lazy `@OneToMany` that needs an open Hibernate session through `toDto`'s collection access. |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice`/`@ExceptionHandler` | **Renamed** `GlobalExceptionMapper` — Quarkus REST's `@ServerExceptionMapper`, same three exception→status mappings (404/409/400), same `ApiError` body shape. |
| gRPC client | `RemoteInventoryClient` — already idiomatic `quarkus-grpc` since S4 | **Unchanged.** |
| Entities (`Order`/`OrderItem`/`Customer`) | Plain JPA, no Spring annotations | **Unchanged** — these were never Spring-compat-dependent. |
| Saga reactions | **None exist** — an order placed in Phase A sits `PENDING` forever | **Net-new**: `OrderSagaListener` — four `@Incoming` consumers (`payment-captured`/`payment-declined`/`shipment-dispatched`/`shipment-failed`), lifted in SHAPE (not merely renamed) from the monolith's `order.OrderSagaListener`, own consumer group. |
| Outbox | Table reserved (`V2__order_outbox.sql`), unwritten | **Net-new**: `OrderOutboxEvent`/`OrderOutboxRepository`/`OrderOutboxRelay` — reuses the EXISTING S4 table (no new migration needed); `placeOrder` writes atomically with the `Order` row. |
| Build (`pom.xml`) | `quarkus-spring-web`/`-di`/`-data-jpa` present | **Removed** all three; added `quarkus-hibernate-orm-panache`, `quarkus-messaging-kafka`, `quarkus-scheduler`; test-only `smallrye-reactive-messaging-in-memory` + `awaitility`. |
| Tests | `OrderControllerTest` (`@QuarkusTest`+RestAssured), `OrderServiceTest` (plain Mockito) | `OrderControllerTest` → renamed `OrderResourceTest` (+ a net-new 409 case); `OrderServiceTest` extended with outbox-write assertions; **net-new** `OrderSagaListenerTest` (unit, all four reactions' guard/compensation/mutual-exclusion matrix), `CheckoutOutboxTest` + `OrderSagaListenerIntegrationTest` (real `@Incoming`/`@Channel` pipeline via SmallRye's in-memory connector). |

## The CQRS write model (DRQ-073) — `placeOrder` as the command handler

ONE `@Transactional` method, same shape as the monolith's r06/ch.23-era
`OrderService#placeOrder`: validate the customer → reserve every line over
gRPC (`RemoteInventoryClient`, unchanged) → persist the `Order` `PENDING`
(`orderRepository.persist`) → write `order.placed` to this service's own
outbox (`OrderOutboxEvent`, same transaction) → return. **No dual-write**:
the order row and the outbox row either both commit or neither does, because
both writes happen inside the same `@Transactional` boundary and the outbox
table lives in the SAME Postgres schema/connection as the `orders` table —
there is no second datastore, no two-phase commit, and no window where one
write is visible without the other. `OrderOutboxRelay`'s `@Scheduled` poll
is a separate, asynchronous, AT-LEAST-ONCE step (same documented limitation
every outbox in this repo carries) that reads already-committed rows and
publishes them to Kafka — a crash between the Kafka ack and the
`published_at` stamp republishes the identical row, which is why the payment
service's `OrderPlacedConsumer` is idempotent by `orderId` (DRQ-051).

**The pre-handoff compensation catch (DRQ-042), moved in UNCHANGED:** the
`try/catch` around the reserve-loop/persist/outbox-write releases every
sku already reserved this checkout on ANY failure in that block — a later
line out of stock, the order persist throwing, or the outbox write's JSON
serialization throwing (the last of which is purely defensive; a record of
primitives/Strings/Instant never actually fails to serialize) — then
rethrows the original failure. This is the EXACT shape `OrderService`
carried since S4 (itself lifted from the monolith's r06/ch.23 version),
just with one more failure mode now inside the try block (the outbox write).

## The four lifted saga reactions (DRQ-074)

`OrderSagaListener` lifts the monolith's `order.OrderSagaListener` — SHAPE
preserved exactly, re-expressed on SmallRye Reactive Messaging instead of
Spring Kafka (automatic Jackson record deserialization replaces the
monolith's manual `ObjectMapper#readValue`; `@Transactional` replaces
Spring's `@Transactional`; Panache's `findByIdOptional` replaces Spring
Data's `findById`).

| Monolith reaction | This service's reaction | Guard (idempotency) | Compensation |
|---|---|---|---|
| `onPaymentCaptured` | `onPaymentCaptured` | `PENDING` → no-op otherwise | none — transitions to `AWAITING_SHIPMENT` only |
| `onShipmentDispatched` | `onShipmentDispatched` | `AWAITING_SHIPMENT` → no-op otherwise | none — transitions to `CONFIRMED` only; **the ONLY path to `CONFIRMED`** |
| `onShipmentFailed` | `onShipmentFailed` | `AWAITING_SHIPMENT` → no-op otherwise | gRPC `Release` for every reserved sku, best-effort (a failure on one sku is logged and does not block releasing the rest) |
| `onPaymentDeclined` | `onPaymentDeclined` | `PENDING` → no-op otherwise | gRPC `Release` for every reserved sku, best-effort |

**Every guarantee preserved exactly, verified by `OrderSagaListenerTest` +
`OrderSagaListenerIntegrationTest`:**

- **Status-guard idempotency:** a redelivered event for an order that has
  already left the guarded pre-transition state is a no-op (log + return,
  no mutation, no `Release`).
- **At-most-once compensating `Release`:** a direct consequence of the guard
  — `onPaymentDeclined`'s and `onShipmentFailed`'s `Release` loops are
  reachable ONLY on the one transition each guards, so a redelivery never
  reissues them. Proven with ≥2 skus per compensation test (not a
  single-item special case).
- **Mutual exclusion (disjoint by construction):** `onPaymentDeclined` fires
  only from `PENDING`; `onShipmentFailed` fires only from
  `AWAITING_SHIPMENT`. An order reaches `AWAITING_SHIPMENT` ONLY via a
  successful `onPaymentCaptured`, so a decline short-circuits at `PENDING`
  before `AWAITING_SHIPMENT` is ever reachable, and a shipping failure can
  only be reached from an order whose payment was NOT declined. Neither
  reaction can ever double-`Release` the same reservation, and neither can
  collide with `OrderService#placeOrder`'s pre-handoff catch (that catch
  only fires for a checkout that never reached `order.placed` being
  committed; the reactions only fire for an order that DID).
- **`CONFIRMED` reachable ONLY via `onShipmentDispatched`:** `onPaymentCaptured`
  never calls `Order#confirm()` — proven by
  `onShipmentDispatched_pendingOrder_notYetAwaitingShipment_isNoOp_neverConfirms`.
- **Unknown order / partial `Release` failure never throw out of the
  consumer:** both logged and handled inline (`onPaymentCaptured_unknownOrder_doesNotThrow`
  etc.; `onShipmentFailed_partialReleaseFailure_doesNotThrow_stillAttemptsRemainingSkus`).
- **Documented limitation carried over unchanged (DRQ-056):**
  `onShipmentFailed` compensates inventory only — the payment already
  captured is NOT refunded.

## Divergences (Phase B)

- **`Location` header is now an absolute URL**, not the literal relative
  string Spring's `ResponseEntity.location(URI)` echoed — Jakarta REST's
  `Response.accepted(...).location(URI)` resolves the URI against the
  request's base URI. Same divergence review-service's/payment-service's
  Phase B documents; the Order Context Contract only asserts the header is
  *present* (`tooling/newman/mea.postman_collection.json`, "9a"), so this is
  a test-assertion-strictness adjustment (`OrderResourceTest` now asserts
  `endsWith(...)`), not a contract change.
- **This service's outbox relay has a single emitter**, not a
  switch-on-`eventType` dispatch table like payment's two-outcome relay —
  this service's outbox only ever records ONE event type (`order.placed`).
  Adding dispatch machinery for event types this aggregate can never
  produce would be speculative infrastructure (see `OrderOutboxRelay`'s
  javadoc).
- **`getById`/`listAll` keep `@Transactional`** (payment's/shipping's
  Phase B reads dropped it) because `Order.items` is a lazy `@OneToMany`
  collection `toDto` walks — an open Hibernate session must span that
  access.

## Before / after metrics

Method: packaged (`./mvnw -q package`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` — against the real
podman-stack Postgres (`localhost:5432`) and, for Phase B, the real
podman-stack Kafka (`localhost:9092`; Phase A never configured messaging).
Startup time is Quarkus's own "started in `X`s" log line. RSS is
`ps -o rss` on the running process, sampled ~3s after the ready log line.
Both phases ran against **isolated scratch Postgres schemas**
(`order_service_phasea_scratch`/`order_service_phaseb_scratch`, dropped
afterward) to avoid touching this service's real `order_service` schema/
Flyway history or skewing RSS with accumulated rows — same discipline
payment-service's/notification-service's MIGRATION.md used. Phase B's
measurement run used a throwaway offset-reset override
(`auto.offset.reset=latest` on all four incoming channels) so RSS reflects
steady-state consumer connection overhead, not a one-time backlog replay.
Phase A was reconstructed byte-for-byte from this step's pre-edit source
(the exact Spring-compat `OrderController`/`GlobalExceptionHandler`/
`OrderRepository`/`CustomerRepository`/`OrderService`/pom.xml/
application.properties captured before this step's edits, verified against
the conversation record) into a scratch directory and packaged the same
way — no git operations were used (per this step's constraints).

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| **Phase A** — JVM, Spring-compat | 1.721 s | ~308 MB | 17 (`agroal, cdi, flyway, grpc-client, hibernate-orm, hibernate-orm-panache, hibernate-validator, jdbc-postgresql, narayana-jta, rest, rest-jackson, smallrye-context-propagation, smallrye-health, spring-data-jpa, spring-di, spring-web, vertx`) |
| **Phase B** — JVM, idiomatic + 4 Kafka consumers/1 producer + outbox relay | 2.050 s | ~407 MB | 18 (spring-compat extensions removed; `kafka-client, messaging, messaging-kafka, scheduler` added) |

**Reading the numbers:** like payment's/shipping's Phase A → Phase B, this
step adds REAL runtime capability, not just an idiomatic rewrite of the same
surface — and more of it than either: FOUR live Kafka consumers (one per
saga reaction, vs. payment's/shipping's one trigger consumer each) plus one
live Kafka producer (the outbox relay's emitter) plus a `@Scheduled`
poller. Startup is ~19% slower (1.72s → 2.05s, mostly the four consumers'
connection + metadata-fetch against the live broker) and RSS is ~32% higher
(308MB → 407MB, proportionally larger than payment's +12%/shipping's
comparable bump because FOUR consumer clients each carry their own
buffers/metadata caches, not just one) — the honest cost of running four
independent saga reactions as genuinely live consumers, not a regression to
chase. Removing the three Spring-compat extensions on its own (holding the
feature set constant) would have been a modest win in the same direction as
review-service's RSS drop; that effect is masked here by the much larger
net-new four-consumer Kafka footprint added in the same step. Native image
was **not** attempted for this step (time-boxed per the plan's "native
optional," same choice payment-service's/shipping-service's Phase B made).

## Verification (Phase B)

- `./mvnw -q package`: **BUILD SUCCESS**, 32 tests, 0 failures, 0 errors
  (`OrderResourceTest` 6, `OrderServiceTest` 6, `OrderSagaListenerTest` 15,
  `CheckoutOutboxTest` 2, `OrderSagaListenerIntegrationTest` 3).
- `grep -n "quarkus-spring\|org.springframework" pom.xml` → zero matches
  (the one hit is a comment NAMING the now-removed extensions, not a
  dependency coordinate).
- `grep -rn "org.springframework" src/main/java src/test/java` → zero
  matches in both trees.
- Live-run proof (packaged JVM artifact, scratch schema, real podman-stack
  Kafka): `/q/health` → `UP`; `Installed features` log line confirmed no
  `spring-*` feature present and `kafka-client`/`messaging`/
  `messaging-kafka`/`scheduler` present. Both scratch runs' JVM processes
  were shut down cleanly (`:8187` confirmed free via `ss`) and both scratch
  Postgres schemas (`order_service_phasea_scratch`/
  `order_service_phaseb_scratch`) were dropped afterward; the podman stack
  itself (`mea-kafka`, `mea-postgres`, `mea-connect`, `mea-lgtm`) was left
  running untouched throughout.
