#!/usr/bin/env python3
"""ch.28 figure: hand-duplicated-vs-registry-schema — the core teaching
contrast of "Contracts & the Service Registry": the same order.placed
contract authored by hand in three independent Java records today, versus
one Avro schema registered once in Apicurio and shared by all three sides.
A before/after side-by-side (the explicitly called-out combined [SIDE]
figure for this pair).

LEFT — today: order-service, payment-service, and notification-service each
author their own `OrderPlacedEvent` record against the same agreed JSON
shape (per DRQ-038, these are plain records, not shared code between Maven
reactors). order-service and payment-service each carry 7 fields, including
`paymentMethod`; notification-service's copy has only 6 — `paymentMethod`
was never added there. JSON binds by field name, so nothing on the wire or
at compile time catches the mismatch; a redelivered event simply has one
fewer field on that side.

RIGHT — ch.28, registry-governed: `order-placed-v1.avsc` is registered once
in Apicurio (auto-register from `examples/09-schema-registry-demo`'s
producer), generating the `OrderPlaced` SpecificRecord every side shares.
The registry, not three independently maintained files, is the contract of
record.

Sourced from examples/07-order-service/.../OrderPlacedEvent.java,
examples/05-payment-service/.../OrderPlacedEvent.java,
examples/03-notification-service/.../OrderPlacedEvent.java, and
examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 700

band_left = {"x": 20, "y": 60, "w": 650, "h": 560,
             "label": "Today — one contract, authored by hand three times", "fill": "#fafafa"}
band_right = {"x": 730, "y": 60, "w": 650, "h": 560,
              "label": "ch.28 — one schema, registered once, shared by all three", "fill": "#eaf4ec"}

# ---- LEFT: three independent hand-authored copies --------------------------
order_rec = node(50, 120, 590, 90,
                  ["order-service — OrderPlacedEvent (record)",
                   "7 fields, incl. paymentMethod"], style="box")
payment_rec = node(50, 240, 590, 90,
                    ["payment-service — OrderPlacedEvent (record)",
                     "own copy — 7 fields, incl. paymentMethod"], style="box")
notif_rec = node(50, 360, 590, 90,
                  ["notification-service — OrderPlacedEvent (record)",
                   "own copy — 6 fields, paymentMethod missing"], style="ink")
drift = node(50, 480, 590, 90,
             ["Drift: JSON binds by name only",
              "nothing on the wire or at compile time catches the mismatch"],
             style="ghost")

left_nodes = [order_rec, payment_rec, notif_rec, drift]
left_edges = [
    connect(order_rec, payment_rec, dashed=True, label="same contract, no shared code"),
    connect(payment_rec, notif_rec, dashed=True, label="same contract, no shared code"),
    connect(notif_rec, drift, amber=True, label="paymentMethod silently absent"),
]

# ---- RIGHT: one schema, registered once ------------------------------------
avsc = node(760, 120, 590, 90,
            ["order-placed-v1.avsc", "one schema definition"], style="box")
registry = node(760, 250, 590, 90,
                ["Apicurio Registry", "registers it once — the contract of record"], style="accent")
generated = node(760, 380, 590, 90,
                 ["OrderPlaced (SpecificRecord)", "generated at build time from the registered schema"],
                 style="accent")

usage_w = (590 - 40) // 3
u1x = 760
u2x = u1x + usage_w + 20
u3x = u2x + usage_w + 20
usage1 = node(u1x, 500, usage_w, 90, ["order-service", "shares the generated record"], style="sub")
usage2 = node(u2x, 500, usage_w, 90, ["payment-service", "shares the generated record"], style="sub")
usage3 = node(u3x, 500, usage_w, 90, ["notification-service", "shares the generated record"], style="sub")

right_nodes = [avsc, registry, generated, usage1, usage2, usage3]
right_edges = [
    connect(avsc, registry, label="register (auto-register on first send)"),
    connect(registry, generated, label="generates"),
    connect(generated, usage1),
    connect(generated, usage2),
    connect(generated, usage3),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.28 — the same order.placed contract: hand-duplicated today, registry-governed after",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 50, "y": 590,
     "text": "Three independently maintained files, meant to describe one event — nothing enforces that they still agree.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 760, "y": 610,
     "text": "One registered schema generates the type every side imports — one file to change.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 760, "y": 626,
     "text": "The registry checks compatibility before it will accept a change.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": H - 14,
     "text": "Sourced from examples/07-order-service, examples/05-payment-service, examples/03-notification-service "
             "(.../OrderPlacedEvent.java) and examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "hand-duplicated-vs-registry-schema", W, H,
    bands=[band_left, band_right],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges,
    notes=notes,
)
