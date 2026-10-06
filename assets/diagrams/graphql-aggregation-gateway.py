#!/usr/bin/env python3
"""ch.26 figure: graphql-aggregation-gateway — one `order(id)` GraphQL query,
fanned out by examples/08-graphql-gateway (:8090) to five already-extracted
services' own read surfaces, then stitched into one OrderView response.

`GatewayApi.order(id)` resolves the order itself over REST from the order
service (:8087, GET /api/orders/{id}). The GraphQL fields nested on that
result are each a `@Source` field resolver, evaluated lazily only when a
client actually selects them, calling the OWNING service in turn: `payments`
-> payment service (:8085, REST), `shipments` -> shipping service (:8088,
REST), and per line item, `reviews` -> review service (:8081, REST) and
`stock` -> inventory service (:9004, gRPC GetStock) -- the ONE non-REST hop.
The gateway holds no state and owns no data: every field is fetched live from
the service that owns it, never cached or denormalized locally. Every
downstream target is fixed operator config, never derived from the incoming
request, and query depth/complexity are bounded (DRQ-069) so the aggregation
surface cannot become a request-controlled fan-out amplifier.

Sourced from: examples/08-graphql-gateway/.../{GatewayApi,OrderRestClient,
PaymentRestClient,ShipmentRestClient,ReviewRestClient}.java and
_plans/iterations/order-plan.md (S7, DRQ-069). No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1650, 820

client = node(40, 340, 220, 110, ["Client", "GraphQL query", "order(id)"], style="user")
gateway = node(320, 300, 300, 190,
               ["GraphQL gateway :8090", "GatewayApi — @Query + @Source resolvers", "owns NO data — pure stitching layer"],
               style="ink")
stitched = node(320, 520, 300, 110,
                ["OrderView response", "order + payments[] + shipments[]", "+ items[].stock + items[].reviews[]"],
                style="box")

orderSvc = node(760, 40, 320, 100, ["Order service", ":8087 — REST", "GET /api/orders/{id}"], style="box")
paymentSvc = node(760, 160, 320, 100, ["Payment service", ":8085 — REST", "GET /payments?orderId="], style="box")
shippingSvc = node(760, 280, 320, 100, ["Shipping service", ":8088 — REST", "GET /shipments?orderId="], style="box")
reviewSvc = node(760, 400, 320, 100, ["Review service", ":8081 — REST, per item sku", "GET /reviews?sku="],
                  style="box")
inventorySvc = node(760, 520, 320, 100, ["Inventory service", ":9004 — gRPC, per item sku", "GetStock(sku)"],
                     style="accent")

nodes = [client, gateway, stitched, orderSvc, paymentSvc, shippingSvc, reviewSvc, inventorySvc]
edges = [
    connect(client, gateway, label="query order(id) / OrderView response", bidir=True),
    connect(gateway, orderSvc, label="REST — resolves the order itself"),
    connect(gateway, paymentSvc, label="REST — payments field"),
    connect(gateway, shippingSvc, label="REST — shipments field"),
    connect(gateway, reviewSvc, label="REST — items[].reviews field"),
    connect(gateway, inventorySvc, label="gRPC — items[].stock field", amber=True),
    connect(gateway, stitched, label="assembles one response"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.26 — the GraphQL aggregation gateway: one order(id) query fans out to five owning services",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 1080, "y": 660,
     "text": "four fields resolve over REST; stock is the ONE field resolved over gRPC — the gateway never mixes",
     "anchor": "middle", "size": 11, "color": "#2f5f3d", "bold": True},
    {"x": 1080, "y": 676,
     "text": "protocols inside a single field, and never caches or denormalizes what it fetches",
     "anchor": "middle", "size": 11, "color": "#2f5f3d", "bold": True},

    {"x": 40, "y": 800,
     "text": "Sourced from examples/08-graphql-gateway/.../{GatewayApi,OrderRestClient,PaymentRestClient,"
             "ShipmentRestClient,ReviewRestClient}.java and _plans/iterations/order-plan.md (S7, DRQ-069).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "graphql-aggregation-gateway", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
