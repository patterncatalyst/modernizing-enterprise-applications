#!/usr/bin/env python3
"""ch.27 figure: chassis-scorecard — the nine MicroProfile/Quarkus chassis
capabilities, each row showing its actual status in this repo's code, not
a feature checklist. Mirrors resilience-scorecard.py's used/deferred idiom,
collapsed to a single column (one scorecard, not a two-column comparison)
with a third style — PARTIAL — for the two capabilities that are wired on
some but not all services.

USED (accent, green fill): Config, REST Client, Native/build-time, Dev-mode.
PARTIAL (plain box): Health, Security/JWT.
DEFERRED (ghost, dashed): Fault Tolerance, Metrics, OpenAPI.

Every row's status and detail phrase is given ground truth, verified against
the code (poms, application.properties, service annotations) — not inferred
here. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1500, 1080

band = {"x": 20, "y": 90, "w": 1460, "h": 910,
        "label": "Chassis scorecard — nine chassis capabilities, status verified against this repo's code",
        "fill": "#fafafa"}

ROW_X, ROW_W = 50, 1400
ROW_Y0, ROW_H, GUTTER = 150, 72, 14

ROWS = [
    ("Config", "USED", "accent",
     "@ConfigProperty + %dev/%prod/%test profiles — proxy base-urls, order-service inventory.grpc.timeout-ms"),
    ("Fault Tolerance", "DEFERRED", "ghost",
     "No @Retry/@Timeout/@CircuitBreaker/@Fallback/@Bulkhead — manual withDeadlineAfter + REST-client timeouts stand in"),
    ("Health", "PARTIAL", "box",
     "quarkus-smallrye-health on 6 of 8 services (auto /q/health) — no custom checks; platform wiring deferred to Part 9"),
    ("Metrics", "DEFERRED", "ghost",
     "No Micrometer, no /q/metrics — deferred to ch.30"),
    ("OpenAPI", "DEFERRED", "ghost",
     "Monolith had springdoc; no quarkus-smallrye-openapi on the services — gateway's GraphQL schema replaces it"),
    ("REST Client", "USED", "accent",
     "@RegisterRestClient/@RestClient in shipping + the gateway's four resolver clients — config-driven URLs/timeouts"),
    ("Security / JWT", "PARTIAL", "box",
     "HTTP Basic + @RolesAllowed on review-service, reproducing the monolith's SecurityConfig — no quarkus-smallrye-jwt; OIDC deferred"),
    ("Native / build-time", "USED", "accent",
     "native Maven profile in all 8 poms + @RegisterForReflection — ch.03 measured ~31x startup / ~4x memory"),
    ("Dev-mode", "USED", "accent",
     "quarkus:dev live reload; Dev Services disabled (shared Postgres) — %test + in-memory messaging substitutes"),
]

nodes = []
for i, (name, status, style, detail) in enumerate(ROWS):
    y = ROW_Y0 + i * (ROW_H + GUTTER)
    title = f"{i+1}. {name} — {status}"
    nodes.append(node(ROW_X, y, ROW_W, ROW_H, [title, detail], style=style))

# legend — the three row styles, reusing the same node() shapes at a glance
LEGEND_Y = ROW_Y0 + 9 * (ROW_H + GUTTER) + 16
legend_items = [
    ("USED", "accent", "accent green fill"),
    ("PARTIAL", "box", "plain white box"),
    ("DEFERRED", "ghost", "dashed ghost outline"),
]
LEGEND_W, LEGEND_GAP = 300, 24
for i, (label, style, caption) in enumerate(legend_items):
    x = ROW_X + i * (LEGEND_W + LEGEND_GAP)
    nodes.append(node(x, LEGEND_Y, LEGEND_W, 50, [label, caption], style=style))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.27 — the Quarkus / MicroProfile chassis: what this repo wired, row by row",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "status is verified against the code in this repo, not a feature checklist — PARTIAL means wired on some services, not all",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 50, "y": H - 40,
     "text": "Four capabilities used, two partial, three deferred — the chassis a tutorial repo needs, not the one a production platform needs.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 50, "y": H - 16,
     "text": "Verified against poms, application.properties, and service annotations across the 8 services + gateway.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "chassis-scorecard", W, H,
    bands=[band],
    nodes=nodes,
    edges=[],
    notes=notes,
)
