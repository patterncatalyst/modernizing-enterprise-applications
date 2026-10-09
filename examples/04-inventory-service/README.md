# inventory-service

**r05/ch.19 S4+S5+S6 ("the third strangler extraction, synchronous gRPC +
CDC").** The monolith's (`examples/00-monolith`) Inventory bounded context,
extracted onto Quarkus. Mirrors review-service's/notification-service's
two-phase pattern (DRQ-029/DRQ-035), extended here with a gRPC contract and a
Debezium-CDC-backed owned schema (DRQ-039...046). **Phase B (S6) is complete**
— see `MIGRATION.md` for the full before/after record.

- **Port `8084`** (HTTP) **+ `9004`** (gRPC server, separate from HTTP;
  `9005` is the dedicated `@QuarkusTest` port). Read endpoints (identical
  external contract to the monolith): `GET /api/inventory` (array of
  `StockDto`), `GET /api/inventory/{sku}` (`StockDto`, 404 if unknown).
- **Persistence: OWNS ITS OWN SCHEMA from day one.** Points at the SAME
  compose-stack Postgres *instance* every example in this repo shares
  (`localhost:5432`, db `monolith`), but lives in its own `inventory` Postgres
  SCHEMA, migrated by its own Flyway history
  (`src/main/resources/db/migration`) -- it never reads or writes the
  monolith's `public.inventory_items` table directly. See
  `InventoryItem.java`'s javadoc for why (a CDC-fed writable replica cannot
  share the upstream table without two writers racing).
- **Seeded + kept current by Debezium CDC** (`InventoryCdcConsumer`,
  `mea.public.inventory_items` topic): the initial snapshot (`op=r` events)
  backfills this service's table from the monolith's current data; streamed
  `op=c/u/d` events keep it in sync during the transition window (DRQ-040).
  Idempotent, keyed-by-id `UPSERT`/`DELETE` (see `InventoryCdcWriter`).
- **gRPC SERVER (`InventoryGrpcServiceImpl`, S6, DRQ-039/DRQ-041/DRQ-042)
  implementing `src/main/proto/.../inventory.proto`'s `InventoryGrpcService`:**
  - `CheckStock(sku, requested_qty)` -- read-only availability probe.
  - `Reserve(sku, requested_qty)` -- server-side ATOMIC check-and-decrement:
    a single conditional `UPDATE ... SET quantity_on_hand = quantity_on_hand
    - :n WHERE sku = :sku AND quantity_on_hand >= :n`; rows-affected == 1
    means reserved, == 0 means insufficient stock (no decrement). No
    application-level lock needed -- the database's own row-level write lock
    on the `UPDATE` makes it concurrency-safe.
  - `Release(sku, qty)` -- the compensating re-increment (DRQ-042, a taste of
    saga): `UPDATE ... SET quantity_on_hand = quantity_on_hand + :n WHERE
    sku = :sku`. At-least-once/idempotency caveat documented in
    `InventoryGrpcServiceImpl#release`'s javadoc, deliberately deferred to
    ch.23's saga.
  - `GetStock(sku)` -- full stock read, the ch.16 `InventoryAclRoute`
    content-enricher fetch target.
  The wire vocabulary (`stock_keeping_unit`, `unit_price_cents`,
  `on_hand_qty`, `requested_qty`, `reservation_ok`) is deliberately distinct
  from `StockDto`, so it is a real anti-corruption-layer contract.
- **Phase A → Phase B (DRQ-029/DRQ-044):** the read surface moved from
  Spring-compat (`InventoryController`/`InventoryService`/
  `InventoryRepository` via `quarkus-spring-web`/`-di`/`-data-jpa`) to
  idiomatic Quarkus REST (`InventoryResource`) + Panache
  (`InventoryRepository implements PanacheRepository<InventoryItem>`) + plain
  CDI, same `StockDto` JSON shape and 404 contract. See `MIGRATION.md`.

## Verified

- `./mvnw -q package` -- **BUILD SUCCESS**; no `quarkus-spring-*` dependency
  remains, no `org.springframework` import remains anywhere in `src/`.
- `@QuarkusTest` (16 tests, 0 failures):
  - `InventoryResourceTest` (3, renamed from `InventoryControllerTest`) --
    read surface (`GET /api/inventory` -> 200 array, `/{sku}` -> 200, unknown
    sku -> 404), self-seeded via the same idempotent
    `InventoryCdcWriter#upsert` path the CDC consumer uses.
  - `InventoryCdcConsumerTest` (3, unchanged) -- feeds Debezium-shaped JSON
    envelopes (`op=r/c/u/d`) through the real `@Incoming("inventory-cdc")`
    pipeline via SmallRye's in-memory connector.
  - `InventoryGrpcServiceTest` (10, net-new) -- `CheckStock`/`GetStock`
    (read-only), `Reserve` sufficient/insufficient/sequential-over-reserve,
    `Release` restores/unknown-sku, and a **concurrency test**: two
    concurrent `Reserve` calls racing the last unit of stock -- exactly one
    succeeds.
- Live smoke test against the real compose-stack Postgres + Kafka (packaged
  JVM build, `:8084` HTTP + `:9004` gRPC via `grpcurl`): `Reserve` decremented
  stock by exactly the requested quantity; the compensating `Release`
  restored it exactly to baseline; an over-large `Reserve` left stock
  untouched. See `MIGRATION.md` for the full transcript + before/after
  startup/RSS metrics.
- End-to-end backfill against the live compose stack + registered Debezium
  connector: see `CUTOVER.md`/the r05 plan evidence trail (appended at S10).
