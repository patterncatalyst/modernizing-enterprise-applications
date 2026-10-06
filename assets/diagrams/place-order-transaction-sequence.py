#!/usr/bin/env python3
"""ch.08 figure: place-order-transaction-sequence — the structural heart of
the book's "before" picture: OrderService#placeOrder as ONE Spring
@Transactional spanning five bounded contexts, in-process, one database,
atomic rollback for free. Reused conceptually by ch.09 (Smells 2/3/4/5),
ch.12 (event storming), ch.22 (ACID -> ACD), and ch.23/24 (sagas rebuild
explicitly what Postgres gave away here for free).

Call order and exception semantics sourced verbatim from
examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/order/
OrderService.java (placeOrder, reference/monolith-before branch) and
_docs/08-designing-the-monolith.md ("The core logic: OrderService#placeOrder,
call by call"). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1680, 700

client = node(30, 270, 150, 80, ["Client", "POST /api/orders"], style="sub")

band = {"x": 200, "y": 170, "w": 1310, "h": 300,
        "label": "ONE @Transactional — OrderService#placeOrder (order/OrderService.java)",
        "fill": "#eaf4ec"}

STEP_Y, STEP_H, STEP_W, GUTTER = 240, 120, 145, 12
STEP_X0 = 220
steps = [
    ("validate", ["Validate customer", "customerRepository.findById", "404 if missing"]),
    ("reserve", ["Reserve stock (loop)", "InventoryService: find + reserve()", "pessimistic lock — SMELL[ch.16] no ACL"]),
    ("persist", ["Persist order", "orderRepository.save", "cascades order_items"]),
    ("charge", ["Charge payment", "PaymentService.charge", "decline -> rolls back all above"]),
    ("confirm", ["Confirm order", "order.confirm()", "status -> CONFIRMED"]),
    ("dispatch", ["Dispatch shipment", "ShippingService.dispatch", "always succeeds (demo)"]),
    ("notify", ["Notify customer", "NotificationService", "SMELL[ch.17]: sync, same txn"]),
    ("commit", ["COMMIT", "one Postgres transaction", "five contexts' writes, atomic"]),
]

step_nodes = {}
nodes = [client]
for i, (key, lines) in enumerate(steps):
    x = STEP_X0 + i * (STEP_W + GUTTER)
    style = "accent" if key == "commit" else "box"
    n = node(x, STEP_Y, STEP_W, STEP_H, lines, style=style)
    step_nodes[key] = n
    nodes.append(n)

response = node(1540, 270, 120, 80, ["201 Created", "OrderDto"], style="ink")
nodes.append(response)

edges = [connect(client, step_nodes["validate"])]
order_keys = [k for k, _ in steps]
for a, b in zip(order_keys, order_keys[1:]):
    edges.append(connect(step_nodes[a], step_nodes[b]))
edges.append(connect(step_nodes["commit"], response, amber=True, label="txn commits"))

# ---- failure branch 1: insufficient stock — exits before any write --------
insufficient = node(step_nodes["reserve"]["x"] - 10, 480, 260, 90,
                     ["409 Insufficient Stock", "InsufficientStockException", "no writes yet — exits before persist"],
                     style="ghost")
nodes.append(insufficient)
edges.append(connect(step_nodes["reserve"], insufficient, dashed=True, label="quantity exceeds on-hand"))

# ---- failure branch 2: payment decline — rolls back everything written ----
rollback = node(step_nodes["charge"]["x"] - 40, 480, 300, 100,
                ["402 Payment Required", "PaymentDeclinedException", "ROLLBACK ALL — reserve + persist undone"],
                style="ink")
nodes.append(rollback)
edges.append(connect(step_nodes["charge"], rollback, dashed=True, label="method contains DECLINE"))
edges.append({"x1": rollback["x"] + 20, "y1": rollback["y"], "x2": step_nodes["persist"]["x"] + 40,
              "y2": step_nodes["persist"]["y"] + step_nodes["persist"]["h"],
              "dashed": True, "amber": True})
edges.append({"x1": rollback["x"] + 60, "y1": rollback["y"], "x2": step_nodes["reserve"]["x"] + 160,
              "y2": step_nodes["reserve"]["y"] + step_nodes["reserve"]["h"],
              "dashed": True, "amber": True,
              "label": "one shared Postgres transaction — undone automatically, free", "ly": -8})

notes = [
    {"x": W / 2, "y": 32, "text": "ch.08 — OrderService#placeOrder: one @Transactional spans five bounded contexts",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "validate -> reserve -> persist -> charge -> confirm -> dispatch -> notify -> commit, in-process, one database",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 40, "y": H - 46,
     "text": "SMELL[ch.22]/[ch.23]/[ch.24]: this rollback-for-free behavior disappears the moment any one of these contexts gets its own database.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": H - 16,
     "text": "Sourced from examples/00-monolith/.../order/OrderService.java#placeOrder (reference/monolith-before) and _docs/08-designing-the-monolith.md.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "place-order-transaction-sequence", W, H,
    bands=[band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
