#!/usr/bin/env python3
"""ch.23 figure: payment-choreographed-saga-sequence — the full happy +
failure flow for the Payment extraction's choreographed saga.

Checkout stays synchronous for exactly the span it always was: `POST
/api/orders` -> `OrderService.placeOrder()` reserves stock over the
SYNCHRONOUS gRPC `Reserve` (:9004, unchanged from ch.19) -> persists the
order `PENDING` -> writes the `order.placed` outbox row in the SAME
`@Transactional` -> returns `202 Accepted` + `Location` (DRQ-047, H1). Only
the PAYMENT OUTCOME is async from here: the monolith's `OutboxRelay` relays
`order.placed` to Kafka; the Quarkus payment service's `OrderPlacedConsumer`
reacts, `PaymentService.processOrderPlaced()` charges (idempotent by
orderId) and writes CAPTURED-or-DECLINED to its OWN transactional outbox
(DRQ-053) in the SAME transaction as the `Payment` row; its own
`PaymentOutboxRelay` relays `payment.captured`/`payment.declined` to Kafka
(DRQ-048). The monolith's `OrderSagaListener` — its FIRST Kafka consumer
(H4) — reacts: `onPaymentCaptured` confirms the order and dispatches
shipping; `onPaymentDeclined` marks `PAYMENT_DECLINED` and issues the
compensating gRPC `Release` (DRQ-049), restoring stock net-zero. Both
reactions guard on the order's current status (idempotent, DRQ-051) so a
redelivery is a safe no-op. The client never learns the outcome from the
POST response — it observes the terminal state by polling `GET
/api/orders/{id}`.

Sourced from: examples/00-monolith/.../order/{OrderController,OrderService,
OrderSagaListener}.java, examples/00-monolith/.../common/outbox/{OutboxEvent,
OutboxRelay}.java, examples/00-monolith/.../common/Topics.java,
examples/05-payment-service/.../{OrderPlacedConsumer,PaymentService,
PaymentOutboxRelay}.java, and _plans/iterations/payment-plan.md (S6/S8,
DRQ-047/-048/-049/-051/-053). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 1000

# ============================================================================
# Band A — checkout: the ONLY synchronous span (client blocks here, and only here)
# ============================================================================
band_a = {"x": 20, "y": 60, "w": 1560, "h": 300,
          "label": "Checkout — synchronous (client blocks only for this span)", "fill": "#fafafa"}

client = node(40, 110, 150, 90, ["Client", "POST /api/orders"], style="sub")
ordersvc = node(230, 110, 260, 90,
                ["OrderService.placeOrder()", "one @Transactional: reserve, persist, outbox"], style="user")
inventory1 = node(530, 110, 230, 90,
                   ["Inventory service", ":9004 — gRPC Reserve (SYNC)", "atomic decrement, unchanged from ch.19"],
                   style="accent")
resp202 = node(800, 110, 230, 90,
               ["202 Accepted", "Location: /api/orders/{id}", "order status = PENDING"], style="ink")

outboxrow = node(230, 250, 260, 90,
                  ["Outbox row written", "order.placed — SAME @Transactional"], style="sub")
relay1 = node(530, 250, 230, 90, ["OutboxRelay", "@Scheduled poller"], style="box")
kafka_placed = node(800, 250, 230, 90, ["Kafka topic", "order.placed"], style="ink")

band_a_nodes = [client, ordersvc, inventory1, resp202, outboxrow, relay1, kafka_placed]
band_a_edges = [
    connect(client, ordersvc, label="POST"),
    connect(ordersvc, inventory1, label="Reserve (sync gRPC)"),
    connect(inventory1, ordersvc, label="reservation_ok = true", ly=-20),
    connect(ordersvc, resp202, label="return (txn committed)"),
    connect(ordersvc, outboxrow, label="persist PENDING + outbox row"),
    connect(outboxrow, relay1, label="poll unpublished"),
    connect(relay1, kafka_placed, label="publish; stamp published_at"),
]

# ============================================================================
# Band B — payment choreography: async from here on (payment service is the producer)
# ============================================================================
band_b = {"x": 20, "y": 380, "w": 1560, "h": 280,
          "label": "Payment choreography — async (payment service owns capture + outcome)", "fill": "#eaf4ec"}

consumer = node(230, 430, 260, 90,
                 ["OrderPlacedConsumer", "@Incoming(\"order-placed\")"], style="box")
charge = node(530, 430, 250, 90,
              ["PaymentService.processOrderPlaced()", "charge — idempotent by orderId"], style="accent")

payoutboxrow = node(230, 560, 260, 80,
                      ["Payment outbox row", "CAPTURED or DECLINED — SAME txn"], style="sub")
relay2 = node(530, 560, 230, 80, ["PaymentOutboxRelay", "@Scheduled poller"], style="box")
kafka_captured = node(830, 410, 240, 70, ["Kafka topic", "payment.captured"], style="ink")
kafka_declined = node(830, 500, 240, 70, ["Kafka topic", "payment.declined"], style="ink")

band_b_nodes = [consumer, charge, payoutboxrow, relay2, kafka_captured, kafka_declined]
band_b_edges = [
    connect(kafka_placed, consumer, label="@Incoming (consume order.placed)"),
    connect(consumer, charge, label="process"),
    connect(charge, payoutboxrow, label="persist Payment + outbox (same txn)"),
    connect(payoutboxrow, relay2, label="poll unpublished"),
    connect(relay2, kafka_captured, label="CAPTURED", amber=True),
    connect(relay2, kafka_declined, label="DECLINED", dashed=True),
]

# ============================================================================
# Band C — order-saga reaction: the monolith's FIRST Kafka consumer (H4)
# ============================================================================
band_c = {"x": 20, "y": 700, "w": 1560, "h": 280,
          "label": "Order-saga reaction — async (monolith's FIRST Kafka consumer)", "fill": "#fafafa"}

listener = node(230, 750, 290, 90,
                 ["OrderSagaListener", "@KafkaListener — idempotent (status == PENDING)"], style="box")
confirmed = node(570, 720, 260, 80, ["onPaymentCaptured", "CONFIRMED + shipping dispatched"], style="accent")
declined = node(570, 830, 260, 80, ["onPaymentDeclined", "PAYMENT_DECLINED"], style="accent")
shipnode = node(880, 720, 240, 80, ["Shipping dispatch", "(stays in monolith — ch.24 forward-ref)"], style="box")
release2 = node(880, 830, 240, 80,
                  ["Inventory service", ":9004 — gRPC Release (compensating)", "stock restored — NET-ZERO"],
                  style="ink")
clientpoll = node(1180, 775, 350, 90,
                    ["Client", "GET /api/orders/{id} — bounded-wait poll", "observes the terminal state"],
                    style="user")

band_c_nodes = [listener, confirmed, declined, shipnode, release2, clientpoll]
band_c_edges = [
    connect(kafka_captured, listener, label="@KafkaListener(payment.captured)"),
    connect(kafka_declined, listener, label="@KafkaListener(payment.declined)", dashed=True),
    connect(listener, confirmed, label="PENDING -> CONFIRMED"),
    connect(listener, declined, label="PENDING -> PAYMENT_DECLINED", dashed=True),
    connect(confirmed, shipnode, label="dispatch()"),
    connect(declined, release2, label="Release (compensating)"),
    connect(confirmed, clientpoll, label="observed via poll", amber=True),
    connect(declined, clientpoll, label="observed via poll", dashed=True),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.23 — the choreographed saga: synchronous reserve, async payment outcome, async order reaction",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 40, "y": 988,
     "text": "Sourced from examples/00-monolith/.../order/{OrderController,OrderService,OrderSagaListener}.java, "
             "examples/05-payment-service/.../{OrderPlacedConsumer,PaymentService,PaymentOutboxRelay}.java,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "payment-choreographed-saga-sequence", W, H,
    bands=[band_a, band_b, band_c],
    nodes=band_a_nodes + band_b_nodes + band_c_nodes,
    edges=band_a_edges + band_b_edges + band_c_edges,
    notes=notes,
)
