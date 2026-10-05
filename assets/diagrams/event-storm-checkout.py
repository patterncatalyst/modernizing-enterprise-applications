#!/usr/bin/env python3
"""ch.12 figure: event-storm-checkout — two readings of one call chain.

TOP band: today's `OrderService#placeOrder` as it actually executes — one
synchronous call chain, one `@Transactional` (SMELL[ch.22], SMELL[ch.26]).
BOTTOM band: the same business process re-read with the event-storming
grammar — actor, command, aggregate, domain events, policies/reactions, an
external system, and a read model. The domain-event names in the bottom band
are exactly the constants already reserved (unused) in `common/Topics.java`
plus the two this chapter adds to that set (StockReserved, PaymentDeclined,
OrderConfirmed, NotificationSent) — see SMELLS.md smell #4.

Node-style reuse note: this project's generate_diagram.py palette has seven
styles (box/sub/accent/user/kernel/ghost/ink), not the six sticky-note colors
of a physical event-storm wall. The legend note at the bottom of the figure
maps this project's styles onto the standard grammar; no literal
orange/blue/yellow is implied by the SVG's actual fill colors.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 1000

# ---- TOP band: today's synchronous call chain ------------------------------
customerT = node(40, 130, 120, 90, ["Customer"], style="sub")
commandT = node(190, 130, 150, 90, ["placeOrder()", "HTTP request"], style="user")
invT = node(370, 130, 190, 90, ["InventoryService.reserve()", "direct in-process call"], style="ink")
orderT = node(590, 130, 160, 90, ["Order", "persisted"], style="box")
payT = node(780, 130, 190, 90, ["PaymentService.charge()", "direct in-process call"], style="ink")
shipT = node(1000, 130, 190, 90, ["ShippingService.dispatch()", "direct in-process call"], style="ink")
notifT = node(1220, 130, 230, 90, ["NotificationService", "sendOrderConfirmation() — direct call"], style="ink")

top_nodes = [customerT, commandT, invT, orderT, payT, shipT, notifT]
top_edges = [
    connect(customerT, commandT),
    connect(commandT, invT),
    connect(invT, orderT, amber=True, label="SMELL[ch.16] no ACL"),
    connect(orderT, payT, amber=True, label="SMELL[ch.22]/[ch.26]"),
    connect(payT, shipT, amber=True),
    connect(shipT, notifT, amber=True, label="SMELL[ch.17]"),
]

# ---- BOTTOM band: the event-storm reading ----------------------------------
customerB = node(40, 390, 120, 80, ["Customer"], style="sub")
commandB = node(190, 390, 160, 80, ["Place Order", "command"], style="user")
orderAgg = node(380, 390, 160, 80, ["Order", "aggregate"], style="box")

evOrderPlaced = node(380, 510, 170, 90, ["OrderPlaced", "domain event"], style="accent")
polReserve = node(590, 510, 170, 90, ["reserve stock", "policy / reaction"], style="ghost")
evStockReserved = node(800, 510, 180, 90, ["StockReserved", "domain event"], style="accent")
polCapture = node(1020, 510, 180, 90, ["capture payment", "policy / reaction"], style="ghost")
evPaymentCaptured = node(1240, 510, 190, 90, ["PaymentCaptured", "domain event"], style="accent")

extGateway = node(1020, 370, 230, 90, ["Payment Gateway", "external system"], style="ink")
evPaymentDeclined = node(1020, 650, 190, 90, ["PaymentDeclined", "domain event (failure)"], style="accent")

polDispatch = node(1240, 650, 190, 90, ["dispatch shipment", "policy / reaction"], style="ghost")
evShipmentDispatched = node(1020, 790, 210, 90, ["ShipmentDispatched", "domain event"], style="accent")
polNotify = node(790, 790, 190, 90, ["notify customer", "policy / reaction"], style="ghost")
evOrderConfirmed = node(560, 790, 190, 90, ["OrderConfirmed", "domain event"], style="accent")
evNotificationSent = node(330, 790, 200, 90, ["NotificationSent", "domain event"], style="accent")

readModel = node(420, 910, 380, 70, ["Order Status", "read model"], style="kernel")

bottom_nodes = [
    customerB, commandB, orderAgg,
    evOrderPlaced, polReserve, evStockReserved, polCapture, evPaymentCaptured,
    extGateway, evPaymentDeclined,
    polDispatch, evShipmentDispatched, polNotify, evOrderConfirmed, evNotificationSent,
    readModel,
]
bottom_edges = [
    connect(customerB, commandB),
    connect(commandB, orderAgg),
    connect(orderAgg, evOrderPlaced, label="emits"),
    connect(evOrderPlaced, polReserve),
    connect(polReserve, evStockReserved),
    connect(evStockReserved, polCapture),
    connect(extGateway, polCapture, label="authorize/capture"),
    connect(polCapture, evPaymentCaptured, label="approved"),
    connect(polCapture, evPaymentDeclined, label="declined", dashed=True),
    connect(evPaymentCaptured, polDispatch),
    connect(polDispatch, evShipmentDispatched),
    connect(evShipmentDispatched, polNotify),
    connect(polNotify, evOrderConfirmed),
    connect(polNotify, evNotificationSent),
    connect(evOrderPlaced, readModel, dashed=True, label="projects"),
    connect(evPaymentCaptured, readModel, dashed=True),
    connect(evShipmentDispatched, readModel, dashed=True),
    connect(evOrderConfirmed, readModel, dashed=True),
]

notes = [
    {"x": W / 2, "y": 30, "text": "ch.12 — Event-storming the checkout flow: two readings of one call chain",
     "anchor": "middle", "bold": True, "size": 18},

    {"x": 40, "y": 65, "text": "TOP — today: OrderService#placeOrder, one @Transactional, five contexts, synchronous calls",
     "anchor": "start", "bold": True, "size": 14},
    {"x": 40, "y": 345, "text": "BOTTOM — the same process, event-stormed: actor -> command -> aggregate -> domain events, "
                                "policies/reactions, an external system, a read model",
     "anchor": "start", "bold": True, "size": 14},

    {"x": 1020, "y": 360, "text": "ch.25: timeout/retry sit here once this is a network call", "anchor": "start", "size": 10, "color": "#555555"},
    {"x": 1020, "y": 745, "text": "ch.23: saga compensation releases the stock reservation here instead of an ACID rollback",
     "anchor": "start", "size": 10, "color": "#555555"},

    {"x": 380, "y": 505, "text": "Topics.ORDER_PLACED", "anchor": "start", "size": 9, "color": "#3d7a4e"},
    {"x": 1240, "y": 505, "text": "Topics.PAYMENT_CAPTURED", "anchor": "start", "size": 9, "color": "#3d7a4e"},
    {"x": 1020, "y": 785, "text": "Topics.SHIPMENT_DISPATCHED", "anchor": "start", "size": 9, "color": "#3d7a4e"},

    {"x": 40, "y": 985,
     "text": "Legend (this project's diagram palette, not literal sticky-note colors): ACCENT = domain event * "
             "USER = command * SUB = actor * BOX = aggregate * GHOST (dashed) = policy/reaction * "
             "KERNEL = read model * INK = external system / today's direct in-process call",
     "anchor": "start", "size": 10, "color": "#555555"},
]

g.emit(
    "event-storm-checkout", W, H,
    bands=[
        {"x": 20, "y": 50, "w": 1560, "h": 280, "label": "", "fill": "#f4f4f4"},
        {"x": 20, "y": 330, "w": 1560, "h": 660, "label": "", "fill": "#fafafa"},
    ],
    nodes=top_nodes + bottom_nodes,
    edges=top_edges + bottom_edges,
    notes=notes,
)
