#!/usr/bin/env python3
"""ch.24 figure: orchestration-vs-choreography — the chapter's load-bearing
teaching contrast, side by side on this one codebase.

LEFT: ch.23's payment saga is CHOREOGRAPHED. There is no central box: the
payment service's `OrderPlacedConsumer`/`PaymentService` and the monolith's
`OrderSagaListener` each independently subscribe to Kafka topics and decide,
on their own, what their own reaction means for the order. Control is
EMERGENT — it only exists as the sum of every service's independent
reactions; there is no single artifact you can read top-to-bottom to learn
"the saga."

RIGHT: ch.24's shipping saga is ORCHESTRATED. A single Camel Saga EIP
coordinator (`InMemorySagaService`, `ShipmentSagaRoute`'s `.saga()` block)
explicitly sequences enrich -> dispatch -> book-carrier -> emit and owns the
ONE registered compensating action (`direct:ship-compensate`). Control is
CENTRALIZED and VISIBLE in one place — the route reads top-to-bottom as the
whole saga. The monolith still reacts to the OUTCOME
(`shipment.dispatched`/`shipment.failed`), but it never decides what
fulfilment step runs next — that decision lives entirely in the coordinator.

Echoes the reference architecture's orchestration-vs-choreography framing,
re-grounded in this book's own two sagas (ch.23 choreographed payment vs
ch.24 orchestrated shipping) rather than a third example.

Sourced from: examples/05-payment-service/.../{OrderPlacedConsumer,
PaymentService}.java, examples/00-monolith/.../order/OrderSagaListener.java
(ch.23 choreography), examples/06-shipping-service/.../{ShipmentSagaRoute,
ShipmentSagaSteps,ShippingService}.java (ch.24 orchestration), and
_plans/iterations/shipping-plan.md (S11). No codenames; generic/public names
only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 820

band_choreo = {"x": 20, "y": 60, "w": 760, "h": 700,
               "label": "CHOREOGRAPHY — ch.23 payment (no central coordinator)", "fill": "#fafafa"}
band_orch = {"x": 820, "y": 60, "w": 760, "h": 700,
             "label": "ORCHESTRATION — ch.24 shipping (one Camel Saga coordinator)", "fill": "#eaf4ec"}

# ---- LEFT: choreography -----------------------------------------------------
kafkaL = node(60, 110, 300, 70, ["Kafka", "order.placed / payment.captured / payment.declined"], style="ink")
paymentSvcL = node(60, 230, 300, 90,
                    ["Payment service", "OrderPlacedConsumer reacts,", "emits payment.captured/declined"],
                    style="box")
orderListenerL = node(420, 350, 300, 90,
                       ["Monolith: OrderSagaListener", "reacts independently,", "confirms or compensates"],
                       style="box")
noCoordL = node(230, 500, 300, 90,
                 ["NO COORDINATOR", "control is EMERGENT — no process", "owns the sequence top-to-bottom"],
                 style="ghost")

choreo_nodes = [kafkaL, paymentSvcL, orderListenerL, noCoordL]
choreo_edges = [
    connect(kafkaL, paymentSvcL, label="@Incoming — reacts"),
    connect(paymentSvcL, kafkaL, label="emits outcome", dashed=True, ly=18),
    connect(kafkaL, orderListenerL, label="@KafkaListener — reacts"),
]

choreo_note = {"x": 400, "y": 650,
               "text": "each service decides, on its own, what its reaction means for the order —",
               "anchor": "middle", "size": 11.5, "color": "#555555"}
choreo_note2 = {"x": 400, "y": 666,
                "text": "no single artifact reads top-to-bottom as \"the saga\"",
                "anchor": "middle", "size": 11.5, "color": "#555555"}

# ---- RIGHT: orchestration ----------------------------------------------------
coordinatorR = node(860, 150, 300, 110,
                     ["Camel Saga EIP coordinator", "InMemorySagaService",
                      ".saga() — sequences steps + owns compensation"],
                     style="ink")

step1R = node(1240, 110, 180, 50, ["① enrich"], style="box")
step2R = node(1240, 170, 180, 50, ["② dispatch"], style="box")
step3R = node(1240, 230, 180, 50, ["③ book-carrier"], style="accent")
step4R = node(1240, 290, 180, 50, ["④ emit"], style="box")

monolithR = node(860, 370, 300, 90,
                  ["Monolith", "reacts only to the OUTCOME", "(dispatched/failed) — never picks the next step"],
                  style="box")

orch_nodes = [coordinatorR, step1R, step2R, step3R, step4R, monolithR]
orch_edges = [
    connect(coordinatorR, step1R, label="invokes in order", amber=True),
    connect(step1R, step2R),
    connect(step2R, step3R),
    connect(step3R, step4R),
    connect(coordinatorR, monolithR, label="emits outcome event only", dashed=True),
]

orch_note = {"x": 1200, "y": 650,
             "text": "ONE visible place owns the sequence top-to-bottom —",
             "anchor": "middle", "size": 11.5, "color": "#2f5f3d", "bold": True}
orch_note2 = {"x": 1200, "y": 666,
              "text": "reading the route tells you the whole story",
              "anchor": "middle", "size": 11.5, "color": "#2f5f3d", "bold": True}

transform_edge = {"x1": 780, "y1": 200, "x2": 820, "y2": 200, "label": "ch.24", "amber": True}

notes = [
    {"x": W / 2, "y": 32, "text": "orchestration vs. choreography, made concrete on this one codebase",
     "anchor": "middle", "bold": True, "size": 17},
    choreo_note, choreo_note2, orch_note, orch_note2,
    {"x": 40, "y": 800,
     "text": "Sourced from examples/05-payment-service/.../{OrderPlacedConsumer,PaymentService}.java, "
             "examples/00-monolith/.../order/OrderSagaListener.java (ch.23), and "
             "examples/06-shipping-service/.../{ShipmentSagaRoute,ShipmentSagaSteps}.java (ch.24).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "orchestration-vs-choreography", W, H,
    bands=[band_choreo, band_orch],
    nodes=choreo_nodes + orch_nodes,
    edges=choreo_edges + orch_edges + [transform_edge],
    notes=notes,
)
