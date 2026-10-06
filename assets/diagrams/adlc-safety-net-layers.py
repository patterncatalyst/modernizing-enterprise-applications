#!/usr/bin/env python3
"""ch.07 figure: adlc-safety-net-layers — the chapter's closing argument in
"The ADLC Safety Net" (_docs/07-adlc-safety-net.md, "The lesson: necessary,
not sufficient"): the safety net has three layers, not one, and removing any
single layer lets a specific, named class of mistake through.

LAYER 1 — the automated layer: the behavior-equivalence suite (Newman),
cheap enough to re-run against every candidate build. This is what caught
bug #1 — the native-image `@RegisterForReflection` gap — the instant a
native artifact was actually exercised. Remove this layer and the
native-image regression ships silently.

LAYER 2 — adversarial verification: the differential test that stopped the
monolith outright and asked whether the proxy's routing decision survives a
condition under which the two backends cannot possibly agree. This is what
caught bug #2 — the routing predicate that never matched, hidden behind two
backends sharing one `reviews` table. Remove this layer and the routing
defect ships, 49-for-49, straight past a green suite run.

LAYER 3 — the two human gates: plan approval (Plan phase, before any diff
exists) and equivalence sign-off (Verify phase, judging whether the suite's
specific coverage is the right coverage for the stakes). Remove this layer
and a green suite run is trusted as self-certifying — nobody asks what would
make it pass by accident.

Sourced from `_docs/07-adlc-safety-net.md` ("The lesson: necessary, not
sufficient," and the preceding "Verify" / "Operate" sections' bug #1 and
bug #2 narratives). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1300, 900

band1 = {"x": 20, "y": 80, "w": 1260, "h": 230,
         "label": "Layer 1 — automated: the behavior-equivalence suite", "fill": "#fafafa"}
band2 = {"x": 20, "y": 330, "w": 1260, "h": 230,
         "label": "Layer 2 — adversarial verification: the differential test", "fill": "#eaf4ec"}
band3 = {"x": 20, "y": 580, "w": 1260, "h": 230,
         "label": "Layer 3 — human judgment: the two gates", "fill": "#fafafa"}

# ---- Layer 1 ---------------------------------------------------------------
suite = node(60, 150, 520, 120,
             ["Newman behavior-equivalence suite", "re-run against every candidate build",
              "16/16, then 49/49 — JVM and native"], style="accent")
remove1 = node(660, 150, 580, 120,
               ["Remove this layer:", "the native-image @RegisterForReflection gap",
                "ships silently — nothing re-exercises the native artifact"], style="ink")

# ---- Layer 2 ---------------------------------------------------------------
adversarial = node(60, 400, 520, 120,
                    ["Stop the monolith; ask a sharper question", "than \"does the suite pass\":",
                     "does the proxy's decision survive one backend being gone?"], style="accent")
remove2 = node(660, 400, 580, 120,
               ["Remove this layer:", "the routing defect ships, 49-for-49,",
                "hidden behind two backends sharing one table"], style="ink")

# ---- Layer 3 ---------------------------------------------------------------
gate1 = node(60, 650, 290, 120,
             ["Gate 1 — plan approval", "Plan phase", "a human commits before any diff exists"], style="user")
gate2 = node(380, 650, 260, 120,
             ["Gate 2 — equivalence sign-off", "Verify phase",
              "a human judges whether coverage fits the stakes"], style="user")
remove3 = node(690, 650, 550, 120,
               ["Remove this layer:", "a green suite run is trusted as self-certifying —",
                "nobody asks what would make it pass by accident"], style="ink")

nodes = [suite, remove1, adversarial, remove2, gate1, gate2, remove3]

edges = [
    connect(suite, remove1, amber=True, label="catches: a structural defect no JVM run surfaces", ly=-75),
    connect(adversarial, remove2, amber=True, label="catches: shared state masking a routing defect", ly=-75),
    connect(gate1, gate2),
    connect(gate2, remove3, amber=True, label="decides: is this coverage the right coverage?", ly=-75),

    connect(suite, adversarial, dashed=True, label="compensates for layer 1's blind spot"),
    connect(adversarial, gate1, dashed=True, label="judges whether layers 1+2's coverage fit the stakes"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.07 — the safety net has three layers; removing any one lets a specific bug through",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "the ADLC is not safe because an agent is careful — it is safe because all three layers hold at once",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/07-adlc-safety-net.md (\"The lesson: necessary, not sufficient\"; the Verify and Operate sections' bug #1 and bug #2 narratives).",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "adlc-safety-net-layers", W, H,
    bands=[band1, band2, band3],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
