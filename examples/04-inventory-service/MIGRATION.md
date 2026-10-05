# inventory-service — Migration Notes (ch.19 Phase A → Phase B, DRQ-029/DRQ-039/DRQ-041/DRQ-042/DRQ-044)

This file is the measured record of the two-phase Inventory extraction
required by the r05 plan (`_plans/iterations/inventory-plan.md`, step S6) and
follows `examples/02-review-service/MIGRATION.md` + `examples/03-notification-
service/MIGRATION.md`'s template so ch.19 can cite real numbers instead of
placeholders.

- **Phase A** ("lift the read surface onto Quarkus") — S5. The monolith's
  Spring MVC / Spring DI / Spring Data JPA inventory READ surface
  (`InventoryController`/`InventoryService#listAll,getBySku`/`InventoryItem`+
  repository) was moved onto Quarkus largely unchanged via the Quarkiverse
  Spring-compatibility extensions (`quarkus-spring-web`, `quarkus-spring-di`,
  `quarkus-spring-data-jpa`), already pointed at its OWN `inventory` Postgres
  schema from day one (DRQ-044, own-schema — mirrors notification-service's
  DRQ-035 rationale), seeded + kept current by the Debezium CDC backfill/sync
  consumer (`InventoryCdcConsumer`/`InventoryCdcWriter`, also S5, net-new).
  `quarkus-grpc` was already a Phase A dependency so the `inventory.proto`
  contract (S4) compiled and generated Mutiny stubs, but no `@GrpcService`
  bean existed yet — the gRPC server bound no port.
- **Phase B** ("make it idiomatic" + "make it live") — this step (r05 S6).
  The compatibility shim is removed; the read surface runs on Quarkus REST
  (RESTEasy Reactive), Panache, native CDI, and a `@ServerExceptionMapper`,
  with the exact same external HTTP contract — AND a net-new gRPC SERVER
  (`InventoryGrpcServiceImpl`) makes the synchronous `CheckStock`/`Reserve`/
  `Release`/`GetStock` hot path actually live, idiomatic-from-start per
  DRQ-044 (there is no Spring original to lift for RPCs that never existed in
  the monolith — the monolith's `reserve` lived in-JVM against a
  pessimistic-lock derived query, a fundamentally different mechanism).

## Per-component refactor (A → B)

| Component | Phase A (Spring-compat lift) | Phase B (idiomatic Quarkus + net-new gRPC) |
|---|---|---|
| HTTP layer | `InventoryController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@PathVariable` (via `quarkus-spring-web`) | `InventoryResource` — Jakarta REST `@Path`/`@GET`, plain `List<StockDto>`/`StockDto` return, directly on `quarkus-rest-jackson` |
| Data access | `InventoryRepository` — Spring Data `JpaRepository<InventoryItem, Long>` interface (via `quarkus-spring-data-jpa`) | Same class name, now a concrete `@ApplicationScoped` class implementing `PanacheRepository<InventoryItem>`; `findBySku` becomes an explicit simplified-HQL `find(...)` call; **net-new** `reserve(sku, qty)`/`release(sku, qty)` — single conditional/unconditional `update(...)` statements (the gRPC mutating hot path's atomic check-and-decrement / compensating re-increment, DRQ-041/DRQ-042) |
| Entity (`InventoryItem`) | Plain JPA, own schema, CDC-assigned `id` (not `@GeneratedValue`) | **Unchanged** — zero entity edits needed, same finding as review-service's and notification-service's Phase B: Panache's REPOSITORY pattern works against this ordinary `@Entity` class as-is |
| Service | `InventoryService` — `@Service` (Spring stereotype) + `@ApplicationScoped` dual-annotated, constructor injection, read-path only | `@ApplicationScoped` only; Quarkus's simplified constructor injection (single constructor ⇒ no `@Inject`); `repository.findAll()` → `repository.listAll()` (Panache's equivalent); business logic otherwise byte-for-byte unchanged. The mutating `reserve`/`release` concern deliberately does NOT move here — it stays server-side on the gRPC path, not a REST-surface concern |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice`/`@ExceptionHandler`, `ResponseEntity<ApiError>` | `GlobalExceptionMapper` — plain class (no CDI scope, no `@Provider`), one `@ServerExceptionMapper` method, declared outside any `@Path` class so it applies application-wide; same `ApiError` body, same 404 status code |
| DTOs (`StockDto`, `ApiError`) | Plain records | Plain records — **one addition**: `@RegisterForReflection` applied to `ApiError` **proactively from the start** (not re-discovered the hard way) — copied forward from review-service's Phase-B native-image finding (`@ServerExceptionMapper` returning generic `Response` hides the body type from Quarkus's build-time Jackson reflection scan, which breaks native-image serialization until the type is explicitly registered) |
| gRPC server | *(did not exist — `quarkus-grpc` present for stub generation only, no `@GrpcService` bean, no port bound)* | **Net-new** `InventoryGrpcServiceImpl` (`@GrpcService`), implementing the generated Mutiny `InventoryGrpcService` interface: `CheckStock` (read-only availability probe), `Reserve` (atomic check-and-decrement), `Release` (compensating re-increment), `GetStock` (full read, the ch.16 `InventoryAclRoute` content-enricher target). All four RPCs are `@Blocking` (classic, non-reactive `quarkus-hibernate-orm-panache` issues blocking JDBC, which must not run on the Vert.x event-loop thread) |
| CDC consumer/writer (`InventoryCdcConsumer`/`InventoryCdcWriter`) | Net-new in Phase A (no Spring original — the monolith never consumed its own `inventory_items` change stream) | **Unchanged** — kept exactly as Phase A authored them; `InventoryCdcWriter` was already a plain CDI bean using `EntityManager` directly (Quarkus's Spring Data JPA compat extension doesn't support `@Query(nativeQuery = true)` on custom repository methods, confirmed empirically in Phase A — see its javadoc), so there was no spring-compat dependency to remove here |
| Config (`application.properties`) | SmallRye Config, port 8084/8085, own-schema Postgres coords, CDC channel config, no gRPC server port bound | **Added** `quarkus.grpc.server.port=9004` (dev/prod), `quarkus.grpc.server.test-port=9005`, `quarkus.grpc.server.use-separate-server=true`, and a `%test.`-scoped `quarkus.grpc.clients.inventory.*` pointed at the test port for the in-JVM `@GrpcClient` test — otherwise unchanged |
| Build (`pom.xml`) | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa` present | **Removed** all three; added `quarkus-hibernate-orm-panache` |
| Tests | `InventoryControllerTest` (`@QuarkusTest` + REST Assured, seeded via `InventoryCdcWriter#upsert`), `InventoryCdcConsumerTest` (SmallRye in-memory connector) | `InventoryControllerTest` → `InventoryResourceTest` (renamed to match the resource rename, mirroring review-service's `ReviewControllerTest` → `ReviewResourceTest`); identical three cases. `InventoryCdcConsumerTest` **unchanged**. **Net-new** `InventoryGrpcServiceTest` (`@QuarkusTest` + in-JVM `@GrpcClient`): CheckStock (sufficient/insufficient/unknown sku), GetStock, Reserve (sufficient/insufficient/sequential over-reserve), Release (restores/unknown sku), and a **concurrency test** — two concurrent `Reserve` calls racing the last unit of stock |

## gRPC server: port, atomic Reserve, and Release compensation (DRQ-039/DRQ-041/DRQ-042)

- **Port.** A SEPARATE gRPC server (`quarkus.grpc.server.use-separate-server=
  true`, the current Quarkus default — "legacy gRPC support," a unified
  HTTP+gRPC server is opt-in and not used here to keep the two surfaces
  independently documented) on **`:9004`** (dev/prod), distinct from the
  HTTP read surface on `:8084`. `quarkus.grpc.server.test-port=9005` mirrors
  the existing `quarkus.http.test-port=8085` split, so `@QuarkusTest`'s gRPC
  server never collides with a concurrently running `quarkus:dev` instance
  during continuous testing.
- **Reserve's atomic check-and-decrement.** `InventoryRepository#reserve`
  issues a single conditional SQL statement via Panache's
  `update(query, params)`:
  ```
  UPDATE inventory_items
     SET quantity_on_hand = quantity_on_hand - :qty
   WHERE sku = :sku AND quantity_on_hand >= :qty
  ```
  and reports the rows-affected count. Exactly 1 row affected means the
  check-and-decrement succeeded atomically (`reservation_ok=true`); 0 rows
  affected means the `WHERE` predicate failed — either insufficient stock or
  an unknown sku — and **no decrement happened** (`reservation_ok=false`,
  unchanged `on_hand_qty`). This is **concurrency-safe without an explicit
  application-level lock** — no `SELECT ... FOR UPDATE`, no optimistic-locking
  `@Version` column — because the database takes its own row-level write lock
  for the duration of the `UPDATE` statement: two concurrent `Reserve` calls
  racing the same row serialize on that lock, and whichever commits first
  leaves `quantity_on_hand` below the other's `>= :qty` predicate. Proven by
  `InventoryGrpcServiceTest#reserve_concurrentRequestsForLastUnit_
  exactlyOneSucceeds`: two concurrent `Reserve(sku, 1)` calls against a sku
  with exactly 1 unit on hand — exactly one reports `reservation_ok=true`
  (decremented to 0), the other reports `reservation_ok=false` (unchanged) —
  plus a sequential over-reserve test (`reserve_sequentialOverReserve_
  secondCallFailsAfterFirstSucceeds`) for the simpler non-concurrent case.
- **Release's compensation (DRQ-042, a deliberate first taste of saga).**
  `InventoryRepository#release` issues the inverse unconditional statement:
  ```
  UPDATE inventory_items
     SET quantity_on_hand = quantity_on_hand + :qty
   WHERE sku = :sku
  ```
  The monolith calls `Release` when checkout fails **after** a successful
  `Reserve` (notably a payment decline) — once the decrement lives in this
  service's own DB, the monolith's local `@Transactional` can no longer roll
  it back, so the monolith must explicitly restore the observable baseline.
  **Idempotency / at-least-once consideration (documented, not solved here —
  explicitly out of scope per the plan's scope discipline):** unlike
  `Reserve`'s conditional `UPDATE`, this increment carries no dedupe/
  idempotency key. A retried `Release` call whose effect already landed
  server-side (the same at-least-once redelivery hazard
  `InventoryCdcConsumer`'s upsert guards against for CDC) would over-restore
  stock. A full fix — an idempotency key per reservation/compensation, or a
  saga ledger — is deliberately deferred to ch.23's choreographed saga
  (DRQ-042); `InventoryGrpcServiceImpl#release`'s javadoc is that deferral's
  record.

## Verification

- `./mvnw -q package`: **BUILD SUCCESS**.
- No Spring-compat dependencies remain: `grep -n "quarkus-spring-" pom.xml`
  matches only the `<dependencies>` block's leading comment documenting the
  removal, not a dependency.
- No Spring imports remain anywhere in `src/`: `grep -rn "^import org.springframework" src/` → **no matches**.
- `./mvnw -q test` (podman-stack Postgres + Kafka up, but tests use Dev
  Services' isolated Testcontainers Postgres and an in-JVM `@GrpcClient` —
  no live broker/shared DB/external gRPC port touched): **16 tests, 0
  failures, 0 errors**:
  - `InventoryResourceTest` (3): `listAll` 200 + `StockDto` array,
    `getBySku` 200 + shape, unknown sku 404 + `ApiError` shape — unchanged
    across Phase A → Phase B.
  - `InventoryCdcConsumerTest` (3): unchanged from Phase A — snapshot
    backfill, idempotent update-under-redelivery, delete.
  - `InventoryGrpcServiceTest` (10, net-new): `checkStock` sufficient/
    insufficient/unknown-sku, `getStock`, `reserve` sufficient (decrements
    exactly by the requested qty) / insufficient (reports not-ok, stock
    unchanged) / sequential over-reserve, `release` restores / unknown-sku,
    and the **concurrency check** — two concurrent `Reserve` calls for the
    last unit of stock, exactly one succeeds (see previous section).
- **Live smoke test** — packaged JVM build (`java -jar target/quarkus-app/
  quarkus-run.jar`) started against the REAL podman-stack Postgres
  (`localhost:5432`, `inventory` schema, already backfilled by the Debezium
  connector from earlier steps) and REAL Kafka (`localhost:9092`), HTTP on
  `:8084` + gRPC on `:9004`:
  - `GET /api/inventory` → 200, 3 seeded skus, identical `StockDto` shape to
    Phase A.
  - `GET /api/inventory/SKU-DOES-NOT-EXIST` → 404, `{"error":"NOT_FOUND",...}`.
  - `grpcurl` `CheckStock`/`GetStock` against `SKU-WIDGET-001` (48 on hand) →
    `available: true`, `onHandQty: 48` (read-only, confirmed non-mutating).
  - `grpcurl` `Reserve(SKU-WIDGET-001, 5)` → `reservationOk: true,
    onHandQty: 43`; `GET /api/inventory/SKU-WIDGET-001` immediately confirmed
    `quantityOnHand: 43` (decremented exactly once).
  - `grpcurl` `Release(SKU-WIDGET-001, 5)` → `reservationOk: true,
    onHandQty: 48`; REST re-read confirmed `quantityOnHand: 48` — **restored
    exactly to the pre-reserve baseline**, proving the compensation.
  - `grpcurl` `Reserve(SKU-GIZMO-003, 999)` against a sku with only 5 on
    hand → `reservationOk` omitted (protobuf JSON elides default/`false`
    values), `onHandQty: 5` (field present since non-default); REST re-read
    confirmed `quantityOnHand: 5` — **unchanged**, no partial decrement on
    insufficient stock.
  - Service shut down cleanly (`kill` + confirmed via `ss`/`pgrep` that
    `:8084`/`:9004` were released and no orphan JVM remained); the
    podman-stack containers (`mea-postgres`, `mea-kafka`, `mea-connect`,
    `mea-lgtm`) were left running, untouched.

## Before / after metrics

Method: packaged (`./mvnw -q package`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` — against the SAME
podman-stack Postgres on `localhost:5432` (`inventory` schema, already
migrated) and Kafka on `localhost:9092`, profile `prod`. Startup time is
Quarkus's own "started in `X`s" log line. RSS is `ps -o rss` on the running
process, sampled ~6s after the ready log line. Phase A was reconstructed
byte-for-byte from the pre-Phase-B source (the exact Spring-compat
`Controller`/`Service`/`Repository`/`ApiError`/`pom.xml`/
`application.properties` grpc-section captured before this step's edits,
with `InventoryItem`/`InventoryCdcConsumer`/`InventoryCdcWriter` carried over
unchanged since Phase B didn't touch them) into a scratch `/tmp` directory
and packaged the same way — no git operations were used (this is not a git
repository and the step's constraints forbid git regardless); the
reconstruction is a direct, verified copy built and tested green (16 tests,
0 failures) before measuring, not an approximation.

| Build | Startup time | RSS | Installed features |
|---|---|---|---|
| **Phase A** — JVM, Spring-compat (no gRPC port bound) | 1.673 s | ~313 MB | 19 (`agroal, cdi, flyway, grpc-client, hibernate-orm, hibernate-orm-panache, jdbc-postgresql, kafka-client, messaging, messaging-kafka, narayana-jta, rest, rest-jackson, smallrye-context-propagation, smallrye-health, spring-data-jpa, spring-di, spring-web, vertx`) |
| **Phase B** — JVM, idiomatic + gRPC server live | 1.677 s | ~300 MB | 16 (`agroal, cdi, flyway, grpc-client, grpc-server, hibernate-orm, hibernate-orm-panache, jdbc-postgresql, kafka-client, messaging, messaging-kafka, narayana-jta, rest, rest-jackson, smallrye-context-propagation, smallrye-health, vertx`) |

**Reading the numbers:** this is the same honest pattern notification-
service's MIGRATION.md documents — not a clean "remove the shim, everything
shrinks" story. Startup is a wash (1.673s → 1.677s, within run-to-run noise);
RSS actually drops ~4% (313MB → 300MB) **even though** Phase B adds a live
`grpc-server` feature that Phase A's `grpc-client`-only (stub-generation-only)
configuration never bound a port for — the three Spring-compat translation
extensions removed (`spring-web`, `spring-di`, `spring-data-jpa`) cost more at
boot than one more gRPC server costs to add. This is the inverse framing from
notification-service (whose Phase B *added* two live Kafka consumers + a
WebSocket server and so visibly grew RSS) and a *reinforcing* data point for
review-service's original finding: removing the compat shim is a modest,
consistent win on its own, and here that win happens to roughly offset the
real new capability (the gRPC server) being added in the same step. Native
image was **not** attempted for this step (time-boxed per the plan, and S6's
scope is the gRPC server + Panache refactor, not the native-image
comparison); review-service's MIGRATION.md already demonstrates the ~30x
startup/~4x RSS native payoff pattern for this same service family, which
should transfer directly to Inventory's idiomatic Phase B code.
