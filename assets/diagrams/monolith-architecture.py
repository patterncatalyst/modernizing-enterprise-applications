#!/usr/bin/env python3
"""ch.08 figure: monolith-architecture — the reference monolith "before" picture.

One Spring Boot deployable (JDK 25) containing six bounded-context packages
(order, inventory, payment, shipping, notification, review), each drawn as a
controller -> service -> repository stack, all built against ONE shared
PostgreSQL schema (Flyway V1 + V2). Sourced from
`examples/00-monolith/SMELLS.md` and the package layout under
`examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/`.

Smells drawn as the seams this book later cuts:
  - SMELL #2/#5 (ch.26/ch.16): the "god" OrderService reaching directly into
    inventory/payment/shipping's services (bypassing any contract/ACL).
  - SMELL #1 (ch.18): cross-context FK joins living in the one shared schema
    (order_items -> inventory_items, payments/shipments/notifications ->
    orders, reviews -> customers + inventory_items, orders -> customers).
Review is annotated as the first context extracted (ch.15) — this figure
still draws it as designed (all six contexts), per ch.08's own framing of
the monolith "as designed" before that first cut.

No tool/product codenames; only generic names (Spring Boot, PostgreSQL) and
the book's own context names are used.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1460, 700

# ---- context columns --------------------------------------------------
COL_W, GUTTER = 200, 24
COL_X0 = 40
CONTEXTS = [
    ("order", "OrderController", "OrderService", "OrderRepository", "box"),
    ("inventory", "InventoryController", "InventoryService", "InventoryRepository", "box"),
    ("payment", "PaymentController", "PaymentService", "PaymentRepository", "box"),
    ("shipping", "ShippingController", "ShippingService", "ShippingRepository", "box"),
    ("notification", "NotificationController", "NotificationService", "NotificationRepository", "box"),
    ("review", "ReviewController", "ReviewService", "ReviewRepository", "accent"),
]

CTRL_Y, ROW_H = 110, 55
SVC_Y = 180
REPO_Y = 250

cols = {}
for i, (ctx, ctrl_lbl, svc_lbl, repo_lbl, style) in enumerate(CONTEXTS):
    x = COL_X0 + i * (COL_W + GUTTER)
    extra = ["(SMELL[ch.26]: god service)"] if ctx == "order" else []
    ctrl = node(x, CTRL_Y, COL_W, ROW_H, [ctrl_lbl, f"{ctx} context"], style=style)
    svc = node(x, SVC_Y, COL_W, ROW_H, [svc_lbl] + extra, style=style)
    repo = node(x, REPO_Y, COL_W, ROW_H, [repo_lbl], style=style)
    cols[ctx] = {"x": x, "ctrl": ctrl, "svc": svc, "repo": repo}

nodes = []
edges = []
for ctx, c in cols.items():
    nodes += [c["ctrl"], c["svc"], c["repo"]]
    edges += [connect(c["ctrl"], c["svc"]), connect(c["svc"], c["repo"])]

# ---- god-service smell: OrderService reaches directly into inventory/   --
# payment/shipping/notification services (SMELL[ch.26]), bypassing any     --
# contract/ACL (SMELL[ch.16]). Drawn as one highlighted "bus": a drop from  --
# OrderService's right edge into a lane below the context stacks, a trunk  --
# line across, and a branch rising into each target service's left edge   --
# -- so the arrows never cross through an intervening box.
BUS_Y = 400
order_svc = cols["order"]["svc"]
order_edge_x = order_svc["x"] + order_svc["w"]
order_mid_y = order_svc["y"] + order_svc["h"] / 2

# order -> inventory is an adjacent column: a direct line, no detour needed.
inv_svc = cols["inventory"]["svc"]
edges.append(connect(order_svc, inv_svc, amber=True,
                      label="direct call, no ACL (ch.16)"))

bus_targets = ["payment", "shipping", "notification"]
farthest_x = cols[bus_targets[-1]]["svc"]["x"]
edges.append({"x1": order_edge_x, "y1": order_mid_y, "x2": order_edge_x, "y2": BUS_Y, "amber": True})
edges.append({"x1": order_edge_x, "y1": BUS_Y, "x2": farthest_x, "y2": BUS_Y, "amber": True,
              "label": "god service -- direct calls into other contexts (ch.26)"})
for ctx in bus_targets:
    svc = cols[ctx]["svc"]
    bx = svc["x"]
    by = svc["y"] + svc["h"] / 2
    edges.append({"x1": bx, "y1": BUS_Y, "x2": bx, "y2": by, "amber": True})

# ---- PostgreSQL: one shared schema -------------------------------------
PG_BAND_Y, PG_BAND_H = 440, 150
pg_band = {"x": 20, "y": PG_BAND_Y, "w": W - 40, "h": PG_BAND_H,
           "label": "PostgreSQL -- one shared schema (Flyway V1 + V2)", "fill": "#fafafa"}

TABLE_Y, TABLE_H = 470, 50
TABLE_W, TABLE_GUTTER = 150, 12
TABLE_NAMES = ["customers", "orders", "order_items", "inventory_items",
               "payments", "shipments", "notifications", "reviews"]
tables = {}
for i, t in enumerate(TABLE_NAMES):
    x = 40 + i * (TABLE_W + TABLE_GUTTER)
    tables[t] = node(x, TABLE_Y, TABLE_W, TABLE_H, [t], style="sub")

table_nodes = list(tables.values())

# repo -> table ownership connectors (plain, diagonal, clear lane between
# the context stacks and the table row -- no box crossings possible there).
repo_links = [
    ("order", "orders"), ("order", "order_items"),
    ("inventory", "inventory_items"),
    ("payment", "payments"),
    ("shipping", "shipments"),
    ("notification", "notifications"),
    ("review", "reviews"),
]
repo_edges = [connect(cols[ctx]["repo"], tables[t]) for ctx, t in repo_links]

# cross-context FK-join smell (SMELL[ch.18]): adjacent pairs get a direct
# highlighted line; non-adjacent pairs dip into a sub-lane beneath the
# table row so the line never crosses an intervening table box.
FK_DIP_Y = TABLE_Y + TABLE_H + 25


def fk_direct(a_name, b_name):
    return connect(tables[a_name], tables[b_name], amber=True)


def fk_dip(a_name, b_name):
    a, b = tables[a_name], tables[b_name]
    ax, ay = a["x"] + a["w"] / 2, a["y"] + a["h"]
    bx, by = b["x"] + b["w"] / 2, b["y"] + b["h"]
    return [
        {"x1": ax, "y1": ay, "x2": ax, "y2": FK_DIP_Y, "amber": True},
        {"x1": ax, "y1": FK_DIP_Y, "x2": bx, "y2": FK_DIP_Y, "amber": True},
        {"x1": bx, "y1": FK_DIP_Y, "x2": bx, "y2": by, "amber": True},
    ]


fk_edges = []
fk_edges.append(fk_direct("order_items", "inventory_items"))
fk_edges.append(fk_direct("orders", "customers"))
fk_edges += fk_dip("payments", "orders")
fk_edges += fk_dip("shipments", "orders")
fk_edges += fk_dip("notifications", "orders")
fk_edges += fk_dip("reviews", "customers")
fk_edges += fk_dip("reviews", "inventory_items")

nodes += table_nodes
edges += repo_edges
edges += fk_edges

bands = [
    {"x": 20, "y": 70, "w": W - 40, "h": 310,
     "label": "Spring Boot monolith -- one deployable JAR (JDK 25)", "fill": "#ffffff"},
    pg_band,
]

notes = [
    {"x": W / 2, "y": 30, "text": "ch.08 -- The reference monolith (before): six bounded contexts, one deployable, one shared schema",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56, "text": "one REST API surface, documented by springdoc OpenAPI",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": W - 40, "y": 420,
     "text": "SMELL[ch.22]/[ch.23]/[ch.24]: OrderService#placeOrder wraps inventory + order + payment + shipment + "
             "notification in ONE @Transactional spanning five contexts (ACID -> ACD).",
     "anchor": "end", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": 598,
     "text": "SMELL[ch.18]: FK joins cross bounded-context boundaries freely inside the one shared schema -- "
             "order_items->inventory_items, payments/shipments/notifications->orders, reviews->customers+inventory_items, orders->customers.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 614,
     "text": "common.Customer is a shared-kernel entity (not its own bounded context) FK'd directly from order, notification, and review.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": cols["review"]["x"] + COL_W / 2, "y": 95,
     "text": "review: first context extracted (ch.15)", "anchor": "middle", "bold": True, "size": 11.5,
     "color": "#2f5f3d"},
    {"x": cols["review"]["x"] + COL_W / 2, "y": REPO_Y + ROW_H + 18,
     "text": "SMELL[ch.15]: REST-only, no runtime collaborator -- yet governed by the",
     "anchor": "middle", "size": 9.5, "color": "#555555"},
    {"x": cols["review"]["x"] + COL_W / 2, "y": REPO_Y + ROW_H + 30,
     "text": "monolith's one global SecurityFilterChain alongside every other context.",
     "anchor": "middle", "size": 9.5, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "SMELL[ch.17]: the order-confirmation notification is sent synchronously, in-process, inside the checkout transaction.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "monolith-architecture", W, H,
    bands=bands,
    nodes=nodes,
    edges=edges,
    notes=notes,
)
