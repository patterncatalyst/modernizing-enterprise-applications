#!/usr/bin/env python3
"""ch.13 figure: coupling-ladder -- the Constantine coupling taxonomy, walked
worst to best, with every rung pinned to its live example in
examples/00-monolith/ (the two rungs the monolith hasn't reached yet --
message coupling and API/data-structure coupling -- are the target
architecture ch.14 onward builds, not current monolith code).

Rung order and definitions, plus every monolith example, are quoted
directly from _docs/13-coupling-and-modular-monolith.md ("The Constantine
coupling taxonomy, applied to the monolith"):
  1. Content coupling (worst) -- OrderService#placeOrder reading a live,
     JPA-managed InventoryItem straight from
     InventoryService#findBySkuOrThrow (priceCents, sku) -- Smell 5.
  2. Common coupling -- the single shared Postgres schema every context
     (order, inventory, payment, shipping, notification, customers) reads
     and writes with no enforced ownership boundary -- Smell 1.
  3. Control coupling -- OrderCreate.paymentMethod() threaded into
     PaymentService#charge, selecting an approve/decline code path.
  4. Stamp coupling -- OrderItem's constructor taking the full
     InventoryItem entity for the three fields (sku, name, price) it
     actually needs.
  5. Data coupling -- OrderService.listAll() returning List<OrderDto> via
     toDto(), not raw Order entities -- already done right.
  6. Message coupling and 7. API/data-structure coupling (best) -- no
     monolith example; the chapter names these explicitly as "what the
     target architecture looks like once Chapters 14 through 26 are done."

No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1100, 1120

RUNGS = [
    ("1. Content coupling (worst)",
     "reaches inside, reads/writes internals directly",
     ["Monolith example -- Smell 5",
      "OrderService#placeOrder reads a live InventoryItem",
      "(priceCents, sku) from findBySkuOrThrow directly",
      "falls short of textbook only via Java access modifiers"],
     "box", "sub"),
    ("2. Common coupling",
     "shares one global/shared data store",
     ["Monolith example -- Smell 1",
      "order, inventory, payment, shipping, notification +",
      "customers: one shared Postgres schema, no owner"],
     "box", "sub"),
    ("3. Control coupling",
     "passes a flag that dictates the callee's branching",
     ["Monolith example",
      "OrderCreate.paymentMethod() threaded into",
      "PaymentService#charge -- selects approve/decline"],
     "box", "sub"),
    ("4. Stamp coupling",
     "passes a whole structure, receiver uses part",
     ["Monolith example",
      "OrderItem ctor takes the full InventoryItem entity",
      "for 3 fields (sku, name, price) it actually needs"],
     "box", "sub"),
    ("5. Data coupling",
     "passes only simple, purpose-built parameters",
     ["Monolith example -- already done right",
      "OrderService.listAll() returns List<OrderDto>",
      "via toDto(), not raw Order entities"],
     "accent", "accent"),
    ("6. Message coupling",
     "interaction only through message passing",
     ["No live example",
      "target architecture after ch.14-26 --",
      "interaction only through message passing"],
     "ghost", "ghost"),
    ("7. API / data-structure coupling (best)",
     "well-defined contract at the boundary",
     ["No live example",
      "target architecture after ch.14-26 --",
      "well-defined contract types at the boundary"],
     "ghost", "ghost"),
]

LEFT_X, LEFT_W = 50, 330
RIGHT_X, RIGHT_W = 410, 620
ROW_Y0, ROW_H, GUTTER = 100, 120, 16

rung_nodes = []
example_nodes = []
nodes = []
edges = []
for i, (title, defn, example_lines, rstyle, estyle) in enumerate(RUNGS):
    y = ROW_Y0 + i * (ROW_H + GUTTER)
    rn = node(LEFT_X, y, LEFT_W, ROW_H, [title, defn], style=rstyle)
    en = node(RIGHT_X, y, RIGHT_W, ROW_H, example_lines, style=estyle)
    rung_nodes.append(rn)
    example_nodes.append(en)
    nodes.append(rn)
    nodes.append(en)
    edges.append(connect(rn, en))

# ladder chain down the left column, worst (top) to best (bottom)
for i in range(len(rung_nodes) - 1):
    kw = {}
    if i == 0:
        kw = {"amber": True, "label": "Constantine's ladder: worst to best"}
    if i == 4:
        kw = {"amber": True, "label": "no monolith example beyond this point"}
    edges.append(connect(rung_nodes[i], rung_nodes[i + 1], **kw))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.13 -- the Constantine coupling ladder, scored against the monolith",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "worst (content) at top, best (API/data-structure) at bottom -- five of seven rungs already have a live example",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": LEFT_X, "y": H - 40,
     "text": "Message and API/data-structure coupling have no monolith example today -- they're what the extracted services (ch.14-26) are built to reach.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": LEFT_X, "y": H - 16,
     "text": "Sourced from _docs/13-coupling-and-modular-monolith.md (\"The Constantine coupling taxonomy, applied to the monolith\") and examples/00-monolith/SMELLS.md (Smells 1 and 5).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "coupling-ladder", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
