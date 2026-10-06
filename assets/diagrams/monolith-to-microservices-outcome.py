#!/usr/bin/env python3
"""ch.00 figure: monolith-to-microservices-outcome -- the book's headline
before/after arc (justified side-by-side: this IS the required before/after
comparison, not an arbitrary merge of unrelated diagrams).

LEFT -- "before": one Spring Boot deployable (JDK 25) containing six
in-process bounded contexts (order, inventory, payment, shipping,
notification, review) sharing one PostgreSQL schema. The order-placement
flow reaches into inventory, payment, shipping, and notification inside one
ACID @Transactional checkout -- drawn as an amber bus, mirroring the
monolith-architecture.py god-service-bus technique so the lines never cross
a box -- per _docs/00-introduction.md ("The running example") and ch.08's
SMELLS.md (the one-@Transactional-spanning-five-contexts smell).

RIGHT -- "after": six independent Quarkus services, each owning its own
schema, plus the GraphQL gateway, all reached through one Camel REST edge
router; a Kafka event backbone carries the async/outbox and saga-coordination
traffic for notification, payment, shipping, and order. Ports and shape
mirror the project's own final topology (see
strangler-completes-final-topology.py / graphql-aggregation-gateway.py) --
same six services, same ports, no codenames.

Sourced from: _docs/00-introduction.md ("The central thesis", "The running
example"), assets/diagrams/monolith-architecture.py (the "before" smell),
assets/diagrams/strangler-completes-final-topology.py (the "after" topology).
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1560, 860

# ============================= LEFT: before ================================
outer_band_L = {"x": 40, "y": 70, "w": 710, "h": 680,
                "label": "Spring Boot monolith -- one deployable (JDK 25)", "fill": "#ffffff"}

order = node(70, 110, 200, 74, ["Order", "bounded context"], style="box")
inventory = node(290, 110, 200, 74, ["Inventory", "bounded context"], style="box")
payment = node(510, 110, 200, 74, ["Payment", "bounded context"], style="box")
shipping = node(70, 210, 200, 74, ["Shipping", "bounded context"], style="box")
notification = node(290, 210, 200, 74, ["Notification", "bounded context"], style="box")
review = node(510, 210, 200, 74, ["Review", "bounded context"], style="accent")

left_ctx_nodes = [order, inventory, payment, shipping, notification, review]

# ACID-transaction bus: order -> inventory, shipping are adjacent (direct);
# order -> payment, notification are not adjacent, so route through the
# gutter between the two rows (y=197) the same way monolith-architecture's
# god-service bus avoids crossing any box.
bus_y = 197
left_bus_edges = [
    connect(order, inventory, amber=True),
    connect(order, shipping, amber=True),
    {"x1": 170, "y1": 184, "x2": 170, "y2": bus_y, "amber": True},
    {"x1": 170, "y1": bus_y, "x2": 610, "y2": bus_y, "amber": True,
     "label": "one @Transactional checkout (ACID): order -> inventory, payment, shipping, notification"},
    {"x1": 390, "y1": bus_y, "x2": 390, "y2": 210, "amber": True},
    {"x1": 610, "y1": bus_y, "x2": 610, "y2": 184, "amber": True},
]

pg_band = {"x": 70, "y": 340, "w": 640, "h": 150, "label": "One shared PostgreSQL schema", "fill": "#fafafa"}
pg_tables = node(90, 372, 600, 90,
                  ["customers · orders · order_items · inventory_items",
                   "payments · shipments · notifications · reviews",
                   "one Flyway-managed schema, cross-context foreign keys"], style="sub")

left_nodes = left_ctx_nodes + [pg_tables]
left_edges = left_bus_edges

left_notes = [
    {"x": 395, "y": 745, "text": "review is REST-only -- no synchronous collaborator, outside the checkout transaction",
     "anchor": "middle", "size": 10.5, "color": "#555555"},
]

# ============================= RIGHT: after =================================
outer_band_R = {"x": 850, "y": 70, "w": 670, "h": 680,
                "label": "Six Quarkus services + GraphQL gateway, behind one Camel edge router", "fill": "#ffffff"}

router = node(870, 100, 220, 160, ["Camel REST edge router", ":8888 -- single entry point"], style="ink")
kafka = node(870, 300, 220, 160, ["Kafka", "event backbone -- outbox + saga coordination"], style="box")

rev_svc = node(1210, 95, 280, 70, ["Review service :8081", "owns review schema"], style="box")
notif_svc = node(1210, 182, 280, 70, ["Notification service :8083", "owns notification schema"], style="box")
inv_svc = node(1210, 269, 280, 70, ["Inventory service :8084 / :9004", "owns inventory schema"], style="box")
pay_svc = node(1210, 356, 280, 70, ["Payment service :8085", "owns payment schema"], style="box")
ship_svc = node(1210, 443, 280, 70, ["Shipping service :8088", "owns shipping schema"], style="box")
order_svc = node(1210, 530, 280, 70, ["Order service :8087", "owns order schema, CQRS"], style="accent")
gateway = node(1210, 617, 280, 70, ["GraphQL gateway :8090", "aggregates, owns no data"], style="accent")

right_stack = [rev_svc, notif_svc, inv_svc, pay_svc, ship_svc, order_svc, gateway]

router_edges = [connect(router, n) for n in right_stack[:-1]]
router_edges.append(connect(router, gateway, amber=True, label="/graphql"))

kafka_edges = [connect(kafka, n, amber=True, dashed=True) for n in (notif_svc, pay_svc, ship_svc, order_svc)]

right_nodes = [router, kafka] + right_stack
right_edges = router_edges + kafka_edges

right_notes = [
    {"x": 1350, "y": 745, "text": "events: outbox relay (notification, payment, shipping) + saga coordination (order)",
     "anchor": "middle", "size": 10.5, "color": "#555555"},
]

# ============================= divider =======================================
divider_edge = [{"x1": 760, "y1": 400, "x2": 840, "y2": 400, "amber": True}]
divider_notes = [
    {"x": 800, "y": 370, "text": "strangler fig,", "anchor": "middle", "bold": True, "size": 12.5, "color": "#2f5f3d"},
    {"x": 800, "y": 386, "text": "one seam at", "anchor": "middle", "bold": True, "size": 12.5, "color": "#2f5f3d"},
    {"x": 800, "y": 402, "text": "a time", "anchor": "middle", "bold": True, "size": 12.5, "color": "#2f5f3d"},
    {"x": 800, "y": 428, "text": "ch.14-26", "anchor": "middle", "size": 10, "color": "#555555"},
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.00 -- the outcome: one deployable, one shared schema -> six owned-data services behind an edge router",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": 395, "y": 58, "text": "Before", "anchor": "middle", "bold": True, "size": 14, "color": "#555555"},
    {"x": 1185, "y": 58, "text": "After", "anchor": "middle", "bold": True, "size": 14, "color": "#2f5f3d"},
    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/00-introduction.md (\"The running example\"), monolith-architecture.py, and "
             "strangler-completes-final-topology.py -- same six contexts, same ports, no codenames.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
] + left_notes + right_notes + divider_notes

g.emit(
    "monolith-to-microservices-outcome", W, H,
    bands=[outer_band_L, pg_band, outer_band_R],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges + divider_edge,
    notes=notes,
)
