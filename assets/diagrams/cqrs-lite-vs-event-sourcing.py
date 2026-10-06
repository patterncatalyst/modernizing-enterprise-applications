#!/usr/bin/env python3
"""ch.21 figure: cqrs-lite-vs-event-sourcing — the chapter's second contrast,
a legitimate side-by-side ([SIDE] in the diagram plan): LEFT this book's
actual, implemented approach (event-fed read models, "CQRS-lite"); RIGHT full
event sourcing, offered in the chapter purely as illustration and explicitly
NOT implemented anywhere in this codebase.

LEFT — CQRS-lite, as it actually runs today: the `Order` aggregate stays
conventional, mutable rows in Postgres; `order.placed` is one published fact,
not the whole history; `notification-service`'s `Notification` row is the
fold of that one fact, re-derivable by replaying the topic but not itself a
replay-on-read structure. System of record: the `orders` table.

RIGHT — full event sourcing, illustrative only: the chapter's own words,
"this codebase never actually implements it." The event log itself would be
the system of record; current state would not be a stored field but
`OrderState = eventStore.loadEvents(orderId).stream().reduce(OrderState
.empty(), OrderState::apply, (a, b) -> b)` — the chapter's own sketch,
computed on demand or bounded by a snapshot, never persisted as a row anyone
updates in place.

The chapter's own reasons for staying on the left rather than building the
right are named directly: nothing in this migration needs
replay-from-genesis state reconstruction, and event sourcing's real costs —
schema evolution (upcasting) forever, a snapshot strategy to bound replay
cost, and tooling/team unfamiliarity — are exactly the unjustified
complexity ch.3's "microservices are not themselves the goal" discipline
warns against when adopted without a specific need.

Sourced from `_docs/21-event-sourcing-and-cqrs.md` ("Event sourcing: when the
log stops being a side effect and becomes the record", "Why this book builds
CQRS-lite and not event sourcing"), and `examples/03-notification-service/
.../{Notification,NotificationService}.java` for the left side (verified,
discussed in ch.17). The right side has no corresponding code in this
repository, by the chapter's own design — drawn in the dashed/ghost style
reserved for that reason. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1560, 700

# ============================================================================
# LEFT — CQRS-lite: this book's actual, implemented approach
# ============================================================================
band_left = {"x": 20, "y": 60, "w": 740, "h": 560,
             "label": "CQRS-lite — this book's approach, implemented", "fill": "#eaf4ec"}

agg = node(140, 120, 500, 110,
           ["Order aggregate (the write model)", "OrderService.placeOrder() — mutable rows",
            "current state stored directly, Postgres"], style="accent")

evt = node(140, 280, 500, 100,
           ["order.placed event", "published via outbox (ch.17/20)",
            "one fact, not the whole history"], style="ink")

rm = node(140, 430, 500, 120,
          ["notification-service read model", "Notification row = fold of one fact",
           "idempotent check-then-insert, own schema"], style="accent")

left_nodes = [agg, evt, rm]
left_edges = [
    connect(agg, evt, label="outbox row, same transaction"),
    connect(evt, rm, label="idempotent consume"),
]

# ============================================================================
# RIGHT — full event sourcing: illustrative only, not implemented here
# ============================================================================
band_right = {"x": 800, "y": 60, "w": 740, "h": 560,
              "label": "Full event sourcing — illustrative only, not implemented here", "fill": "#fafafa"}

log = node(880, 120, 500, 110,
           ["Event log (the write model, illustrative)", "OrderPlaced, PaymentCaptured,",
            "ShipmentDispatched, OrderConfirmed"], style="ghost")

fold = node(880, 280, 500, 100,
            ["Replay / fold on load", "OrderState = reduce(events, OrderState::apply)",
             "ch.21's own sketch — no such class exists"], style="ghost")

state = node(880, 430, 500, 120,
             ["Current state = computed, not stored", "no mutable row — rebuilt from genesis",
              "or last snapshot, on every read"], style="ghost")

right_nodes = [log, fold, state]
right_edges = [
    connect(log, fold, dashed=True, label="replay (illustrative)"),
    connect(fold, state, dashed=True, label="compute on read"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.21 — CQRS-lite (what this book builds) vs. full event sourcing (illustrative contrast)",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 140, "y": 570,
     "text": "System of record: the orders table. Rebuildable from order.placed only as far as the topic's retention reaches.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 140, "y": 586,
     "text": "Past that window, the write side's own durable storage — not the log — is what the system relies on.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 880, "y": 570,
     "text": "System of record: the log itself — current state is derived, never stored directly. The log is the only copy of the fact.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 880, "y": 586,
     "text": "Real costs this book declines to pay here: schema evolution forever (upcasting), a snapshot strategy, team/tooling unfamiliarity.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 20, "y": 650,
     "text": "Sourced from _docs/21-event-sourcing-and-cqrs.md (\"Event sourcing: when the log stops being a side effect\", \"Why this book builds CQRS-lite and not event sourcing\").",
     "anchor": "start", "size": 10, "color": "#777777"},
    {"x": 20, "y": 666,
     "text": "Left: examples/03-notification-service/.../{Notification,NotificationService}.java (verified, ch.17). Right: illustrative only — no event store exists in this codebase, by design.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "cqrs-lite-vs-event-sourcing", W, H,
    bands=[band_left, band_right],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges,
    notes=notes,
)
