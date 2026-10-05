#!/usr/bin/env python3
"""ch.24 figure: shipping-orchestrated-saga-sequence — the full happy +
failure flow for the Shipping extraction's ORCHESTRATED saga, the ch.24
analogue of payment-choreographed-saga-sequence.svg (ch.23).

Checkout stays exactly as ch.23 left it (unchanged): `POST /api/orders` ->
`OrderService.placeOrder()` reserves stock over the SYNCHRONOUS gRPC
`Reserve` (:9004) -> persists `PENDING` -> writes the `order.placed` outbox
row in the SAME `@Transactional` -> `202 Accepted`. Payment capture is also
unchanged choreography (ch.23): the payment service's `OrderPlacedConsumer`
charges and emits `payment.captured`/`payment.declined` via its OWN
transactional outbox.

What's new for ch.24: TWO independent consumer groups react to
`payment.captured`. The monolith's `OrderSagaListener.onPaymentCaptured()`
unconditionally moves the order `PENDING -> AWAITING_SHIPMENT` and does NOT
dispatch in-process (r07/S9 decommission). Separately, the shipping
service's `PaymentCapturedConsumer` hands the event to
`ShippingService.processPaymentCaptured()`, which starts the Camel Saga EIP
coordinator (`InMemorySagaService`, `direct:ship-start`) -- THE central,
explicit, top-to-bottom-readable coordinator that sequences enrich ->
dispatch -> book-carrier -> emit, the deliberate contrast to ch.23's
choreography (no such box exists there). On success, step 4 emits
`shipment.dispatched` via the shipping service's OWN transactional outbox;
the monolith's `onShipmentDispatched()` reacts and confirms. On the
deterministic `SHIP-FAIL` sentinel (book-carrier throws
`ShipFailException`), the SAME coordinator invokes its registered
`direct:ship-compensate` EXACTLY ONCE (abort or timeout, Camel's
`CamelSagaService` guarantee) -- cancelling the shipment and emitting
`shipment.failed`, in one local transaction. The monolith's
`onShipmentFailed()` reacts and marks `SHIPPING_FAILED`, then issues the
compensating gRPC `Release` for every reserved sku -- DELEGATED: the order
context performs the undo because it owns the reserved-line snapshot
(DRQ-060); the shipping service never calls `Release` directly.

Sourced from: examples/06-shipping-service/.../{ShipmentSagaRoute,
ShipmentSagaSteps,ShippingService}.java, examples/00-monolith/.../order/
OrderSagaListener.java, examples/00-monolith/.../common/{Topics,
OrderStatus}.java, and _plans/iterations/shipping-plan.md (S5/S6, DRQ-056/
057/058/059/060/061/062/063/064). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 2000, 1700

# ============================================================================
# Band A — checkout: unchanged, synchronous (ch.19/ch.23)
# ============================================================================
band_a = {"x": 20, "y": 50, "w": 1960, "h": 250,
          "label": "Checkout — synchronous, unchanged from ch.19/ch.23", "fill": "#fafafa"}

client = node(40, 100, 160, 80, ["Client", "POST /api/orders"], style="sub")
ordersvc = node(230, 100, 260, 80,
                ["OrderService.placeOrder()", "reserve, persist PENDING, outbox — one @Transactional"],
                style="user")
inventory1 = node(520, 100, 220, 80, ["Inventory service", ":9004 — gRPC Reserve (SYNC)"], style="accent")
resp202 = node(770, 100, 210, 80, ["202 Accepted", "order PENDING"], style="ink")
outboxA = node(230, 210, 260, 70, ["order.placed outbox row", "same txn as persist"], style="sub")
relayA = node(520, 210, 220, 70, ["OutboxRelay", "@Scheduled poller"], style="box")
kafkaPlaced = node(770, 210, 220, 70, ["Kafka topic", "order.placed"], style="ink")

band_a_nodes = [client, ordersvc, inventory1, resp202, outboxA, relayA, kafkaPlaced]
band_a_edges = [
    connect(client, ordersvc, label="POST"),
    connect(ordersvc, inventory1, label="Reserve (sync gRPC)"),
    connect(inventory1, ordersvc, label="reservation_ok=true", ly=-18),
    connect(ordersvc, resp202, label="return (txn committed)"),
    connect(ordersvc, outboxA, label="persist + outbox (same txn)"),
    connect(outboxA, relayA, label="poll unpublished"),
    connect(relayA, kafkaPlaced, label="publish"),
]

# ============================================================================
# Band B — payment capture: unchanged choreography (ch.23)
# ============================================================================
band_b = {"x": 20, "y": 320, "w": 1960, "h": 220,
          "label": "Payment capture — async choreography, unchanged from ch.23", "fill": "#fafafa"}

consumerB = node(230, 370, 260, 70, ["OrderPlacedConsumer", "@Incoming(order.placed)"], style="box")
chargeB = node(520, 370, 220, 70, ["PaymentService", "charge — idempotent by orderId"], style="accent")
outboxB = node(230, 460, 260, 70, ["payment outbox row", "CAPTURED — same txn"], style="sub")
relayB = node(520, 460, 220, 70, ["PaymentOutboxRelay", "@Scheduled poller"], style="box")
kafkaCaptured = node(790, 400, 230, 70, ["Kafka topic", "payment.captured"], style="ink")

band_b_nodes = [consumerB, chargeB, outboxB, relayB, kafkaCaptured]
band_b_edges = [
    connect(kafkaPlaced, consumerB, label="@Incoming(order.placed)"),
    connect(consumerB, chargeB, label="charge"),
    connect(chargeB, outboxB, label="persist Payment + outbox"),
    connect(outboxB, relayB, label="poll unpublished"),
    connect(relayB, kafkaCaptured, label="CAPTURED", amber=True),
]

# ============================================================================
# Band C — the orchestrated saga: TWO independent reactions to ONE topic,
# and the central Camel Saga EIP coordinator (THE ch.24 figure).
# ============================================================================
band_c = {"x": 20, "y": 560, "w": 1960, "h": 340,
          "label": "Shipping orchestrated saga — the Camel Saga EIP coordinator sequences fulfilment (ch.24)",
          "fill": "#eaf4ec"}

monolithReact = node(40, 610, 270, 80, ["OrderSagaListener", "onPaymentCaptured() — separate consumer group"],
                      style="box")
awaitingShip = node(40, 710, 270, 70, ["Order: AWAITING_SHIPMENT"], style="ink")

consumerShip = node(360, 610, 240, 80, ["PaymentCapturedConsumer", "shipping service — @Incoming"], style="box")
shipSvc = node(360, 710, 240, 70, ["ShippingService", "processPaymentCaptured() — idempotent by orderId"],
               style="sub")

coordinator = node(660, 640, 290, 120,
                    ["Camel Saga EIP coordinator", "InMemorySagaService — direct:ship-start",
                     ".saga() sequences steps + owns the compensation decision"],
                    style="ink")

stepEnrich = node(1010, 580, 160, 55, ["① enrich", "shipping address"], style="box")
stepDispatch = node(1010, 645, 160, 55, ["② dispatch", "persist PENDING shipment"], style="box")
stepBook = node(1010, 710, 160, 55, ["③ book-carrier", "SHIP-FAIL sentinel throws here"], style="accent")
stepEmit = node(1010, 775, 160, 55, ["④ emit", "shipment.dispatched (same txn as DISPATCHED)"], style="box")

band_c_nodes = [monolithReact, awaitingShip, consumerShip, shipSvc, coordinator,
                stepEnrich, stepDispatch, stepBook, stepEmit]
band_c_edges = [
    connect(kafkaCaptured, monolithReact, label="@KafkaListener(payment.captured)"),
    connect(monolithReact, awaitingShip, label="PENDING -> AWAITING_SHIPMENT"),
    connect(kafkaCaptured, consumerShip, label="@Incoming — separate consumer group", amber=True),
    connect(consumerShip, shipSvc, label="processPaymentCaptured()"),
    connect(shipSvc, coordinator, label="sendBodyAndHeader(direct:ship-start)"),
    connect(coordinator, stepEnrich, label="invokes ①→④ in sequence", amber=True),
    connect(stepEnrich, stepDispatch),
    connect(stepDispatch, stepBook),
    connect(stepBook, stepEmit, label="no exception -> continue"),
]

coordinator_note = {"x": 810, "y": 790, "text": "ONE visible place owns the sequence AND the compensation decision",
                     "anchor": "middle", "size": 11, "color": "#2f5f3d", "bold": True}

# ============================================================================
# Band D — success: saga completes, coordinator marks done
# ============================================================================
band_d = {"x": 20, "y": 920, "w": 1960, "h": 200,
          "label": "Success — saga completes (completionMode=AUTO; no exception thrown)", "fill": "#fafafa"}

relayShip = node(360, 970, 220, 70, ["ShipmentOutboxRelay", "@Scheduled poller"], style="box")
kafkaDispatched = node(610, 970, 230, 70, ["Kafka topic", "shipment.dispatched"], style="ink")
onDispatched = node(870, 970, 280, 70, ["monolith: onShipmentDispatched()", "@KafkaListener — idempotent"],
                     style="box")
confirmed = node(1180, 970, 220, 70, ["Order: CONFIRMED"], style="ink")

band_d_nodes = [relayShip, kafkaDispatched, onDispatched, confirmed]
band_d_edges = [
    connect(stepEmit, relayShip, label="shipment.dispatched outbox"),
    connect(relayShip, kafkaDispatched, label="publish"),
    connect(kafkaDispatched, onDispatched, label="@KafkaListener(shipment.dispatched)"),
    connect(onDispatched, confirmed, label="AWAITING_SHIPMENT -> CONFIRMED"),
]

# ============================================================================
# Band E — failure: book-carrier throws SHIP-FAIL; coordinator compensates,
# and the cross-context inventory undo is DELEGATED to the order context.
# ============================================================================
band_e = {"x": 20, "y": 1140, "w": 1960, "h": 440,
          "label": "Failure — book-carrier throws SHIP-FAIL; coordinator compensates (delegated to the order context)",
          "fill": "#fafafa"}

shipFailEx = node(1010, 1190, 210, 90, ["ShipFailException", "SHIP-FAIL sentinel in address"], style="box")
compensate = node(1260, 1190, 250, 100,
                   ["coordinator invokes", "direct:ship-compensate", "EXACTLY ONCE on abort/timeout"], style="ink")
cancelShip = node(1550, 1190, 210, 90, ["cancel shipment", "PENDING -> CANCELLED (same txn)"], style="box")

failedOutboxE = node(1550, 1320, 210, 90, ["shipment.failed outbox", "same txn as CANCELLED"], style="sub")
relayFailE = node(1260, 1320, 220, 90, ["ShipmentOutboxRelay", "@Scheduled poller"], style="box")
kafkaFailedE = node(1010, 1320, 220, 90, ["Kafka topic", "shipment.failed"], style="ink")
onFailed = node(720, 1320, 260, 90, ["monolith: onShipmentFailed()", "@KafkaListener — idempotent"], style="box")

shippingFailedState = node(420, 1450, 220, 90, ["Order: SHIPPING_FAILED"], style="ink")
release = node(700, 1450, 260, 90,
               ["Inventory service", ":9004 — gRPC Release", "DELEGATED — order owns the reserved-line snapshot"],
               style="accent")
netzero = node(1000, 1450, 200, 90, ["NET-ZERO", "stock restored"], style="ink")

band_e_nodes = [shipFailEx, compensate, cancelShip, failedOutboxE, relayFailE, kafkaFailedE, onFailed,
                shippingFailedState, release, netzero]
band_e_edges = [
    connect(stepBook, shipFailEx, label="throws ShipFailException", dashed=True),
    connect(shipFailEx, compensate, label="saga aborts", amber=True),
    connect(compensate, cancelShip, label="cancel shipment (same txn)"),
    connect(cancelShip, failedOutboxE, label="shipment.failed outbox (same txn)"),
    connect(failedOutboxE, relayFailE, label="poll unpublished"),
    connect(relayFailE, kafkaFailedE, label="publish"),
    connect(kafkaFailedE, onFailed, label="@KafkaListener(shipment.failed)"),
    connect(onFailed, shippingFailedState, label="AWAITING_SHIPMENT -> SHIPPING_FAILED"),
    connect(onFailed, release, label="delegated Release — shipping service NEVER calls Release directly",
            amber=True),
    connect(release, netzero, label="stock restored"),
]

notes = [
    {"x": W / 2, "y": 30,
     "text": "ch.24 — the orchestrated saga: one Camel Saga EIP coordinator sequences fulfilment and owns the compensation decision",
     "anchor": "middle", "bold": True, "size": 17},

    coordinator_note,

    {"x": 40, "y": 1672,
     "text": "Sourced from examples/06-shipping-service/.../{ShipmentSagaRoute,ShipmentSagaSteps,ShippingService}.java, "
             "examples/00-monolith/.../order/OrderSagaListener.java, and _plans/iterations/shipping-plan.md (S5/S6).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "shipping-orchestrated-saga-sequence", W, H,
    bands=[band_a, band_b, band_c, band_d, band_e],
    nodes=band_a_nodes + band_b_nodes + band_c_nodes + band_d_nodes + band_e_nodes,
    edges=band_a_edges + band_b_edges + band_c_edges + band_d_edges + band_e_edges,
    notes=notes,
)
