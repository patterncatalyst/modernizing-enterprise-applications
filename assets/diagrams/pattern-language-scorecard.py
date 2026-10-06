#!/usr/bin/env python3
"""ch.32 figure: pattern-language-scorecard — Richardson's microservices
pattern map re-walked against the completed migration, grouped by concern, each
pattern tagged with where it actually landed (the chapter/service) and a verdict:
accent = used in anger, box = used/adapted, ghost (dashed) = deliberately
deferred and named as such.

This is a synthesis figure: no new claim, a map of claims the book already made
and verified in their own chapters. Grouping follows microservices.io's concern
areas (decomposition, data, communication, reliability, observability,
deployment, cross-cutting/chassis), narrowed to what this book actually walked.

Sourced from: the chapters and examples named in each box; _plans/decisions.md.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1500, 1020

COL_W, COLS = 330, 4
GX = 40        # left margin
GUT = 20       # gutter
ROW_H = 92
RGUT = 16


def cell(col, row, lines, style="box"):
    x = GX + col * (COL_W + GUT)
    y = 150 + row * (ROW_H + RGUT)
    return node(x, y, COL_W, ROW_H, lines, style=style)


# Each column is a concern; a header note sits above it.
nodes = [
    # --- col 0: Decomposition ---
    cell(0, 0, ["Strangler Fig", "ch.14-26 · strangler-proxy", "used — six seams, front door"], "accent"),
    cell(0, 1, ["Bounded Context / DDD", "ch.11-13 · the six contexts", "used — event storming -> seams"], "accent"),
    cell(0, 2, ["Anti-Corruption Layer", "ch.16 · content-based routing", "used — honest ACL at the seam"], "accent"),
    cell(0, 3, ["Branch by Abstraction", "ch.14 · flag-gated proxy", "adapted — routing flag form"], "box"),

    # --- col 1: Data ---
    cell(1, 0, ["Database per Service", "ch.18 · owned schemas", "used — all six own their data"], "accent"),
    cell(1, 1, ["Transactional Outbox", "ch.17,20 · outbox relay", "used — no dual write"], "accent"),
    cell(1, 2, ["Change Data Capture", "ch.19 · Debezium backfill", "used — transition-only, retired"], "accent"),
    cell(1, 3, ["Event Sourcing / CQRS", "ch.21,26 · order read model", "CQRS used · full ES deferred"], "box"),

    # --- col 2: Communication & contracts ---
    cell(2, 0, ["Saga (choreographed)", "ch.23 · payment", "used — events + compensation"], "accent"),
    cell(2, 1, ["Saga (orchestrated)", "ch.24 · shipping · Camel EIP", "used — InMemorySagaService"], "accent"),
    cell(2, 2, ["API Composition / GraphQL", "ch.26 · graphql-gateway", "used — aggregation gateway"], "accent"),
    cell(2, 3, ["Schema Registry / contracts", "ch.28 · Avro + Apicurio", "used — demonstrator (isolated)"], "box"),

    # --- col 3: Reliability, ops, chassis ---
    cell(3, 0, ["Microservice Chassis", "ch.27 · Quarkus/MicroProfile", "used — config/health/metrics"], "accent"),
    cell(3, 1, ["Reliability patterns", "ch.25 · timeout/retry/idemp.", "used where earned · CB deferred"], "box"),
    cell(3, 2, ["Service Mesh + Tracing", "ch.30 · Istio + LGTM", "used — mTLS, OTel (minikube)"], "accent"),
    cell(3, 3, ["Supply-chain + CI gates", "ch.31 · SBOM/scan/equiv.", "used — verified live"], "accent"),
]

# column headers
headers = ["Decomposition", "Data", "Communication & Contracts", "Reliability · Ops · Chassis"]
notes = [
    {"x": W / 2, "y": 40,
     "text": "ch.32 — the pattern language, re-walked: where each pattern actually landed, and the verdict",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 62,
     "text": "accent = used in anger · outline = used/adapted or partial · dashed = deliberately deferred and named",
     "anchor": "middle", "size": 11, "color": "#555555"},
]
for i, h in enumerate(headers):
    x = GX + i * (COL_W + GUT) + COL_W / 2
    notes.append({"x": x, "y": 128, "text": h, "anchor": "middle", "bold": True, "size": 13, "color": "#2f5f3d"})

# a deferred row across the bottom (ghosts)
dnodes = [
    cell(0, 4, ["Rewrite / big-bang", "— not taken —", "deferred — strangler instead"], "ghost"),
    cell(1, 4, ["Full Event Sourcing", "ch.21 · concept only", "deferred — CQRS-lite sufficed"], "ghost"),
    cell(2, 4, ["LRA / Narayana saga", "ch.24 · named", "deferred — in-memory saga"], "ghost"),
    cell(3, 4, ["GitOps operator + canary", "ch.31 · named", "deferred — no speculative infra"], "ghost"),
]
nodes += dnodes
notes.append({"x": GX, "y": 150 + 4 * (ROW_H + RGUT) - 8,
              "text": "deliberately left on the shelf:", "anchor": "start", "size": 11, "color": "#777777"})

notes.append({"x": GX, "y": H - 20,
              "text": "Every box is a claim verified in its own chapter; this figure maps them, it does not re-prove them.",
              "anchor": "start", "size": 10, "color": "#777777"})

g.emit("pattern-language-scorecard", W, H, bands=[], nodes=nodes, edges=[], notes=notes)
