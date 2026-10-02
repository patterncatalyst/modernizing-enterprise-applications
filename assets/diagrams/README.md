# Diagram catalogue

Every figure in this book is a paired `name.svg` (committed, embedded via
`{% include excalidraw.html file="..." alt="..." caption="..." %}`) plus
`name.excalidraw` (editable source), generated with
`scripts/generate_diagram.py` (see the `lgtm-diagram-generator` skill), via
a per-diagram `name.py` spec (the editable source of record for layout) that
imports the shared `_lib.py` octagon/edge-routing helpers in this directory.
This file is the catalogue — one row per diagram, append-only, serialized
(single writer per r02-plan §K).

| Diagram | Chapter | What it shows |
|---|---|---|
| `sdlc-vs-adlc` | ch.05 | Traditional SDLC (8 steps, weeks-months) vs. the agentic SDLC (8 steps, hours-days), a transformation arrow, and a "Key differences" table. Source: `sdlc-vs-adlc.py`. |
| `adlc-tooling` | ch.05 | The agentic loop (8 steps) annotated with generic tool roles at each step (coding assistant, local agent runtime, local coding agent, human, internal developer platform (IDP), hosted AI platform, issue tracker). Source: `adlc-tooling.py`. |
| `adlc-local-vs-hosted` | ch.05 | The agentic loop split into a "local" execution path (local coding agent + Podman + cloud dev environment) and a "hosted" path (hosted AI platform), side by side. Source: `adlc-local-vs-hosted.py`. |
| `agentic-sdlc-to-adlc` | ch.05 | Maps the deck's 8-step agentic SDLC onto this book's 7-phase ADLC (Frame/Map/Plan/Generate/Verify/Operate/Reconcile), marking the two human gates (plan approval; equivalence sign-off). Source: `agentic-sdlc-to-adlc.py`. |
| `strangler-review-extraction` | ch.15 | The walking-skeleton topology, before vs. after the Review cutover: Client + the Newman behavior-equivalence suite fan into the Camel strangler proxy (`:8888`, flag `strangler.review.enabled`), which routes `/api/reviews*` to the Quarkus Review service (`:8081`) and everything else to the Spring monolith (`:8080`); both backends still share one PostgreSQL instance. Before: flag off, Review served by the (6-context) monolith, review-service not yet live. After: flag on (committed default), Review served by Quarkus, monolith slimmed to 5 contexts and decommissioned for Review. Sourced from `examples/01-strangler-proxy/` (`StranglerProxyRoute.java`, `CUTOVER.md`) and `examples/00-monolith/SMELLS.md` (smell #6). Source: `strangler-review-extraction.py`. |
| `review-two-phase-migration` | ch.15 | The repeatable per-service migration template on Review: Phase A (lift onto Quarkus via the Spring-compatibility extensions) -> Phase B (idiomatic Quarkus REST/Panache/CDI) -> a native-image build, with the real measured before/after (startup time, RSS) from `examples/02-review-service/MIGRATION.md`. Source: `review-two-phase-migration.py`. |
