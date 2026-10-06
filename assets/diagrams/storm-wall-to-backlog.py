#!/usr/bin/env python3
"""ch.12 figure: storm-wall-to-backlog -- "From the wall to the backlog": the
four event boundaries this chapter's process-level storm found, each mapped
to the extraction chapter that cuts it and the mechanism that chapter uses.

Rows are quoted directly from `_docs/12-event-storming.md` ("From the wall to
the backlog"): "the OrderPlaced -> StockReserved boundary is inventory's seam,
cut with CDC backfill and a decomposed database in Chapter 19; the
StockReserved -> PaymentCaptured/PaymentDeclined boundary is payment's seam,
cut as a choreographed saga in Chapter 23 ... the PaymentCaptured ->
ShipmentDispatched boundary is shipping's seam, cut in Chapter 24 as an
orchestrated saga using the Camel Saga EIP ... and the ShipmentDispatched ->
OrderConfirmed/NotificationSent boundary is notification's seam, cut first,
in Chapter 17, as a transactional outbox feeding an asynchronous consumer."

The footnote on OrderService/ch.26 and the ch.16 ACL precondition are drawn
from the same section and from "The god OrderService itself deserves one
more look..." -- no mechanism is claimed for ch.26 here because the chapter
doesn't name one; it only explains why that extraction has to come last.
The ch.13 pointer is the chapter's own closing hand-off ("the wall only
answers *where* to cut; *in what order* is Chapter 13's question").
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 900

COL_A_X, COL_A_W = 50, 520
COL_B_X, COL_B_W = 640, 220
COL_C_X, COL_C_W = 930, 620
ROW_Y0, ROW_H, GUTTER = 150, 110, 24

ROWS = [
    ("OrderPlaced -> StockReserved", "inventory's seam",
     "ch.19", "Inventory",
     "CDC backfill", "+ a decomposed database"),
    ("StockReserved -> PaymentCaptured / PaymentDeclined", "payment's seam",
     "ch.23", "Payment",
     "Choreographed saga", "policy becomes a Kafka consumer on the payment.captured sibling topic"),
    ("PaymentCaptured -> ShipmentDispatched", "shipping's seam",
     "ch.24", "Shipping",
     "Orchestrated saga", "Camel Saga EIP -- one coordinating definition, not peer-to-peer events"),
    ("ShipmentDispatched -> OrderConfirmed / NotificationSent", "notification's seam -- cut first",
     "ch.17", "Notification",
     "Transactional outbox", "feeding an asynchronous consumer"),
]

nodes = []
edges = []

for i, (boundary, seam_note, chnum, ctx, mech, mech_detail) in enumerate(ROWS):
    y = ROW_Y0 + i * (ROW_H + GUTTER)
    a = node(COL_A_X, y, COL_A_W, ROW_H, [boundary, seam_note], style="sub")
    b = node(COL_B_X, y, COL_B_W, ROW_H, [chnum, ctx], style="accent")
    c = node(COL_C_X, y, COL_C_W, ROW_H, [mech, mech_detail], style="box")
    nodes += [a, b, c]
    edges.append(connect(a, b))
    edges.append(connect(b, c))

# ---- footnote: OrderService / ch.26 -- extracted last, no single mechanism --
foot = node(COL_A_X, ROW_Y0 + 4 * (ROW_H + GUTTER) + 10, COL_A_W + (COL_B_X - COL_A_X - COL_A_W) + COL_B_W,
            90,
            ["OrderService itself -- ch.26, cut last",
             "issues every policy's command above; waits until all four boundaries already fire events"],
            style="ghost")
nodes.append(foot)

notes = [
    {"x": W / 2, "y": 32, "text": "ch.12 -- From the storm wall to the extraction backlog",
     "anchor": "middle", "bold": True, "size": 18},
    {"x": W / 2, "y": 58,
     "text": "every boundary this chapter's wall found, mapped to the chapter that cuts it and the mechanism it uses",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": COL_A_X, "y": ROW_Y0 - 16, "text": "Event boundary (the storm wall)", "anchor": "start", "bold": True, "size": 12.5},
    {"x": COL_B_X, "y": ROW_Y0 - 16, "text": "Extraction chapter", "anchor": "start", "bold": True, "size": 12.5},
    {"x": COL_C_X, "y": ROW_Y0 - 16, "text": "Mechanism", "anchor": "start", "bold": True, "size": 12.5},

    {"x": COL_A_X, "y": foot["y"] + foot["h"] + 26,
     "text": "ch.16's anti-corruption layer is the precondition at every row above: an event can't carry "
             "contract-shaped data until it exists (Smell 5).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": COL_A_X, "y": foot["y"] + foot["h"] + 44,
     "text": "This wall answers *where* to cut; *in what order* is ch.13's question (coupling analysis), not this figure's.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": COL_A_X, "y": H - 16,
     "text": "Sourced from _docs/12-event-storming.md (\"From the wall to the backlog\"), citing build-plan.md Section E.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "storm-wall-to-backlog", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
