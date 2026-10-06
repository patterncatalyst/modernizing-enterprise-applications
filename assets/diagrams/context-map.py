#!/usr/bin/env python3
"""ch.11 figure: context-map — the six bounded contexts (order, inventory,
payment, shipping, notification, review) drawn as a DDD context map, with
the relationship TYPE labelled on each edge exactly as
`_docs/11-ddd-and-hexagonal.md` frames it.

order -> inventory: conformist today ("order.OrderService accepts
inventory's internal representation wholesale, with no negotiation at
all"), with no anti-corruption layer at the seam ("a missing
anti-corruption layer (ACL)") even though inventory already builds the
contract it would need (`StockDto`) and never hands it to OrderService. The
relationship the chapter says this seam SHOULD have is customer/supplier
("order is the downstream consumer of inventory's stock data, inventory is
the upstream supplier... a stable, versioned contract").

order -> payment / shipping / notification: direct, synchronous, in-process
calls inside placeOrder's single transaction -- "OrderService is
constructor-injected not just with its own repository but with
InventoryService, PaymentService, ShippingService, and NotificationService
directly -- four other contexts' concrete implementations, not four ports."
The chapter does not apply one of the four named DDD context-mapping
patterns to these three edges specifically, so they are labelled as what
the chapter actually calls them: direct calls nobody negotiated.

All six -> PostgreSQL: "one Postgres schema... is a shared kernel with no
edges... the entire database treated as if it belonged equally to all six
contexts at once." Review's edge is quoted differently (read-only lookups
against customers/inventory_items), since the chapter is explicit that
review has zero outbound edges to its sibling contexts -- "a context with
no outbound edges to its siblings," Smell 6, already cured (ch.15).

Sourced from `_docs/11-ddd-and-hexagonal.md` ("Context mapping: naming the
relationship nobody decided", "Why Review could leave first"),
`examples/00-monolith/.../inventory/InventoryService.java#findBySkuOrThrow`,
`.../order/OrderService.java#placeOrder`. No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 620

# ---- the six context boxes, one row --------------------------------------
CONTEXTS = [
    ("order", ["order", "checkout aggregate -- 4 outbound edges"], "accent"),
    ("inventory", ["inventory", "stock per SKU -- conformed-to by order"], "box"),
    ("payment", ["payment", "charge capture -- called from placeOrder"], "box"),
    ("shipping", ["shipping", "dispatch -- called from placeOrder"], "box"),
    ("notification", ["notification", "confirmation msg -- called from placeOrder"], "box"),
    ("review", ["review", "a rating on a purchased SKU"], "ghost"),
]

COL_W, GUTTER, COL_X0, ROW_Y, ROW_H = 200, 20, 50, 200, 100
cols = {}
nodes = []
for i, (name, lines, style) in enumerate(CONTEXTS):
    x = COL_X0 + i * (COL_W + GUTTER)
    n = node(x, ROW_Y, COL_W, ROW_H, lines, style=style)
    cols[name] = n
    nodes.append(n)

order_n, inv_n = cols["order"], cols["inventory"]

# ---- the missing ACL, sitting above the order<->inventory seam -----------
acl_box = node(110, 108, 340, 62,
               ["Anti-corruption layer -- missing",
                "StockDto exists, unused by OrderService",
                "should be: customer/supplier (negotiated contract)"],
               style="ghost")
nodes.append(acl_box)

edges = []

# the central seam: conformist today, no ACL -- routed along the TOP edge of
# both boxes (not through their vertical center) so the label lands in the
# clear gap below the ACL callout instead of printing over either box's text
edges.append({"x1": order_n["x"] + order_n["w"], "y1": ROW_Y, "x2": inv_n["x"], "y2": ROW_Y,
              "amber": True, "label": "conformist -- raw entity, no ACL", "ly": -22})

# the ACL gap, drawn as a bridge from the callout box into both sides of the seam
edges.append(connect(acl_box, order_n, dashed=True))
edges.append(connect(acl_box, inv_n, dashed=True))

# order's three other outbound edges: direct synchronous calls, no port --
# routed below the row (bus pattern) so no line crosses an intervening box
BUS_Y = ROW_Y + ROW_H + 25
ox = order_n["x"] + order_n["w"]
order_mid_y = order_n["y"] + order_n["h"] / 2
bus_targets = ["payment", "shipping", "notification"]
farthest_x = cols[bus_targets[-1]]["x"]

edges.append({"x1": ox, "y1": order_mid_y, "x2": ox, "y2": BUS_Y, "amber": True})
edges.append({"x1": ox, "y1": BUS_Y, "x2": farthest_x, "y2": BUS_Y, "amber": True,
              "label": "direct synchronous call -- no port, same @Transactional"})
for name in bus_targets:
    t = cols[name]
    tx = t["x"] + t["w"] / 2
    ty = t["y"] + t["h"]
    edges.append({"x1": tx, "y1": BUS_Y, "x2": tx, "y2": ty, "amber": True})

# ---- the one shared Postgres schema: shared kernel with no edges ---------
PG_Y, PG_H = 410, 90
pg_band = {"x": 40, "y": PG_Y, "w": W - 80, "h": PG_H,
           "label": "PostgreSQL -- one shared schema (shared kernel, ungoverned -- no edges)",
           "fill": "#fafafa"}
pg_box = node(W / 2 - 220, PG_Y + 28, 440, 44,
              ["one schema -- every table joinable by any context's JPA mappings"], style="sub")
nodes.append(pg_box)

db_edges = []
for name, n in cols.items():
    if name == "review":
        e = connect(n, pg_box)
        e["label"] = "read-only lookups (customers, inventory_items)"
        db_edges.append(e)
    else:
        db_edges.append(connect(n, pg_box))

bands = [
    {"x": 20, "y": 70, "w": W - 40, "h": 300,
     "label": "Six bounded contexts -- edge = the context-mapping relationship that actually exists", "fill": "#ffffff"},
    pg_band,
]

review_n = cols["review"]
notes = [
    {"x": W / 2, "y": 32, "text": "ch.11 -- Context map: six bounded contexts, one relationship nobody decided",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "order->inventory is conformist with no ACL; order's other edges are direct and synchronous; one ungoverned shared-kernel schema sits under all six",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": review_n["x"] + review_n["w"] / 2, "y": ROW_Y - 12,
     "text": "zero outbound edges to siblings -- smell 6, cured (ch.15)",
     "anchor": "middle", "size": 10, "color": "#2f5f3d"},

    {"x": 40, "y": PG_Y + PG_H + 24,
     "text": "Shared kernel with no edges: nobody drew a narrow, governed slice of shared model -- the whole schema is treated as commonly",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": PG_Y + PG_H + 38,
     "text": "owned by all six contexts, because a foreign key is the cheapest way to relate two rows that happen to live in the same schema.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": H - 32,
     "text": "Sourced from _docs/11-ddd-and-hexagonal.md (\"Context mapping: naming the relationship nobody decided\", \"Why Review could leave first\").",
     "anchor": "start", "size": 10, "color": "#777777"},
    {"x": 40, "y": H - 16,
     "text": "Quoted code: examples/00-monolith/.../inventory/InventoryService.java#findBySkuOrThrow, .../order/OrderService.java#placeOrder.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "context-map", W, H,
    bands=bands,
    nodes=nodes,
    edges=edges + db_edges,
    notes=notes,
)
