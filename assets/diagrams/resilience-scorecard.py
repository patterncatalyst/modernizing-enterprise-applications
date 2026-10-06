#!/usr/bin/env python3
"""ch.25 figure: resilience-scorecard — the two-column scorecard
`_docs/25-failure-modes-and-resilience.md` closes on in its own
"## The resilience scorecard" section: "here is exactly what this project
has built against the canonical resilience chassis, and exactly what it has
left for a reader's own production traffic to justify." Every row quotes
that section's two paragraphs directly; nothing here is inferred.

LEFT column — "Implemented, real, and proven by a negative check": the gRPC
deadline, at-least-once delivery paired with two-layer idempotent
consumption, the explicit compensating action, transparent failure
propagation at the proxy, and MicroProfile Health on both Quarkus services.

RIGHT column — "Deferred by design, with a stated reason": the circuit
breaker, the bulkhead, retry backoff/jitter/cap on the outbox relay, the
dead-letter path, platform-level health consumption, and an idempotency key
on the compensating `Release` call.

Sourced verbatim from `_docs/25-failure-modes-and-resilience.md`'s
"## The resilience scorecard" section. No codenames; generic/public names
only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 1020

band_left = {"x": 20, "y": 90, "w": 710, "h": 870,
             "label": "Implemented, real, and proven by a negative check", "fill": "#eaf4ec"}
band_right = {"x": 760, "y": 90, "w": 720, "h": 870,
              "label": "Deferred by design, with a stated reason", "fill": "#fafafa"}

LEFT_X, LEFT_W = 50, 650
RIGHT_X, RIGHT_W = 790, 660
ROW_Y0, ROW_H, GUTTER = 140, 120, 18

IMPLEMENTED = [
    ["Per-call gRPC deadline",
     "RemoteInventoryClient, inventory.grpc.timeout-ms",
     "the one synchronous, mutating hot-path collaborator"],
    ["At-least-once + two-layer idempotency",
     "outbox relay + NotificationService check-then-insert",
     "+ unique index; CDC consumer's ON CONFLICT upsert"],
    ["Compensating action, not rollback",
     "compensateRemoteReservations",
     "runs the moment a write crosses a service boundary"],
    ["Transparent failure propagation",
     "StranglerProxyRoute: throwExceptionOnFailure=false",
     "un-degraded, at the proxy layer"],
    ["MicroProfile Health endpoints",
     "both Quarkus services (notification, inventory)",
     "includes automatic broker-connectivity health"],
]

DEFERRED = [
    ["Circuit breaker",
     "around the inventory gRPC call",
     "no measured failure rate yet; call site is in the monolith"],
    ["Bulkhead",
     "isolating that call's resource consumption",
     "no observed starvation of the rest of the monolith's I/O"],
    ["Retry backoff, jitter, cap",
     "on the outbox relay's retry",
     "ch.20's own named gap"],
    ["Dead-letter path",
     "for a message that can never successfully process",
     "ch.20's poison-message gap, generalized"],
    ["Platform-level health consumption",
     "liveness/readiness gating traffic or restarts",
     "Part 9's subject, not this chapter's"],
    ["Idempotency key on Release",
     "the compensating call itself",
     "named in its own javadoc; inherited by ch.23/24"],
]

nodes = []
for i, lines in enumerate(IMPLEMENTED):
    y = ROW_Y0 + i * (ROW_H + GUTTER)
    nodes.append(node(LEFT_X, y, LEFT_W, ROW_H, lines, style="accent"))

for i, lines in enumerate(DEFERRED):
    y = ROW_Y0 + i * (ROW_H + GUTTER)
    nodes.append(node(RIGHT_X, y, RIGHT_W, ROW_H, lines, style="ghost"))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.25 — the resilience scorecard: what this project built, and what it left for measured traffic to justify",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "every implemented row was proven by a negative check (kill it, watch RED, restart it, watch GREEN) — every deferred row has a stated reason, not a silent gap",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 50, "y": H - 40,
     "text": "Chapter 3's discipline, restated: microservices are not the goal, and neither is a resilience pattern catalog completed for its own sake.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 50, "y": H - 16,
     "text": "Sourced verbatim from _docs/25-failure-modes-and-resilience.md (\"## The resilience scorecard\").",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "resilience-scorecard", W, H,
    bands=[band_left, band_right],
    nodes=nodes,
    edges=[],
    notes=notes,
)
