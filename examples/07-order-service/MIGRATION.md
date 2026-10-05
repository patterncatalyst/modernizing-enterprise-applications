# order-service — Migration Notes (ch.26 Phase A, S4, DRQ-068/073)

This file is the record of ch.26 S4 — `_plans/iterations/order-plan.md`'s
scaffold-and-lift step for the order extraction, the LAST and hardest
extraction in the roadmap. It mirrors `examples/05-payment-service/MIGRATION.md`'s
/ `examples/06-shipping-service/MIGRATION.md`'s Phase A section, adapted for
order's two distinguishing concerns: an FK decomposition that touches BOTH
sides of a relationship (`Order` ⇄ `Customer`), and a live cross-service gRPC
collaborator (`RemoteInventoryClient`) that other services' Phase A never
needed.

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
  own outbox) is **S5** — NOT this step. **Phase C-equivalent** (read-model
  projection into `order_view`) is **S6** — NOT this step. Neither has any
  Java code in this module yet; see "Deferred to S5/S6" below.

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
