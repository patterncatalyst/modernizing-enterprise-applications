#!/usr/bin/env python3
"""ch.24 figure: shipping-saga-compensation-flow — the Camel Saga EIP
coordinator's compensating-action model, contrasted with ch.19's in-line
catch and ch.23's choreographed reaction. Three stacked bands, each ending
NET-ZERO, showing how the TRIGGER for compensation changes across the book
even though the inventory undo (`gRPC Release`) stays the same call.

TOP (ch.19): compensation runs from an in-line `catch`, on the SAME request
thread, the instant the in-process payment charge throws/declines — still
true today for a reserve failure in both later modes.

MIDDLE (ch.23): there is no request-thread `catch` to lean on anymore — a
payment decline is DISCOVERED by reacting to the `payment.declined` EVENT,
across two async hops (payment service emits via its own outbox -> the
monolith's `OrderSagaListener` consumes and reacts). No coordinator exists;
the order context is the only process making a decision, and it only learns
there was a failure after the fact, from an event.

BOTTOM (ch.24): the Camel Saga EIP coordinator is COORDINATOR-INITIATED —
it does not wait to be told about the failure via an event; it IS the
process running the step that throws (`book-carrier`), so it invokes its
registered `direct:ship-compensate` immediately, in-process, the instant the
exception (or a timeout) occurs. Only the CROSS-CONTEXT part — undoing the
reserved inventory, which the shipping service does not own the snapshot
for — is delegated onward via `shipment.failed` to the monolith's
`onShipmentFailed()`, which performs the actual compensating `Release`
(DRQ-060). So ch.24 keeps ch.19's immediacy (no event needed to DECIDE to
compensate) while still needing ch.23's event hop for the one piece of
compensation that is genuinely cross-context.

Sourced from: examples/00-monolith/.../order/{OrderService,
OrderSagaListener}.java (ch.19/ch.23), examples/05-payment-service/.../
PaymentService.java (ch.23), examples/06-shipping-service/.../
{ShipmentSagaRoute,ShipmentSagaSteps}.java (ch.24), and
_plans/iterations/shipping-plan.md (S5, DRQ-059/060). No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1700, 1080

# ============================================================================
# Band 1 — ch.19: in-line catch, same request thread
# ============================================================================
band1 = {"x": 20, "y": 50, "w": 1660, "h": 260,
         "label": "ch.19 — in-line catch, same request thread (still true for a reserve failure in any mode)",
         "fill": "#fafafa"}

checkout1 = node(40, 110, 260, 90, ["OrderService.placeOrder()", "reserve (ok) -> persist -> charge"], style="user")
decline1 = node(340, 110, 230, 90, ["payment charge declines", "in-process, SAME request"], style="box")
catch1 = node(610, 110, 260, 90, ["catch (RuntimeException)", "compensateRemoteReservations()"], style="ink")
release1 = node(910, 110, 260, 90, ["gRPC Release :9004", "same request thread, no event involved"], style="accent")
netzero1 = node(1210, 110, 180, 90, ["NET-ZERO", "stock restored"], style="ink")

band1_nodes = [checkout1, decline1, catch1, release1, netzero1]
band1_edges = [
    connect(checkout1, decline1, label="charge()"),
    connect(decline1, catch1, label="throws", amber=True),
    connect(catch1, release1, label="Release (compensating)"),
    connect(release1, netzero1, label="restored"),
]

# ============================================================================
# Band 2 — ch.23: choreographed reaction, two async hops, no coordinator
# ============================================================================
band2 = {"x": 20, "y": 350, "w": 1660, "h": 280,
         "label": "ch.23 — choreographed reaction: the failure is DISCOVERED via an event, two async hops, no coordinator",
         "fill": "#fafafa"}

paymentSvc2 = node(40, 410, 240, 90, ["PaymentService", "processOrderPlaced() declines"], style="box")
outbox2 = node(320, 410, 230, 80, ["payment.declined outbox", "payment service's OWN outbox"], style="sub")
hop2 = node(590, 440, 110, 50, ["2 HOPS", "emit -> react"], style="ghost")
listener2 = node(740, 410, 300, 90,
                  ["OrderSagaListener.onPaymentDeclined()", "discovers the decline via the EVENT, idempotent"],
                  style="accent")
release2 = node(1080, 410, 260, 90, ["gRPC Release :9004", "reacts — SAME call ch.19 introduced"], style="accent")
netzero2 = node(1380, 410, 180, 90, ["NET-ZERO", "stock restored"], style="ink")

band2_nodes = [paymentSvc2, outbox2, hop2, listener2, release2, netzero2]
band2_edges = [
    connect(paymentSvc2, outbox2, label="declines"),
    connect(outbox2, listener2, label="emit -> Kafka -> consume (2 hops)", amber=True),
    connect(listener2, release2, label="reacts: Release"),
    connect(release2, netzero2, label="restored"),
]

# ============================================================================
# Band 3 — ch.24: coordinator-INITIATED, cross-context part delegated
# ============================================================================
band3 = {"x": 20, "y": 680, "w": 1660, "h": 380,
         "label": "ch.24 — coordinator-INITIATED: the coordinator decides to compensate immediately; only the cross-context undo is delegated",
         "fill": "#eaf4ec"}

bookCarrier3 = node(40, 740, 240, 90, ["book-carrier step", "throws ShipFailException (SHIP-FAIL)"], style="accent")
coordinator3 = node(320, 740, 280, 100,
                     ["Camel Saga EIP coordinator", "invokes direct:ship-compensate",
                      "EXACTLY ONCE — NO EVENT needed to decide"],
                     style="ink")
cancelEmit3 = node(640, 740, 250, 90, ["cancel shipment +", "emit shipment.failed", "same txn (local, shipping svc)"],
                    style="box")
kafkaFailed3 = node(930, 740, 220, 90, ["Kafka topic", "shipment.failed"], style="ink")
onFailed3 = node(1190, 740, 250, 90, ["monolith: onShipmentFailed()", "delegated cross-context reaction"],
                  style="box")

release3 = node(930, 890, 260, 90,
                 ["gRPC Release :9004", "delegated — order owns the", "reserved-line snapshot (DRQ-060)"],
                 style="accent")
netzero3 = node(1230, 890, 210, 90, ["NET-ZERO", "stock restored"], style="ink")

band3_nodes = [bookCarrier3, coordinator3, cancelEmit3, kafkaFailed3, onFailed3, release3, netzero3]
band3_edges = [
    connect(bookCarrier3, coordinator3, label="throws -> coordinator reacts IMMEDIATELY, in-process", amber=True),
    connect(coordinator3, cancelEmit3, label="direct:ship-compensate"),
    connect(cancelEmit3, kafkaFailed3, label="outbox relay"),
    connect(kafkaFailed3, onFailed3, label="@KafkaListener(shipment.failed)"),
    connect(onFailed3, release3, label="delegated Release — NOT called by shipping service"),
    connect(release3, netzero3, label="restored"),
]

notes = [
    {"x": W / 2, "y": 30,
     "text": "compensation across three extractions: in-line catch -> choreographed reaction -> coordinator-initiated",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 40, "y": 1062,
     "text": "Sourced from examples/00-monolith/.../order/{OrderService,OrderSagaListener}.java, "
             "examples/05-payment-service/.../PaymentService.java, "
             "examples/06-shipping-service/.../{ShipmentSagaRoute,ShipmentSagaSteps}.java (DRQ-059/060).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "shipping-saga-compensation-flow", W, H,
    bands=[band1, band2, band3],
    nodes=band1_nodes + band2_nodes + band3_nodes,
    edges=band1_edges + band2_edges + band3_edges,
    notes=notes,
)
