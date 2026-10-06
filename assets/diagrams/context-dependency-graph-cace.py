#!/usr/bin/env python3
"""ch.13 figure: context-dependency-graph-cace -- the six contexts' current
dependency graph, each node annotated with the afferent/efferent coupling
(Ca/Ce) the chapter computes for it, resolving into the extraction order
build-plan.md Section E already runs.

TOP band -- the dependency graph as it exists in examples/00-monolith/
today: OrderService calls into inventory, payment, shipping, and
notification (its four efferent edges -- the god-service orchestration
coupling, SMELL[ch.26]), while payment, shipping ("Shipment"), and
notification each carry a direct order_id foreign key back into order
(order's afferent edges). Review has neither -- "no runtime dependency on
order/inventory/payment/shipping/notification," Chapter 9's sixth,
already-cured smell. The one dashed edge (shipping -> payment) is not part
of today's graph -- the chapter names it explicitly as a dependency
shipping "will gain... once payment is extracted."

BOTTOM band -- the same six contexts sorted by that score into the order
build-plan.md Section E runs: review (ch.15) -> notification (ch.17) ->
inventory (ch.19) -> payment (ch.23) -> shipping (ch.24) -> order (ch.26),
lowest Ca/Ce first, highest last.

Ca/Ce values and every edge are quoted from
_docs/13-coupling-and-modular-monolith.md ("Afferent and efferent coupling:
the metric that produces a ranking"). No codenames; generic/public names
only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1300, 900

# ============================================================================
# TOP band -- current dependency graph, Ca/Ce per context
# ============================================================================
band_top = {"x": 20, "y": 60, "w": 1260, "h": 480,
            "label": "Dependency graph, current monolith -- Ca/Ce per context",
            "fill": "#fafafa"}

review = node(560, 90, 200, 80,
              ["Review", "Ca: ~0 (nothing calls in)", "Ce: ~0 (no runtime dep)"], style="ghost")

order_n = node(560, 260, 200, 120,
               ["Order", "Ca: highest (3 inbound FKs)", "Ce: highest = 4 (injects 4 services)"], style="ink")
inventory = node(160, 260, 240, 120,
                 ["Inventory", "Ca: moderate (call + FK join)", "Ce: ~0"], style="box")
notification = node(900, 260, 240, 120,
                     ["Notification", "Ca: low (1 inbound call)", "Ce: ~0"], style="box")
payment = node(160, 420, 240, 100,
               ["Payment", "Ca: moderate (1 inbound call)", "Ce: ~0 today"], style="box")
shipping = node(900, 420, 240, 100,
                ["Shipping", "Ca: moderate (1 inbound call)", "Ce: ~0 today; +1 post-ch.23"], style="box")

top_nodes = [review, order_n, inventory, notification, payment, shipping]
top_edges = [
    connect(order_n, inventory, amber=True, label="OrderService call + OrderItem FK", lx=-24, ly=-14),
    connect(order_n, payment, amber=True, label="OrderService.charge()", ly=-16),
    connect(order_n, shipping, amber=True, label="OrderService.dispatch()", ly=-16),
    connect(order_n, notification, amber=True, label="notify() call (Smell 4, sync)", ly=-16),
    connect(payment, order_n, label="Payment.order_id FK", ly=18),
    connect(shipping, order_n, label="Shipment.order_id FK", ly=18),
    connect(notification, order_n, label="Notification.order_id FK", ly=18),
    connect(shipping, payment, dashed=True, label="future -- after payment extracted (ch.23)"),
]

# ============================================================================
# BOTTOM band -- resolved extraction order
# ============================================================================
band_bottom = {"x": 20, "y": 560, "w": 1260, "h": 260,
               "label": "Resolved extraction order -- build-plan.md Section E, lowest coupling first",
               "fill": "#eaf4ec"}

SEQ = [
    ("1. Review -- ch.15", "Ca~ 0, Ce~ 0 -- lowest score", "ghost"),
    ("2. Notification -- ch.17", "low Ca, low Ce", "box"),
    ("3. Inventory -- ch.19", "moderate Ca, Ce~ 0", "box"),
    ("4. Payment -- ch.23", "moderate Ca, Ce~ 0", "box"),
    ("5. Shipping -- ch.24", "moderate Ca, Ce~ 0", "box"),
    ("6. Order -- ch.26", "highest Ca, highest Ce", "ink"),
]
SEQ_W, SEQ_GUTTER, SEQ_X0, SEQ_Y, SEQ_H = 190, 16, 40, 640, 110

seq_nodes = []
for i, (title, detail, style) in enumerate(SEQ):
    x = SEQ_X0 + i * (SEQ_W + SEQ_GUTTER)
    n = node(x, SEQ_Y, SEQ_W, SEQ_H, [title, detail], style=style)
    seq_nodes.append(n)

bottom_edges = []
for i in range(len(seq_nodes) - 1):
    bottom_edges.append(connect(seq_nodes[i], seq_nodes[i + 1], amber=True))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.13 -- the dependency graph, scored by Ca/Ce, resolves into the extraction order",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 40, "y": 554,
     "text": "Order's own extraction can't complete until each of the other five has removed one of its four outbound edges -- which is why order goes last, not first.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/13-coupling-and-modular-monolith.md (\"Afferent and efferent coupling: the metric that produces a ranking\") and build-plan.md Section E.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "context-dependency-graph-cace", W, H,
    bands=[band_top, band_bottom],
    nodes=top_nodes + seq_nodes,
    edges=top_edges + bottom_edges,
    notes=notes,
)
