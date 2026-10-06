#!/usr/bin/env python3
"""ch.03 figure: microservice-traits — the ten traits the chapter says the
word "microservices" is actually promising, laid out as a labelled grid
instead of the wall of prose in "The ten microservice traits" section.

All ten traits and their wording are quoted or closely paraphrased directly
from _docs/03-modernization-as-engineering.md ("The ten microservice
traits"):
  1. independently deployable -- "it ships on its own schedule, not the
     monolith's shared release train."
  2. modeled around a business domain -- "not a technical layer, so a
     service's boundary matches a bounded context rather than a tier like
     'data access' or 'UI.'"
  3. communicate over the network -- "rather than through in-process calls,
     which is the same change that turns a stack trace into a distributed
     trace."
  4. a form of opinionated service-oriented architecture -- "the
     integration ideas SOA introduced, with much stronger defaults about
     size and ownership than SOA ever enforced."
  5. technology agnostic -- "so one team's service can run a different
     language or framework than its neighbor's without coordination."
  6. a distributed system (true of the set, not one service) -- "a set of
     them, taken together, forms a distributed system, with everything
     that phrase implies about partial failure and the loss of a single
     transaction boundary."
  7. API-focused -- "hiding its internals entirely behind a service
     boundary rather than exposing a shared library or a shared table."
  8. decentralized governance -- "governance over how a service is built
     is decentralized, pushed to the team that owns it rather than
     mandated centrally."
  9. decentralized data management -- "the data each service holds is
     under decentralized data management, owned by one service rather
     than shared across many."
  10. designed for failure -- "built on the assumption that any one
      service can be unavailable at any moment, rather than the
      monolith's implicit assumption that if the process is up, every
      module inside it is reachable."

The chapter's own framing note ("None of these ten traits is this book's
goal in isolation... together, though, they are the destination the
strangler-fig sequence in Chapter 4 is aimed at") is carried in the
figure's bottom note. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1600, 560

TRAITS = [
    ("1. Independently deployable",
     ["ships on its own schedule,", "not the shared release train"]),
    ("2. Modeled around a business domain",
     ["boundary matches a bounded", "context, not a technical layer"]),
    ("3. Communicate over the network",
     ["not in-process calls --", "a stack trace becomes a network trace"]),
    ("4. Opinionated SOA",
     ["SOA's integration ideas,", "stronger defaults on size + ownership"]),
    ("5. Technology agnostic",
     ["one team's language or framework", "needs no neighbor's coordination"]),
    ("6. A distributed system",
     ["true of the set, not one service --", "partial failure is now possible"]),
    ("7. API-focused",
     ["internals hidden behind the API,", "not a shared library or table"]),
    ("8. Decentralized governance",
     ["how a service is built is", "owned by the team that builds it"]),
    ("9. Decentralized data management",
     ["each service owns its own data,", "not shared across many services"]),
    ("10. Designed for failure",
     ["any service can be down, any time --", "not: if the process is up, all is reachable"]),
]

COLS, ROWS = 5, 2
COL_W, GUTTER_X = 280, 20
ROW_H, GUTTER_Y = 140, 24
X0, Y0 = 40, 150

nodes = []
for i, (title, lines) in enumerate(TRAITS):
    r, c = divmod(i, COLS)
    x = X0 + c * (COL_W + GUTTER_X)
    y = Y0 + r * (ROW_H + GUTTER_Y)
    nodes.append(node(x, y, COL_W, ROW_H, [title] + lines, style="box"))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.03 -- the ten microservice traits: what the word is actually promising",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "each service's boundary matches a bounded context -- the target this book's six extractions are built toward, one at a time",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": X0, "y": Y0 + 2 * ROW_H + GUTTER_Y + 36,
     "text": "None of these ten traits is this book's own goal in isolation. Together, they are the destination the strangler-fig",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": X0, "y": Y0 + 2 * ROW_H + GUTTER_Y + 52,
     "text": "sequence in Chapter 4 is aimed at -- each of the six extractions is a bet that one more bounded context can acquire them.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": X0, "y": H - 16,
     "text": "Sourced from _docs/03-modernization-as-engineering.md (\"The ten microservice traits\").",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "microservice-traits", W, H,
    bands=[],
    nodes=nodes,
    edges=[],
    notes=notes,
)
