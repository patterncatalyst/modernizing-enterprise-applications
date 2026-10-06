#!/usr/bin/env python3
"""ch.02 figure: ledger-files-to-adlc-phases — maps the four ledger files
`_docs/02-project-ledger.md` walks through (`_plans/build-plan.md`,
`_plans/iterations/r02-plan.md`, `_plans/iterations/r02-status.md`,
`_plans/decisions.md`, and the still-unwritten `_plans/reconciliation.md`)
onto the ADLC's seven phases, and shows how an interrupted session resumes
from the same two files.

Phase list and the one-line gloss on each phase ("a human states what should
happen, an agent does reconnaissance, produces a plan, writes code against
that plan, verifies the result, rolls it out, and records the outcome") are
quoted/paraphrased from the chapter's "Why a ledger, and why now" section
(line ~27-30): "Frame -> Map -> Plan -> Generate -> Verify -> Operate ->
Reconcile."

File-to-phase ties:
- build-plan.md + r02-plan.md under Plan: "a single long document... covers
  the chosen approach... the ADLC's own phase table... an iteration-by-
  iteration release plan" (build plan section) and r02-plan.md "breaks the
  iteration into numbered, acceptance-gated steps before any of them start"
  (per-iteration section).
- r02-status.md under Generate/Verify: "updated as those steps complete --
  including, critically, at the exact moment someone has to stop."
- decisions.md + reconciliation.md under Reconcile: the chapter's own quoted
  phase-table row -- "| 7. Reconcile | Append decision outcomes, update
  build-plan status, record drift | -- | the three ledger artifacts |
  change log / traceability |". reconciliation.md is drawn ghosted/dashed
  because the chapter is explicit it "does not exist in this project yet...
  scoped as one of the walking skeleton's own remaining steps."
- The feedback bus (Reconcile -> build-plan.md) is the same phase-table row:
  Reconcile also "update[s] build-plan status," so the discipline loops back
  into the same file Plan produced.
- Plan and Verify are tinted "human gate" (style=user) per the chapter's own
  "The ledger and the two human gates" section: "approving a plan before any
  code gets written against it, and signing off that a verification result
  is sufficient evidence."
- The resume callout quotes r02-status.md verbatim: "Saved before a user
  break/possible reboot." and the heading "Remaining in r02 (resume here)",
  both quoted in the chapter's "Per-iteration plans and resume records"
  section.

Sourced from `_docs/02-project-ledger.md` only. No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 680

# ---- tier 1: the seven ADLC phases, as this chapter states them ----------
PHASE_Y, PHASE_H, PHASE_W, GUTTER, PHASE_X0 = 90, 70, 200, 20, 40
PHASES = [
    ("frame", "Frame", "states what should happen", "sub"),
    ("map", "Map", "does reconnaissance", "sub"),
    ("plan", "Plan", "produces the plan", "user"),
    ("generate", "Generate", "writes code + tests", "box"),
    ("verify", "Verify", "verifies the result", "user"),
    ("operate", "Operate", "rolls it out", "sub"),
    ("reconcile", "Reconcile", "records the outcome", "accent"),
]
phase_nodes = {}
nodes = []
for i, (key, name, gloss, style) in enumerate(PHASES):
    x = PHASE_X0 + i * (PHASE_W + GUTTER)
    n = node(x, PHASE_Y, PHASE_W, PHASE_H, [name, gloss], style=style)
    phase_nodes[key] = n
    nodes.append(n)

phase_band = {"x": 20, "y": 68, "w": W - 40, "h": 112,
              "label": "ADLC phases -- ch.2's own cycle (Frame -> Map -> Plan -> Generate -> Verify -> Operate -> Reconcile)",
              "fill": "#ffffff"}

# ---- tier 2: the ledger files this project actually keeps -----------------
LEDGER_Y, LEDGER_H = 235, 110
LEDGER = [
    ("build_plan", 40, 288, ["_plans/build-plan.md", "chapters A-M, phase table,", "iteration backlog"], "box"),
    ("r02_plan", 348, 288, ["_plans/iterations/r02-plan.md", "S1-S14, acceptance-gated,", "written before any step starts"], "box"),
    ("r02_status", 656, 288, ["_plans/iterations/r02-status.md", "updated as each step completes --", "this project's own resume record"], "box"),
    ("decisions", 964, 288, ["_plans/decisions.md", "append decision outcomes --", "DRQ rows: fixed / accepted / proposed"], "box"),
    ("reconciliation", 1272, 288, ["_plans/reconciliation.md", "record drift vs. cited sources --", "scoped, not yet written (this chapter)"], "ghost"),
]
ledger_nodes = {}
for key, x, w, lines, style in LEDGER:
    n = node(x, LEDGER_Y, w, LEDGER_H, lines, style=style)
    ledger_nodes[key] = n
    nodes.append(n)

ledger_band = {"x": 20, "y": 186, "w": W - 40, "h": 174,
               "label": "This project's own `_plans/` files -- checkable against the repository as you read",
               "fill": "#fafafa"}

# ---- phase -> file edges (clear space between the two bands) --------------
edges = [
    connect(phase_nodes["plan"], ledger_nodes["build_plan"]),
    connect(phase_nodes["plan"], ledger_nodes["r02_plan"]),
    connect(phase_nodes["generate"], ledger_nodes["r02_status"], label="steps S1-S14 executed", ly=-18),
    connect(phase_nodes["verify"], ledger_nodes["r02_status"], label="equivalence-gate result recorded", ly=14),
    connect(phase_nodes["reconcile"], ledger_nodes["decisions"], amber=True, label="append decision outcomes", ly=-16),
    connect(phase_nodes["reconcile"], ledger_nodes["reconciliation"], amber=True, dashed=True,
            label="record drift -- not yet written", ly=12),
]

# ---- feedback bus: Reconcile also updates build-plan.md's status line -----
BUS_Y = 380
dec = ledger_nodes["decisions"]
bp = ledger_nodes["build_plan"]
dec_x = dec["x"] + dec["w"] / 2
bp_x = bp["x"] + bp["w"] / 2
edges += [
    {"x1": dec_x, "y1": dec["y"] + dec["h"], "x2": dec_x, "y2": BUS_Y, "dashed": True, "amber": True},
    {"x1": dec_x, "y1": BUS_Y, "x2": bp_x, "y2": BUS_Y, "dashed": True, "amber": True,
     "label": "Reconcile also appends a build-plan.md status line -- same file, same discipline"},
    {"x1": bp_x, "y1": BUS_Y, "x2": bp_x, "y2": bp["y"] + bp["h"], "dashed": True, "amber": True},
]

# ---- tier 3: the resume callout, fed by the one file that says so ---------
RESUME_Y, RESUME_H = 430, 120
resume = node(270, RESUME_Y, 1060, RESUME_H, [
    "Resume mechanism: r02-status.md, read again",
    "\"Saved before a user break/possible reboot.\"",
    "\"Remaining in r02 (resume here)\" -- names S13, S14 by number",
    "Next session reads this one file plus r02-plan.md's step definitions and continues",
], style="ink")
nodes.append(resume)

status_n = ledger_nodes["r02_status"]
edges.append(connect(status_n, resume, dashed=True, amber=True,
                      label="written before stopping; read again to resume", lx=260, ly=-6))

resume_band = {"x": 150, "y": 400, "w": 1300, "h": 180,
               "label": "Interruption and resume -- not hypothetical; this project's own checkpoint",
               "fill": "#eaf4ec"}

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.02 -- The ledger's files, mapped onto the ADLC's own phases",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "Plan and Verify (blue) are this chapter's two human gates; Reconcile (green) is where the ledger actually gets written",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 40, "y": H - 46,
     "text": "Sourced from _docs/02-project-ledger.md (\"The build plan\", \"Per-iteration plans and resume records\", \"The ledger and the two human gates\").",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": H - 16,
     "text": "Reconcile row and resume-record quotes are the chapter's own quotes from build-plan.md's phase table and r02-status.md.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "ledger-files-to-adlc-phases", W, H,
    bands=[phase_band, ledger_band, resume_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
