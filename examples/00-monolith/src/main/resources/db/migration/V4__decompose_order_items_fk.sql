-- r05/ch.19 S8 (DRQ-043, realizing ch.18): decompose the cross-context FK
-- order_items.inventory_item_id -> inventory_items.id.
--
-- Once inventory is reachable only over the gRPC seam (S7) and is destined to
-- own its own database (ch.19's end state), order_items can no longer hold a
-- live DB-level FK into inventory_items. order.OrderItem now persists a
-- denormalized SNAPSHOT of exactly what the order context needs to render a
-- line item: sku (a plain string, a SOFT reference only), product_name, and
-- unit_price_cents (already present, now the price AT ORDER TIME). This
-- migration adds the two missing snapshot columns, BACKFILLS them from the
-- current inventory_items join so every pre-existing order_items row keeps
-- its historical data, then drops the FK constraint and the now-unused
-- inventory_item_id column + its index.
--
-- FORWARD-ONLY, idempotent-safe: this runs against the persistent dev
-- Postgres that already has V1-V3 applied. Every DDL step below is guarded
-- (IF [NOT] EXISTS) so a partial/retried apply (or a future fresh bring-up,
-- where V1 already creates order_items without inventory_item_id) does not
-- fail.

-- 1) Add the snapshot columns if they aren't there yet (nullable for now so
--    the backfill below can populate pre-existing rows before we enforce
--    NOT NULL).
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS sku VARCHAR(64);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);

-- 2) Backfill every existing order_items row's snapshot from the current
--    inventory_items join -- preserves historical orders' data before the
--    join is removed. Guarded by inventory_item_id's existence so this is a
--    no-op (and does not error) on a fresh schema that never had the column,
--    or on a re-run after the column has already been dropped.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'order_items' AND column_name = 'inventory_item_id'
    ) THEN
        UPDATE order_items oi
        SET sku = ii.sku,
            product_name = ii.name
        FROM inventory_items ii
        WHERE oi.inventory_item_id = ii.id
          AND (oi.sku IS NULL OR oi.product_name IS NULL);
    END IF;
END $$;

-- 3) Now that every row has a snapshot, enforce NOT NULL (mirrors the
--    original inventory_item_id's NOT NULL constraint).
ALTER TABLE order_items ALTER COLUMN sku SET NOT NULL;
ALTER TABLE order_items ALTER COLUMN product_name SET NOT NULL;

-- 4) Drop the cross-context FK constraint (the whole point of this
--    migration -- curing SMELL #1/[ch.18] for the order->inventory seam).
ALTER TABLE order_items DROP CONSTRAINT IF EXISTS order_items_inventory_item_id_fkey;

-- 5) Drop the now-unused index and column. inventory_items itself is
--    deliberately LEFT IN PLACE in the shared schema (write-only history
--    during the transition, same treatment as reviews/notifications under
--    SMELL #1) until inventory fully cuts over to its own database (ch.19
--    decommission step).
DROP INDEX IF EXISTS idx_order_items_inventory_item_id;
ALTER TABLE order_items DROP COLUMN IF EXISTS inventory_item_id;
