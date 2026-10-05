---
title: "Build Plan — Modernizing Enterprise Applications (Canonical, Synthesized)"
description: "The canonical round-1 plan: a Spring monolith strangled to Quarkus + Camel microservices, driven by an AI Development Lifecycle (ADLC), testing woven throughout. Synthesized from three competing Opus candidates."
status: "round-1 planning only — nothing built, scaffolded, or pushed until the user approves"
synthesis_of: [plan-candidate-risk, plan-candidate-simple, plan-candidate-outcome]
---

# Modernizing Enterprise Applications — Canonical Build Plan

> **ROUND-1 IS PLANNING ONLY.** This document and `decisions.md` are the only
> artifacts produced now. No repository is created, nothing is scaffolded, no
> code is written, nothing is pushed. The build begins at r02 **only after the
> user approves this plan.**

---

## Judgment (what was taken from each candidate, and the final counts)

**From the risk-first candidate — the build's backbone.** I kept its
single most valuable mechanism: **r02 is a thin walking skeleton that retires
the four biggest risks at once** (does the monolith demonstrate a pattern? does
the strangle work? is the ADLC real? does a chapter hit the 2k bar with running
code?), and its **behavior-equivalence suite (the Newman collection captured against
the monolith) gating every extraction in CI via the equivalence gate**. I
also took its risk register, its podman/minikube boundary discipline, and its
reconciliation-log drift control. I left its *Inventory-first* walking-skeleton
choice (replaced with Review-first — see tension T1) and softened its 31-chapter
sprawl where patterns clustered.

**From the outcome-first candidate — the pedagogy.** I kept the explicit
**learning arc**, the **"ADLC in Action" per-chapter callout** as the
demonstration mechanism, the **rising-difficulty extraction order**
(review → notification → inventory → payment → shipping → order), the
"anticlimactic first win," and the "reference book deepen-here" sidebars. I left
its 49-chapter / 12-part structure (over-scopes; violates scope discipline) and
its one-chapter-per-extraction-plus-one-per-pattern inflation.

**From the simplest-first candidate — the scope discipline.** I kept its
**aggressive reuse posture** (appendices reuse-as-is, zero net-new appendix
authoring in early iterations), its **demotion decisions** (caching → appendix;
event sourcing → light/optional; Netflix OSS → historical framing; Fixed deploy
→ a paragraph), and its insistence that *every chapter and pattern earn its
place*. I left its 16-chapter count as under-serving "cover ALL ~45 deck
patterns at 2k words with running code."

**Final counts and rationale.** **11 parts (Part 0–10) + Appendices; 33 core
chapters (00–32); 9 iterations (r01 planning … r09 close).** 33 is the
defensible middle: 16 cannot carry 45 patterns + running code at 2k words each;
49 over-scopes. Each of the six extractions earns its own chapter (the spine),
the data cluster gets the room it genuinely needs (Part 6), and demotions
(caching, event-sourcing-light, Netflix-OSS-as-history, Fixed-deploy) keep it
from drifting toward 49. It sits just under the risk plan's 31 on the narrative
spine while adding the extraction-anchored chapters the arc requires.
**User-accepted as-is (DRQ-009); no compression of Parts 9–10.**

---

## A. Approach (and what was rejected, incl. cross-plan tension resolution)

### A.1 Chosen approach

A **single continuous shipping/e-commerce narrative**: build a believable
Spring Boot monolith first, then **strangle it to Quarkus + Camel microservices
one seam at a time**, introducing each pattern **at the moment the migration
forces it**, with **testing woven through every step** and the **monolith's
Newman collection as a behavior-equivalence suite**, enforced by an
**equivalence gate** that blocks every extraction. The whole thing is executed through an **ADLC (AI Development
Lifecycle)** that the book both *teaches* (a dedicated Part) and *demonstrates*
(an "ADLC in Action" callout in every migration chapter) — and the book's own
`_plans/` ledger (`decisions.md` / `build-plan.md` / `reconciliation.md`) is the
worked example: the book is built the way it teaches.

**Three framing decisions the brief required:**

1. **ADLC = dedicated Part AND woven thread (both).** Part 2 teaches the
   lifecycle so the reader can *do* it; every migration chapter carries a fixed
   **"ADLC in Action"** callout showing the actual Frame → Plan → Generate →
   Verify → Reconcile trace, agent tier, MCP calls, gates, and `DRQ-NNN` IDs
   used to produce *that* chapter's code. The repo's own ledger is Exhibit A.

2. **Monolith/decomposition sequencing IS the narrative.** Chronological
   migration timeline: why → how-we-work (ADLC) → build the "before" monolith →
   find the seams → strangle slice by slice → data/transactions → coordinate
   (sagas/resilience) → communicate → operate → deliver. The reader never meets
   a pattern before the migration forces it.

3. **Reuse-vs-fresh ≈ 60% adapt/reuse, 40% fresh.** Reuse theory + appendices +
   infra + harnesses heavily (CNDP, EIP-Camel, DataMesh, DDD-Obs per the
   reuse-map); author fresh the spine: the reference monolith and its deliberate
   smells, the strangler narrative + per-step code, Part 2 (ADLC), and the
   CI/CD-for-migration material. The ratio is a *means*, not a goal.

### A.2 Cross-plan tensions and how each was resolved

- **T1 — First-extraction choice (risk: Inventory; outcome/simple: Review).**
  **Resolved → Review is the walking-skeleton slice (r02).** It is REST-only
  with no synchronous dependency, so it is simultaneously (a) the *least* domain
  surface to get end-to-end (risk plan's goal — prove the full loop once) and
  (b) the "anticlimactic first win" that teaches the loop before difficulty
  rises (outcome plan's goal). Review exercises the entire seam machinery —
  Camel strangler proxy, ACL, Quarkus scaffold, Newman equivalence, ADLC trace,
  flag-gated cutover, monolith-module decommission — with nothing else to
  confound it. This grafts outcome+simple's choice onto risk's walking-skeleton
  mechanism.

- **T2 — Chapter count (16 vs 31 vs 49).** **Resolved → 33** (see Judgment).

- **T3 — ADLC placement (thread vs Part vs both).** **Resolved → both**, taking
  the risk/outcome position over simple's thread-only, because requirement #F
  ("ADLC *replaces* the SDLC") demands explicit instruction, and the per-chapter
  callout prevents the Part from becoming hand-wavy.

- **T4 — Extraction order.** **Resolved → outcome plan's rising-difficulty
  order**: review (REST leaf) → notification (event consumer) → inventory
  (sync gRPC + decomposed DB/CDC) → payment (choreographed saga) → shipping
  (orchestrated saga) → order + gateway (core aggregate + CQRS, last). Each step
  *forces* exactly the next pattern.

- **T5 — Data cluster depth (simple demotes ES/CQRS; outcome keeps both).**
  **Resolved → CQRS is load-bearing** (the GraphQL gateway read side needs it);
  **event sourcing is covered but kept light/optional** (high build cost, the
  house migration doesn't require it) — a graft of simple's demotion onto
  outcome's retention.

- **T6 — Toolchain (DataMesh Docker vs project podman).** **Resolved →
  podman-only** (already fixed); minikube only for k8s-specific chapters; single
  compose source; "pin image tags once in `.env`" discipline inherited. No
  Docker inheritance.

- **T7 — Iteration count (7 vs 8 vs 10).** **Resolved → 9** (r01 planning + r02
  walking skeleton + r03–r09), keeping risk's walking-skeleton r02 while
  compressing outcome's 10 where parts combine cleanly.

### A.3 Rejected alternatives (one line each)

- **Pattern-catalog structure (mirror the deck).** Rejected: describes patterns,
  doesn't demonstrate migration; the deck is the thing being superseded.
- **ADLC thread-only.** Rejected: too easy to become hand-wavy; no home for the
  full lifecycle model (requirement #F).
- **ADLC dedicated-Part-only.** Rejected: readers never see it carry weight on
  real code.
- **Decompose a borrowed monolith.** Rejected: no clean monolith exists; a fresh
  one lets us plant *deliberate* smells each pattern then cures.
- **One mega-reactor up front / big-bang authoring.** Rejected: invites scope
  explosion and defers the riskiest proof; grow the reactor one service at a time
  behind the walking skeleton.
- **Full k8s/mesh/GitOps from chapter 1.** Rejected: gold-plating; minikube
  arrives only when the operate-it narrative needs it.
- **Chasing every deck §6 gap (Wasm, FinOps, serverless deep-dive, Backstage).**
  Rejected: scope discipline — named as "further horizons," not built.

### A.4 Standing constraints honored

Scope discipline (no speculative infrastructure; demotions are explicit
decisions), conceptual coherence (consistent abstraction level per part),
security-by-design (OWASP Top 10 / CIS woven into contracts, registry, mesh,
CI/CD, and the ADLC Verify gate), and **never push upstream without explicit
permission** (r01 is planning only; repo/branch/push only after approval).

---

## B. Site architecture

Jekyll/GitHub Pages in the inherited house skeleton (`_docs`, `_parts`,
`_plans`, `examples/`, `_example_pages/`, `_layouts`, `assets/css/site.css`),
scaffolded with **lgtm-jekyll**, authored with **lgtm-tutorial**. `_config.yml`
cloned verbatim from the reuse-map §5 pattern (collections: `docs`, `plans`
[unpublished], `example_pages`, `parts`; `exclude: examples/ scripts/ demos/
presentation/`). Chapters **min 2000 words excluding code/diagrams**; every
chapter ships a runnable example and a verification-status footer.

- **Accent:** **"migration green" `--accent: #3d7a4e`** — **CONFIRMED**
  (distinct from amber CNDP/EIP, red DataMesh, teal DDD-Obs; see DRQ-023).
- **`brand_emoji`:** **🏗️** — **CONFIRMED**. **`github_repo`:**
  `modernizing-enterprise-applications`; **`github_username`:** `patterncatalyst`.
- **Code samples:** tabbed Spring Boot ⇄ Quarkus panels (`_includes/codetabs.html`
  + `assets/js/codetabs.js`, ported from cloud-native-design-patterns and
  recolored to `--accent`) throughout, per DRQ-033.

### B.1 `_parts` (ordered)

| # | Part | Blurb |
|---|---|---|
| 0 | Setting Up | Toolchain (SDKMAN/JDK 25/Maven/Quarkus+Camel CLI), the podman observability stack, the repo, the ADLC ledger, how to run every example. |
| 1 | Why Modernize | Modernization as disciplined engineering; when microservices are a bad idea; strategies; the assessment rubric and evolution arc reframed for 2026. |
| 2 | The AI Development Lifecycle (ADLC) | What replaces the SDLC: phases, agent roles, MCP tools, human gates, the safety net, and a full worked loop on a trivial change. |
| 3 | The Reference Monolith | Build the Spring Boot "before" picture: domain, modules, persistence, API, seed data, deliberate smells, and the test suite that becomes the equivalence suite. |
| 4 | Finding the Seams | Strategic + tactical DDD, hexagonal, event storming, coupling theory, modular monolith / decomposed DBs — deciding *where* to cut. |
| 5 | The Strangler Fig in Practice | The strangler family and the first two extractions (review, notification); ACL/content-based routing; going event-driven. |
| 6 | Data Across the Seam | Shared data → CDC/log-tailing (+ inventory extraction) → outbox → event-sourcing/CQRS → ACID→ACD. |
| 7 | Coordinating Across Services | Distributed transactions, choreographed saga (+ payment), orchestrated saga (+ shipping), failure modes and the resilience chassis. |
| 8 | Communication & Contracts | REST/gRPC/GraphQL (+ order extraction & gateway, strangler completes), the Quarkus/MicroProfile chassis, the schema/API/service registry. |
| 9 | Operating the Modernized System | Deployment patterns; service mesh, observability (OTel/LGTM/Kiali), distributed tracing — on minikube. |
| 10 | Delivering & Reflection | CI/CD for the strangler on **GitHub Actions** (the workflow files, the equivalence-gate job, the deployment jobs named explicitly), GitOps, progressive delivery, supply-chain security; then the pattern-map/chassis/anti-patterns/what's-next close. |
| A+ | Appendices | Deep-dive reference material (mostly reuse-as-is / adapt). |

### B.2 Ordered chapter list (title + 1–2 line synopsis)

**Part 0 — Setting Up**
- **00 Introduction & How to Use This Book** — the thesis (modernize *with* an
  agentic ADLC), the shipping through-line, the before/after, reading paths,
  conventions, the reference-book map. *(no code)*
- **01 Prerequisites & the Toolchain** — SDKMAN/JDK 25/Maven/Quarkus CLI/Camel
  CLI+TUI; the `lgtm-podman-stack` observability stack; minikube reserved for
  Part 9–10; verify-your-setup gate.
- **02 The Project Ledger** — the three ADLC artifacts (`decisions.md` with the
  `DRQ-NNN` convention, `build-plan.md`, `reconciliation.md`) introduced as the
  reader's own migration memory.

**Part 1 — Why Modernize**
- **03 Modernization as Engineering, Not Fashion** — goals, the 10 microservice
  traits, "microservices are not the goal," and *when microservices are a bad
  idea.* *Deepen: Newman.*
- **04 Strategies & Assessment** — Lift&Shift / Modernize&Extend / Rip&Rewrite
  (+ Repurchase/Retire/Retain, Container-Native Virtualization as non-targets);
  the strategic-value × change-frequency 2×2; the ease-of-migration rubric; the
  evolution arc (Monolith→SOA→MS 1.0→Cloud-Native 2.0) reframed for 2026.

**Part 2 — The ADLC**
- **05 From SDLC to ADLC** — why the traditional lifecycle breaks under
  agent-assisted work; the ADLC phases; which SDLC steps agents replace/augment;
  human gates. *Deepen: Albada.* *Further reading:* Daniel Oh's **Enterprise
  Agentic AI Workshop** (Quarkus/Java 25, LangChain4j-style declarative agents —
  danieloh30.github.io/agentic-ai-java-workshop) is an additional primary
  agentic reference, Quarkus/Java-native and directly analogous to our ADLC; its
  human-gate + OpenTelemetry-tracing exercise maps to our two human gates and
  Verify-phase observability, and its plan-and-execute dynamic re-planning maps
  to our Plan→Generate→Verify loop and repair rounds.
- **06 Agents, Skills & MCP Tools in the Loop** — the lgtm-relay tiering (Opus
  plan → Sonnet execute → Opus validate); the MCP surface (Quarkus Agent, Camel
  MCP); the lgtm-* skill map; when the human must decide. *Further reading:*
  Oh's workshop supervisor-orchestration exercise (a coordinator delegating to
  specialist sub-agents) is directly analogous to this relay orchestration
  model.
- **07 The ADLC Safety Net (a worked loop)** — tests/contracts/reconciliation as
  the guardrails that make agentic generation trustworthy; a full loop run
  end-to-end on a trivial change, establishing the "ADLC in Action" callout
  format used thereafter. *Further reading:* Oh's workshop governs agent
  behavior via `AGENTS.md` (its reference projects use `AGENTS.md`/`CLAUDE.md`
  context files) — the analog of our `_plans/` ledger + agent governance.
  Recommended as a hands-on companion for readers who want to **build** agents;
  this book uses the agentic workflow to **do** the modernization.

**Part 3 — The Reference Monolith**
- **08 Designing the Monolith** — the six bounded contexts as one Spring Boot
  deployable: domain model, layered modules, single shared schema, REST API,
  seed data. *First running example.*
- **09 The Deliberate Smells** — the coupling/god-service/shared-table/
  transaction-scope smells planted on purpose, each tagged to the pattern that
  will later cure it. *Deepen: Newman ch.3.*
- **10 Testing the Monolith (the Equivalence Suite)** — unit + Testcontainers
  integration tests + the **Newman contract collection** captured against the
  running monolith; this collection becomes the behavior-equivalence suite,
  re-run against every extraction via the equivalence gate. *Reuse: EIP-Camel
  testing strategy + CNDP Newman appendix.*

**Part 4 — Finding the Seams**
- **11 Strategic & Tactical DDD + Hexagonal** — subdomains, bounded contexts,
  context mapping, aggregates, domain events; keeping protocols out of the
  domain core so extraction is mechanical. *Reuse/adapt: CNDP DDD/hexagonal.
  Deepen: Ozkaya.*
- **12 Event Storming the Monolith** — discovering seams from behavior, not
  schema; produces the decomposition backlog; run as an ADLC reconnaissance
  step. *Reuse/adapt: DDD-Obs event-storming addendum.*
- **13 Coupling, the Modular Monolith & When *Not* to Split** — Khononov's three
  dimensions; the modular monolith and decomposed-DB monolith as legitimate
  destinations; the distributed-monolith trap. *Reuse: CNDP coupling. Deepen:
  Newman ch.4.*

**Part 5 — The Strangler Fig in Practice**
- **14 The Strangler Fig Pattern** — Fowler's pattern, the three steps, and the
  proxy / redirection / shared-database variants; the Camel strangler proxy used
  for cutover. *Adapt: CNDP monolith-to-microservices (promoted from appendix).*
- **15 Extraction 1 — Review Service (the walking skeleton)** — REST-only, OIDC,
  no sync dependency; prove the full loop: ACL, Quarkus scaffold, equivalence
  gate pass, flag-gated cutover, decommission the monolith module. *The
  deliberately anticlimactic first win; authored first in r02.*
- **16 Content-Based Routing & the Anti-Corruption Layer** — Camel content-based
  router, message translator, content enricher as the translation layer at the
  seam; the decorating-collaborator pattern for untouchable legacy. *Reuse:
  EIP-Camel routing + transformation.*
- **17 Extraction 2 — Notification Service (going event-driven)** — introduces
  the Kafka backbone, the **outbox** in the monolith for reliable publication,
  and content-based event routing; first taste of eventual consistency.

**Part 6 — Data Across the Seam**
- **18 From Shared Data to Owned Data** — the shared-data pattern and its limits;
  the cost of the shared schema; planning the split. *Deepen: Kleppmann.*
- **19 Transaction Log Tailing, CDC & Extraction 3 — Inventory** — Debezium-style
  CDC/log-tailing to bridge the monolith's tables during transition; gRPC-first
  inventory with a decomposed DB and CDC backfill; decorating collaborator
  retires the monolith's inventory calls.
- **20 The Outbox Pattern, Done Right** — transactional outbox vs dual-write;
  idempotent consumers; ordering and deduplication.
- **21 Event Sourcing & CQRS** — CQRS read/write split (load-bearing for the
  gateway read side); event sourcing covered but kept light/optional; trade-offs.
  *Reuse/adapt: CNDP. Deepen: Kleppmann.*
- **22 ACID → ACD: Living Without Isolation** — the consistency just given up;
  dirty reads, lost updates, non-repeatable reads; where it bites and how to
  bound it. *Deepen: Kleppmann ch.7.*

**Part 7 — Coordinating Across Services**
- **23 Distributed Transactions, the Saga & Extraction 4 — Payment
  (choreographed)** — why 2PC is off the table; compensating transactions;
  event choreography (`order.placed`→`payment.captured`); failure-path tests.
  *Reuse: DDD-Obs failure-path Newman payloads; CNDP saga appendix.*
- **24 Extraction 5 — Shipping Service (orchestrated saga)** — the same outcome
  via a central orchestrator using the **Camel Saga EIP**; choreography vs
  orchestration made concrete on one codebase.
- **25 Failure Modes & the Resilience Chassis** — partial-failure taxonomy;
  timeouts, retry+jitter, circuit breaker, bulkhead, load shedding; SmallRye
  Fault Tolerance as the Quarkus chassis (reframed off Netflix OSS). *Reuse:
  CNDP failure-modes.*

**Part 8 — Communication & Contracts**
- **26 Communication Styles & Extraction 6 — Order + GraphQL Gateway** —
  REST/gRPC/GraphQL and when each fits; the core aggregate extracted last; the
  GraphQL gateway as the CQRS read-aggregation surface; the strangler completes
  and the monolith order module is decommissioned.
- **27 The Quarkus / MicroProfile Chassis** — Config, Fault Tolerance, Health,
  Metrics, OpenAPI, REST Client, JWT; build-time optimization, native image,
  dev-mode inner loop — "what you migrated *to*." *Reuse: DataMesh Quarkus
  primer + Spring-vs-Quarkus twin.*
- **28 Contracts & the Service Registry** — schema/API/shared-data-type registry
  use cases and enterprise requirements; Apicurio with Avro/Protobuf/JSONSchema/
  OpenAPI/AsyncAPI; self-documenting schemas citing decision IDs.

**Part 9 — Operating the Modernized System**
- **29 Deployment Patterns** — rolling, breaking-schema-change, and blue-green
  tied to the cutover (Fixed demoted to a paragraph); which fits synchronous vs
  event-driven services. *Adapt: EIP-Camel k8s deploy + DataMesh k8s/.*
- **30 Mesh, Observability & Distributed Tracing** — Istio (mesh/mTLS/canary),
  Kiali, OpenTelemetry + the LGTM stack, spans/causality/context propagation,
  cross-context debugging — on minikube. *Reuse: DDD-Obs dashboards +
  lgtm-minikube-stack.*

**Part 10 — Delivering & Reflection**
- **31 CI/CD, GitOps, Progressive Delivery & Supply Chain** — **names the actual
  GitHub Actions workflows** (`.github/workflows/code-ci.yml` and friends), the
  equivalence-gate job that runs the behavior-equivalence suite on every
  extraction, and the deployment jobs, not just the concepts behind them; UBI
  image builds; GitOps; feature-flag/canary progressive cutover; SBOM + CVE
  scan + policy-as-code (security-by-design, the deck's gap). *Partly net-new.*
- **32 The Pattern Language, Revisited** — Richardson's map re-walked with the
  completed migration; anti-patterns of over-decomposition; the chassis in 2026;
  honestly-scoped "further horizons" (serverless, Wasm, platform engineering,
  FinOps, the ADLC's own evolution).

### B.3 Appendix list (reuse verdicts)

| Appendix | Source | Verdict |
|---|---|---|
| A. The Reference Monolith — module map | — | **new** (companion to Part 3) |
| B. The Target Reactor — module & contract map | — | **new** (companion to Parts 5–8) |
| C. DDD & Hexagonal (condensed) | CNDP `19-appendix-f` | adapt (core material promoted into ch.11) |
| D. Sagas — state/persistence/compensation | CNDP `17-appendix-d` | adapt (pairs with ch.23–24) |
| E. Coupling (Khononov) | CNDP `20-appendix-g` | reuse-as-is (feeds ch.13) |
| F. Failure Modes & Defensive Toolkit | CNDP `26-appendix-m` | adapt (feeds ch.25) |
| G. Feature Flags | CNDP `27-appendix-n` + EIP `26` | adapt (merge; feeds cutover) |
| H. Caching Patterns | CNDP `25-appendix-l` | reference / light-adapt *(demoted here)* |
| I. Camel CLI & TUI | EIP `39`/`40` | reuse-as-is |
| J. Citrus Testing | EIP `41` | reuse-as-is (load-bearing) |
| K. Three-Tier Testing Strategy | EIP `37` | reuse-as-is |
| L. Newman as Executable Contracts | CNDP `28-appendix-o` | reuse-as-is |
| M. Kafka Fundamentals & Tuning | EIP `20,32–36` | reuse-as-is (subset) |
| N. Quarkus Dev Services / Testcontainers | EIP `23` + DataMesh wire-compat gotcha | reuse-as-is |
| O. Quarkus vs Spring Boot (measured twin) | DataMesh `12` + `spring-boot-compare/` | reuse-as-is (central) |
| P. AI/MCP for Integration | EIP `42` | adapt (runtime-AI vs ADLC-time-AI; cross-ref Part 2) |
| Q. Kubernetes Deploy on Minikube | EIP `38` + DataMesh `k8s/` | adapt (feeds ch.29–30) |
| R. Observability Economics | DDD-Obs `05` | reference |
| S. Worked EIP Case Studies (Loan Broker, Bond Trading) | EIP `28`/`29` | reference (further reading) |
| T. Graceful Shutdown & L7 Routing | CNDP `21`/`22` | reference |
| U. Glossary / Pattern Index | — | new |
| V. Agentic Patterns Inside the Modernized System *(OPTIONAL)* | DataMesh langchain4j/MCP Camel routes; pattern adapted from Daniel Oh's Enterprise Agentic AI Workshop | adapt/new — **OPTIONAL, scope-guarded; may be deferred past r09** |

Appendices are deliberately reuse-as-is / adapt / reference — **zero net-new
appendix prose in r02–r06**; they are ported in r08. **Appendix V is explicitly
out of the ADLC's scope**: the ADLC (Part 2) is about *how* the book is built;
Appendix V would be an optional AI *capability inside the shipped system* (e.g.
a LangChain4j `@Agent` doing AI order-classification on a Camel route, built on
the datamesh project's existing langchain4j/MCP routes) — distinct from the
ADLC, non-load-bearing, and droppable without affecting the core 33 chapters.

---

## C. Pattern-coverage matrix (EVERY deck pattern → home)

"Runnable?" = a full runnable example; "demoted" = covered but not given a full
example (reason given). All ~45 patterns appear.

| Deck pattern (category) | Home | Runnable? | Note |
|---|---|---|---|
| Lift&Shift / Modernize&Extend / Rip&Rewrite (A) | ch.04 | conceptual | strategy framing; book executes Modernize&Extend→strangler |
| Repurchase/Retire/Retain; Container-Native Virtualization (A) | ch.04 | demoted | non-targets; a paragraph |
| Monolith (B) | ch.08 | yes | the "before" (built) |
| Modular Monolith (B) | ch.13 | yes | legit destination |
| Modular Monolith w/ decomposed DBs (B) | ch.13, ch.18 | yes | distributed-monolith trap |
| Strangler Fig + proxy/redirection/shared-DB variants (B) | ch.14 | yes | the spine |
| Content-based Routing (B) | ch.16, ch.17 | yes (Camel) | cutover + event routing |
| Decorating Collaborator (B) | ch.16, ch.19 | yes | proxy-side + inventory retire |
| Shared Data (C) | ch.18 | yes | and its limits |
| Transaction Log Tailing (C) | ch.19 | yes | folded into CDC |
| Simple CDC (C) | ch.19 | yes (Debezium) | inventory backfill |
| Outbox (C) | ch.17 (intro), ch.20 | yes | reliable-publish workhorse |
| Event Sourcing (C) | ch.21 | light/optional | demoted-light: high cost, not required |
| CQRS (C) | ch.21, ch.26 | yes | load-bearing via gateway read side |
| Distributed Transactions ACID→ACD (C) | ch.22 | conceptual | framing for sagas |
| Saga — general (D) | ch.23 | yes | compensations |
| Saga — Choreographed (D) | ch.23 | yes | + payment extraction, failure paths |
| Saga — Orchestrated (D) | ch.24 | yes (Camel Saga EIP) | + shipping extraction |
| Circuit Breaker / Discovery / Health (E) | ch.25, ch.27, ch.30 | yes (SmallRye/MP/platform) | reframed off Netflix OSS |
| Microservices Chassis (E) | ch.27, ch.32 | yes | Quarkus + platform |
| Fixed / Rolling / Breaking-Schema / Blue-Green (F) | ch.29 | yes (manifests) | Fixed demoted to a paragraph |
| Service/Schema/API registry + requirements (G) | ch.28 | yes (Apicurio) | |
| Schema management (G) | ch.28 | yes | |
| Service Mesh (H) | ch.30 | yes (Istio, minikube) | |
| Observability / Kiali (H) | ch.30 | yes | |
| Distributed Tracing (H) | ch.30 | yes (OTel) | reframed off Zipkin |
| Caching (H) | App. H | demoted → appendix | generic; no migration driver |
| API Gateway + Service Discovery evolution (H) | ch.26, ch.28 | yes | |
| REST / gRPC / GraphQL (H) | ch.26 | yes | |
| MicroProfile (H) | ch.27 | yes (via Quarkus) | |
| Unit / E2E / Microservice / Testing-Data (I) | ch.10 + woven + App. J/K/L | yes | testing throughout |
| Event-driven topologies / Reduction functions (as test concerns) (I) | ch.10, ch.17 | yes | |

**Demotions & why:** Container-Native Virtualization + Repurchase/Retire/Retain
(non-migration targets — mentioned, not demonstrated); **Event Sourcing** (high
build cost, the house migration doesn't require it — conceptual/optional, not a
full build); **Caching** (generic, no migration driver — appendix); **Fixed
deployment** (niche — a paragraph in ch.29); **Netflix OSS / Microservices 1.0
stack** (legacy in 2026 — historical framing in ch.04/ch.32, replaced by
K8s-native discovery, OTel, SmallRye, Istio); **Loan Broker / Bond Trading EIP
case studies** (not migration-specific — reference Appendix S).

**NEW topics beyond the deck (~9, filling deck-inventory §6 gaps):** (1) the
**Agentic ADLC** (Part 2 + per-chapter callouts); (2) **Quarkus** as the
concrete runtime (native/dev-mode/Panache, ch.27, App. O); (3) **Apache Camel /
EIP** specifics (routing, Saga EIP, Kamelets); (4) **OpenTelemetry + LGTM** (ch.30);
(5) **GitOps + progressive delivery** (ch.31); (6) **Supply-chain / SBOM / CVE /
policy-as-code** (ch.31); (7) **explicit DDD + event storming** (Part 4); (8) the
**Spring→Quarkus measured comparison** (App. O, the book's premise); (9) an
**optional in-system agentic capability** — a LangChain4j `@Agent`/AI-assisted
Camel route (e.g. AI order-classification) adapted from the datamesh project's
existing langchain4j/MCP Camel routes (App. V, **OPTIONAL**, may be deferred;
distinct from the ADLC itself, which is about *how* the book is built, not an
AI feature *in* the shipped system).

---

## D. Reference monolith design

A **fresh Spring Boot 3.x monolith (JDK 25)** — one deployable, one database,
one JVM — the believable common ancestor of the sibling target architectures
(reuse-map §6). **Kept permanently in-repo at `examples/00-monolith/`
(CONFIRMED, DRQ-024)** as the living "before" and the equivalence suite's
referent.

The monolith is intentionally the simple, legacy-shaped "before" — it is not
where Quarkus's strengths are shown. The non-trivial, production-shaped
examples live on the Quarkus "after" side of each extraction (§E, DRQ-032),
so the before/after contrast stays pedagogically sharp.

- **Domain (six bounded contexts as packages/modules):** `order`, `inventory`,
  `payment`, `shipping`, `notification`, `review`. Reuse the shared DTO
  vocabulary (`OrderDto`, `OrderStatus`, `StockDto`, `ReviewDto`,
  `NotificationDto`, `OrderCreate`, `Topics`) so extracted services line up 1:1
  with DataMesh/DDD-Obs/EIP-Camel.
- **Architecture:** classic layered Spring Boot (controller → service →
  repository), Spring MVC REST, Spring Data JPA, Bean Validation, Spring
  Security on review endpoints — deliberately **not** hexagonal at first (a smell).
  *Forward reference (DRQ-029):* this choice is deliberate beyond the smell —
  Spring MVC, Spring Data JPA, and Spring Security are exactly the mainstream
  Spring APIs with Quarkiverse Spring-compatibility counterparts
  (`quarkus-spring-web`/`-di`/`-data-jpa`/`-security`/etc.), so every later
  extraction's Phase A ("lift onto Quarkus," §E) has a clean, unexotic bridge —
  no bespoke Spring usage in the monolith that the compatibility extensions
  can't cover.
- **Persistence:** one PostgreSQL schema shared across all six contexts, with
  cross-context foreign keys and JPA joins (the data-coupling smell). Flyway
  migrations; deterministic seed data at startup.
- **API surface:** REST for all six contexts; an in-process order-placement flow
  touching inventory → payment → shipping → notification **in one ACID
  `@Transactional`** (the distributed-transaction smell cured by saga).
  springdoc OpenAPI seeds the Newman behavior-equivalence suite.
- **Deliberate smells (each tagged to its curing chapter):**
  1. Shared schema / cross-context joins → owned data + CDC (ch.18/19).
  2. God `OrderService` reaching into other modules → hardest/last extraction
     (ch.26) + motivates sagas.
  3. One in-process ACID transaction across contexts → saga (ch.23/24),
     ACID→ACD (ch.22).
  4. Synchronous notification inside the checkout transaction → outbox +
     event-driven extraction (ch.17).
  5. No ACL / leaky domain model → ACL at the seam (ch.16).
  6. Review tangled into shared security but genuinely independent → easy first
     strand (ch.15, the walking skeleton).
- **Testing (the equivalence suite):** JUnit unit tests per service; Testcontainers
  integration tests over real Postgres; a **Newman contract collection** (happy
  path + out-of-stock + payment-decline) re-run **unchanged** against each
  extracted service to prove behavioral equivalence (reuse CNDP `appendix-o` +
  DDD-Obs payload library).
- **Scale discipline:** match DataMesh's "3–9 files per module" bar — realistic,
  not sprawling (reuse-map §4).

---

## E. Decomposition roadmap (strangler-fig sequence → chapters)

Order follows rising difficulty and the deck's "least-complex / highest-ROI
first" guidance (p.18). Each step = a chapter, a `DRQ-NNN` decision, a
`build-plan.md` row, a `demos/demo-*.sh`, a flag-gated cutover behind the Camel
strangler proxy, and an equivalence-gate check before the monolith module is decommissioned.

| # | Service | Seam / mechanism | Data & transaction handling | Target | Chapters | Status |
|---|---|---|---|---|---|---|
| 0 | — | Insert the Camel strangler proxy in front of the monolith | n/a | — | ch.14 | DONE (r02/r04) |
| 1 | **review** | REST leaf, no sync deps; proxy redirect by URI; ACL | Own schema from day one; no shared-txn entanglement | Quarkus REST + OIDC | ch.15 *(walking skeleton, r02)* | **DONE** (r02 — extraction 1 of 6) |
| 2 | **notification** | Event consumer; content-based routing; decorating collaborator | **Outbox** in monolith → Kafka; idempotent consumer | Quarkus + Camel (Kafka consumer, WebSockets.Next) | ch.16–17 | **DONE** (r04 — extraction 2 of 6) |
| 3 | **inventory** | Synchronous gRPC; decomposed DB; decorating collaborator | **CDC (Debezium)** backfill from shared schema → owned DB, then cut writes | Quarkus gRPC server | ch.19 | **DONE** (r05 — extraction 3 of 6) |
| 4 | **payment** | Event choreography (`order.placed`→`payment.captured`) | Choreographed **saga**; compensations; ACID→ACD realized | Quarkus + Camel (Kafka) | ch.23 | **DONE** (r06/ch.23, S14 reconcile — extraction 4 of 6; was "in progress" through S1–S13) |
| 5 | **shipping** | Completes the chain; alternative orchestration | **Orchestrated saga** via Camel Saga EIP | Quarkus + Camel (Saga EIP) | ch.24 | not started (resumes from ch.23, see `payment-plan.md` resume boundary) |
| 6 | **order** (+ gateway) | Core aggregate, extracted last; GraphQL read side | **CQRS** read model; event-sourcing optional; monolith decommissioned | Quarkus REST/Kafka + GraphQL gateway | ch.26 | not started |

**Extractions done: 4 of 6** (review, notification, inventory, payment) as of r06/ch.23 S14. Status column introduced/backfilled at this reconcile pass (r06/ch.23 S14); ch.15/ch.17/ch.19 marked DONE retroactively from their own `_plans/iterations/{notification,inventory}-plan.md` step annotations and committed `examples/*/CUTOVER.md` evidence — no new work implied by the backfill.

**Migration depth (CONFIRMED, revised DRQ-026):** every extraction above
(1–6 — review, notification, inventory, payment, shipping, order+gateway) runs
the full `migrate-spring-to-quarkus` process in complete depth — not "full
once for Review, then summarize the rest." **Scope impact:** this enlarges
iterations r04–r07 (each carries a full per-service migration writeup plus
tests, not an abbreviated pass for five of the six services). **Mitigation:**
per-service migration chapters share a repeatable template, with the full
mechanical migration detail living in a shared appendix that each chapter
references, so chapters stay readable and prose doesn't balloon.

**Two-phase migration strategy (CONFIRMED, DRQ-029) — the repeatable
per-service template referenced above:** each of the six full-depth
`migrate-spring-to-quarkus` runs is itself taught in two phases, not one jump.
**Phase A — lift onto Quarkus:** the Spring-API source for that service moves
onto Quarkus largely unchanged, using the Quarkiverse **Spring-compatibility
extensions** — `quarkus-spring-web` (Spring MVC REST annotations),
`quarkus-spring-di` (Spring DI annotations), `quarkus-spring-data-jpa` (Spring
Data repositories), `quarkus-spring-security`, `quarkus-spring-boot-properties`,
`quarkus-spring-cache`, `quarkus-spring-scheduled`, and
`quarkus-spring-cloud-config-client` as applicable. This is the fast, low-risk
bridge: the service runs natively on Quarkus and passes the equivalence gate
quickly, with no idiom rewrite blocking the gate. **Phase B —
make it idiomatic:** the same service is then refactored off the compatibility
shim to idiomatic Quarkus — RESTEasy Reactive/Quarkus REST, Panache
(active-record or repository), native CDI, SmallRye Config, and Quarkus
Security — with a measured before/after (startup time, memory, native-image
size/build) captured as the teaching payoff. Phase A de-risks each extraction
(equivalence-gate-green fast); Phase B is where the "why Quarkus" argument gets made
with numbers. Together A→B is the single repeatable template every extraction
chapter (15, 17, 19, 23, 24, 26) follows, reinforcing the full-depth decision
(DRQ-026) without requiring six independent one-shot rewrites.

**Seam toolkit (consistent across all six):** a Camel strangler proxy routing by
URI/content; an ACL (Camel message translator + content enricher) at each
boundary; feature flags (OpenFeature/flagd) as the cutover mechanism; the
equivalence gate as the go/no-go checkpoint. **Reversibility** is a design property — every step
is flag-reversible before decommission. End state matches the DataMesh "after"
picture, so examples cross-reference it directly rather than re-author.

**Example quality bar (CONFIRMED, DRQ-032).** Each extraction above must be a
non-trivial, production-shaped example — not a toy CRUD stub — that
demonstrates specific Quarkus strengths, reusing/adapting patterns from the
sibling `datamesh-reference-arch-quarkus` project (with attribution) rather
than re-authoring from scratch:

- **notification** → SmallRye Reactive Messaging / Kafka, adapted from
  datamesh's `notification-service`.
- **inventory** → `quarkus-grpc` service-to-service calls + Panache
  persistence, adapted from datamesh's `inventory-service`.
- **payment** / **shipping** → choreographed/orchestrated sagas, including
  Camel-on-Quarkus EIPs (Saga EIP for shipping), adapted from datamesh's
  `payment-service` / `shipping-service` and its orchestration-styles demo
  (`ai-rules-service`, `_docs/13-orchestration-styles.md`).
- **order + gateway** → a SmallRye GraphQL gateway federating the extracted
  services into the CQRS read side, adapted from datamesh's
  `graphql-gateway`.
- **Cross-cutting (all six)** → Quarkus Dev Services, continuous testing, and
  native image, pulled from datamesh's `quarkus-deep-dive` part and its
  `demo-continuous-testing.sh` / `demo-native.sh` / `demo-oidc.sh`; OIDC on
  the review service per §E row 1.
- **Optional AI route** → langchain4j/MCP, adapted from datamesh's
  `ai-mcp-service` / `ai-rules-service` (ties to Appendix V, DRQ-028; stays
  optional/scope-guarded).

Review (ch.15, r02) remains the deliberately simple REST-leaf walking
skeleton — that is a *sequencing* choice (prove the loop first), not the
quality bar. Every extraction from notification onward raises the bar to a
full Quarkus-strength showcase.

---

## F. ADLC / agentic-workflow design

The ADLC is taught in Part 2 and **demonstrated** in every migration chapter via
a fixed "ADLC in Action" callout. The repo's own `_plans/` ledger is the worked
example — the book is built the way it teaches.

### F.1 Phases (replacing requirements→design→build→test→deploy→maintain)

| ADLC phase | What happens | Human gate? | Agents / tools / skills | Replaces |
|---|---|---|---|---|
| **1. Frame** | Human states the step's outcome + acceptance criteria into a PRD + `decisions.md` entry | author intent | human | requirements |
| **2. Map** | Agent reconnaissance: classify the legacy module, map dependencies, find the seam | — | camel-mcp `migration_analyze`; quarkus-agent `migrate-spring-to-quarkus`; Explore subagent | design/analysis |
| **3. Plan** | Opus produces the step plan, `DRQ-NNN`, and `build-plan.md` rows | **GATE: human approves plan before any code** | lgtm-relay (Opus plan) | design + task breakdown |
| **4. Generate** | Sonnet scaffolds + writes code + tests | — | lgtm-relay (Sonnet); lgtm-quarkus; lgtm-camel; quarkus-agent `create`/`skills`; camel-mcp route scaffold | implementation |
| **5. Verify** | Opus validates: run Citrus/Newman/Dev Services, check the equivalence gate, security scan; write `reconciliation.md` | **GATE: human signs off on equivalence** | lgtm-relay (Opus validate); camel-mcp `validate_route`; Newman/Citrus | code review + QA + security |
| **6. Operate** | Deploy behind a flag, observe via LGTM, shift traffic | human controls rollout % | lgtm-podman/minikube-stack; feature flags | deploy |
| **7. Reconcile** | Append decision outcomes, update build-plan status, record drift | — | the three ledger artifacts | change log / traceability |

### F.2 How it replaces the SDLC
The SDLC passes artifacts between roles over weeks; the ADLC cycles one engineer
plus model tiers over hours, with two human gates (Plan approval, Verify
sign-off) replacing stage-gate committees. The three ledger artifacts replace the
heavyweight design doc + test plan + traceability matrix with a lightweight,
append-only, agent-maintained memory.

### F.3 How each chapter DEMONSTRATES it (not describes)
Every migration chapter (15, 17, 19, 23, 24, 26) ends with an **"ADLC in
Action"** callout: the Frame statement, the actual `DRQ-NNN` entry, the agent
tier + MCP tool used, the gate decisions, the equivalence-gate/reconciliation result, and
the decision-log IDs touched. **Demo mechanism (CONFIRMED, DRQ-025):** agent/MCP
tool usage in these callouts is shown as pre-captured, reproducible tool
output — narrated transcripts checked into the repo alongside the chapter — not
run live-where-cheap at build/read time. This keeps every chapter deterministic
and reviewable regardless of tool/version drift. The reader sees the same loop
six times at rising difficulty — which is how a method becomes a habit. ch.07
runs the loop once on a trivial change so the stakes are low the first time.
The r02 walking skeleton demonstrates the full loop once, end-to-end, before
the pattern is scaled.

### F.4 Security in the ADLC
The Verify gate includes dependency/CVE scan, Camel secure-by-default validation,
and secrets hygiene, so security-by-design is *part of the loop*, not a late
audit (OWASP/CIS).

---

## G. Testing strategy (per pattern, woven throughout)

Spine = EIP-Camel's **three-tier strategy** (reuse-as-is): MockEndpoint/
AdviceWith unit tests, Dev Services + REST Assured integration tests, Newman
black-box contract tests; plus **Citrus** for end-to-end route tests and
**Testcontainers / Quarkus Dev Services** for self-provisioning ITs.

| Pattern / step | Unit | Contract | Integration | E2E |
|---|---|---|---|---|
| Monolith baseline (the equivalence suite) | JUnit per service | — | Testcontainers / `@SpringBootTest` | **Newman full flow (equivalence suite)** |
| Each extraction | JUnit + Camel MockEndpoint/AdviceWith | **Newman collection run *unchanged* vs monolith** | Dev Services (Kafka/PG/Apicurio) + REST Assured; Citrus per route | Newman vs the whole mesh |
| Content-based routing / ACL | MockEndpoint + AdviceWith | — | Citrus vs real Kafka/HTTP/DB | — |
| Outbox / CDC | publisher unit | schema contract (Apicurio) | Dev Services + Debezium IT | event-arrival assertions |
| Saga (choreo + orchestrated) | compensation unit tests | — | Citrus multi-step | Newman happy + failure (DDD-Obs out-of-stock / payment-decline) |
| CQRS / read model | — | — | eventual-consistency read-after-write with bounded waits | — |
| Resilience chassis | — | — | fault-injection (timeouts, breaker trips) | — |
| Deployment / mesh | — | — | minikube smoke | canary / blue-green verification |

**Load-bearing rule:** an extracted service is "done" only when it passes the
*same* Newman collection the monolith passed — a per-chapter acceptance gate and
a CI gate. Every chapter carries a **verification-status footer** naming exactly
which tests/demos were run. Reused harnesses: EIP-Camel Citrus (one
`.citrus.it.yaml` per route), DataMesh single-collection-many-environments
Newman, DDD-Obs payload library, and the "pin image tags once in `.env`,
confirm Dev Services matches" discipline.

---

## H. CI/CD design

**GitHub Actions is the project's CI/CD platform** (per `lgtm-github`) — every
pipeline below is a real `.github/workflows/*.yml` file in the repo, not a
conceptual stand-in. **Part 10 (ch.31) MUST explicitly call out the concrete
GitHub Actions used** — the workflow file(s), the equivalence-gate job, and the
deployment jobs — so readers see the actual CI/CD implementation, not just the
underlying concepts.

- **Pipelines (GitHub Actions, per `lgtm-github`):**
  1. **Site CI** — Jekyll build + static validation (link check, front-matter,
     word-count ≥ 2000, example-dir presence, verification-footer presence) on
     every PR.
  2. **Code CI** (`.github/workflows/code-ci.yml`, minimal version introduced
     in r02 via S-CI, expanded through r04–r07) — Maven reactor build +
     unit/integration (Dev Services) + native-image smoke for Quarkus
     services; the **equivalence gate** job runs the behavior-equivalence
     suite (the monolith's Newman collection) against each extracted service
     and **fails the build if behavior diverges**.
  3. **Migration/cutover pipeline (ch.31 subject)** — builds the monolith *and*
     the growing service set together, runs the equivalence gate, deploys to
     minikube via dedicated deployment jobs, performs flag/canary cutover,
     verifies, and can roll back.
- **GitOps:** declarative manifests in `k8s/` (reuse DataMesh base + kustomize +
  Istio + KEDA overlays, retargeted to podman-built images); Argo-style sync
  demonstrated on minikube; progressive delivery via feature flags
  (OpenFeature/flagd) + Istio traffic splitting.
- **Supply chain / security:** SBOM generation, dependency/CVE scan, and a
  policy-as-code gate wired into Code CI and the ADLC Verify gate (new material;
  the deck's gap; aligns with security-by-design).
- **podman vs minikube boundary:** `lgtm-podman-stack` is the dev inner loop *and*
  CI substrate (Postgres/Kafka/Apicurio/LGTM); `lgtm-minikube-stack` is the
  k8s-specific substrate for ch.29 (deploy), ch.30 (mesh/observability), ch.31
  (GitOps). Images are podman-built throughout; minikube consumes them. Stated
  once in ch.01 and never crossed silently.

---

## I. Presentation plan (lgtm-presentation)

Rebuild the original deck (`_source/refactoring-for-app-modernization-original.
pdf`, 75 slides) with **lgtm-presentation** (Red Hat house style, pptxgenjs
16:9) as a **companion** to the book, modernized per deck-inventory §6 and
mirroring the book's parts. Target **~45–55 slides**.

Outline (one section per book part):
1. Title + Why Modernize (Part 1) — strategies, when-not-to, the 2×2.
2. **NEW: The ADLC** (Part 2) — the phase diagram, agents/tools/gates, the
   ledger — the centerpiece absent from the original.
3. Meet the Monolith (Part 3) — domain, smells, the equivalence suite.
4. Finding the Seams (Part 4) — DDD / event storming / coupling.
5. Strangler Fig in Practice (Part 5) — the proxy, the six-step sequence.
6. Data Across the Seam (Part 6) — shared→CDC→outbox→CQRS→ACID→ACD.
7. Coordinating (Part 7) — choreography vs orchestration, failure modes.
8. Communication, Contracts, Chassis (Part 8).
9. Operating & Delivering (Parts 9–10) — deploy, mesh, observability, CI/CD,
   GitOps, supply chain.
10. Reflection (Part 10 close) — pattern map, chassis 2026, further horizons.

Reuse the original's strong diagrams (strangler variants, saga worked example,
chassis hexagon, Richardson map) regenerated via `lgtm-diagram-generator` to the
house style; replace dated tooling slides (Netflix OSS) with K8s-native / OTel /
Quarkus / Camel equivalents. Built **last (r09)** so it never drifts ahead of
the chapters. **Companion `.docx` CONFIRMED deferred past r09** — the deck
`.pptx` is the only presentation artifact in r09; the `.docx` companion is
explicitly out of scope until after r09 (DRQ-027).

---

## J. Iteration / release plan

Risk-first sequencing: **r02 is a thin walking skeleton that proves every hard
part once** before anything scales. Each `rNN` is a coherent shippable
increment; later relays resume from the `build-plan.md` status table.

| Iter | Theme | Deliverable (shippable increment) | Risks retired |
|---|---|---|---|
| **r01** | **Planning (this relay)** | this `build-plan.md`, `decisions.md` seed, PRD, chosen accent/emoji, confirmed podman — **artifacts only, no build** | scope framing; toolchain decision |
| **r02** | **Walking skeleton** | site scaffold (lgtm-jekyll) + podman stack + **monolith built (ch.08–10 incl. the equivalence suite)** + **Review extracted (ch.15) end-to-end** + **ch.15 authored to 2k with runnable code + tests + equivalence pass** + **ADLC demonstrated once** (ch.07 loop + full plan→execute→validate trace in `_plans` + chapter callout) + **minimal Code-CI green (equivalence gate runs in GitHub Actions)** | the four biggest risks at once |
| **r03** | Front matter + ADLC | Part 0 (00–02), Part 1 (03–04), Part 2 (05–07), finish Part 3 prose (08–10) | ADLC-Part credibility; monolith completeness |
| **r04** | Seams + first event-driven | Part 4 (11–13), ch.14, ch.16, **Notification extraction (ch.17, full-depth migrate-spring-to-quarkus)** + outbox intro | decomposition-pattern coverage; event-driven mechanics |
| **r05** | Data patterns | Part 6 (18–22) incl. **Inventory extraction (ch.19, full-depth migrate-spring-to-quarkus)** + CDC, outbox-done-right, CQRS, ACID→ACD | the hardest data patterns; decomposed-DB mechanics |
| **r06** | Sagas + resilience | Part 7 (23–25) incl. **Payment + Shipping extractions (both full-depth migrate-spring-to-quarkus)**, saga failure paths | saga choreo+orchestrated; failure modes |
| **r07** | Comms + contracts | Part 8 (26–28) incl. **Order extraction + gateway (full-depth migrate-spring-to-quarkus; strangler completes)**, chassis, registry | core-aggregate extraction; contract governance |

**Scope impact (DRQ-026, revised):** r04–r07 are enlarged relative to the
original plan — each now carries a *full*, not summarized, per-service
migration writeup and test suite. Mitigation: a shared per-service migration
template + appendix (see §E) keeps the added detail from inflating chapter
prose past the 2k target.
| **r08** | Operate + deliver + appendices | Part 9 (29–30), ch.31; minikube substrate; port/adapt all reuse appendices; reconciliation pass | mesh/deploy/observability on k8s; CI/CD; reuse drift |
| **r09** | Deck + close | ch.32 conclusion; rebuild deck (lgtm-presentation); cross-linking; final validation; release via lgtm-github | deck/content drift; final acceptance |

**Round-1 boundary:** r01 ends at approved planning artifacts. No repo is
created, nothing is pushed, until the user approves. Repo creation + first branch
happens at the *start of r02, after approval* (per standing memory).

---

## K. Execution mapping

All work runs through **lgtm-relay** (Opus plan → Sonnet execute → Opus
validate), gated on user review of each iteration's plan before any code.

| Work stream | Primary skill(s) / MCP | Executor tier | Parallel? | Collision risk |
|---|---|---|---|---|
| Site scaffold | lgtm-jekyll | Sonnet | sequential first (owns `_config.yml`, CSS) | **high** — must land before chapters |
| Chapter authoring | lgtm-tutorial | Sonnet / Opus validate | parallel across `_docs/NN-*.md` once scaffold exists | low per-file; **serialize `_config.yml`/`_parts/`/nav** |
| Monolith code | Spring (manual) + lgtm-quarkus (twin) | Sonnet | sequential (shared reactor `pom.xml`) | **high** — one writer for reactor root |
| Service extraction | quarkus-agent MCP + `migrate-spring-to-quarkus`, lgtm-quarkus | Sonnet | one service at a time (walking skeleton first) | med — isolate per-module dirs; serialize reactor root |
| Camel routes / seams | lgtm-camel + camel-mcp | Sonnet | parallel per route after seam exists | low (per-route files + Citrus tests) |
| Infra (dev loop + CI) | lgtm-podman-stack | Sonnet | parallel with authoring | **high** — do NOT pull lgtm-docker-stack; single compose source |
| k8s substrate | lgtm-minikube-stack | Sonnet | after services exist (r08) | med — `k8s/` overlays, one writer |
| Testing harness | Citrus/Newman (reuse) | Sonnet | parallel per example | low |
| Diagrams | lgtm-diagram-generator | Sonnet | parallel | low; serialize `assets/diagrams/README.md` catalogue |
| Deck | lgtm-presentation | Sonnet | last (r09) | low (own `presentation/`) |
| Repo / release | lgtm-github | Sonnet / Opus validate | **gated** | **gate** — never without explicit user permission |

**Single-writer (serialize) files:** `_config.yml`, `assets/css/site.css`,
`_parts/*`, nav, the Maven reactor `pom.xml`, the shared `domain-model`/
`contracts` modules, `assets/diagrams/README.md`, and the three `_plans/*`
ledgers. **Safe to parallelize:** independent `_docs/NN-*.md`, independent
diagram SVG pairs, per-example dirs, per-concern compose files, and independent
new service modules not touching shared modules. Each relay updates
`build-plan.md` status so the next resumes without re-reading code.

---

## L. Acceptance criteria

### L.1 Round-1 (this relay) — checkable now
- [ ] Plan written to the output path, covering sections A–M.
- [ ] Every ~45 deck patterns appear in §C with a home + runnable/demoted verdict.
- [ ] ADLC decision stated explicitly (Part + thread) with a concrete phase model
      and a per-chapter demonstration mechanism.
- [ ] Monolith design names the six contexts, the deliberate smells, and the
      behavior-equivalence-suite approach.
- [ ] Strangler sequence maps first-seam → full decomposition → chapters.
- [ ] Iteration plan defines r02 as a walking skeleton proving the top risks,
      with clean resume boundaries.
- [ ] podman/minikube boundary stated; no Docker inheritance.
- [ ] `decisions.md` seeded (DRQ-NNN) with fixed + synthesis decisions.
- [ ] Open questions consolidated for the user; nothing built or pushed.

### L.2 Eventual build — acceptance bar
- [ ] Every chapter ≥ 2000 words excluding code/diagrams, progressive, with a
      runnable example and a verification-status footer naming the tests run.
- [ ] The monolith runs; the equivalence gate is green in CI.
- [ ] Every extracted service passes the monolith's Newman collection unchanged
      before its monolith module is decommissioned.
- [ ] Every one of the six extractions (review, notification, inventory,
      payment, shipping, order+gateway) runs the full `migrate-spring-to-quarkus`
      process in complete depth — no service's migration is merely summarized.
- [ ] Every migration chapter carries a real "ADLC in Action" callout with
      traceable Frame/Plan/Generate/Verify/Reconcile evidence.
- [ ] Site CI + Code CI (reactor build + tests + equivalence gate) green;
      security-by-design evidence (SBOM/CVE/secure-by-default) in CI.
- [ ] Reconciliation log shows zero unexplained drift from reused sources.
- [ ] Full system runs on minikube; deck rebuilt and consistent with chapters.
- [ ] Single toolchain honored (podman dev/CI; minikube only for k8s chapters).

---

## M. Risks + detection

| ID | Risk | How it silently goes wrong | Detection / mitigation |
|---|---|---|---|
| **R1** | Scope explosion | 33 ch × 2k × running code balloons; "one more pattern" creep | r02 walking skeleton caps the pattern before scaling; `build-plan.md` is the only backlog; explicit demotion list; scope-discipline memory |
| **R2** | Monolith doesn't demonstrate the patterns | smells too subtle/absent | deliberate-smell table (§D) each tagged to a curing chapter; r02 proves one smell→cure loop first |
| **R3** | ADLC is hand-wavy | described in Part 2, never shown | per-chapter "ADLC in Action" callout is an acceptance gate; r02 demonstrates the full loop once; `_plans/*` is living evidence |
| **R4** | Reuse drift | reused material diverges from source | `reconciliation.md` tracks every artifact→source; verdict column in §B.3; r08 reconciliation pass; retarget-domain checklist |
| **R5** | podman/docker conflict | someone pulls lgtm-docker-stack / DataMesh compose; Dev Services tag mismatch | single podman compose source; boundary in ch.01 + `decisions.md` DRQ; "pin tags once in `.env`"; CI uses the same stack |
| **R6** | Chapters miss 2k / ship without code | prose-only or stub chapters slip | Site CI static validation (word count + example-dir + footer); Code CI equivalence gate |
| **R7** | Pattern-coverage gaps | a deck pattern quietly never lands | §C matrix is the checklist; validate phase cross-checks matrix vs shipped chapters each relay |
| **R8** | Equivalence suite rots | Newman collection drifts as API evolves | collection versioned with the monolith; CI runs it against every service; failure blocks "done" |
| **R9** | Shared-root collisions across parallel relays | two executors edit `pom.xml`/`_config.yml`/compose at once | §K single-writer rule; per-module isolation otherwise |
| **R10** | Premature push / repo creation | agent creates repo or pushes without approval | standing memory gate; repo creation deferred to start of r02 after approval; lgtm-github only on explicit permission |
| **R11** | Tool/version drift (JDK 25, Quarkus, Camel 4.2x, Spring 3.x) | examples break on newer releases | version matrix in `decisions.md`; Dev Services + CI pin versions; Quarkus/Camel MCP for version-matched ground truth |
| **R12** | Pattern-before-motivation | a pattern taught before its pain | extraction sequence dictates pattern order; matrix is a checklist, not an outline; arc review per iteration |

---

*Canonical synthesized plan. Round-1 planning only — no repository, branch, code,
or push until the user approves.*
