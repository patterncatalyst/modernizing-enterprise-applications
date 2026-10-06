#!/usr/bin/env python3
"""ch.16 figure: cbr-vs-acl-seam — the chapter's central distinction: at the
SAME physical seam (`direct:stockFor` / `InventoryAclRoute`), two orthogonal
concerns live, not two stages of one pipeline. Drawn as a cross, not a
left-to-right flow, because that is the point: you can change either axis
without touching the other.

HORIZONTAL axis — content-based routing: `.choice()` on the flag
`strangler.inventory.enabled` decides WHICH backend answers a stock lookup
today, the monolith's own `/api/inventory/{sku}`; from ch.19 onward,
inventory's own Quarkus/gRPC service. This is the same flag-driven
`choice()`/`when()`/`otherwise()` mechanism `StranglerProxyRoute` already
runs on the Review seam (ch.14/15) — correct and current for this
mid-migration chapter, not something this figure "modernizes" away.

VERTICAL axis — the anti-corruption layer: `.enrich(...)` performs the
request-reply fetch and hands the reply to a custom `AggregationStrategy`
(`StockDtoTranslatingStrategy`) that decides WHAT shape is safe to carry
back — always `StockDto`, regardless of which backend on the horizontal
axis answered. A perfectly-routed request down either branch still corrupts
the caller if this axis is missing (Smell 5).

The two axes cross at one point, `.enrich()` inside `InventoryAclRoute`,
because that one Camel step both performs the routed fetch (consumes the
horizontal decision) and triggers the translation (produces the vertical
one) — which is exactly why it is easy to mistake for a single pipeline
stage instead of two orthogonal decisions sharing an address.

Sourced from `_docs/16-content-based-routing-acl.md` ("Where content-based
routing stops helping," "The anti-corruption layer: a contract, not a
request," "The ACL route, sketched" — `InventoryAclRoute`, `TARGET_PROPERTY`
`stockLookupTarget`, `StockDtoTranslatingStrategy`) and
`examples/01-strangler-proxy/.../StranglerProxyRoute.java` (the
`strangler.review.enabled` flag-driven `choice()` this chapter's router
mirrors). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 970

band = {"x": 20, "y": 70, "w": W - 40, "h": H - 140,
        "label": "The same physical seam — direct:stockFor / InventoryAclRoute",
        "fill": "#fafafa"}

# ---- the crossing point: one Camel step, two orthogonal jobs ---------------
center = node(W / 2 - 180, H / 2 - 60, 360, 120,
              [".enrich() — InventoryAclRoute", "one Camel step: performs the routed fetch",
               "AND triggers the translation below"],
              style="ink")

# ---- HORIZONTAL axis: content-based routing — WHICH backend answers -------
monolith = node(60, H / 2 - 55, 300, 110,
                 ["otherwise: the monolith", "GET /api/inventory/{sku} — :8080",
                  "StockDto already built there (ch.11)"],
                 style="box")
inventory_svc = node(W - 360, H / 2 - 55, 300, 110,
                      ["when inventoryEnabled: inventory svc", "ch.19 Quarkus gRPC — :9004",
                       "not live yet in this chapter"],
                      style="ghost")

# ---- VERTICAL axis: anti-corruption layer — WHAT is safe to carry back ----
raw_reply = node(W / 2 - 190, 110, 380, 110,
                  ["resource exchange: the raw reply", "monolith JSON today / StockReply (ch.19)",
                   "the shape the ACL must NOT pass through"],
                  style="box")
stock_dto = node(W / 2 - 190, H - 210, 380, 110,
                  ["StockDto", "the one contract order may hold,",
                   "regardless of which backend answered"],
                  style="accent")

nodes = [center, monolith, inventory_svc, raw_reply, stock_dto]

edges = [
    connect(monolith, center,
            label="flag=false -> monolith URI"),
    connect(center, inventory_svc, dashed=True,
            label="flag=true (ch.19) -> inventory svc URI"),
    connect(raw_reply, center, dashed=True, label="enrich() request-reply fetch"),
    connect(center, stock_dto, amber=True,
            label="StockDtoTranslatingStrategy.aggregate() -> StockDto"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.16 — one seam, two orthogonal questions: which backend answers, and what is safe to carry back",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": W / 2, "y": H / 2 - 95,
     "text": "ROUTING AXIS — .choice() on strangler.inventory.enabled decides WHICH backend answers",
     "anchor": "middle", "size": 11.5, "color": "#555555"},
    {"x": 60, "y": H / 2 + 90,
     "text": "ACL AXIS — enrich() + AggregationStrategy decide WHAT shape is safe to carry back, independent of which side answered",
     "anchor": "start", "size": 11.5, "color": "#555555"},

    {"x": 60, "y": H - 90,
     "text": "Full property write on both branches: enrich() calls setProperty(stockLookupTarget, monolithBaseUrl+... | inventoryServiceBaseUrl+...).",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 60, "y": H - 54,
     "text": "A perfectly-routed request down either branch above still corrupts the caller if the vertical axis is missing — that is exactly Smell 5.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 60, "y": H - 20,
     "text": "Sourced from _docs/16-content-based-routing-acl.md (\"Where content-based routing stops helping\"; \"The ACL route, sketched\") "
             "and examples/01-strangler-proxy/.../StranglerProxyRoute.java (the strangler.review.enabled choice() this router mirrors).",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "cbr-vs-acl-seam", W, H,
    bands=[band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
