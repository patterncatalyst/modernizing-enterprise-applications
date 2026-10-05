#!/usr/bin/env python3
"""ch.23 figure: checkout-sync-async-contract — the sync->async checkout
contract change (DRQ-047, H1), before vs. after, side by side.

BEFORE (payment.mode=synchronous, the unchanged default baseline):
`OrderService.placeOrder()` reserves stock (sync gRPC, unchanged), charges
payment IN-PROCESS inside the SAME `@Transactional`, confirms the order, and
dispatches shipping — all before the HTTP response is written. The POST
itself carries the final answer: `201 Created` (order CONFIRMED) or `402
Payment Required` (charge declined, nothing persisted — the surrounding
transaction rolls back before the `order.placed` outbox row is ever
written).

AFTER (payment.mode=choreographed, current): `placeOrder()` still reserves
stock synchronously (gRPC Reserve is unchanged — only the payment OUTCOME
moves async), but stops calling payment/shipping in-line. It persists the
order `PENDING` and returns `202 Accepted` + `Location` IMMEDIATELY. The
terminal status — `CONFIRMED` or `PAYMENT_DECLINED` — is reached eventually,
over the choreography (see payment-choreographed-saga-sequence.svg), and the
client observes it by polling `GET /api/orders/{id}`. The synchronous `402`
is GONE: a decline is now an eventual `PAYMENT_DECLINED`, never an HTTP
status on the POST.

Sourced from: examples/00-monolith/.../order/{OrderController,
OrderService}.java (the `payment.mode` flag and its javadoc), and
_plans/iterations/payment-plan.md (S2/S6, DRQ-047 — "H1: the sync->async
checkout contract change"). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 680

band_before = {"x": 20, "y": 60, "w": 630, "h": 560,
               "label": "BEFORE — payment.mode=synchronous (default baseline)", "fill": "#fafafa"}
band_after = {"x": 710, "y": 60, "w": 670, "h": 560,
              "label": "AFTER — payment.mode=choreographed (current)", "fill": "#eaf4ec"}

# ---- BEFORE -----------------------------------------------------------------
client_b = node(50, 110, 240, 80, ["Client", "POST /api/orders"], style="sub")
reserve_b = node(50, 220, 240, 70, ["gRPC Reserve (sync)", "unchanged, :9004"], style="accent")
inprocess_b = node(50, 320, 240, 90,
                    ["Charge payment IN-PROCESS", "same @Transactional as reserve+persist"], style="user")
confirmed_b = node(50, 450, 240, 80, ["201 Created", "order CONFIRMED"], style="ink")
declined_b = node(330, 450, 240, 80, ["402 Payment Required", "charge declined — nothing persisted"], style="box")

before_nodes = [client_b, reserve_b, inprocess_b, confirmed_b, declined_b]
before_edges = [
    connect(client_b, reserve_b, label="POST"),
    connect(reserve_b, inprocess_b, label="reservation_ok = true"),
    connect(inprocess_b, confirmed_b, label="capture OK"),
    connect(inprocess_b, declined_b, label="capture declined", dashed=True),
]

# ---- AFTER --------------------------------------------------------------
client_a = node(730, 110, 240, 80, ["Client", "POST /api/orders"], style="sub")
reserve_a = node(730, 220, 240, 70, ["gRPC Reserve (sync)", "UNCHANGED, :9004"], style="accent")
pending_a = node(730, 320, 240, 90,
                  ["Persist PENDING", "no in-line charge; outbox row in same txn"], style="user")
resp_a = node(730, 450, 240, 80, ["202 Accepted", "+ Location — IMMEDIATELY"], style="ink")
poll_a = node(1010, 450, 320, 80, ["Client polls", "GET /api/orders/{id}"], style="sub")
confirmed_a = node(1010, 560, 150, 50, ["CONFIRMED", "(eventually)"], style="accent")
declined_a = node(1190, 560, 150, 50, ["PAYMENT_DECLINED", "(eventually)"], style="accent")

after_nodes = [client_a, reserve_a, pending_a, resp_a, poll_a, confirmed_a, declined_a]
after_edges = [
    connect(client_a, reserve_a, label="POST"),
    connect(reserve_a, pending_a, label="reservation_ok = true"),
    connect(pending_a, resp_a, label="return (txn committed)"),
    connect(resp_a, poll_a, label="observe via GET", amber=True),
    connect(poll_a, confirmed_a, label="captured"),
    connect(poll_a, declined_a, label="declined", dashed=True),
]

# transformation arrow between the two panels, mirroring the sdlc-vs-adlc convention
transform_edge = {"x1": 650, "y1": 160, "x2": 710, "y2": 160, "label": "DRQ-047", "amber": True}

# the removed synchronous 402, called out explicitly as GONE in the AFTER panel
removed_402 = node(410, 560, 240, 70,
                    ["synchronous 402 — GONE", "replaced by an eventual PAYMENT_DECLINED"], style="ghost")
removed_edge = connect(declined_b, removed_402, dashed=True, label="removed")

notes = [
    {"x": W / 2, "y": 32, "text": "ch.23 — the sync->async checkout contract change (DRQ-047, H1)",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 50, "y": 208, "text": "the POST carries the FINAL answer", "anchor": "start", "size": 11,
     "color": "#555555"},
    {"x": 730, "y": 208, "text": "the POST carries only an ACCEPTANCE; the terminal status arrives later",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 50, "y": 650,
     "text": "Sourced from examples/00-monolith/.../order/{OrderController,OrderService}.java "
             "(the payment.mode flag) and _plans/iterations/payment-plan.md (DRQ-047).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "checkout-sync-async-contract", W, H,
    bands=[band_before, band_after],
    nodes=before_nodes + after_nodes + [removed_402],
    edges=before_edges + after_edges + [transform_edge, removed_edge],
    notes=notes,
)
