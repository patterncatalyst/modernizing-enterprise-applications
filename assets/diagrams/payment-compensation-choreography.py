#!/usr/bin/env python3
"""ch.23 figure: payment-compensation-choreography — compensation moves from
an in-line `catch` (ch.19) to a choreographed event reaction (ch.23,
DRQ-049), ending net-zero either way.

TOP band: the ch.19 shape (still true for the synchronous baseline,
`payment.mode=synchronous`, and for a reserve failure in BOTH modes).
`OrderService.placeOrder()`'s own try/catch calls the compensating gRPC
`Release` directly, on the SAME request thread, the instant the in-process
payment charge throws/declines — see
inventory-reserve-compensation-sequence.svg for the full ch.19 picture.

BOTTOM band: the ch.23 shape (`payment.mode=choreographed`). There is no
request-thread `catch` to lean on for a payment decline anymore — the
decline is discovered by a DIFFERENT process (the payment service) after the
checkout request has already returned `202 Accepted`. Compensation is
triggered by REACTING to the `payment.declined` EVENT, across TWO async
hops: the payment service emits it (via its own transactional outbox) ->
the monolith's `OrderSagaListener` consumes it and reacts -> the reaction
issues the SAME compensating gRPC `Release` ch.19 introduced. The
idempotency guard (status == PENDING) makes this fire at most once per
order (DRQ-051). Either path ends the same way: stock restored, NET-ZERO.

Sourced from: examples/00-monolith/.../order/OrderService.java
(placeOrder's catch, compensateRemoteReservations — ch.19, DRQ-042),
examples/00-monolith/.../order/OrderSagaListener.java (onPaymentDeclined —
ch.23, DRQ-049/-051), examples/05-payment-service/.../PaymentService.java
(processOrderPlaced emits payment.declined via its own outbox, DRQ-053),
and _plans/iterations/payment-plan.md (H3 — "Scenario 3 compensation via
choreography"). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1360, 760

# ============================================================================
# TOP band — ch.19: compensation from an in-line catch, same request thread
# ============================================================================
band_old = {"x": 20, "y": 60, "w": 1320, "h": 260,
            "label": "ch.19 — compensation from an in-line catch (same request thread; synchronous mode, and any reserve failure)",
            "fill": "#fafafa"}

checkout1 = node(40, 130, 260, 90,
                  ["OrderService.placeOrder()", "reserve (ok) -> persist -> charge"], style="user")
decline1 = node(340, 130, 230, 90, ["Payment charge declines", "in-process, SAME request"], style="box")
catch1 = node(610, 130, 260, 90,
               ["catch (RuntimeException ex)", "compensateRemoteReservations()"], style="ink")
release1 = node(910, 130, 250, 90,
                 ["gRPC Release :9004", "SAME request thread, no event involved"], style="accent")
netzero1 = node(1190, 130, 130, 90, ["NET-ZERO", "stock restored"], style="ink")

old_nodes = [checkout1, decline1, catch1, release1, netzero1]
old_edges = [
    connect(checkout1, decline1, label="charge()"),
    connect(decline1, catch1, label="throws", amber=True),
    connect(catch1, release1, label="Release (compensating)"),
    connect(release1, netzero1, label="restored"),
]

# ============================================================================
# BOTTOM band — ch.23: compensation via choreography, two async hops
# ============================================================================
band_new = {"x": 20, "y": 360, "w": 1320, "h": 360,
            "label": "ch.23 — compensation via choreography (choreographed mode): no catch to lean on — two async hops",
            "fill": "#eaf4ec"}

checkout2 = node(40, 420, 230, 80,
                  ["OrderService.placeOrder()", "reserve (ok) -> persist PENDING"], style="user")
resp2 = node(40, 520, 230, 70, ["202 Accepted", "request already returned"], style="box")

paymentsvc = node(340, 420, 250, 80,
                   ["PaymentService", "processOrderPlaced() declines"], style="box")
outbox2 = node(340, 520, 250, 70, ["payment.declined", "payment service's OWN outbox"], style="sub")

hop1 = node(650, 470, 90, 50, ["HOP 1", "emit"], style="ghost")

listener2 = node(800, 420, 270, 80,
                  ["OrderSagaListener.onPaymentDeclined()", "idempotent — status == PENDING"], style="accent")

hop2 = node(650, 560, 90, 50, ["HOP 2", "react"], style="ghost")

release2 = node(800, 520, 270, 80,
                  ["gRPC Release :9004", "SAME call ch.19 introduced"], style="accent")
netzero2 = node(1130, 470, 170, 90, ["NET-ZERO", "stock restored"], style="ink")

new_nodes = [checkout2, resp2, paymentsvc, outbox2, hop1, listener2, hop2, release2, netzero2]
new_edges = [
    connect(checkout2, resp2, label="return"),
    connect(resp2, paymentsvc, label="order.placed (earlier hop, already shown)", dashed=True),
    connect(paymentsvc, outbox2, label="charge declines"),
    connect(outbox2, listener2, label="emit -> Kafka -> consume (HOP 1 + HOP 2)", amber=True),
    connect(listener2, release2, label="react: Release (compensating)"),
    connect(release2, netzero2, label="restored"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.23 — compensation moves from an in-line catch to a choreographed event reaction (DRQ-049)",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 40, "y": 735,
     "text": "Sourced from examples/00-monolith/.../order/{OrderService,OrderSagaListener}.java, "
             "examples/05-payment-service/.../PaymentService.java, and _plans/iterations/payment-plan.md (H3, DRQ-049/-051).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "payment-compensation-choreography", W, H,
    bands=[band_old, band_new],
    nodes=old_nodes + new_nodes,
    edges=old_edges + new_edges,
    notes=notes,
)
