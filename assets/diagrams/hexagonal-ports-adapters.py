#!/usr/bin/env python3
"""ch.11 figure: hexagonal-ports-adapters -- the ports-and-adapters discipline
`_docs/11-ddd-and-hexagonal.md` names as the structural counterpart to
strategic/tactical DDD: "A domain core sits in the middle, holding entities,
aggregates, and business logic, and depends on nothing -- no web framework,
no ORM, no message broker. It declares ports: inbound ports describing what
it can be asked to do, outbound ports describing what it needs from the
world. Driving adapters -- a REST controller, a gRPC service, a message
consumer -- translate an incoming protocol into a call on an inbound port.
Driven adapters -- a database repository, an event publisher -- implement
the outbound ports the core declared. Every dependency arrow points inward,
toward the core."

This generator draws rectangles, not literal hexagons -- the hexagon's only
real content is "ports in the middle, adapters on the outside, dependencies
pointing in," which this layered left-core-right layout preserves without
inventing a new primitive.

REST controller / repository are drawn with the chapter's own worked
example (`examples/02-review-service`'s `ReviewResource` / `ReviewService` /
`ReviewRepository`, quoted verbatim in ch.11); gRPC service / message
consumer / event publisher are left at the chapter's own generic level,
since ch.11's prose names the adapter TYPE but does not quote a specific
class for those three. The caveat note is ch.11's own: `ReviewRepository`
is a concrete class, not a declared Port interface -- "not textbook
hexagonal with an explicit Port interface the domain owns."

Sourced from `_docs/11-ddd-and-hexagonal.md` ("Hexagonal architecture: ports
that make a seam cheap to cut", "Why Review could leave first"). No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 620

# ---- domain core, center ---------------------------------------------------
core = node(610, 230, 280, 160,
            ["Domain core",
             "entities, aggregates, business logic",
             "no web framework, no ORM, no message broker"],
            style="accent")

# ---- ports, flanking the core ----------------------------------------------
inbound_port = node(380, 260, 190, 100,
                     ["Inbound port(s)",
                      "what the core can be asked to do",
                      "e.g. createReview(), placeOrder()"],
                     style="box")
outbound_port = node(950, 260, 190, 100,
                      ["Outbound port(s)",
                       "what the core needs from the world",
                       "e.g. findBySku(), persist()"],
                      style="box")

# ---- driving adapters, left column -----------------------------------------
rest_adapter = node(50, 90, 260, 110,
                     ["REST controller",
                      "e.g. ReviewResource (ch.11 excerpt)",
                      "translates an HTTP request into a port call"],
                     style="sub")
grpc_adapter = node(50, 255, 260, 110,
                     ["gRPC service",
                      "translates an incoming RPC into a port call"],
                     style="sub")
consumer_adapter = node(50, 420, 260, 110,
                         ["Message consumer",
                          "translates an incoming broker message",
                          "into a port call"],
                         style="sub")

# ---- driven adapters, right column -----------------------------------------
repo_adapter = node(1190, 160, 260, 130,
                     ["Database repository",
                      "e.g. ReviewRepository (ch.11 excerpt)",
                      "implements the outbound port"],
                     style="sub")
publisher_adapter = node(1190, 360, 260, 130,
                          ["Event publisher",
                           "implements the outbound port",
                           "the core declared"],
                          style="sub")

nodes = [core, inbound_port, outbound_port,
         rest_adapter, grpc_adapter, consumer_adapter,
         repo_adapter, publisher_adapter]

# ---- every dependency arrow points inward, toward the core ----------------
edges = [
    connect(rest_adapter, inbound_port, amber=True),
    connect(grpc_adapter, inbound_port, amber=True),
    connect(consumer_adapter, inbound_port, amber=True),
    connect(inbound_port, core, amber=True,
            label="dependency points inward -- adapters are swappable", ly=130),

    connect(repo_adapter, outbound_port, amber=True),
    connect(publisher_adapter, outbound_port, amber=True),
    connect(outbound_port, core, amber=True,
            label="core declares the port; the adapter implements it", ly=130),
]

notes = [
    {"x": W / 2, "y": 32, "text": "ch.11 -- Hexagonal architecture: ports and adapters, dependencies pointing inward",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "driving adapters translate a protocol into an inbound-port call; driven adapters implement the outbound port the core declared",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": W / 2, "y": core["y"] - 14,
     "text": "the core never imports a protocol -- a protocol can be swapped without the core noticing",
     "anchor": "middle", "size": 10.5, "color": "#2f5f3d"},

    {"x": 40, "y": H - 60,
     "text": "Caveat (ch.11's own): examples/02-review-service's ReviewRepository is a concrete class, not a declared Port interface the core",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": H - 46,
     "text": "programs against -- what made Review cheap to extract was zero outbound edges to other contexts, not a textbook-perfect hexagon.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/11-ddd-and-hexagonal.md (\"Hexagonal architecture\", \"Why Review could leave first\") -- ReviewResource/ReviewService/ReviewRepository quoted verbatim from examples/02-review-service.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "hexagonal-ports-adapters", W, H,
    bands=[],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
