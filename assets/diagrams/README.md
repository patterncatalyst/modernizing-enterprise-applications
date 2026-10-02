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
| _(next diagram lands in r02 S11, the ch.15 strangler-seam figure)_ | | |
