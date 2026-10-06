#!/usr/bin/env python3
"""ch.18 figure: shared-db-to-database-per-service — the chapter's core
before/after, grounded in the one relationship ch.18 and ch.19 both use as
their running example: `OrderItem`'s FK into `InventoryItem`.

LEFT: today's shared schema. `order_items.inventory_item_id` is a live
`@ManyToOne` FK straight into `inventory_items`, enforced by Postgres inside
one database (`V1__init_schema.sql`). `order_items.unit_price_cents` is
already a captured snapshot, but `sku` and the product name are not — they
are read live off whatever `inventory_items` currently holds, which is the
exact mechanism behind the chapter's SKU-rename bug: rename a SKU next
quarter and every historical order's display silently relabels itself, with
no record anything changed.

RIGHT: the ch.19 target. Inventory owns its own database; the FK is dropped
(`ALTER TABLE order_items DROP CONSTRAINT order_items_inventory_item_id_fkey`)
and `order_items` gains `inventory_item_sku` / `product_name_snapshot`
columns, written once at order-placement time the same way
`unit_price_cents` already is — so the SKU-rename bug becomes impossible by
construction, not merely fixed. Inventory's own data moves to its new owned
database the way ch.19's "Planning the split" section describes: a CDC
connector tails the shared schema's write-ahead log and backfills the new
database in near-real-time, underneath a system that keeps running.

Sourced verbatim from `_docs/18-shared-data-to-owned-data.md` ("Why
shared-database coupling is the hardest coupling to break," "From foreign key
to snapshot: the denormalization move, concretely," "Planning the split"),
cross-checked against `examples/00-monolith/.../db/migration/
V1__init_schema.sql` (`order_items.inventory_item_id REFERENCES
inventory_items (id)`) and `OrderItem`'s own `SMELL[ch.18]` javadoc. The
illustrative post-FK columns and the `ALTER TABLE` are explicitly marked in
the chapter as a sketch of ch.19's target shape, not existing code. No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 820

band_left = {"x": 20, "y": 60, "w": 760, "h": 700,
             "label": "SHARED DATABASE — live FK join (today)", "fill": "#fafafa"}
band_right = {"x": 820, "y": 60, "w": 760, "h": 700,
              "label": "DATABASE PER SERVICE — snapshot + event feed (ch.19 target)", "fill": "#eaf4ec"}

# ============================================================================
# LEFT — one shared schema, order_items' live FK into inventory_items
# ============================================================================
schemaL = node(100, 110, 600, 70,
               ["PostgreSQL — one shared schema", "V1__init_schema.sql"], style="ink")
orderItemsL = node(60, 230, 320, 130,
                    ["order_items", "id PK", "order_id FK -> orders",
                     "inventory_item_id FK -> inventory_items",
                     "unit_price_cents (already a snapshot)"], style="box")
inventoryItemsL = node(420, 230, 320, 110,
                        ["inventory_items", "id PK", "sku, name, price_cents",
                         "quantity_on_hand, updated_at"], style="box")
bugL = node(100, 410, 600, 110, style="ghost", lines=[
    "Live join reads inventory's CURRENT row",
    "Rename a SKU next quarter and every past order's display",
    "silently relabels itself — no record anything changed",
])

left_nodes = [schemaL, orderItemsL, inventoryItemsL, bugL]
left_edges = [
    connect(orderItemsL, inventoryItemsL, label="@ManyToOne -- live join, read every time", amber=True),
    connect(orderItemsL, bugL, dashed=True, label="sku / product name read live, not captured"),
]

# ============================================================================
# RIGHT — two owned databases: snapshot columns + a CDC event feed
# ============================================================================
orderDbR = node(860, 110, 320, 150, style="accent", lines=[
    "Order database (owned)", "order_items",
    "inventory_item_sku (snapshot)",
    "product_name_snapshot (snapshot)",
    "unit_price_cents (snapshot, unchanged)",
])
inventoryDbR = node(1260, 110, 300, 110, style="accent", lines=[
    "Inventory database (owned)", "inventory_items",
    "id, sku, name, price_cents",
    "quantity_on_hand, updated_at",
])
noFkR = node(860, 290, 320, 90, style="ink", lines=[
    "FK DROPPED",
    "order_items_inventory_item_id_fkey removed",
    "no live reference into inventory's schema",
])
cdcR = node(1060, 440, 320, 100, style="box", lines=[
    "CDC connector (ch.19)",
    "tails the shared schema's write-ahead log,",
    "backfills inventory_items into its own database",
])

right_nodes = [orderDbR, inventoryDbR, noFkR, cdcR]
right_edges = [
    connect(orderDbR, noFkR),
    connect(noFkR, cdcR, dashed=True, label="old FK path retired after cutover"),
    connect(cdcR, inventoryDbR, label="backfill replication", amber=True),
]

transform_edge = {"x1": 780, "y1": 265, "x2": 820, "y2": 265, "label": "ch.19", "amber": True}

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.18 -- Shared database to database-per-service: one FK join, before and after",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "order_items -> inventory_items: a live @ManyToOne today, a point-in-time snapshot plus a CDC event feed after ch.19",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 40, "y": 740,
     "text": "SMELL[ch.18]: order_items.inventory_item_id is one of nine FKs crossing a bounded-context line in this one schema.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 860, "y": 740,
     "text": "Written once at order-placement, frozen forever -- a later SKU rename can no longer relabel a historical order.",
     "anchor": "start", "size": 11, "color": "#2f5f3d", "bold": True},
    {"x": 860, "y": 756,
     "text": "A live stock check at checkout still crosses the seam synchronously (gRPC) -- this snapshot is for history, not for \"in stock right now.\"",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": 800,
     "text": "Sourced from _docs/18-shared-data-to-owned-data.md; examples/00-monolith/.../db/migration/V1__init_schema.sql; OrderItem's SMELL[ch.18] javadoc. Post-FK columns are ch.19's illustrative target, not existing code.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "shared-db-to-database-per-service", W, H,
    bands=[band_left, band_right],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges + [transform_edge],
    notes=notes,
)
