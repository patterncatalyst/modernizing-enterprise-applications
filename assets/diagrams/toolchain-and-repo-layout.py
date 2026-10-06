#!/usr/bin/env python3
"""ch.01 figure: toolchain-and-repo-layout — the local toolchain and repo tree
`_docs/01-prerequisites.md` walks a reader through before any pattern gets
discussed: SDKMAN-managed JDK/Maven/Quarkus-CLI/Camel-CLI on one side, the
podman-only compose stack (Postgres, Kafka in KRaft mode, the Grafana LGTM
bundle) on the other, both landing in the same repository tree underneath.

LEFT band — "Local toolchain, installed via SDKMAN": JDK 25 (Temurin) and
Maven 3.9.x are the two pieces the chapter calls required ("two of them are
required for everything that follows"); the Quarkus CLI and Camel CLI are
drawn dashed/ghost because the chapter is explicit both are optional
conveniences scoped to later parts ("optional, but convenient" / "optional,
scoped to Part 5 onward").

RIGHT band — "Podman stack": the three services `compose.yaml` defines and
`scripts/stack-up.sh` brings up and health-checks — Postgres, single-broker
KRaft-mode Kafka ("no ZooKeeper"), and the Grafana LGTM bundle (Loki, Grafana,
Tempo, Mimir, plus an embedded OpenTelemetry Collector). The chapter is
explicit this is podman compose, never docker compose.

BOTTOM band — repository layout, the exact directories from the chapter's own
orientation table: `examples/`, `tooling/`, `demos/`, `infra/`, `scripts/`,
`_docs/`. `examples/`, `tooling/`, and `_docs/` are the three the figure brief
calls out by name, drawn accent; the rest are drawn plain for context.

Two edges carry the only two cross-references the chapter draws explicitly:
Maven/the wrapper builds and runs `examples/` (`mvn -f examples/00-monolith
package`, `./mvnw package`), and `scripts/stack-up.sh` / `stack-down.sh`
bring the podman stack up and down.

Sourced from `_docs/01-prerequisites.md` ("What you need, and where it comes
from", "Bring up the infrastructure stack", "Finding your way around the
repository"), `compose.yaml`, `.env.example`, `scripts/stack-up.sh`. No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1500, 720

# ============================================================================
# LEFT band — local toolchain, installed via SDKMAN
# ============================================================================
band_tool = {"x": 20, "y": 60, "w": 720, "h": 250,
             "label": "Local toolchain — installed via SDKMAN", "fill": "#fafafa"}

jdk = node(40, 110, 330, 85, ["JDK 25 (Temurin)", "sdk install java 25-tem", "required"], style="accent")
mvn = node(390, 110, 330, 85, ["Maven 3.9.x", "sdk install maven 3.9.9", "required, or ./mvnw per-project"], style="accent")
quarkus_cli = node(40, 205, 330, 85, ["Quarkus CLI", "sdk install quarkus", "optional"], style="ghost")
camel_cli = node(390, 205, 330, 85, ["Camel CLI", "sdk install camel", "optional, scoped to Part 5+"], style="ghost")

tool_nodes = [jdk, mvn, quarkus_cli, camel_cli]

# ============================================================================
# RIGHT band — podman stack (compose.yaml; podman compose, not docker)
# ============================================================================
band_podman = {"x": 760, "y": 60, "w": 720, "h": 250,
               "label": "Podman stack — compose.yaml (podman compose, not docker)", "fill": "#eaf4ec"}

postgres = node(780, 120, 213, 160, ["Postgres", "monolith + review DB (shared)"], style="box")
kafka = node(1013, 120, 213, 160, ["Kafka — KRaft mode", "single broker, no ZooKeeper"], style="box")
lgtm = node(1246, 120, 213, 160, ["Grafana LGTM bundle", "Loki + Tempo + Mimir", "+ OTel Collector"], style="box")

podman_nodes = [postgres, kafka, lgtm]

# ============================================================================
# BOTTOM band — repository layout (repo root)
# ============================================================================
band_repo = {"x": 20, "y": 350, "w": 1460, "h": 250,
             "label": "Repository layout — repo root", "fill": "#ffffff"}

examples_n = node(40, 410, 220, 130, ["examples/", "00-monolith, 01-strangler-proxy,", "02-review-service"], style="accent")
tooling_n = node(280, 410, 220, 130, ["tooling/", "tooling/newman/ — behavior-", "equivalence collection"], style="accent")
demos_n = node(520, 410, 220, 130, ["demos/", "demo-equivalence.sh,", "demo-cutover.sh"], style="box")
infra_n = node(760, 410, 220, 130, ["infra/", "OTel Collector config,", "Grafana datasources, DB init"], style="box")
scripts_n = node(1000, 410, 220, 130, ["scripts/", "stack-up.sh / stack-down.sh"], style="box")
docs_n = node(1240, 410, 220, 130, ["_docs/", "the chapters —", "01-prerequisites.md is here"], style="accent")

repo_nodes = [examples_n, tooling_n, demos_n, infra_n, scripts_n, docs_n]

# ============================================================================
# edges — the two explicit cross-references the chapter draws
#
# The build edge is routed as an elbow through the toolchain band's own
# right-hand margin (clear of the Camel CLI box sitting directly under
# Maven) and along the repository band's top padding, below its own
# "Repository layout" label, rather than a direct diagonal that would cut
# through a box or print over that label.
# ============================================================================
mvn_right_y = mvn["y"] + mvn["h"] / 2
GUTTER_X, BUS_Y = 726, 385
examples_cx = examples_n["x"] + examples_n["w"] / 2

edges = [
    {"x1": mvn["x"] + mvn["w"], "y1": mvn_right_y, "x2": GUTTER_X, "y2": mvn_right_y, "amber": True},
    {"x1": GUTTER_X, "y1": mvn_right_y, "x2": GUTTER_X, "y2": BUS_Y, "amber": True},
    {"x1": GUTTER_X, "y1": BUS_Y, "x2": examples_cx, "y2": BUS_Y, "amber": True,
     "label": "builds & runs — mvn / ./mvnw package"},
    {"x1": examples_cx, "y1": BUS_Y, "x2": examples_cx, "y2": examples_n["y"], "amber": True},

    connect(scripts_n, kafka, amber=True, label="stack-up.sh / stack-down.sh"),
]

bands = [band_tool, band_podman, band_repo]
nodes = tool_nodes + podman_nodes + repo_nodes

notes = [
    {"x": W / 2, "y": 32, "text": "ch.01 — Toolchain and repo layout: what you install, and where it lands",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 52,
     "text": "SDKMAN-managed JDK/Maven/CLI tooling and the podman compose stack both build and back the examples/ tree checked into this repository",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 20, "y": 632,
     "text": "Required for every chapter: JDK 25 (Temurin) and Maven 3.9.x, or each project's own ./mvnw. The Quarkus CLI and Camel CLI are optional conveniences scoped to Part 5 onward.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 20, "y": 648,
     "text": "podman compose, not docker compose, is a fixed decision: compose.yaml pins the exact Postgres, Kafka, and Grafana LGTM image tags Quarkus Dev Services and Testcontainers expect later.",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 20, "y": 684,
     "text": "Sourced from _docs/01-prerequisites.md (\"What you need, and where it comes from\", \"Bring up the infrastructure stack\", \"Finding your way around the repository\"), compose.yaml, .env.example, scripts/stack-up.sh.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "toolchain-and-repo-layout", W, H,
    bands=bands,
    nodes=nodes,
    edges=edges,
    notes=notes,
)
