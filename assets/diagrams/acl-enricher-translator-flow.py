#!/usr/bin/env python3
"""ch.16 figure: acl-enricher-translator-flow — the ACL pipeline inside
`InventoryAclRoute`, left to right: an exchange arrives on `direct:stockFor`
knowing only a SKU; `.choice()` on `strangler.inventory.enabled` resolves a
concrete backend URI (today's monolith `/api/inventory/{sku}`, or ch.19's
inventory gRPC service once it exists) and stashes it on the exchange
property `stockLookupTarget`; `.enrich(...)` performs the request-reply
fetch against that URI; `StockDtoTranslatingStrategy.aggregate()` converts
whatever came back into `StockDto` via `StockDtoTranslator.translate(reply)`
and merges it onto the ORIGINAL exchange (not the resource exchange) so
headers/properties survive; the exchange leaves the route knowing the stock
fact, not the SKU alone.

Unlike cbr-vs-acl-seam.py (which draws routing and translation as two
orthogonal axes crossing at one point), this figure is the ordinary
left-to-right sequence a reader follows through the route's own source —
the FUNC complement to that ARCH figure.

Sourced from `_docs/16-content-based-routing-acl.md` ("The ACL route,
sketched"; "How the route works") — exact names: `InventoryAclRoute`,
`direct:stockFor`, `TARGET_PROPERTY`/`stockLookupTarget`, `StockDto`,
`StockDtoTranslatingStrategy`, `StockDtoTranslator.translate(reply)`,
`stockQuantity` header. `InventoryAclRoute` is a sketch in this chapter —
not wired into `examples/01-strangler-proxy/` yet — so the ch.19 backend
box is drawn ghost/dashed, matching the chapter's own verification footer.
No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1560, 620

band = {"x": 20, "y": 60, "w": W - 40, "h": 420,
        "label": "InventoryAclRoute — from(\"direct:stockFor\")", "fill": "#eaf4ec"}

STEP_Y, STEP_H = 110, 120

entry = node(50, STEP_Y, 220, STEP_H,
             ["direct:stockFor", "exchange arrives", "header: sku only"], style="sub")
choice = node(300, STEP_Y, 230, STEP_H,
              [".choice()", "flag: strangler.inventory.enabled",
               "setProperty(stockLookupTarget, uri)"], style="box")
enrich = node(560, STEP_Y, 230, STEP_H,
              [".enrich(stockLookupTarget, ...)", "dynamic request-reply fetch",
               "against the resolved URI"], style="accent")
aggregate = node(820, STEP_Y, 250, STEP_H,
                  ["StockDtoTranslatingStrategy", ".aggregate(original, resource)",
                   "StockDtoTranslator.translate(reply)"], style="accent")
merged = node(1100, STEP_Y, 250, STEP_H,
              ["original exchange, merged", "body = StockDto",
               "header: stockQuantity"], style="ink")

main_nodes = [entry, choice, enrich, aggregate, merged]
main_edges = [
    connect(entry, choice),
    connect(choice, enrich, label="stockLookupTarget resolved", ly=-70),
    connect(enrich, aggregate, label="resource exchange (raw reply)", ly=-70),
    connect(aggregate, merged, amber=True, label="original.getMessage().setBody(stock)", ly=-70),
]

# ---- the flag-selected backend the choice() step points enrich() at -------
BACK_Y = 330
monolith = node(430, BACK_Y, 260, 100,
                 ["otherwise: monolith", "GET /api/inventory/{sku} — :8080",
                  "already builds StockDto"], style="box")
inventory_svc = node(720, BACK_Y, 260, 100,
                      ["when inventoryEnabled: inventory svc", "ch.19 gRPC — :9004",
                       "StockReply — different vocabulary"], style="ghost")

backend_nodes = [monolith, inventory_svc]
backend_edges = [
    connect(choice, monolith, dashed=True, label="otherwise"),
    connect(choice, inventory_svc, dashed=True, label="when flag=true", lx=48, ly=30),
    connect(monolith, enrich, label="reply"),
    connect(inventory_svc, enrich, dashed=True, label="reply (ch.19)"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.16 — the ACL pipeline: SKU in, StockDto out, regardless of which backend answered",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 50, "y": H - 70,
     "text": "Returning the ORIGINAL exchange (not the resource exchange) from aggregate() is what keeps this a merge: headers and "
             "properties the caller already set survive; only the body changes.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 50, "y": H - 36,
     "text": "InventoryAclRoute is a sketch in this chapter — not wired into examples/01-strangler-proxy/ yet; the ch.19 backend box above is drawn ghost for that reason.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 50, "y": H - 12,
     "text": "Sourced from _docs/16-content-based-routing-acl.md (\"The ACL route, sketched\"; \"How the route works\").",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "acl-enricher-translator-flow", W, H,
    bands=[band],
    nodes=main_nodes + backend_nodes,
    edges=main_edges + backend_edges,
    notes=notes,
)
