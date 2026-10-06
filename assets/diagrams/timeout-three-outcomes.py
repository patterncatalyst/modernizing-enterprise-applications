#!/usr/bin/env python3
"""ch.25 figure: timeout-three-outcomes — the structural claim
_docs/25-failure-modes-and-resilience.md opens its "Timeouts and deadlines"
section with: "A method call inside one JVM either returns or throws — there
is no third outcome. A gRPC call across a network can return, throw, or
simply never come back at all." The chapter ties that third outcome directly
to Chapter 19's extraction: "The moment Chapter 19 moved that call behind a
gRPC stub reaching a separate process over a separate network, a third
outcome became possible for the first time in this project's history."

Left reference box: the in-JVM call (two outcomes only), contrasted with the
gRPC call below it (three outcomes). The three outcome boxes mirror
`RemoteInventoryClient#reserve`'s own documented behavior: returns a
`ReserveReply`; throws `StatusRuntimeException` (`DEADLINE_EXCEEDED` /
`UNAVAILABLE`, left unmapped so Spring's default handling turns it into a
`500`); or, absent any bound, never returns. The never-returns branch is
followed to Sam Newman's and Michael Nygard's named consequence the chapter
quotes: a blocked thread holds its pool slot for as long as the far side
takes, and "enough of them, blocking long enough, exhausts the monolith's
own thread pool and turns one slow downstream dependency into a total
outage" — cascading failure. The ghost box shows what actually prevents that
chain in this project's own code: `withDeadlineAfter(timeoutMs)`
(`inventory.grpc.timeout-ms`, default 5000ms) converts outcome 3 into
outcome 2 before cascading failure has time to set in — this project has not
observed the cascade, because the deadline already closes it off.

Sourced from `_docs/25-failure-modes-and-resilience.md` ("Timeouts and
deadlines: the gRPC client that fails cleanly instead of hanging") and
`examples/00-monolith/.../inventory/RemoteInventoryClient.java`'s `reserve`
method and javadoc, already quoted in Chapter 19. No codenames; generic/
public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 880

injvm = node(60, 60, 320, 110,
             ["In-JVM call — the old baseline", "one process, one call stack",
              "returns or throws, in microseconds — no third outcome"],
             style="sub")

caller = node(60, 300, 220, 110,
              ["Checkout thread",
               "inventoryService.reserve(sku, qty)"],
              style="user")

grpc = node(340, 300, 280, 110,
            ["gRPC call crosses the network",
             "RemoteInventoryClient#reserve",
             "withDeadlineAfter(timeoutMs)"],
            style="accent")

outcome1 = node(700, 140, 300, 110,
                ["Outcome 1 — returns",
                 "ReserveReply",
                 "reservation result known, checkout proceeds"],
                style="box")

outcome2 = node(700, 300, 300, 110,
                ["Outcome 2 — throws",
                 "StatusRuntimeException",
                 "DEADLINE_EXCEEDED / UNAVAILABLE, unmapped -> 500"],
                style="box")

outcome3 = node(700, 460, 300, 110,
                ["Outcome 3 — never returns",
                 "the outcome only a network call adds",
                 "nothing tells the caller which case this is"],
                style="ink")

mitigate = node(1080, 300, 360, 110,
                ["Bounded here, today",
                 "the deadline fires first: outcome 3",
                 "becomes outcome 2 before it can cascade"],
                style="ghost")

cascade1 = node(700, 620, 300, 100,
                ["Thread stays blocked",
                 "holds its connection/pool slot",
                 "for as long as the far side takes"],
                style="box")

cascade2 = node(1110, 620, 360, 100,
                ["Cascading failure (Nygard)",
                 "enough blocked threads exhaust the pool",
                 "a healthy caller goes down with it"],
                style="ink")

nodes = [injvm, caller, grpc, outcome1, outcome2, outcome3, mitigate, cascade1, cascade2]

edges = [
    connect(injvm, grpc, dashed=True,
            label="ch.19: this call moved behind a gRPC stub"),
    connect(caller, grpc),
    connect(grpc, outcome1),
    connect(grpc, outcome2),
    connect(grpc, outcome3, amber=True,
            label="structural: can simply not come back", ly=10),
    connect(outcome3, mitigate, dashed=True,
            label="inventory.grpc.timeout-ms bounds it here"),
    connect(outcome3, cascade1, amber=True,
            label="without a bound in place"),
    connect(cascade1, cascade2, amber=True,
            label="repeats under load", ly=-14),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.25 — a call across the gRPC boundary has a third outcome an in-JVM call never had",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "returns, throws, or never comes back — and the third outcome is where cascading failure starts",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 60, "y": H - 40,
     "text": "This project's deadline already closes off the cascade below — the chain is the risk a bare gRPC call carries, not an incident this book observed.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 60, "y": H - 16,
     "text": "Sourced from _docs/25-failure-modes-and-resilience.md (\"Timeouts and deadlines\") and RemoteInventoryClient#reserve's deadline and javadoc (examples/00-monolith/).",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "timeout-three-outcomes", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
