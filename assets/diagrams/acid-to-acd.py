#!/usr/bin/env python3
"""ch.22 figure: acid-to-acd — the conceptual spine of "ACID -> ACD: Living
Without Isolation." A legitimate before/after side-by-side (per the diagram
plan, this pair is one of the explicitly called-out combined figures, and
the top-priority figure for ch.22).

TOP band — ACID, today: `OrderService#placeOrder` wraps the inventory
decrement, order persistence, payment capture, and shipment dispatch in one
`@Transactional`, backed by one Postgres connection's write-ahead log. Any
failure anywhere rolls back everything, everywhere — the monolith's checkout
author never wrote a line of failure-recovery code, because the database
wrote it automatically. This is SMELL #3 in `examples/00-monolith/
SMELLS.md`: "One in-process ACID transaction spanning contexts."

BOTTOM band — ACD, after decomposition: each extracted service keeps full
LOCAL Atomicity/Consistency/Isolation/Durability inside its own database —
nothing about the cut asks a service to give up transactions inside its own
boundary. What does NOT survive is atomicity (and isolation) ACROSS
services: four separate commits, on four separate connections, to four
separate databases, each able to succeed or fail independently of the other
three. The property that used to be free — the database gave it to you for
nothing — is exactly the one this chapter's title names as gone: no
cross-service isolation. What replaces the automatic rollback is a
deliberately written, explicit compensating action (ch.23 choreographed,
ch.24 orchestrated) — a new forward-moving transaction, written by the
service that owns the data being undone, run by a saga rather than a
database. Two-phase commit (the textbook fix) is rejected for the reasons
ch.22 details: a single-point-of-failure coordinator, locks held across
blocking network round-trips, and a recentralization of the operational
ownership decomposition was meant to distribute.

Sourced from `_docs/22-acid-to-acd.md` ("SMELL #3," "Why the free rollback
cannot survive decomposition," "ACD: what survives the cut, and what has to
be rebuilt"), `examples/00-monolith/SMELLS.md` (SMELL #3's row and cure
mapping), and `order/OrderService.java#placeOrder`'s own `@Transactional`
javadoc ("the ACID -> ACD story told in ch.22"). No codenames; generic/public
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
# TOP band — ACID: one in-process transaction spans four contexts
# ============================================================================
band_old = {"x": 20, "y": 60, "w": 1360, "h": 260,
            "label": "ACID, today — one in-process transaction spans four contexts (SMELL #3)",
            "fill": "#fafafa"}

begin = node(40, 130, 220, 110, ["BEGIN @Transactional", "OrderService.placeOrder()"], style="user")
inv = node(280, 130, 190, 110, ["Inventory reserve"], style="box")
ordp = node(490, 130, 190, 110, ["Order persist"], style="box")
pay = node(700, 130, 190, 110, ["Payment charge"], style="box")
ship = node(910, 130, 190, 110, ["Shipping dispatch"], style="box")
commit = node(1120, 130, 240, 110, ["COMMIT — or ROLLBACK", "everything, automatically"], style="ink")

old_nodes = [begin, inv, ordp, pay, ship, commit]
old_edges = [
    connect(begin, inv),
    connect(inv, ordp),
    connect(ordp, pay),
    connect(pay, ship),
    connect(ship, commit, label="one WAL, one commit point", amber=True, ly=-62),
]

# ============================================================================
# BOTTOM band — ACD: four services, four commits, no cross-service isolation
# ============================================================================
band_new = {"x": 20, "y": 380, "w": 1360, "h": 400,
            "label": "ACD, after decomposition — four services, four commits, no cross-service isolation",
            "fill": "#eaf4ec"}

ordersvc = node(40, 450, 260, 100, ["Order service", "commits locally — own DB"], style="accent")
invsvc = node(330, 450, 260, 100, ["Inventory service", "commits locally — own DB"], style="accent")
paysvc = node(620, 450, 260, 100, ["Payment service", "commits locally — own DB"], style="accent")
shipsvc = node(910, 450, 260, 100, ["Shipping service", "commits locally — own DB"], style="accent")

saga = node(620, 610, 260, 110,
            ["Saga — compensating action", "explicit Release, triggered by", "payment.declined (ch.23/24)"],
            style="ink")

new_nodes = [ordersvc, invsvc, paysvc, shipsvc, saga]
new_edges = [
    connect(ordersvc, invsvc, dashed=True, label="separate commit, separate DB"),
    connect(invsvc, paysvc, dashed=True, label="no cross-service isolation"),
    connect(paysvc, shipsvc, dashed=True, label="no cross-service isolation"),
    connect(paysvc, saga, label="payment.declined", amber=True),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.22 — ACID -> ACD: the isolation given up, and what replaces the free rollback",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 40, "y": 272,
     "text": "Atomic + Consistent + Isolated + Durable — all four, free, because there is one process and one database. Any failure anywhere rolls back everything.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 575,
     "text": "Kept: full LOCAL ACID inside each service's own database. Given up: atomicity and isolation ACROSS services — four commits on four DBs, each independent of the other three.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 591,
     "text": "Atomic (locally) + Consistent (eventually) + Durable survive as ACD. No cross-service isolation is what the chapter's title names as gone — not a value judgement, a tradeoff.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 620, "y": 735,
     "text": "Nothing makes this compensating call happen automatically the way Postgres made the monolith's rollback automatic — a saga author writes it, names it, and tests it.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": 800,
     "text": "Sourced from _docs/22-acid-to-acd.md; examples/00-monolith/SMELLS.md (SMELL #3); order/OrderService.java#placeOrder's @Transactional javadoc. Two-phase commit is rejected (coordinator SPOF, blocking locks, recentralized ownership) — see the chapter's own treatment.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "acid-to-acd", W, H,
    bands=[band_old, band_new],
    nodes=old_nodes + new_nodes,
    edges=old_edges + new_edges,
    notes=notes,
)
