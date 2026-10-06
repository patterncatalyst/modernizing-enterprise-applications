#!/usr/bin/env python3
"""ch.06 figure: plan-execute-validate-relay — the chapter's central concept,
"Agents: a model in a loop with tools" / the plan -> execute -> validate
relay: three model tiers plus two human gates, Frame-to-Verify.

TIER 1 — Opus, planning role: reads the Frame statement and the Map-phase
reconnaissance, drafts the step plan that becomes a decisions.md entry and a
build-plan.md row. Sits in front of HUMAN GATE 1 (plan approval) — "the
cheapest place in the whole lifecycle to catch a wrong assumption," and "a
second model's enthusiasm for its own plan is not a substitute for that
human's sign-off."

TIER 2 — Sonnet, execution role: carries out Generate — scaffolds the
project, writes the code, writes the tests, runs them. "It does not get to
decide, on its own authority, that its own output is correct."

TIER 3 — Opus again, now in the ADVERSARIAL role: re-reads the approved
plan, re-reads the diff Sonnet actually produced, checks the two against
each other and against the behavior-equivalence suite, before recommending
HUMAN GATE 2 (equivalence sign-off). "The same model tier that proposed the
plan is the one skeptical enough to doubt whether its own plan was executed
faithfully — a different posture than 'wrote it, therefore vouches for it.'"

Frame itself stays entirely human, per the chapter's own phase-by-phase
section ("Frame stays entirely human").

Tier names (Opus plan -> Sonnet execute -> Opus validate) are sourced from
`_plans/build-plan.md` line 229-230 ("the lgtm-relay tiering (Opus plan ->
Sonnet execute -> Opus validate)") and its ADLC phase table, lines 610-612
(Plan: "Opus produces the step plan... GATE: human approves plan before any
code"; Generate: "Sonnet scaffolds + writes code + tests"; Verify: "Opus
validates: run Citrus/Newman/Dev Services, check the equivalence gate...
GATE: human signs off on equivalence") — the same artifacts
`_docs/06-agents-skills-mcp.md` names directly (`decisions.md`,
`build-plan.md`, "Captured -- the actual Review-extraction plan draft").
No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 560

ROW_Y, ROW_H = 190, 130

frame = node(40, ROW_Y, 200, ROW_H,
             ["Frame — human", "states intent, writes", "the decisions.md entry"], style="user")
plan = node(260, ROW_Y, 230, ROW_H,
            ["Plan tier — planning model", "reads Frame + Map recon,", "drafts the step plan"], style="accent")
gate1 = node(510, ROW_Y, 200, ROW_H,
             ["Human gate 1", "plan approval —", "before any diff exists"], style="user")
execute = node(730, ROW_Y, 230, ROW_H,
               ["Execute tier — execution model", "Generate: scaffolds, writes", "code + tests, runs them"], style="accent")
validate = node(980, ROW_Y, 240, ROW_H,
                ["Validate tier — planning model", "adversarial: re-reads plan + diff", "against the equivalence suite"], style="accent")
gate2 = node(1240, ROW_Y, 200, ROW_H,
             ["Human gate 2", "equivalence sign-off —", "Verify phase"], style="user")

nodes = [frame, plan, gate1, execute, validate, gate2]

edges = [
    connect(frame, plan),
    connect(plan, gate1, amber=True),
    connect(gate1, execute, amber=True),
    connect(execute, validate),
    connect(validate, gate2, amber=True),
]

# ---- the bus lane: Plan and Validate are the same model tier -------------
BUS_Y = 420
validate_bottom_x = validate["x"] + validate["w"] / 2
plan_bottom_x = plan["x"] + plan["w"] / 2
row_bottom = ROW_Y + ROW_H

edges += [
    {"x1": validate_bottom_x, "y1": row_bottom, "x2": validate_bottom_x, "y2": BUS_Y, "dashed": True},
    {"x1": validate_bottom_x, "y1": BUS_Y, "x2": plan_bottom_x, "y2": BUS_Y, "dashed": True, "bidir": True,
     "label": "same model tier — now adversarial, not self-vouching"},
    {"x1": plan_bottom_x, "y1": BUS_Y, "x2": plan_bottom_x, "y2": row_bottom, "dashed": True},
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.06 — the relay: Plan -> Execute -> Validate, gated by two human sign-offs",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "The planning model plans and, later, validates adversarially; the execution model builds in between — the tier that drafted the plan is the one that checks it was executed faithfully",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 40, "y": H - 46,
     "text": "Sourced from _docs/06-agents-skills-mcp.md (\"Agents: a model in a loop with tools\" — the plan -> execute -> validate section).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": H - 16,
     "text": "Tier names from _plans/build-plan.md (line 229-230; ADLC phase table lines 610-612: Plan/Generate/Verify rows).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "plan-execute-validate-relay", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
