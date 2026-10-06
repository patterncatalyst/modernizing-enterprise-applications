#!/usr/bin/env python3
"""ch.10 figure: whitebox-pyramid-vs-blackbox-equivalence — the chapter's one
new idea, drawn as a legitimate side-by-side (per the diagram plan, this
pair is the explicitly called-out combined [SIDE] figure for ch.10).

LEFT — white-box: "Everything above this line is white-box: it knows the
monolith's internals, calls its Java classes directly or through Spring's
test slices, and in Tier 3's case inspects a real database it is free to
reset between runs... A white-box test can't even compile against code
that doesn't share the monolith's class names, let alone its database
rows."

RIGHT — black-box: the Newman behavior-equivalence suite
(`tooling/newman/mea.postman_collection.json`) "earns the name
behavior-equivalence suite for a specific, critical reason: every request
in it targets a `baseUrl` collection variable rather than a hardcoded
host, so the exact same collection can point at the monolith on one run
and at a freshly extracted Quarkus service on the next, with not one
assertion edited in between." It sees "a status code, a JSON body, and a
second HTTP response it chose to fetch itself. That restriction is the
whole point."

Sourced from `_docs/10-testing-the-monolith.md` ("From a pyramid to a
contract: why the equivalence suite is different") and
`tooling/newman/mea.postman_collection.json`. No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1560, 760

# ============================================================================
# LEFT band — white-box: the Tier 1-3 pyramid, calling Java directly
# ============================================================================
band_left = {"x": 20, "y": 70, "w": 740, "h": 560,
             "label": "White-box — Tier 1-3 pyramid calls Java directly, inspects a real DB",
             "fill": "#fafafa"}

tier3m = node(270, 140, 240, 70, ["Tier 3 — Integration", "real Postgres, Testcontainers"], style="box")
tier2m = node(220, 240, 340, 70, ["Tier 2 — Slice (@WebMvcTest)", "service mocked, web stack boots"], style="box")
tier1m = node(170, 340, 440, 70, ["Tier 1 — Unit (Mockito)", "every collaborator mocked"], style="box")
internals = node(110, 440, 560, 110, [
    "Monolith internals",
    "OrderService, PaymentService, live Postgres rows",
    "called directly in-process; Tier 3 inspects the DB",
], style="ink")

left_nodes = [tier3m, tier2m, tier1m, internals]
left_edges = [
    connect(tier3m, tier2m),
    connect(tier2m, tier1m),
    connect(tier1m, internals, label="direct Java calls, DB inspection (Tier 3)", amber=True),
]

# ============================================================================
# RIGHT band — black-box: the Newman behavior-equivalence suite
# ============================================================================
band_right = {"x": 800, "y": 70, "w": 740, "h": 560,
              "label": "Black-box — the behavior-equivalence suite sees only HTTP",
              "fill": "#eaf4ec"}

newman = node(940, 140, 460, 120, [
    "Newman — mea.postman_collection.json",
    "4 scenario folders — smoke, happy-path, OOS, declined",
    "+ Review Context Contract (forward reference)",
], style="accent")

baseurl = node(990, 300, 360, 80, [
    "baseUrl — collection variable",
    "re-pointable: monolith today, Quarkus tomorrow",
], style="box")

monolith_target = node(830, 440, 300, 110, [
    "Monolith :8080 (today)",
    "baseline this suite is captured from",
], style="box")

quarkus_target = node(1200, 440, 300, 110, [
    "Quarkus service (after extraction)",
    "equivalence gate: unedited collection",
], style="ghost")

right_nodes = [newman, baseurl, monolith_target, quarkus_target]
right_edges = [
    connect(newman, baseurl, label="HTTP only — status code + JSON body"),
    connect(baseurl, monolith_target),
    connect(baseurl, quarkus_target, dashed=True, label="re-pointed, zero assertions edited", amber=True),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.10 — same system, two kinds of test: white-box pyramid vs. black-box equivalence suite",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "the pyramid knows Java class names; the equivalence suite only knows baseUrl and HTTP",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 110, "y": 575,
     "text": "A white-box test can't compile against code that doesn't share the monolith's class names,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 110, "y": 591,
     "text": "let alone its database rows — it proves the implementation, not the behavior, is correct.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 940, "y": 575,
     "text": "Every request targets baseUrl, not a hardcoded host — the same collection, unedited, can point",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 940, "y": 591,
     "text": "at the monolith today or a Quarkus service tomorrow, with not one assertion changed in between.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 20, "y": H - 32,
     "text": "Sourced from _docs/10-testing-the-monolith.md (\"From a pyramid to a contract: why the equivalence suite is different\").",
     "anchor": "start", "size": 10, "color": "#777777"},
    {"x": 20, "y": H - 16,
     "text": "Collection: tooling/newman/mea.postman_collection.json.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "whitebox-pyramid-vs-blackbox-equivalence", W, H,
    bands=[band_left, band_right],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges,
    notes=notes,
)
