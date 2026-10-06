#!/usr/bin/env python3
"""ch.26 figure: order-cqrs-split — the CQRS write/read split inside the
extracted order service (examples/07-order-service).

Command path (top band, ONE @Transactional, DRQ-073): `POST /api/orders` ->
`OrderService.placeOrder()` validates the customer, reserves every line over
the SYNCHRONOUS gRPC `Reserve` (:9004, unchanged since ch.19), persists the
`Order`/`OrderItem` write-model aggregate `PENDING`, and writes `order.placed`
to this service's OWN transactional outbox in the SAME transaction -- no
dual-write. `OrderOutboxRelay` later relays the row to Kafka.

Projection (middle band, the write/read bridge, DRQ-067/074): `OrderService
.placeOrder()` itself calls `OrderViewProjector.project(order, null, null)`
in that SAME transaction to create the initial `PENDING` row in the
denormalized `order_view` read model. Separately, `OrderSagaListener`'s four
lifecycle reactions (`payment.captured`, `payment.declined`,
`shipment.dispatched`, `shipment.failed`) each call the SAME projector,
inside the SAME @Transactional as their own write to the `Order` aggregate --
an upsert keyed on orderId, never a second write path. The write model
(`Order`/`OrderItem`) and the read model (`order_view`) are two DISTINCT
tables in the SAME order-service PostgreSQL schema -- not two databases, and
never joined at read time.

Query path (bottom band, DRQ-067): `OrderService.getById`/`listAll` read
EXCLUSIVELY from `OrderViewRepository` -- `OrderRepository` (the write-model
aggregate) is NEVER consulted for a read. The GraphQL gateway's `order(id)`
query does not special-case this service either: it calls the SAME `GET
/api/orders/{id}` REST endpoint every other client uses (see the
graphql-aggregation-gateway figure for the gateway's own fan-out).

Sourced from: examples/07-order-service/.../{OrderService,OrderViewProjector,
OrderSagaListener,OrderResource}.java and _plans/iterations/order-plan.md
(S5/S6, DRQ-067/073/074). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1450, 1080

# ============================================================================
# Band A — command path: the write model, one @Transactional
# ============================================================================
band_a = {"x": 20, "y": 60, "w": 1410, "h": 300,
          "label": "Command path — one @Transactional (write model)", "fill": "#fafafa"}

client = node(40, 110, 180, 80, ["Client", "POST /api/orders"], style="sub")
ordersvc = node(260, 110, 300, 80,
                ["OrderService.placeOrder()", "validate customer; reserve; persist; outbox"], style="user")
inventory = node(600, 110, 250, 80, ["Inventory service", ":9004 — gRPC Reserve (sync)"], style="accent")
writeStore = node(890, 110, 280, 80,
                   ["Write model", "Order + OrderItem (PostgreSQL)", "persisted PENDING"], style="box")

resp = node(260, 210, 300, 80, ["202 response", "order status = PENDING"], style="ink")
outboxRow = node(600, 210, 250, 80, ["order.placed outbox row", "SAME @Transactional as persist"], style="sub")
relay = node(890, 210, 280, 80, ["OrderOutboxRelay", "@Scheduled poller"], style="box")
kafkaPlaced = node(1180, 210, 240, 80, ["Kafka topic", "order.placed"], style="ink")

band_a_nodes = [client, ordersvc, inventory, writeStore, resp, outboxRow, relay, kafkaPlaced]
band_a_edges = [
    connect(client, ordersvc, label="POST"),
    connect(ordersvc, inventory, label="Reserve (sync gRPC)"),
    connect(inventory, ordersvc, label="reservation_ok=true", ly=-20),
    connect(ordersvc, writeStore, label="persist (same txn)"),
    connect(ordersvc, resp, label="return (txn committed)"),
    connect(ordersvc, outboxRow, label="write outbox (same txn)"),
    connect(outboxRow, relay, label="poll unpublished"),
    connect(relay, kafkaPlaced, label="publish"),
]

# ============================================================================
# Band B — projection: the write/read bridge, same transaction as each write
# ============================================================================
band_b = {"x": 20, "y": 390, "w": 1410, "h": 260,
          "label": "Projection — the write/read bridge (same @Transactional as each write)", "fill": "#eaf4ec"}

kafka4 = node(260, 440, 260, 90,
              ["Kafka topics", "payment.captured / payment.declined", "shipment.dispatched / shipment.failed"],
              style="ink")
listener = node(560, 440, 260, 90, ["OrderSagaListener", "4 reactions — status-guarded, idempotent"], style="box")
projector = node(890, 440, 260, 110,
                  ["OrderViewProjector.project()", "upsert by orderId", "same @Transactional as the write"],
                  style="accent")
readStore = node(1180, 440, 240, 110, ["Read model", "order_view (PostgreSQL)", "denormalized, upserted"],
                  style="box")

band_b_nodes = [kafka4, listener, projector, readStore]
band_b_edges = [
    connect(kafka4, listener, label="@Incoming (4 topics)"),
    connect(listener, projector, label="project(order, paymentStatus, shipmentStatus)"),
    connect(writeStore, projector, label="project(order, null, null) — initial PENDING, same txn", amber=True),
    connect(projector, readStore, label="upsert order_view (same txn)", amber=True),
]

# ============================================================================
# Band C — query path: reads EXCLUSIVELY from the read model
# ============================================================================
band_c = {"x": 20, "y": 690, "w": 1410, "h": 320,
          "label": "Query path — reads EXCLUSIVELY from the read model (no aggregate fallback)", "fill": "#fafafa"}

clientHttp = node(40, 740, 220, 90, ["Client", "GET /api/orders(/{id})"], style="sub")
getById = node(1180, 740, 240, 90, ["OrderService.getById()/listAll()", "orderViewRepository ONLY"], style="user")

clientGraphQL = node(40, 860, 220, 90, ["Client", "GraphQL order(id) query"], style="sub")
gatewayNode = node(1180, 860, 240, 90, ["GraphQL gateway :8090", "order(id) — see fig. 2"], style="accent")

band_c_nodes = [clientHttp, getById, clientGraphQL, gatewayNode]
band_c_edges = [
    connect(clientHttp, getById, label="GET /api/orders(/{id})"),
    connect(clientGraphQL, gatewayNode, label="query order(id)"),
    connect(gatewayNode, getById, label="REST GET :8087, same endpoint any client uses"),
    connect(getById, readStore, label="reads EXCLUSIVELY — no aggregate fallback", amber=True),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.26 — the CQRS write/read split: command path, projection bridge, read-only query path",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": W / 2, "y": 378,
     "text": "write model and read model are two DISTINCT tables in the SAME order-service PostgreSQL schema — never joined at read time",
     "anchor": "middle", "size": 11, "color": "#2f5f3d", "bold": True},

    {"x": 40, "y": 1060,
     "text": "Sourced from examples/07-order-service/.../{OrderService,OrderViewProjector,OrderSagaListener,OrderResource}.java "
             "and _plans/iterations/order-plan.md (S5/S6, DRQ-067/073/074).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "order-cqrs-split", W, H,
    bands=[band_a, band_b, band_c],
    nodes=band_a_nodes + band_b_nodes + band_c_nodes,
    edges=band_a_edges + band_b_edges + band_c_edges,
    notes=notes,
)
