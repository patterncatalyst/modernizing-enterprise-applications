#!/usr/bin/env python3
"""ch.15 figure: strangler-review-extraction — the walking-skeleton topology.

Two stacked states (before/after the Review cutover), each an hourglass:
Client + the Newman behavior-equivalence suite fan into the Camel strangler
proxy (:8888, flag `strangler.review.enabled`); the proxy fans out to the
Spring monolith (:8080, default target) and the Quarkus Review service
(:8081, only when the flag is on); both backends fan back into the one
shared PostgreSQL instance. Sourced from `examples/01-strangler-proxy/`
(StranglerProxyRoute.java + CUTOVER.md) and `examples/00-monolith/SMELLS.md`
(smell #6, cured). No tool/product codenames; the behavior-equivalence suite
is always called out by that house name only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1320, 1040
PANEL_H = 420


def build_panel(panel_y, after):
    """Return (nodes, edges, notes) for one state of the cutover."""
    newman = node(40, panel_y + 10, 220, 90,
                   ["Newman", "behavior-equivalence suite", "16 reqs / 49 assertions"], style="sub")
    client = node(40, panel_y + 150, 220, 90,
                   ["Client", "(browser / API caller)"], style="user")

    if not after:
        proxy_lines = ["Camel strangler proxy", ":8888",
                       "flag: strangler.review.enabled = false"]
        monolith_lines = ["Spring monolith", ":8080 — 6 contexts", "(incl. Review)"]
        review_style = "ghost"
        review_lines = ["Quarkus Review service", ":8081 — Phase A lift", "(not yet live)"]
        review_edge_kw = {"label": "/api/reviews* (flag=true only)", "dashed": True}
        proxy_main_edge_label = "/api/** incl. /api/reviews* (flag=false)"
        suite_label = "run #1: 49/49 green"
        pg_extra = "(single schema)"
    else:
        proxy_lines = ["Camel strangler proxy", ":8888",
                       "flag: strangler.review.enabled = true (default)"]
        monolith_lines = ["Spring monolith", ":8080 — 5 contexts", "(Review decommissioned)"]
        review_style = "accent"
        review_lines = ["Quarkus Review service", ":8081 — Phase A lift", "(live, serving /api/reviews*)"]
        review_edge_kw = {"label": "/api/reviews* (flag=true)", "amber": True}
        proxy_main_edge_label = "/api/** (everything else)"
        suite_label = "run #2 (post routing fix): 49/49 green"
        pg_extra = "(Phase A: still shared — see MIGRATION.md)"

    proxy = node(330, panel_y + 150, 250, 110, proxy_lines, style="accent")
    monolith = node(650, panel_y + 20, 270, 100, monolith_lines, style="box")
    review = node(650, panel_y + 300, 270, 100, review_lines, style=review_style)
    pg = node(1000, panel_y + 140, 240, 130,
              ["PostgreSQL", "shared instance", "`reviews` table", pg_extra], style="sub")

    nodes = [newman, client, proxy, monolith, review, pg]
    edges = [
        connect(newman, proxy, label=suite_label),
        connect(client, proxy, label="HTTP"),
        connect(proxy, monolith, label=proxy_main_edge_label),
        connect(proxy, review, **review_edge_kw),
        connect(monolith, pg),
        connect(review, pg, dashed=(not after)),
    ]
    return nodes, edges


before_nodes, before_edges = build_panel(70, after=False)
after_nodes, after_edges = build_panel(70 + PANEL_H + 60, after=True)

notes = [
    {"x": W / 2, "y": 32, "text": "ch.15 — Extracting Review: the strangler-fig seam (before -> after cutover)",
     "anchor": "middle", "bold": True, "size": 18},

    {"x": 40, "y": 65, "text": "BEFORE — flag OFF (r02 S7–S9): Review still served by the monolith",
     "anchor": "start", "bold": True, "size": 14},
    {"x": 40, "y": 70 + PANEL_H + 48,
     "text": "AFTER — flag ON, committed default (r02 S10): Review served by Quarkus; monolith decommissioned",
     "anchor": "start", "bold": True, "size": 14},

    {"x": 40, "y": 70 + PANEL_H + 60 + 420,
     "text": "proxy predicate fixed post-S10: matches the FULL CamelHttpPath "
             "(startsWith '/api/reviews'), not a path relative to the /api consumer prefix",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": H - 14,
     "text": "The flag is the whole cutover: a config change + restart, no route code change. "
             "Decommission (step 3, after routing was verified) is the one irreversible move.",
     "anchor": "start", "size": 11, "color": "#555555"},
]

g.emit(
    "strangler-review-extraction", W, H,
    bands=[],
    nodes=before_nodes + after_nodes,
    edges=before_edges + after_edges,
    notes=notes,
)
