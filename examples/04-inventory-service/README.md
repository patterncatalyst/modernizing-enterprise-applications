# inventory-service

**r05/ch.19 S4+S5 ("the third strangler extraction, synchronous gRPC + CDC").**
The monolith's (`examples/00-monolith`) Inventory bounded context, extracted
onto Quarkus. Mirrors review-service's/notification-service's two-phase
pattern (DRQ-029/DRQ-035), extended here with a gRPC contract and a
Debezium-CDC-backed owned schema (DRQ-039...046).

- **Port `8084`.** Endpoints (identical external contract to the monolith):
  `GET /api/inventory` (array of `StockDto`), `GET /api/inventory/{sku}`
  (`StockDto`, 404 if unknown).
- **Persistence: OWNS ITS OWN SCHEMA from day one.** Points at the SAME
  podman-stack Postgres *instance* every example in this repo shares
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
  Idempotent, keyed-by-id `UPSERT`/`DELETE` (see `InventoryRepository`).
- **gRPC contract (`src/main/proto/.../inventory.proto`, S4):**
  `InventoryGrpcService` -- `CheckStock`, `Reserve`, `Release`, `GetStock` --
  a deliberately distinct wire vocabulary (`stock_keeping_unit`,
  `unit_price_cents`, `on_hand_qty`, `requested_qty`, `reservation_ok`) from
  `StockDto`, so it is a real anti-corruption-layer contract (ch.16's
  `InventoryAclRoute` translator maps between the two). **Only the proto +
  generated Mutiny stubs are built here** -- the gRPC SERVER implementation
  (the mutating `Reserve`/`Release` atomic check-and-decrement) is **S6**
  (Phase B, idiomatic-from-start, DRQ-044).
- **Phase A** (this step, DRQ-044): the read surface
  (`InventoryController`/`InventoryService`/`InventoryRepository`/
  `InventoryItem`) is lifted via the Quarkiverse Spring-compat extensions
  (`quarkus-spring-web`/`-di`/`-data-jpa`), minimal change, same `StockDto`
  JSON shape and 404 contract as the monolith. **Phase B** (idiomatic Quarkus
  REST + Panache) is S6.

## Verified

- `./mvnw -q package` -- builds, including proto-stub codegen.
- `@QuarkusTest`:
  - `InventoryControllerTest` -- read surface (`GET /api/inventory` -> 200
    array, `/{sku}` -> 200, unknown sku -> 404), self-seeded via the same
    idempotent `InventoryRepository#upsert` path the CDC consumer uses.
  - `InventoryCdcConsumerTest` -- feeds Debezium-shaped JSON envelopes
    (`op=r/c/u/d`) through the real `@Incoming("inventory-cdc")` pipeline via
    SmallRye's in-memory connector: snapshot backfill, idempotent redelivery,
    and delete are all asserted both at the repository level and via the
    `/api/inventory` read surface.
- End-to-end backfill against the live podman stack + registered Debezium
  connector: see `CUTOVER.md`/the r05 plan evidence trail (appended at S10).

## Deferred to S6

- Phase B idiomatic refactor (Quarkus REST + Panache, drop spring-compat).
- The gRPC server implementation: `Reserve`'s atomic check-and-decrement and
  `Release`'s compensating re-increment, against this service's own schema.
