#!/usr/bin/env python3
"""ch.08 figure: monolith-shared-schema-er — the shared-database ER that
makes SMELL #1 (SMELLS.md: "shared schema and cross-context foreign-key
joins") visceral: eight tables, one Postgres schema, nine foreign keys
crossing bounded-context ownership lines freely. Deliberately drawn clean and
general (table name + key columns only, no controller/service framing) so it
can be re-embedded unchanged in ch.18 ("From Shared Data to Owned Data") and
referenced again from ch.09, ch.11, and ch.13.

Tables and every foreign key below are quoted verbatim from
examples/00-monolith/src/main/resources/db/migration/V1__init_schema.sql on
branch reference/monolith-before, cross-checked against SMELLS.md's smell #1
row and _docs/08-designing-the-monolith.md's "Persistence: one shared schema,
two Flyway migrations" section. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 760

# ---- top row: the two tables referenced from multiple contexts ------------
TOP_Y, TOP_H = 90, 120
customers = node(300, TOP_Y, 260, TOP_H,
                  ["customers (shared kernel)", "id PK", "name, email, created_at"], style="sub")
inventory_items = node(900, TOP_Y, 260, TOP_H,
                        ["inventory_items", "id PK", "sku, name, price_cents",
                         "quantity_on_hand, updated_at"], style="box")

# ---- bottom row: the six context-owned, referencing tables -----------------
BOT_Y, BOT_H, BOT_W, GUTTER = 330, 150, 220, 20
BOT_X0 = 40
bottom_defs = [
    ("orders", ["orders", "id PK", "customer_id FK", "status, total_cents, shipping_address"]),
    ("order_items", ["order_items", "id PK", "order_id FK, inventory_item_id FK", "quantity, unit_price_cents"]),
    ("payments", ["payments", "id PK", "order_id FK", "amount_cents, method, status"]),
    ("shipments", ["shipments", "id PK", "order_id FK", "address, status"]),
    ("notifications", ["notifications", "id PK", "customer_id FK, order_id FK (nullable)", "channel, message"]),
    ("reviews", ["reviews", "id PK", "customer_id FK, inventory_item_id FK", "rating, comment"]),
]
bottom = {}
for i, (name, lines) in enumerate(bottom_defs):
    x = BOT_X0 + i * (BOT_W + GUTTER)
    bottom[name] = node(x, BOT_Y, BOT_W, BOT_H, lines, style="box")

all_tables = {"customers": customers, "inventory_items": inventory_items, **bottom}
nodes = list(all_tables.values())

# ---- FK edges ---------------------------------------------------------------
# within the bottom row: order_items -> orders is adjacent, a direct line.
# everything else is routed through a dedicated lane so no line crosses an
# intervening table box.
edges = [connect(bottom["order_items"], bottom["orders"], amber=True)]

# bottom-row -> bottom-row, non-adjacent (payments/shipments/notifications -> orders):
# dip BELOW the row.
DIP_DOWN_Y = BOT_Y + BOT_H + 30


def dip_down(a_name, b_name):
    a, b = bottom[a_name], bottom[b_name]
    ax = a["x"] + a["w"] / 2
    ay = a["y"] + a["h"]
    bx = b["x"] + b["w"] * 0.75  # land on orders' bottom edge, offset per source, so lines don't overlap
    by = b["y"] + b["h"]
    return [
        {"x1": ax, "y1": ay, "x2": ax, "y2": DIP_DOWN_Y, "amber": True},
        {"x1": ax, "y1": DIP_DOWN_Y, "x2": bx, "y2": DIP_DOWN_Y, "amber": True},
        {"x1": bx, "y1": DIP_DOWN_Y, "x2": bx, "y2": by, "amber": True},
    ]


for src in ["payments", "shipments", "notifications"]:
    edges += dip_down(src, "orders")

# bottom-row -> top-row: route through the clear lane between the two rows
# (top row ends at TOP_Y+TOP_H; bottom row starts at BOT_Y — the gap between
# them has no boxes in it).
LANE_Y = TOP_Y + TOP_H + (BOT_Y - (TOP_Y + TOP_H)) / 2


def dip_up(a_name, b_name):
    a, b = bottom[a_name], all_tables[b_name]
    ax = a["x"] + a["w"] / 2
    ay = a["y"]
    bx = b["x"] + b["w"] / 2
    by = b["y"] + b["h"]
    return [
        {"x1": ax, "y1": ay, "x2": ax, "y2": LANE_Y, "amber": True},
        {"x1": ax, "y1": LANE_Y, "x2": bx, "y2": LANE_Y, "amber": True},
        {"x1": bx, "y1": LANE_Y, "x2": bx, "y2": by, "amber": True},
    ]


edges += dip_up("orders", "customers")
edges += dip_up("order_items", "inventory_items")
edges += dip_up("notifications", "customers")
edges += dip_up("reviews", "customers")
edges += dip_up("reviews", "inventory_items")

bands = [
    {"x": 20, "y": 70, "w": W - 40, "h": H - 100,
     "label": "PostgreSQL — one shared schema (V1__init_schema.sql, Flyway)", "fill": "#fafafa"},
]

notes = [
    {"x": W / 2, "y": 32, "text": "ch.08 — Shared schema: eight tables, one Postgres schema, nine cross-context foreign keys",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "SMELL[ch.18] (SMELLS.md #1): every FK below crosses a bounded-context ownership boundary, enforced only because everything shares one database",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from examples/00-monolith/.../db/migration/V1__init_schema.sql (reference/monolith-before) and SMELLS.md smell #1. Reused in ch.18.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "monolith-shared-schema-er", W, H,
    bands=bands,
    nodes=nodes,
    edges=edges,
    notes=notes,
)
