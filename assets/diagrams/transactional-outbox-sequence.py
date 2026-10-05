#!/usr/bin/env python3
"""ch.17 figure: transactional-outbox-sequence — the outbox guarantee,
contrasted with the smell it replaces.

TOP band: the OLD flow (cured in r04/S8, SMELL #4) — `OrderService#placeOrder`
ran inventory-reserve, order-persist, payment-charge, and shipping-dispatch,
then called `NotificationService#sendOrderConfirmation()` SYNCHRONOUSLY,
in-process, inside the SAME `@Transactional` — before committing. Any
failure anywhere, including in notification delivery, rolled back every
other write in the same transaction.

BOTTOM band: the NEW flow (current) — the same checkout steps, but the last
write before COMMIT is an outbox row (`order.placed`), persisted in the SAME
transaction as the order itself (one atomic commit — see
`OrderService#writeOrderPlacedOutboxEvent`, called from inside `placeOrder`'s
`@Transactional`). A separate, later, asynchronous step — the `@Scheduled`
`OutboxRelay` — polls for unpublished rows, publishes to Kafka, and stamps
`published_at` only after the broker acks the send (AT-LEAST-ONCE: a crash
between the ack and the stamp republishes the identical row next poll). The
notification service's `OrderPlacedConsumer` is IDEMPOTENT by construction
(check-then-insert, deduped by orderId — see `NotificationService
#recordOrderPlaced`), which is what makes at-least-once delivery safe.

Sourced from: examples/00-monolith/.../order/OrderService.java (placeOrder,
writeOrderPlacedOutboxEvent), examples/00-monolith/.../common/outbox/
{OutboxEvent,OutboxRelay}.java (Javadoc: atomic write, at-least-once,
dedupe-by-aggregateId contract), examples/00-monolith/SMELLS.md (smell #4,
cured in r04/S8), examples/03-notification-service/.../NotificationService
.java (recordOrderPlaced: idempotent check-then-insert), and
_plans/iterations/notification-plan.md (DRQ-034/DRQ-037). No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1880, 980

# ============================================================================
# TOP band — OLD: synchronous notification inside the checkout transaction
# ============================================================================
band_old = {"x": 20, "y": 60, "w": 1840, "h": 230,
            "label": "OLD — cured in r04/S8: synchronous notification inside the checkout transaction (SMELL #4)",
            "fill": "#fafafa"}

client1 = node(40, 130, 150, 100, ["Client", "POST /api/orders"], style="sub")
begin1 = node(210, 130, 190, 100, ["OrderService.placeOrder()", "BEGIN @Transactional"], style="user")
inv1 = node(420, 130, 190, 100, ["Inventory reserve +", "Order persist"], style="box")
pay1 = node(630, 130, 170, 100, ["Payment charge"], style="box")
ship1 = node(820, 130, 170, 100, ["Shipping dispatch"], style="box")
notifsync = node(1010, 130, 250, 100,
                  ["NotificationService", "sendOrderConfirmation()",
                   "SYNCHRONOUS — same txn"], style="ghost")
commit1 = node(1280, 130, 230, 100,
               ["COMMIT", "(or ROLLBACK everything,", "incl. on notification failure)"], style="ink")

old_nodes = [client1, begin1, inv1, pay1, ship1, notifsync, commit1]
old_edges = [
    connect(client1, begin1),
    connect(begin1, inv1),
    connect(inv1, pay1),
    connect(pay1, ship1),
    connect(ship1, notifsync, label="SMELL #4 (removed, r04/S8)", amber=True, ly=-60),
    connect(notifsync, commit1, label="blocks checkout's own commit", amber=True, ly=-60),
]

# ============================================================================
# BOTTOM band — NEW: transactional outbox + async relay + idempotent consumer
# ============================================================================
band_new = {"x": 20, "y": 340, "w": 1840, "h": 500,
            "label": "NEW (current) — transactional outbox + async event-driven consumer",
            "fill": "#eaf4ec"}
band_txn = {"x": 190, "y": 430, "w": 1320, "h": 150,
            "label": "ONE @Transactional — atomic, all-or-nothing commit",
            "fill": "#ffffff"}

client2 = node(40, 450, 150, 100, ["Client", "POST /api/orders"], style="sub")
begin2 = node(210, 450, 190, 100, ["OrderService.placeOrder()", "BEGIN @Transactional"], style="user")
inv2 = node(420, 450, 190, 100, ["Inventory reserve +", "Order persist"], style="box")
pay2 = node(630, 450, 170, 100, ["Payment charge"], style="box")
ship2 = node(820, 450, 170, 100, ["Shipping dispatch"], style="box")
outboxwrite = node(1010, 450, 250, 100,
                    ["Write outbox row", "order.placed — SAME txn"], style="accent")
commit2 = node(1280, 450, 230, 100,
               ["COMMIT", "order + outbox — ONE atomic write"], style="ink")

relay = node(1010, 680, 250, 120,
             ["OutboxRelay", "@Scheduled poller (2000ms)",
              "publish -> stamp published_at on ack"], style="box")
kafka = node(1310, 680, 220, 120,
             ["Kafka topic", "order.placed", "at-least-once delivery"], style="ink")
consumer = node(1580, 680, 260, 120,
                ["OrderPlacedConsumer", "idempotent — dedupe by orderId",
                 "persists to own schema"], style="accent")

new_nodes = [client2, begin2, inv2, pay2, ship2, outboxwrite, commit2, relay, kafka, consumer]
new_edges = [
    connect(client2, begin2),
    connect(begin2, inv2),
    connect(inv2, pay2),
    connect(pay2, ship2),
    connect(ship2, outboxwrite, label="write (not publish)", amber=True, ly=-60),
    connect(outboxwrite, commit2),
    connect(outboxwrite, relay,
            label="poll WHERE published_at IS NULL (separate process, after commit)", dashed=True),
    connect(relay, kafka, label="publish (blocks for broker ack)", ly=-75),
    connect(kafka, consumer, label="@Incoming (idempotent)", ly=-75),
]

notes = [
    {"x": W / 2, "y": 32, "text": "ch.17 — The transactional-outbox guarantee: before (synchronous, SMELL #4) vs after (atomic outbox write + async relay)",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 40, "y": 272,
     "text": "Any failure anywhere in this chain — including the notification call — rolled back inventory, payment, and shipping together.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 400,
     "text": "Notification delivery is now OUTSIDE the checkout transaction's latency and failure domain: the outbox write can only",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 416,
     "text": "succeed or fail with the rest of the transaction — it never talks to Kafka itself.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 1010, "y": 822,
     "text": "AT-LEAST-ONCE: a crash between the Kafka ack and the published_at", "anchor": "start", "size": 10, "color": "#555555"},
    {"x": 1010, "y": 836,
     "text": "stamp republishes the identical row on the next poll.", "anchor": "start", "size": 10, "color": "#555555"},

    {"x": 1580, "y": 822,
     "text": "IDEMPOTENT by design: check-then-insert by orderId makes a", "anchor": "start", "size": 10, "color": "#555555"},
    {"x": 1580, "y": 836,
     "text": "redelivered event a safe no-op (DRQ-034 / DRQ-037).", "anchor": "start", "size": 10, "color": "#555555"},

    {"x": 40, "y": 955,
     "text": "Same business steps, same transaction boundary for order + outbox — only the LAST write before commit changed (a call -> a row).",
     "anchor": "start", "size": 11, "color": "#555555"},
]

g.emit(
    "transactional-outbox-sequence", W, H,
    bands=[band_old, band_new, band_txn],
    nodes=old_nodes + new_nodes,
    edges=old_edges + new_edges,
    notes=notes,
)
