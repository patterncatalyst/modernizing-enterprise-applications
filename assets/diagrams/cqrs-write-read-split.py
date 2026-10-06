#!/usr/bin/env python3
"""ch.21 figure: cqrs-write-read-split — the CQRS shape this chapter teaches,
grounded in the one concrete instance the chapter names: `OrderService
#placeOrder`'s `Order` aggregate as the write model, `order.placed` as the
event that crosses the seam, and `examples/03-notification-service` as the
already-running CQRS read model ("CQRS: one model for writing, maybe several
for reading").

Write model: `order.OrderService#placeOrder`, in the monolith — an `Order`
aggregate, backed by ordinary relational rows, whose entire job is to decide
whether a checkout is valid and enforce that decision transactionally. It
stays exactly what it would be without any of this chapter's machinery: a
conventional aggregate, no event-sourcing anywhere near it.

Event: `order.placed`, published through the outbox + relay this book built
in ch.17/20 — the only new piece CQRS-lite adds, letting other services react
to what the write side already decided without querying its database
directly.

Read model: `examples/03-notification-service`'s `Notification` entity — its
own Postgres schema (`notification`, not the monolith's `public`), fed by a
single idempotent, check-then-insert reaction to `order.placed`
(`NotificationService#recordOrderPlaced`). The chapter calls this a
materialized view, Kleppmann's term: a cache of a derived answer, rebuildable
from the source event, not itself the source of truth for anything.

A second, lighter read-model box marks the chapter's own forward pointer —
"Chapter 26 is where this chapter's read/write split gets built in full," a
GraphQL gateway aggregating read models projected from order, inventory,
shipping, and notification — drawn as not-yet-built (ghost/dashed) since
ch.21 explicitly defers it; see the order-cqrs-split figure for ch.26's own
detailed write/read split inside order-service itself, which this figure
does not duplicate.

Sourced from `_docs/21-event-sourcing-and-cqrs.md` ("CQRS: one model for
writing, maybe several for reading", "`notification-service` is already a
CQRS read model, and nothing more", "Forward to the gateway..."),
`examples/00-monolith/.../order/OrderService.java#placeOrder`, and
`examples/03-notification-service/.../{Notification,NotificationService,
OrderPlacedConsumer}.java` (all verified and discussed in ch.17). No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1440, 640

band = {"x": 20, "y": 56, "w": 1400, "h": 440,
        "label": "CQRS: one model for writing, one or more models for reading", "fill": "#fafafa"}

write = node(50, 170, 300, 130,
             ["Write model: Order aggregate", "OrderService.placeOrder() (monolith)",
              "enforces invariants, Postgres orders"], style="accent")

event = node(400, 170, 220, 130,
             ["order.placed", "Kafka topic",
              "via outbox (ch.17/20)"], style="ink")

read1 = node(900, 90, 340, 130,
             ["Read model: notification-service", "Notification entity, own schema",
              "materialized view: has customer been told"], style="accent")

read2 = node(900, 350, 340, 130,
             ["Read model: ch.26 gateway (forward)", "would project order, inventory,",
              "shipping, notification — not yet built"], style="ghost")

nodes = [write, event, read1, read2]
edges = [
    connect(write, event, label="outbox row, same transaction as order", ly=-82),
    connect(event, read1, label="idempotent consume — check-then-insert"),
    connect(event, read2, dashed=True, label="ch.26 — not yet built"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.21 — CQRS: write model, event, read model(s)",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 50, "y": 522,
     "text": "The write side stays a conventional, invariant-enforcing aggregate — no event-sourcing machinery anywhere near it.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 900, "y": 522,
     "text": "A CQRS read model is eventually consistent and, because it's derived data, rebuildable by replaying",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 900, "y": 538,
     "text": "order.placed within retention.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 50, "y": 590,
     "text": "Sourced from _docs/21-event-sourcing-and-cqrs.md (\"CQRS: one model for writing, maybe several for reading\", \"notification-service is already a CQRS read model\").",
     "anchor": "start", "size": 10, "color": "#777777"},
    {"x": 50, "y": 606,
     "text": "Code: examples/00-monolith/.../order/OrderService.java#placeOrder; examples/03-notification-service/.../{Notification,NotificationService,OrderPlacedConsumer}.java.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "cqrs-write-read-split", W, H,
    bands=[band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
