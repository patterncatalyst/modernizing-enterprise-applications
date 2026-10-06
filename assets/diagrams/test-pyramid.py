#!/usr/bin/env python3
"""ch.10 figure: test-pyramid — the monolith's three-tier JUnit pyramid,
with the exact tier names, class counts, and "what it proves" framing
`_docs/10-testing-the-monolith.md` uses ("Three tiers, one `mvn verify`",
"Tier 1 — Mockito service unit tests", "Tier 2 — @WebMvcTest controller
slices", "Tier 3 — @DataJpaTest and a real Postgres").

Counts are quoted verbatim from the chapter, not invented for this figure:

- "Thirteen test classes carry the pyramid: four Tier 1 unit-test classes
  against the four services with real branching logic —
  InventoryServiceTest, OrderServiceTest, PaymentServiceTest,
  ReviewServiceTest... six Tier 2 @WebMvcTest slices against the six REST
  controllers, and a mixed Tier 3 of two @DataJpaTest repository tests
  plus one full-stack smoke test."
- "Counting methods rather than classes: forty-one plain @Test methods,
  plus one @ParameterizedTest in PaymentServiceTest that expands into four
  more executions at run time — forty-five test executions in total,
  every one of them green on a clean `mvn verify`."

What each tier proves is quoted from the chapter's own three bullets:
  Tier 1: "does this one class's logic do the right thing" — every
    collaborator mocked, no Spring context, no database, no network.
  Tier 2: "does this one controller bind, validate, and serialize
    correctly" — service layer mocked, only the web MVC stack boots.
  Tier 3: "does this actually work against a real Postgres" — real
    Hibernate mappings, a real Flyway-migrated schema, real transactions;
    "slow and few by design."

Sourced from `_docs/10-testing-the-monolith.md` ("Three tiers, one
`mvn verify`", "Tier 1 — Mockito service unit tests", "Tier 2 —
`@WebMvcTest` controller slices", "Tier 3 — `@DataJpaTest` and a real
Postgres") and `examples/00-monolith/src/test/`. No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1200, 780

CX = 600

# narrow at the top (Tier 3, slow and few), wide at the base (Tier 1, fast and many)
tier3 = node(CX - 280, 120, 560, 150, [
    "Tier 3 — Integration",
    "3 classes — 2 repository ITs + SixContextsSmokeTest",
    "real Postgres via Testcontainers, real Hibernate + Flyway",
    "proves: works against a real Postgres — slow, few by design",
], style="box")

tier2 = node(CX - 380, 310, 760, 150, [
    "Tier 2 — Slice (@WebMvcTest)",
    "6 classes — one @WebMvcTest slice per REST controller",
    "service layer mocked, only the web MVC stack boots",
    "proves: does this controller bind, validate, and serialize correctly",
], style="box")

tier1 = node(CX - 490, 500, 980, 150, [
    "Tier 1 — Unit (Mockito)",
    "4 classes — InventoryServiceTest, OrderServiceTest, PaymentServiceTest, ReviewServiceTest",
    "no Spring context, no database, no network — every collaborator mocked",
    "proves: does this one class's logic do the right thing",
], style="box")

nodes = [tier3, tier2, tier1]
edges = [
    connect(tier3, tier2),
    connect(tier2, tier1, label="one `mvn verify` run exercises all three tiers", amber=True),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.10 — the monolith's test pyramid: three tiers, one `mvn verify`",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "13 test classes, 41 @Test methods + 1 parameterized test (4 executions) = 45 executions, every one green",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": CX - 490, "y": H - 32,
     "text": "Sourced from _docs/10-testing-the-monolith.md (\"Three tiers, one mvn verify\"; Tier 1/2/3 sections).",
     "anchor": "start", "size": 10, "color": "#777777"},
    {"x": CX - 490, "y": H - 16,
     "text": "Class and method counts quoted verbatim from the chapter; tests live in examples/00-monolith/src/test/.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "test-pyramid", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
