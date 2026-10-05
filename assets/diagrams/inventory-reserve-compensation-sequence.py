#!/usr/bin/env python3
"""ch.19 figure: inventory-reserve-compensation-sequence — the cross-seam
saga-lite, contrasted with the smell it replaces.

TOP band: the OLD flow (SMELL #3, SMELLS.md row 3 — "one in-process ACID
transaction spanning contexts"). `OrderService#placeOrder` reserved stock
in-JVM, under a pessimistic lock, inside the SAME `@Transactional` as order
persistence and payment charge. A payment decline rolled the inventory
decrement back automatically — Postgres gave checkout a "free rollback"
because everything lived in one database, one transaction.

BOTTOM band: the NEW flow (current, r05/ch.19 S7/S11, DRQ-041/DRQ-042). Once
inventory owns its own database, that free rollback is gone: `reserve`
becomes a synchronous gRPC call (`InventoryGrpcServiceImpl#reserve`, an
atomic conditional `UPDATE ... WHERE quantity_on_hand >= :n`) that commits in
the inventory service's OWN transaction, outside the monolith's. If
`reservation_ok=false`, nothing was decremented and no compensation is
needed (maps to `InsufficientStockException`, 409). If `Reserve` succeeds but
checkout later fails (notably a payment decline), `OrderService`'s catch
block issues the compensating gRPC `Release` — explicit, best-effort
compensation (no idempotency key / saga ledger yet; a documented limitation
deferred to ch.23's choreographed saga) standing in for the automatic
rollback that disappeared when the schema split.

Sourced from: examples/00-monolith/.../order/OrderService.java (placeOrder,
compensateRemoteReservations), .../inventory/RemoteInventoryClient.java,
examples/00-monolith/SMELLS.md (smell #3), examples/04-inventory-service/
.../InventoryGrpcServiceImpl.java (reserve/release Javadoc on atomicity and
the at-least-once idempotency caveat), and _plans/iterations/inventory-plan.md
(DRQ-041/DRQ-042). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 920

# ============================================================================
# TOP band — OLD: in-process ACID transaction (SMELL #3, the "free rollback")
# ============================================================================
band_old = {"x": 20, "y": 60, "w": 1360, "h": 230,
            "label": "OLD — one in-process ACID transaction spanning contexts (SMELL #3): the \"free rollback\"",
            "fill": "#fafafa"}

client1 = node(40, 130, 150, 100, ["Client", "checkout request"], style="sub")
begin1 = node(220, 130, 210, 100, ["OrderService.placeOrder()", "BEGIN @Transactional"], style="user")
reserve1 = node(460, 130, 230, 100, ["Inventory reserve", "local in-JVM, pessimistic lock",
                                     "decrement in the SAME txn"], style="box")
pay1 = node(720, 130, 190, 100, ["Payment charge", "same txn"], style="box")
outcome1 = node(940, 130, 360, 100, ["COMMIT or ROLLBACK",
                                     "Postgres undoes the decrement",
                                     "automatically on ANY failure -- FREE"], style="ink")

old_nodes = [client1, begin1, reserve1, pay1, outcome1]
old_edges = [
    connect(client1, begin1),
    connect(begin1, reserve1, label="same @Transactional", ly=-56),
    connect(reserve1, pay1),
    connect(pay1, outcome1, label="decline -> automatic rollback (free)", amber=True, ly=-60),
]

# ============================================================================
# BOTTOM band — NEW: cross-seam saga-lite, explicit compensation
# ============================================================================
band_new = {"x": 20, "y": 340, "w": 1360, "h": 500,
            "label": "NEW (ch.19, current) -- cross-seam saga-lite: explicit compensation replaces the free rollback",
            "fill": "#eaf4ec"}

client2 = node(40, 400, 150, 90, ["Client", "checkout request"], style="sub")
checkout2 = node(220, 400, 220, 90, ["OrderService.placeOrder()",
                                     ":8080 monolith, local @Transactional",
                                     "(no longer spans inventory)"], style="user")
reserve_rpc = node(470, 400, 250, 90, ["gRPC Reserve :9004",
                                       "atomic conditional decrement",
                                       "inventory service's OWN DB"], style="accent")
pay2 = node(750, 400, 190, 90, ["Payment charge", "monolith, same local txn"], style="box")
confirm2 = node(990, 400, 350, 90, ["SUCCESS -- order CONFIRMED",
                                    "stock stays decremented",
                                    "(committed in inventory service)"], style="ink")

insufficient = node(470, 560, 250, 90, ["reservation_ok = false",
                                        "409 Insufficient Stock",
                                        "no decrement -> no Release needed"], style="ghost")
release_rpc = node(750, 560, 250, 90, ["gRPC Release :9004",
                                       "compensating re-increment",
                                       "DRQ-042 -- first taste of saga"], style="accent")
restored2 = node(1030, 560, 310, 90, ["DECLINE -- stock RESTORED",
                                      "best-effort, no idempotency key yet",
                                      "(full saga ledger deferred to ch.23)"], style="box")

new_nodes = [client2, checkout2, reserve_rpc, pay2, confirm2, insufficient, release_rpc, restored2]
new_edges = [
    {"x1": 190, "y1": 445, "x2": 220, "y2": 445, "label": "HTTP"},
    {"x1": 440, "y1": 445, "x2": 470, "y2": 445, "label": "Reserve"},
    {"x1": 720, "y1": 420, "x2": 750, "y2": 420, "label": "reservation_ok = true"},
    {"x1": 595, "y1": 490, "x2": 595, "y2": 560, "label": "reservation_ok = false", "dashed": True},
    {"x1": 940, "y1": 430, "x2": 990, "y2": 430, "label": "success", "amber": True},
    {"x1": 845, "y1": 490, "x2": 845, "y2": 560, "label": "decline", "dashed": True},
    {"x1": 1000, "y1": 605, "x2": 1030, "y2": 605, "label": "stock restored"},
]

notes = [
    {"x": W / 2, "y": 32, "text": "ch.19 -- Reserve/Release: the cross-seam saga-lite that replaces the in-process \"free rollback\"",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 40, "y": 272,
     "text": "One shared database, one transaction: a payment decline unwound the inventory decrement for free, with no application code written for it.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 400 - 10,
     "text": "Once inventory owns its own database, that automatic rollback cannot cross the service boundary -- compensation must be explicit.",
     "anchor": "start", "size": 11, "color": "#1d1d1d", "bold": True},

    {"x": 470, "y": 680,
     "text": "DRQ-042 (honest limitation): Release is an UNCONDITIONAL increment with no dedupe key -- a retried Release after an",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 470, "y": 694,
     "text": "at-least-once redelivery could over-restore stock; a full fix (idempotency key / saga ledger) is deferred to ch.23.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": 868,
     "text": "Sourced from examples/00-monolith/.../order/OrderService.java, .../inventory/RemoteInventoryClient.java, SMELLS.md (smell #3),",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 882,
     "text": "examples/04-inventory-service/.../InventoryGrpcServiceImpl.java, and _plans/iterations/inventory-plan.md (DRQ-041/DRQ-042).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "inventory-reserve-compensation-sequence", W, H,
    bands=[band_old, band_new],
    nodes=old_nodes + new_nodes,
    edges=old_edges + new_edges,
    notes=notes,
)
