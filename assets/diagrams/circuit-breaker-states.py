#!/usr/bin/env python3
"""ch.25 figure: circuit-breaker-states — Michael Nygard's circuit breaker
state machine as `_docs/25-failure-modes-and-resilience.md` describes it in
"Where a circuit breaker and a bulkhead would go — and why they aren't here
yet": "a small state machine — closed (calls flow through normally), open
(calls fail immediately, without even attempting the network round-trip,
once a failure threshold trips), and half-open (a trial call periodically
checks whether the dependency has recovered before the breaker closes
again)." The half-open -> open re-trip (a failed trial call re-opens the
breaker) is the one transition the chapter implies rather than states
outright, included here because a state machine with only one exit from
half-open isn't the pattern Nygard or MicroProfile Fault Tolerance describe.

This is a named, not-yet-built pattern: the chapter is explicit that "None of
those three annotations [@Timeout, @CircuitBreaker, @Bulkhead] exist anywhere
in this codebase today," and names exactly where one would wrap —
`RemoteInventoryClient`'s `reserve` and `release` calls — and exactly which
chassis would supply it (SmallRye Fault Tolerance, Quarkus-side; the call
site itself still lives in the Spring monolith today). The ghost-styled
callout and dashed edge make that deferred status visible rather than
implying this diagram is live code.

Sourced from `_docs/25-failure-modes-and-resilience.md` ("Where a circuit
breaker and a bulkhead would go — and why they aren't here yet"). No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1300, 800

wrap = node(380, 60, 540, 110,
            ["Would wrap this call site",
             "RemoteInventoryClient#reserve / #release",
             "no @CircuitBreaker in this codebase today"],
            style="ghost")

closed = node(60, 300, 280, 140,
              ["CLOSED",
               "calls flow through normally",
               "no short-circuit, full network round-trip"],
              style="accent")

open_ = node(900, 300, 280, 140,
             ["OPEN",
              "calls fail immediately",
              "no network round-trip even attempted"],
             style="ink")

half = node(470, 540, 280, 140,
            ["HALF-OPEN",
             "a trial call checks",
             "whether the dependency has recovered"],
            style="user")

nodes = [wrap, closed, open_, half]

edges = [
    connect(wrap, closed, dashed=True,
            label="SmallRye Fault Tolerance @CircuitBreaker — named, not added"),

    connect(closed, open_, amber=True,
            label="failure threshold trips"),

    # open -> half-open: cool-down elapses, offset left of center so it
    # doesn't sit on top of the half-open -> open line below.
    {"x1": 970, "y1": 440, "x2": 660, "y2": 540,
     "label": "cool-down elapses, trial call allowed", "ly": -10},

    # half-open -> open: trial call fails, offset right of center.
    {"x1": 710, "y1": 540, "x2": 1060, "y2": 440,
     "dashed": True, "label": "trial call fails, re-opens", "ly": 18},

    connect(half, closed, amber=True,
            label="trial call succeeds"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.25 — the circuit breaker's state machine: named here, not yet built",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "closed -> open on a failure threshold; open -> half-open on cool-down; half-open resolves either way",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 60, "y": H - 40,
     "text": "Deferred by design: no measured failure rate yet justifies short-circuiting over retrying, and the call site is in the Spring monolith, not a Quarkus chassis.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 60, "y": H - 16,
     "text": "Sourced from _docs/25-failure-modes-and-resilience.md (\"Where a circuit breaker and a bulkhead would go — and why they aren't here yet\").",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "circuit-breaker-states", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
