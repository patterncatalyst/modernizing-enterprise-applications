#!/usr/bin/env python3
"""ch.20 figure: outbox-four-piece-pipeline — "Four pieces carry this pattern
end to end" (ch.20, "How the code works"): the write, the relay, the broker,
and the idempotent consumer, as one labeled flow.

PIECE 1 — the write: `OrderService#writeOrderPlacedOutboxEvent`, called from
inside `placeOrder`'s `@Transactional`, persists the outbox row through the
same `OutboxRepository` the relay reads from — one more row in the same
commit as the order itself.

PIECE 2 — the relay: `OutboxRelay`'s `@Scheduled` method polls on a fixed
delay (default 2000ms), pulling a bounded batch of unpublished rows
oldest-first via `findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()`
(backed by the partial index `idx_outbox_unpublished`).

PIECE 3 — the broker: Kafka's `order.placed` topic. The relay's send blocks
for the broker's acknowledgment (`.get(5, TimeUnit.SECONDS)`) before
`published_at` is stamped — publish-then-stamp, not the reverse — which is
what makes the at-least-once guarantee a duplicate risk rather than a
silent-loss risk.

PIECE 4 — the consumer: `NotificationService#recordOrderPlaced`, idempotent
by a cheap application-level check-then-insert (`findByOrderId` first,
insert second), backed by a database-level partial unique index as the layer
that holds even when the application check races and loses.

The same four-piece shape is not unique to this one pipeline — this
project's Payment extraction mirrors it idiomatically in its own service
(`examples/05-payment-service/.../PaymentOutboxEvent.java` /
`PaymentOutboxRelay.java`, explicitly documented in their own Javadoc as
"mirroring the monolith's proven `common.outbox.OutboxRelay`"), which is why
this figure is captioned as the pattern's shape, not a one-off diagram of a
single chapter's code.

Sourced from `_docs/20-outbox-pattern-done-right.md` ("How the code works"),
`examples/00-monolith/.../order/OrderService.java`,
`examples/00-monolith/.../common/outbox/{OutboxEvent,OutboxRelay}.java`,
`examples/03-notification-service/.../NotificationService.java`, and
`examples/05-payment-service/.../{PaymentOutboxEvent,PaymentOutboxRelay}.java`
(verified in this working tree) for the cross-service mirror. No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1440, 440

band = {"x": 20, "y": 56, "w": 1400, "h": 300, "label": "", "fill": "#fafafa"}

piece1 = node(40, 140, 300, 120,
              ["PIECE 1 — the write", "order row + outbox row,", "SAME @Transactional"], style="accent")
piece2 = node(380, 140, 280, 120,
              ["PIECE 2 — the relay", "@Scheduled poll, 2000ms,", "oldest-unpublished-first"], style="box")
piece3 = node(700, 140, 220, 120,
              ["PIECE 3 — the broker", "Kafka topic order.placed,", "keyed by aggregate id"], style="ink")
piece4 = node(960, 140, 320, 120,
              ["PIECE 4 — the consumer", "idempotent — check-then-insert,", "deduped by order id"], style="accent")

nodes = [piece1, piece2, piece3, piece4]
edges = [
    connect(piece1, piece2, label="poll WHERE published_at IS NULL (separate process, after commit)", ly=-70),
    connect(piece2, piece3, label="publish (blocks for broker ack)", ly=-70),
    connect(piece3, piece4, label="@Incoming — at-least-once delivery", ly=-70),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.20 — the outbox's four moving parts, end to end",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 40, "y": 290,
     "text": "OrderService#writeOrderPlacedOutboxEvent writes through the same OutboxRepository the relay below reads from.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 380, "y": 290,
     "text": "findTop50ByPublishedAtIsNullOrderByCreatedAtAsc(), backed by the partial index idx_outbox_unpublished.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 700, "y": 290,
     "text": ".get(5, TimeUnit.SECONDS) blocks for the ack; published_at is stamped only after.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 960, "y": 290,
     "text": "NotificationService#recordOrderPlaced: findByOrderId, then insert; backstopped by a DB unique index.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": 340,
     "text": "At-least-once (the relay) + idempotent (the consumer) is the whole guarantee — Kleppmann's \"effectively-once.\" Neither half alone is the pattern.",
     "anchor": "start", "size": 11, "color": "#1d1d1d"},

    {"x": 40, "y": 420,
     "text": "Sourced from _docs/20-outbox-pattern-done-right.md; examples/00-monolith/.../order/OrderService.java, .../common/outbox/{OutboxEvent,OutboxRelay}.java; examples/03-notification-service/.../NotificationService.java. Same shape mirrored in examples/05-payment-service/.../{PaymentOutboxEvent,PaymentOutboxRelay}.java.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "outbox-four-piece-pipeline", W, H,
    bands=[band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
