#!/usr/bin/env python3
"""ch.20 figure: dual-write-vs-outbox — the core teaching contrast of "The
Outbox Pattern, Done Right": the dual-write hazard this chapter names versus
the transactional outbox that cures it. A legitimate before/after
side-by-side (per the diagram plan, this pair is one of the explicitly
called-out combined figures).

TOP band — the dual-write hazard: "save to the database, then publish to
Kafka" as two independent writes to two independent systems over two
independent network paths. Nothing makes them succeed or fail together; a
crash, a client timeout, or a pod reschedule in the gap between them leaves
one system updated and the other not, with nothing downstream watching for
the mismatch. Reversing the order (publish first, save second) only
relocates the same gap — it does not close it.

BOTTOM band — the transactional outbox: the business write and the outbox
row commit together in the SAME transaction, on the SAME database — there is
no interval in which one exists and the other doesn't. The second hop
(outbox row to Kafka) is still a separate step, but it is now explicit,
bounded, and owned by one piece of infrastructure (`OutboxRelay`) instead of
hidden inside application code that pretends the gap isn't there. At-least-
once delivery out of the relay is safe only because the consumer on the far
side is idempotent.

Sourced from `_docs/20-outbox-pattern-done-right.md` ("The dual-write
problem" and "The transactional outbox" sections), which in turn cites
`examples/00-monolith/.../order/OrderService.java` (`placeOrder`,
`writeOrderPlacedOutboxEvent`), `examples/00-monolith/.../common/outbox/
{OutboxEvent,OutboxRelay}.java`, and `examples/03-notification-service/.../
NotificationService.java#recordOrderPlaced`. No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 820

# ============================================================================
# TOP band — the dual-write hazard
# ============================================================================
band_old = {"x": 20, "y": 60, "w": 1360, "h": 260,
            "label": "The dual-write hazard — two independent writes, two systems, no shared commit",
            "fill": "#fafafa"}

client1 = node(40, 130, 150, 110, ["Client", "POST /api/orders"], style="sub")
svc1 = node(210, 130, 220, 110, ["OrderService.placeOrder()", "no shared transaction boundary"], style="user")
write1 = node(450, 130, 210, 110, ["write 1: Postgres", "orderRepository.save(order)"], style="box")
write2 = node(680, 130, 210, 110, ["write 2: Kafka", "kafkaTemplate.send(order.placed)"], style="box")
crash = node(910, 130, 200, 110, ["CRASH WINDOW", "process dies, client times out,", "or the pod is rescheduled"], style="ghost")
outcome1 = node(1130, 130, 230, 110, ["NO SHARED OUTCOME", "order with no event — or an", "event for no order"], style="ink")

old_nodes = [client1, svc1, write1, write2, crash, outcome1]
old_edges = [
    connect(client1, svc1),
    connect(svc1, write1, label="orderRepository.save()"),
    connect(write1, write2, label="two independent writes, no joint commit", amber=True, ly=-62),
    connect(write2, crash, dashed=True),
    connect(crash, outcome1),
]

# ============================================================================
# BOTTOM band — the transactional outbox
# ============================================================================
band_new = {"x": 20, "y": 380, "w": 1360, "h": 400,
            "label": "The transactional outbox — one atomic commit, a decoupled at-least-once publish",
            "fill": "#eaf4ec"}

client2 = node(40, 450, 150, 100, ["Client", "POST /api/orders"], style="sub")
svc2 = node(210, 450, 220, 100, ["OrderService.placeOrder()", "BEGIN @Transactional"], style="user")
txnwrite = node(450, 450, 260, 100, ["order row + outbox row", "SAME transaction, SAME commit"], style="accent")
commit2 = node(730, 450, 220, 100, ["COMMIT", "atomic — one write, not two"], style="ink")

relay = node(450, 610, 220, 110, ["OutboxRelay", "@Scheduled poll, 2000ms"], style="box")
kafka2 = node(690, 610, 180, 110, ["Kafka", "order.placed"], style="ink")
consumer2 = node(890, 610, 260, 110, ["NotificationService", "idempotent consumer"], style="accent")

new_nodes = [client2, svc2, txnwrite, commit2, relay, kafka2, consumer2]
new_edges = [
    connect(client2, svc2),
    connect(svc2, txnwrite, label="order + outbox, SAME txn"),
    connect(txnwrite, commit2),
    connect(txnwrite, relay, dashed=True, label="poll WHERE published_at IS NULL (after commit)"),
    connect(relay, kafka2, label="publish (blocks for broker ack)"),
    connect(kafka2, consumer2, label="at-least-once -> idempotent consume"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.20 — the dual-write hazard vs. the transactional outbox",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 40, "y": 272,
     "text": "A crash, a client timeout, or a pod reschedule in the gap between the two writes leaves one system updated and the other not — nothing is watching for the mismatch.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 288,
     "text": "Reversing the order (publish, then save) relocates the same gap; it does not close it. No sequencing of two independent writes to two independent systems closes this gap.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 755,
     "text": "The second hop (table to broker) is still separate — but now explicit, bounded, and owned by one piece of infrastructure, not hidden inside application code.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 771,
     "text": "At-least-once delivery out of the relay is only safe because the consumer on the far side is idempotent (check-then-insert, deduped by aggregate id).",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 800,
     "text": "Sourced from _docs/20-outbox-pattern-done-right.md; examples/00-monolith/.../order/OrderService.java, .../common/outbox/{OutboxEvent,OutboxRelay}.java; examples/03-notification-service/.../NotificationService.java.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "dual-write-vs-outbox", W, H,
    bands=[band_old, band_new],
    nodes=old_nodes + new_nodes,
    edges=old_edges + new_edges,
    notes=notes,
)
