-- inventory-service owns this schema from day one (r05/ch.19 S5, DRQ-044).
-- Distinct from examples/00-monolith's `public.inventory_items` table: this
-- is a SEPARATE table in a SEPARATE Postgres schema, in the SAME
-- podman-stack Postgres *instance* (localhost:5432, db `monolith`) every
-- example in this repo shares. Flyway (quarkus.flyway.schemas=inventory)
-- creates the `inventory` schema automatically before running this
-- migration; the explicit CREATE SCHEMA below is defensive/idempotent, not
-- required.
--
-- Why own schema, not shared (mirrors notification-service's own-schema
-- rationale, DRQ-035): this table is seeded + kept current by Debezium CDC
-- (S5, DRQ-040) tailing the monolith's `public.inventory_items` WAL. A
-- CDC-fed replica that shared the upstream table would have two writers
-- racing on the same rows (the monolith's still-live write path, and this
-- service's CDC consumer) with no way to reconcile them -- owning the table
-- avoids that split-brain entirely, same as notification's event-driven read
-- model.
CREATE SCHEMA IF NOT EXISTS inventory;

-- `id` is NOT a locally-generated identity -- it is CDC-ASSIGNED, carried
-- over verbatim from the monolith's `public.inventory_items.id` so this
-- service's copy and the source row always agree on identity (see
-- InventoryRepository#upsert, the idempotent keyed-by-id UPSERT the CDC
-- consumer drives). `sku` keeps its own UNIQUE constraint, matching the
-- monolith's table, since the public read surface (StockDto) is keyed by sku.
CREATE TABLE IF NOT EXISTS inventory.inventory_items (
    id               BIGINT       PRIMARY KEY,
    sku              VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    price_cents      BIGINT       NOT NULL,
    quantity_on_hand INTEGER      NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inventory_items_sku
    ON inventory.inventory_items (sku);
