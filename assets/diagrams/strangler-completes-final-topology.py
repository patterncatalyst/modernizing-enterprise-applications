#!/usr/bin/env python3
"""ch.26 figure: strangler-completes-final-topology — the capstone end-state
topology once the sixth and last extraction (order + the GraphQL gateway)
lands and the monolith is decommissioned (ch.26 S10, DRQ-070/075).

The Camel strangler proxy sheds its strangler role and becomes the system's
permanent REST (+ GraphQL) edge router on :8888: the `.choice()` content-based
routing collapses to straight per-context forwarding, and every
`strangler.*.enabled` cutover flag is retired -- there is no monolith left to
fall back to, so the flags have nothing left to choose between. One client
entry point reaches all six extracted Quarkus services (review :8081,
notification :8083, inventory :8084/:9004, payment :8085, order :8087,
shipping :8088) plus the GraphQL gateway (:8090, itself a pure aggregation
layer over five of those six -- see the graphql-aggregation-gateway figure).

The Spring monolith (:8080) is REMOVED from the running topology -- no edge
routes to it, no live traffic reaches it -- but kept FROZEN in-repo (DRQ-024)
as the "before" picture and the golden-baseline referent the (now converted)
contract suite was built from. It stands alone in this figure, deliberately
unwired, to make that removal visually literal.

Sourced from: examples/01-strangler-proxy/.../StranglerProxyRoute.java
(edge-router shape, ch.26 S10), examples/0{2,3,4,5,6,7}-*-service and
examples/08-graphql-gateway `application.properties` (ports), and
_plans/iterations/order-plan.md (S10, DRQ-024/070/075). No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1450, 950

# ---- client + edge router ---------------------------------------------------
client = node(40, 430, 220, 100, ["Client", "REST + GraphQL"], style="user")
router = node(300, 380, 280, 180,
              ["Camel edge router :8888", "flagless — permanent REST/GraphQL entry point",
               "strangler .choice() collapsed; strangler.*.enabled retired (S10)"],
              style="ink")

# ---- the six extracted services + the GraphQL gateway ----------------------
services_band = {"x": 970, "y": 20, "w": 440, "h": 800,
                  "label": "Six extracted Quarkus services + the GraphQL gateway", "fill": "#eaf4ec"}

review = node(1000, 40, 380, 90, ["Review service", ":8081 — REST (ch.15)"], style="box")
notification = node(1000, 150, 380, 90, ["Notification service", ":8083 — REST + WebSocket (ch.17)"], style="box")
inventory = node(1000, 260, 380, 90, ["Inventory service", ":8084 REST / :9004 gRPC (ch.19)"], style="accent")
payment = node(1000, 370, 380, 90, ["Payment service", ":8085 — REST (ch.23)"], style="box")
shipping = node(1000, 480, 380, 90, ["Shipping service", ":8088 — REST (ch.24)"], style="box")
order = node(1000, 590, 380, 90, ["Order service", ":8087 — REST, CQRS write+read split (ch.26, fig. 1)"],
             style="accent")
gateway = node(1000, 700, 380, 90, ["GraphQL gateway", ":8090 — aggregation, owns no data (ch.26, fig. 2)"],
               style="accent")

service_nodes = [review, notification, inventory, payment, shipping, order, gateway]

edges = [
    connect(client, router, label="single entry point"),
    connect(router, review, label="/api/reviews/**"),
    connect(router, notification, label="/api/notifications/**"),
    connect(router, inventory, label="/api/inventory/**"),
    connect(router, payment, label="/api/payments/**"),
    connect(router, shipping, label="/api/shipments/**"),
    connect(router, order, label="/api/orders/**"),
    connect(router, gateway, label="/graphql", amber=True),
]

# ---- the frozen monolith: standalone, no edges in or out --------------------
monolith = node(40, 650, 460, 220,
                 ["Spring monolith", ":8080 — FROZEN, kept in-repo (DRQ-024)",
                  "reference / monolith-before baseline for the contract suite",
                  "REMOVED from the running topology — no live traffic, no routes in"],
                 style="ghost")

nodes = [client, router] + service_nodes + [monolith]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.26 — the strangler completes: final topology (six services + the GraphQL gateway behind one flagless edge router)",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 270, "y": 598,
     "text": "the monolith is a historical artifact only — nothing in the running topology calls it",
     "anchor": "middle", "size": 11, "color": "#555555"},

    {"x": 40, "y": 930,
     "text": "Sourced from examples/01-strangler-proxy/.../StranglerProxyRoute.java (ch.26 S10), the six services' + "
             "gateway's application.properties, and _plans/iterations/order-plan.md (S10, DRQ-024/070/075).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "strangler-completes-final-topology", W, H,
    bands=[services_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
