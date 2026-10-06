#!/usr/bin/env python3
"""ch.16 figure: leaked-entity-vs-stockdto — the chapter's "before and after:
what crosses the seam" comparison (per the diagram plan, the chapter's one
explicitly-combined [SIDE] figure).

LEFT — today: `order.OrderService#placeOrder` calls
`inventoryService.findBySkuOrThrow(sku)` as an ordinary in-process Java
method call and holds the result, `InventoryItem` — a JPA `@Entity` owned by
inventory's own `EntityManager`, mapped onto inventory's own table — for the
rest of the method. `OrderService` has a compile-time import of
`inventory.InventoryItem`: inventory's lazy-loading behavior, column names,
and migrations are now silently `order`'s problem too (Smell 5).

RIGHT — through the ACL: the same call goes through
`producerTemplate.requestBodyAndHeader("direct:stockFor", ...)` and returns
`StockDto` — a plain record carrying no persistence annotations, no
lazy-loading behavior, no column-name coupling. `OrderService` never imports
`InventoryItem` at all; inventory can rename columns, swap its persistence
provider, or move databases, and nothing on this side has to change.

Sourced verbatim from `_docs/16-content-based-routing-acl.md` ("Before and
after: what crosses the seam") and `examples/00-monolith/.../order/
OrderService.java#placeOrder` (the SMELL[ch.16] call site, unmodified). The
right-hand code is explicitly labeled illustrative in the chapter — the
shape ch.19 moves this lookup to, not code running today. No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 640

band_left = {"x": 20, "y": 60, "w": 650, "h": 540,
             "label": "TODAY — the leaked entity (order/OrderService.java)", "fill": "#fafafa"}
band_right = {"x": 730, "y": 60, "w": 650, "h": 540,
              "label": "THROUGH THE ACL — the translated contract (illustrative, ch.19)", "fill": "#eaf4ec"}

# ---- LEFT: the raw @Entity crossing the seam -------------------------------
order_l = node(50, 120, 260, 90,
               ["order.OrderService", "placeOrder()"], style="user")
call_l = node(50, 250, 260, 100,
              ["inventoryService.findBySkuOrThrow(sku)", "ordinary Java method call —",
               "same JVM, same process"], style="box")
entity_l = node(50, 400, 260, 110,
                ["InventoryItem", "@Entity — inventory's own", "EntityManager + table mapping"],
                style="ink")

left_nodes = [order_l, call_l, entity_l]
left_edges = [
    connect(order_l, call_l),
    connect(call_l, entity_l, amber=True, label="compile-time import"),
]

left_note1 = {"x": 50, "y": 545,
              "text": "OrderService depends, at compile time, on inventory's own",
              "anchor": "start", "size": 11, "color": "#555555"}
left_note2 = {"x": 50, "y": 561,
              "text": "persistence class — lazy-loading, column names, and migrations",
              "anchor": "start", "size": 11, "color": "#555555"}
left_note3 = {"x": 50, "y": 577,
              "text": "all become order's problem too (Smell 5).",
              "anchor": "start", "size": 11, "color": "#555555"}

# ---- RIGHT: the StockDto contract crossing the seam ------------------------
order_r = node(760, 120, 260, 90,
               ["order.OrderService", "placeOrder() — ch.19 shape"], style="user")
call_r = node(760, 250, 260, 100,
              ["producerTemplate.requestBodyAndHeader(", "\"direct:stockFor\", sku)",
               "seam address, not backend address"], style="accent")
dto_r = node(760, 400, 260, 110,
             ["StockDto", "plain record — no persistence", "annotations, no column coupling"],
             style="accent")

# the entity it never imports, called out as absent — mirrors the
# checkout-sync-async-contract.py "removed" idiom
no_entity_r = node(1040, 400, 310, 110,
                    ["InventoryItem — never imported", "no compile-time coupling to",
                     "inventory's persistence model"],
                    style="ghost")

right_nodes = [order_r, call_r, dto_r, no_entity_r]
right_edges = [
    connect(order_r, call_r),
    connect(call_r, dto_r, amber=True, label="InventoryAclRoute resolves + translates"),
    connect(order_r, no_entity_r, dashed=True, label="no dependency"),
]

right_note1 = {"x": 760, "y": 545,
               "text": "Inventory can rename columns, swap its persistence provider,",
               "anchor": "start", "size": 11, "color": "#555555"}
right_note2 = {"x": 760, "y": 561,
               "text": "or move databases entirely, and nothing here has to change —",
               "anchor": "start", "size": 11, "color": "#555555"}
right_note3 = {"x": 760, "y": 577,
               "text": "this side never saw the thing that changed.",
               "anchor": "start", "size": 11, "color": "#555555"}

transform_edge = {"x1": 670, "y1": 300, "x2": 730, "y2": 300, "label": "ch.16's ACL", "amber": True}

notes = [
    {"x": W / 2, "y": 32, "text": "ch.16 — before and after: what type does the caller actually hold after a stock lookup?",
     "anchor": "middle", "bold": True, "size": 16},

    left_note1, left_note2, left_note3,
    right_note1, right_note2, right_note3,

    {"x": 40, "y": H - 14,
     "text": "Sourced from _docs/16-content-based-routing-acl.md (\"Before and after: what crosses the seam\") and "
             "examples/00-monolith/.../order/OrderService.java#placeOrder (SMELL[ch.16], unmodified).",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "leaked-entity-vs-stockdto", W, H,
    bands=[band_left, band_right],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges + [transform_edge],
    notes=notes,
)
