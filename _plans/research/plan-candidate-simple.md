---
title: "Plan Candidate — Simplest-Thing-First"
description: "Round-1 planning candidate for modernizing-enterprise-applications, optimized for maximum reuse and the smallest coherent shippable spine."
status: proposal
framing: simplest-thing-first
date: 2026-10-02
---

# Plan Candidate: Modernizing Enterprise Applications (Simplest-Thing-First)

> **Round-1 PLANNING ONLY.** Nothing is built, scaffolded, branched, or pushed
> until the user approves this plan. This document is the artifact under review.
>
> **Framing:** the smallest coherent spine that delivers real professional value,
> maximizing reuse from the four sibling projects (CNDP, EIP-Camel,
> DataMesh-Quarkus, DDD-Obs) and minimizing net-new authoring. Every chapter and
> every pattern must earn its place; lower-value patterns fold into appendices.

---

## A. Approach (and rejected alternatives)

### A.1 Core decisions

**ADLC: a thin spine thread + one anchor chapter, NOT a dedicated part.**
The ADLC (AI Development Lifecycle) is woven through the book as a recurring
"How this chapter was built with agents" sidebar and a **single anchor chapter**
early in Part 1 that defines the ADLC phases once. Rationale: a dedicated ADLC
*part* would be speculative apparatus (violates scope discipline) and would
divorce the agentic story from the actual migration work. The strongest ADLC
proof is the repo's own `_plans/{decisions,build-plan,reconciliation}.md`
artifacts (reuse-map §5) — the book *shows* the ADLC by exhibiting its own
build process, not by theorizing about it. Demotes gold-plating risk.

**Monolith/decomposition: sequencing IS the narrative.** The book's spine is a
linear migration story: build the monolith (Part 1) → find the seams (Part 2) →
strangle service by service (Part 3) → operate the result (Part 4). The pattern
catalog is delivered *in the order the migration needs each pattern*, not as a
reference taxonomy. This is the single most important simplicity lever: the
reader never meets a pattern before the migration forces it. Patterns that the
house migration never needs become appendices (reference), not chapters.

**Reuse-vs-fresh ratio: aggressively reuse (~80/20).** Target ~80% adapted/
reused prose and infra, ~20% net-new connective tissue (the monolith itself,
the strangler sequencing narrative, the ADLC thread). The reuse-map already
catalogs ~35 chapters/appendices with reuse verdicts and a complete domain
model that three sibling projects converged on. Net-new authoring is confined
to: (1) the Spring monolith source, (2) the migration-sequencing narrative, and
(3) the ADLC thread. Everything else is adapt-or-reference.

### A.2 Rejected alternatives

- **Dedicated ADLC part (rejected):** too much speculative infrastructure;
  separates the agentic story from the migration work it should illuminate.
- **Pattern-catalog-as-reference-book (rejected):** the deck is already a
  catalog; re-telling it as a flat catalog adds no value over the deck and
  buries the migration narrative. The reuse-map explicitly says promote
  `appendix-k-monolith-to-microservices` *out of* appendix status to become the
  spine — a catalog structure fights that.
- **Fresh everything to "get it exactly right" (rejected):** violates the
  framing and ignores a domain model that three projects already share. The
  monolith is explicitly designed as the "common ancestor" of the siblings
  (reuse-map §6) so examples can cross-reference "here's the fully-decomposed
  version" instead of re-authoring it.
- **Full k8s/mesh/GitOps from chapter 1 (rejected):** Podman dev-loop is the
  default (decision already made); minikube/Istio/KEDA arrive only in the
  operate-it part where the narrative needs them. Leading with the full
  substrate is gold-plating the infrastructure.

---

## B. Site architecture

House skeleton inherited verbatim from the sibling Jekyll sites (reuse-map §5):
`_docs`, `_parts`, `_plans`, `examples/`, `_layouts`, `assets/css/site.css`.
Accent color: **blue or green** (none of the four siblings used these; keeps the
"modernization" book visually distinct — reuse-map §5 recommendation). Proposal:
`--accent` green (`#3d7a3d`-ish) to connote "migration/growth"; final choice is
an open question for the user.

### B.1 Parts (lean — 5 parts including appendices)

| Order | Part | Blurb |
|---|---|---|
| 0 | Getting Started | Why modernize, the ADLC, the house example, the Podman dev loop. |
| 1 | The Monolith and the Seams | Build the Spring monolith; DDD/event-storming to find bounded contexts and extraction seams. |
| 2 | Strangling the Monolith | The strangler-fig migration, service by service, to Quarkus+Camel, with data/transaction patterns introduced exactly where needed, tested at every step. |
| 3 | Operating the Decomposed System | Deployment, CI/CD, GitOps, observability, resilience, security/supply-chain on Podman then minikube. |
| 4 | Appendices | Deep-dive reference material (mostly reuse-as-is from siblings). |

### B.2 Ordered chapter list (title + synopsis)

Target: **~16 core chapters**, each min 2000 words excl. code/diagrams. Lean by
design — several deck sections collapse into single chapters; low-value patterns
are pushed to appendices.

**Part 0 — Getting Started**
1. **Why Modernize, and When Not To** — Adapts deck pp.3–8 (modernization
   definition, microservices traits, "microservices are not the goal," when
   microservices are a *bad* idea). Sets the risk-reducing, business-reason-first
   tone. *Net-new framing over deck; no code.*
2. **The ADLC: Modernizing with AI Agents** — The anchor chapter. Defines the
   ADLC phases replacing the SDLC, where agents + MCP tools fit, and introduces
   the repo's own `_plans/` artifacts as the worked ADLC example. Adapts
   EIP-Camel `42-appendix-ai-mcp` + DataMesh `ai-rules-service`/`14-ai-rules-triage`
   reframed from runtime-AI to build-time-agentic-migration.
3. **The House Example and the Dev Loop** — Introduces the shipping/e-commerce
   domain (order/inventory/payment/shipping/notification/review), the Podman
   stack (lgtm-podman-stack), and the testing philosophy. Reuses DataMesh domain
   model §6 + Podman infra (EIP-Camel).

**Part 1 — The Monolith and the Seams**
4. **Building the Reference Monolith** — Design and build the Spring Boot
   monolith with deliberate smells (see §D). *Net-new code; the book's "before"
   picture.* First running example + tests.
5. **Finding the Seams: DDD and Event Storming** — Strategic/tactical DDD,
   bounded contexts, event storming to locate extraction boundaries. Adapts CNDP
   `19-appendix-f-ddd-hexagonal` + DDD-Obs `addendum-a-event-storming`.
6. **Coupling, Cohesion, and What *Not* to Split** — Khononov coupling model;
   the anti-pattern of over-decomposition (distributed monolith). Reuses CNDP
   `20-appendix-g-coupling` + deck pp.22–24 (modular monolith as a legitimate
   destination).

**Part 2 — Strangling the Monolith**
7. **The Strangler Fig** — Fowler's pattern and its variants (proxy,
   redirection, shared-DB), content-based routing and decorating collaborator as
   cutover mechanics. The spine chapter: adapts CNDP
   `24-appendix-k-monolith-to-microservices` (promoted to core) + EIP-Camel
   routing chapters `09–11` + deck pp.25–30. Introduces Camel at the seam.
8. **Extracting the First Service (Quarkus + Camel)** — Extract `review-service`
   (REST-only, no sync deps — the easiest seam, per deck "start least complex").
   Quarkus primer folded in (adapts DataMesh `11-quarkus-capability-tour`), the
   anti-corruption layer / translation-at-the-seam (EIP-Camel transformation
   chapters `12–13`), feature-flag-gated cutover (CNDP `27-appendix-n-feature-flags`
   + EIP-Camel `26-appendix-feature-flags`).
9. **Spring vs Quarkus: the Measured Before/After** — Reuses DataMesh
   `12-quarkus-vs-spring-boot` + `spring-boot-compare/` nearly as-is (the new
   book's whole premise). Startup/memory/native comparison on the extracted
   service.
10. **Data at the Seam: Shared Data, CDC, and the Outbox** — When a service
    takes its data with it. Shared-data → transaction-log-tailing → CDC → outbox,
    in migration order. Adapts deck pp.31–38 + EIP-Camel Kafka appendices;
    Apicurio schema registry introduced here (deck pp.56–58).
11. **Distributed Transactions and Sagas** — ACID→ACD, choreographed vs
    orchestrated saga (Camel Saga EIP). Adapts CNDP `17-appendix-d-sagas` + deck
    pp.39–43; reuses DDD-Obs checkout-saga test collections (happy + failure
    paths) as the runnable artifact. Extract the order→payment→shipping
    choreography here.
12. **Failure Is Now a Network Away: Resilience** — In-process calls became
    network calls; timeouts, retry+jitter, circuit breaker, bulkhead. Adapts CNDP
    `26-appendix-m-failure-modes`. Folds in the deck's "chassis" resilience
    concerns (pp.15,67) reframed as platform/MicroProfile-provided.

**Part 3 — Operating the Decomposed System**
13. **Deploying the Migration** — Deployment patterns (rolling, blue-green,
    breaking-schema-change) tied to the strangler cutover; Containerfiles (UBI),
    Podman → minikube. Adapts deck pp.52–55 + EIP-Camel `38-appendix-kubernetes-deploy`
    + DataMesh `k8s/` manifests. lgtm-minikube-stack enters here.
14. **CI/CD and GitOps for the Strangler** — Pipelines for incremental
    migration + deployments, GitOps, progressive delivery, SBOM/supply-chain
    (net-new topic beyond deck). *Mostly net-new (deck lists CI/CD only as
    out-of-scope); scoped tightly to what the migration needs.*
15. **Observability for the Decomposed System** — OpenTelemetry (not Zipkin),
    LGTM stack, distributed tracing, cross-context debugging. Adapts DDD-Obs
    `02–04` + reuses its Grafana saga dashboards as-is + deck pp.60–62 reframed
    on OTel.
16. **Service Mesh, Gateways, and the Finished Architecture** — Istio
    (mesh/mTLS/canary), API gateway, service discovery evolution, and the
    Richardson pattern-map / chassis recap as the closing synthesis. Adapts deck
    pp.59,64,66,67 + DataMesh Istio overlays.

### B.3 Appendix list

| Appendix | Source | Verdict |
|---|---|---|
| A. Citrus end-to-end testing | EIP-Camel `41-appendix-citrus-testing` | reuse as-is |
| B. Three-tier testing strategy | EIP-Camel `37-appendix-testing-strategies` | reuse as-is |
| C. Newman as executable contracts | CNDP `28-appendix-o-newman` | reuse as-is |
| D. Quarkus Dev Services / Testcontainers | EIP-Camel `23-appendix-quarkus-dev` | reuse as-is |
| E. Kafka fundamentals + tuning | EIP-Camel `20,32–36` kafka appendices | reuse as-is |
| F. Camel CLI + TUI | EIP-Camel `39,40` | reuse as-is |
| G. Camel security (secure-by-default) | EIP-Camel `43-appendix-security` | reuse as-is |
| H. Caching patterns | CNDP `25-appendix-l-caching` | reference / light-adapt |
| I. Graceful shutdown + L7 routing | CNDP `21,22` | reference |
| J. Observability economics | DDD-Obs `05-observability-economics` | reference |
| K. EIP case studies (loan broker, bond trading) | EIP-Camel `28,29` | reference (further reading) |

Appendices are deliberately **reuse-as-is or reference** — zero net-new appendix
authoring in iteration 1. This is where lower-value deck patterns land.

---

## C. Pattern-coverage matrix

Every deck pattern mapped to a chapter or appendix, with demotions flagged.

| Deck pattern (category) | Lands in | Treatment |
|---|---|---|
| Lift&Shift / Modernize&Extend / Rip&Rewrite (A) | Ch.1 | Covered as strategy framing; the book *executes* Modernize&Extend→strangler |
| Repurchase/Retire/Retain, Container-Native Virt (A) | Ch.1 | Named, demoted to a paragraph (non-targets) |
| Monolith / Modular Monolith / decomposed-DB monolith (B) | Ch.6 | Core (modular monolith as legit destination) |
| Strangler Fig + 3 variants (B) | Ch.7 | Core spine |
| Content-based Routing (B) | Ch.7 | Core (cutover mechanic, via Camel) |
| Decorating Collaborator (B) | Ch.7 | Core (cutover mechanic) |
| Shared Data (C) | Ch.10 | Core |
| Transaction Log Tailing (C) | Ch.10 | Core |
| Event Sourcing (C) | Ch.10 | **Demoted to a sidebar** — the house migration doesn't need full event sourcing; covered conceptually, not built (avoids gold-plating) |
| CQRS (C) | Ch.10 | Covered; built only if the read-aggregation (graphql-gateway) path needs it, else conceptual |
| CDC (C) | Ch.10 | Core |
| Outbox (C) | Ch.10 | Core (the reliable-publish workhorse) |
| ACID vs ACD (C) | Ch.11 | Core framing |
| Saga choreographed + orchestrated (D) | Ch.11 | Core (both; Camel Saga EIP for orchestrated) |
| Circuit breaker / discovery / health / chassis (E) | Ch.12, Ch.16 | Core, reframed as platform/MicroProfile-provided |
| Fixed / Rolling / Breaking-schema / Blue-green deploy (F) | Ch.13 | Rolling, blue-green, breaking-schema core; **Fixed demoted** to a paragraph (rare) |
| Service/Schema/API registry (G) | Ch.10 | Core (Apicurio at the data seam) |
| Service Mesh (H) | Ch.16 | Core |
| Observability / Kiali (H) | Ch.15 | Core, reframed on OTel+LGTM |
| Distributed Tracing (H) | Ch.15 | Core, OTel |
| Caching (H) | App.H | **Demoted to appendix** (generic; monolith has no caching layer worth migrating) |
| API Gateway + Service Discovery evolution (H) | Ch.16 | Core |
| REST / gRPC / GraphQL (H) | Ch.8, Ch.16 | Covered across extraction + gateway chapters |
| MicroProfile landscape (H) | Ch.12 | Covered, reframed around Quarkus runtime |
| Unit / E2E / microservice / test-data (I) | threaded + App.A–D | Testing woven per-pattern; deep detail in appendices |

### C.1 Demoted patterns and why
- **Event Sourcing** — high build cost, not required by the house migration;
  conceptual sidebar only.
- **Fixed deployment** — niche; one paragraph in Ch.13.
- **Caching** — generic, no migration driver; appendix.
- **EIP case studies, loan broker/bond trading** — not migration-specific;
  reference appendix.

### C.2 NEW topics beyond the deck (the "2026 re-telling" gaps, deck §6)
| New topic | Lands in | Source |
|---|---|---|
| Agentic workflow / ADLC replacing SDLC | Ch.2 (anchor) + thread | net-new + EIP-Camel ai-mcp, DataMesh ai-rules |
| Quarkus runtime (native, dev-mode, Panache) | Ch.8, Ch.9 | DataMesh quarkus-tour + spring-boot-compare |
| Apache Camel + Kamelets at the seam | Ch.7, Ch.10 | EIP-Camel routing/transformation |
| OpenTelemetry + LGTM (replacing Zipkin) | Ch.15 | DDD-Obs |
| GitOps / progressive delivery | Ch.14 | net-new (thin) |
| SBOM / supply-chain / CVE scanning | Ch.14 | net-new (thin) |
| DDD vocabulary (bounded contexts, aggregates) | Ch.5, Ch.6 | CNDP ddd-hexagonal |
| Explicit event storming | Ch.5 | DDD-Obs |

Deliberately **not** chased (deck §6 gaps that don't earn their place in iter 1):
WebAssembly, FinOps/green-computing, serverless/Knative deep-dive, platform-
engineering/Backstage. Named as "further horizons" in the closing chapter; not
built. (Scope discipline.)

---

## D. Reference monolith design

A **Spring Boot** monolith in the shipping/e-commerce domain — the believable
"common ancestor" of the three sibling target architectures (reuse-map §6).
**Deliberately minimal** — match DataMesh's "3–9 files per service" scale; do
not over-build (reuse-map §4 warning).

### D.1 Domain model (one deployable, one DB, one JVM)
Six modules-as-packages, mirroring the eventual bounded contexts:
`order`, `inventory`, `payment`, `shipping`, `notification`, `review`.
Reuse the shared DTO vocabulary: `OrderDto`, `OrderStatus`, `StockDto`,
`ReviewDto`, `NotificationDto`, `OrderCreate` (reuse-map §6).

### D.2 Spring modules & persistence
- Single Spring Boot app, layered (controller → service → repository).
- One PostgreSQL schema, shared across all six packages (the monolith's defining
  property — one DB).
- Spring Data JPA; a single `@Transactional` order-placement flow spanning
  order→inventory→payment (the ACID flow later broken into a saga).

### D.3 API surface
REST endpoints for order placement, inventory query, review CRUD; an internal
in-process call chain order→inventory→payment→shipping→notification (the
synchronous flow that becomes choreography).

### D.4 Seed data
Small deterministic seed (a handful of SKUs, customers, orders) sufficient for
the Newman/Citrus contract tests to assert equivalence pre/post extraction.

### D.5 Deliberate "smells" (the teaching surface)
- Cross-module calls reaching directly into other packages' repositories (hidden
  coupling — the seam-finding target in Ch.5/6).
- One god-service orchestrating the whole order flow in a single transaction.
- Shared DB tables written by multiple modules (the data-ownership problem for
  Ch.10).
- No tests on the riskiest flow initially (motivates "add characterization tests
  before you strangle" in Ch.7).

### D.6 Testing
Characterization tests (REST Assured + Newman contract collection) established in
Ch.4 as the **behavioral ground truth** that every extracted service must
reconcile against (this IS the `reconciliation.md` pattern, reuse-map §5). Reuse
CNDP Newman appendix + DDD-Obs collections retargeted.

---

## E. Decomposition roadmap

Strangler-fig sequence, ordered least-complex-first (deck p.18 guidance). Each
step = one `demos/demo-*.sh` milestone (reuse-map §4) and one chapter beat.

| # | Seam extracted | Why this order | Data/txn handling | Target (Quarkus+Camel) | Chapter |
|---|---|---|---|---|---|
| 1 | `review-service` | REST-only, no sync deps — easiest, proves the mechanism | Takes its own tables; no cross-txn | Quarkus REST (Panache), OIDC | Ch.8 |
| 2 | `inventory-service` | One real consumer (order); low-latency sync | Shared-data → owned DB; gRPC read | Quarkus gRPC | Ch.10 |
| 3 | `payment` + `shipping` | The choreography chain; needs events | Outbox + CDC → Kafka; saga | Quarkus Kafka + Camel Saga EIP | Ch.10, Ch.11 |
| 4 | `notification-service` | Pure event consumer; last, lowest risk | Kafka consumer → WebSocket | Quarkus Reactive Messaging | Ch.11 |
| 5 | `order-service` | The core aggregate; extracted last (hardest) | Becomes the write-path owner + producer | Quarkus REST + Kafka producer + Camel | Ch.11 |

**Seams & mechanics:** Camel proxy in front of the monolith (content-based
routing) routes each resource to monolith-or-service; feature flags gate cutover;
anti-corruption layer (Camel message translator) at each seam. Monolith tables
hand off via CDC/outbox as each service takes ownership. End state:
order→payment→shipping→notification choreography + graphql-gateway read
aggregation — the exact DataMesh "after" picture, so the book can cross-reference
it directly rather than re-author.

**Reversibility** is the design property (CNDP appendix-k): every step is
flag-reversible before decommissioning the monolith slice.

---

## F. ADLC / agentic-workflow design

The ADLC (AI Development Lifecycle) replaces the SDLC phases with
agent-augmented ones. Defined once (Ch.2), demonstrated throughout.

### F.1 Phases (replacing SDLC)
| SDLC phase | ADLC phase | Agents + MCP tools |
|---|---|---|
| Requirements | **Intent + PRD** | Opus planning (lgtm-relay plan phase); PRD.md template |
| Design | **Agentic planning** | `_plans/decisions.md` (DRQ-NNN), `build-plan.md` with skill/MCP mapping |
| Implement | **Agent execution** | Sonnet execute (lgtm-relay); lgtm-quarkus, lgtm-camel, quarkus-agent MCP, camel-mcp |
| Test | **Woven verification** | Citrus/Newman/Dev Services; verification-status footers |
| Review | **Opus validation** | lgtm-relay validate phase; reconciliation.md vs monolith ground truth |
| Deploy | **GitOps** | lgtm-podman-stack / lgtm-minikube-stack, lgtm-github |

### F.2 Where agents + MCP tools fit (concrete)
- **Migration analysis:** camel-mcp `camel_migration_analyze` / `migration_recipes`
  to assess Spring→Camel route conversions.
- **Spring→Quarkus:** quarkus-agent `migrate-spring-to-quarkus` skill (gate-driven)
  on each extracted service.
- **Legacy classification/triage:** reframe DataMesh `ai-rules-service` (Drools +
  LangChain4j) from a *runtime* feature to a *build-time* agent that classifies
  monolith code into subdomains — the seam-finding assistant in Ch.5.
- **Catalog lookups:** camel-mcp catalog tools + quarkus_searchDocs during
  authoring.

### F.3 How each chapter DEMONSTRATES it
Each chapter closes with a short **"Built with the ADLC"** sidebar naming the
plan→execute→validate relay, the skills/MCP used, and the decision IDs recorded —
and the repo's own `_plans/` directory is Exhibit A. The ADLC is proven by the
book's own construction, not asserted. (This also keeps the apparatus from being
gold-plated: the ADLC artifacts are things we build *anyway* to run the project.)

---

## G. Testing strategy per pattern

"Testing throughout" is satisfied by the three-tier pyramid (EIP-Camel
`37-appendix-testing-strategies`, reuse-as-is) applied at every migration step:

| Pattern / step | Test tier | Harness (reused) |
|---|---|---|
| Monolith baseline | Characterization / contract | Newman collection = behavioral ground truth (App.C) |
| Each extraction (Ch.8+) | Contract equivalence | Same Newman collection run against monolith AND extracted service (CNDP newman pattern) |
| Camel routes at seams | Unit (MockEndpoint/AdviceWith) | camel-mcp `camel_route_test_scaffold`, Citrus (App.A) |
| Quarkus services | Integration (Dev Services) | Testcontainers/Dev Services, REST Assured (App.D) |
| Saga (Ch.11) | Happy + failure path | DDD-Obs `checkout-happy-path` + `checkout-failure-paths` collections (reuse-as-is) |
| Data patterns (outbox/CDC) | Integration w/ real Kafka/Postgres | Citrus + Dev Services |
| Deployments (Ch.13) | Smoke / canary verification | Newman against deployed env |

Every chapter carries a **verification-status footer** (reuse-map §4) naming
exactly which tests/demos were run — the strongest professional-grade signal and
directly the "testing throughout" requirement.

---

## H. CI/CD design

Scoped to what the migration needs (Ch.14) — not a platform-engineering treatise.

- **Pipeline for the strangler migration:** per-service build → test (3-tier) →
  contract-equivalence gate (Newman vs monolith) → container build (UBI) → deploy.
  The contract gate is the migration-specific twist: a service can't cut over
  until it passes the monolith's characterization suite.
- **Deployment pipelines:** rolling + blue-green (Ch.13 patterns) wired to the
  cutover; feature-flag flip as the release step (decoupled from deploy).
- **GitOps:** declarative manifests (DataMesh `k8s/` kustomize overlays) as the
  source of truth; progressive delivery via Istio canary (Ch.16).
- **Supply chain:** SBOM generation + CVE scan as a pipeline stage (net-new,
  thin — aligns with the user's security-by-design standing constraint).
- **Podman/minikube fit:** dev-loop builds on Podman; CI builds images; minikube
  is the deploy target for the operate-it chapters. GitHub Actions via lgtm-github.
- **Chapter coverage:** Ch.14 primary; deployment mechanics in Ch.13; GitOps/
  canary in Ch.16.

---

## I. Presentation plan + deck outline

Rebuild the original 75-slide deck with lgtm-presentation (Red Hat house style,
pptxgenjs), modernized per deck §6 and realigned to the book's narrative. Target
a **tighter ~40–50 slide** deck (cut dated Microservices-1.0/Netflix-OSS depth to
a single "how we got here" slide; add ADLC, Quarkus, Camel, OTel, GitOps).

Deck outline (maps 1:1 to book parts):
1. Title + "modernization is an engineering discipline, not a goal" (deck pp.3–8).
2. The ADLC: modernizing with agents (NEW).
3. The house example + Podman dev loop.
4. Strategies (Lift&Shift/Modernize&Extend/Rip&Rewrite) — condensed from pp.9–20.
5. Architecture evolution — one slide (monolith→SOA→MS1.0→cloud-native), dated
   stack compressed.
6. Finding seams: DDD + event storming (NEW emphasis).
7. Strangler fig + variants + Camel routing (pp.25–30).
8. Data patterns: shared→CDC→outbox→registry (pp.31–38).
9. Sagas: ACID→ACD, choreographed vs orchestrated (pp.39–43).
10. Resilience / chassis, reframed on platform+MicroProfile (pp.15,67).
11. Deploy patterns + CI/CD + GitOps + SBOM (pp.52–55 + NEW).
12. Observability on OTel+LGTM, mesh, gateway (pp.59–64, reframed).
13. Synthesis: Richardson pattern map + chassis + "further horizons" (pp.66–68).

Companion .docx (lgtm-presentation) optional, deferred past iteration 1.

---

## J. Iteration / release plan

Round-1 = **planning only** (this document + PRD + decisions seed). Each later
iteration is independently shippable (`_rNN.x` tarball via lgtm-github).

| Iter | Scope | Shippable outcome |
|---|---|---|
| **r01** | **PLANNING ONLY** (this plan approved → PRD.md, decisions.md seed, CLAUDE.md skeleton, repo created + branch). No code. | Reviewed plan + project skeleton |
| r02 | **The spine:** site scaffold (lgtm-jekyll/tutorial), monolith (Ch.4), ADLC anchor (Ch.2), house example (Ch.3), Podman stack, baseline tests (App.C). | A runnable monolith + a site that explains it — genuinely useful alone |
| r03 | **Find the seams:** Ch.1, Ch.5, Ch.6 + DDD/event-storming reuse. | The "why + where" half of the book |
| r04 | **First strangle:** Ch.7, Ch.8, Ch.9 (review-service extracted; Spring-vs-Quarkus comparison). First Quarkus+Camel service running. | Proof the migration mechanism works end to end |
| r05 | **Data + sagas:** Ch.10, Ch.11, Ch.12 (remaining extractions, outbox/CDC, saga, resilience). | Full decomposition complete |
| r06 | **Operate it:** Ch.13–16 (deploy, CI/CD, observability, mesh) + minikube substrate. | Production-shaped end state |
| r07 | **Deck + appendices polish** (lgtm-presentation deck; appendices A–K reuse-finalized). | Talk-ready deck + reference depth |

**Resumable boundaries:** each iteration ends with all chapters carrying
verification footers and a green CI; `build-plan.md` step-status table makes
resumption unambiguous. Depth accrues r02→r07; r02 alone is a coherent
"here's the monolith and how we'll approach it" deliverable.

---

## K. Execution mapping

Run via **lgtm-relay** (Opus plan → Sonnet execute → Opus validate), gated on
user review of the plan before any code (user requirement #1).

| Stream | Skill | Subagent tier | Parallel? | Collision risk |
|---|---|---|---|---|
| Site scaffold | lgtm-jekyll | Sonnet exec | Sequential first (owns `_config.yml`, CSS) | High if parallel — must land before chapters |
| Chapter authoring | lgtm-tutorial | Sonnet exec, Opus validate | Parallel *across chapters* once scaffold exists | Low (per-file `_docs/NN-*.md`); watch `_parts/` + nav |
| Monolith code | lgtm-quarkus (Spring side hand-built) | Sonnet exec | Sequential (foundation) | Owns `examples/` reactor root |
| Service extraction | lgtm-quarkus + lgtm-camel | Sonnet exec | Sequential per migration step (each depends on prior) | Shared `examples/` reactor pom |
| Infra (dev) | lgtm-podman-stack | Sonnet exec | Parallel-safe | Own `_infra/`, compose files |
| Infra (k8s) | lgtm-minikube-stack | Sonnet exec | Parallel-safe, late | Own `k8s/`, scripts |
| Diagrams | lgtm-diagram-generator | Sonnet exec | Parallel-safe | Own `assets/diagrams/` |
| Deck | lgtm-presentation | Sonnet exec | Parallel-safe, late | Own `presentation/` |
| Git / GitHub | lgtm-github | — | Sequential (release boundaries) | Owns remote/tags |
| Tests | Citrus/Newman/Dev Services | Sonnet exec | Parallel per example | Per-example `test/` |

**File-collision discipline:** `_config.yml`, `_parts/*`, nav, and the
`examples/pom.xml` reactor root are **single-writer** — serialize edits to these.
Everything else (`_docs/NN-*.md`, per-example dirs, per-concern compose files) is
naturally sharded by number/name and parallel-safe.

---

## L. Acceptance criteria

### L.1 Round-1 planning (this iteration)
- [ ] Plan covers sections A–M and is approved by the user.
- [ ] PRD.md authored from the 13-section DataMesh template (reuse-map §5).
- [ ] `_plans/decisions.md` seeded with the standing decisions (Podman default,
      fresh Spring monolith, accent color, ADLC-as-thread, reuse ratio) as
      DRQ-NNN entries.
- [ ] `CLAUDE.md` skeleton with skill/MCP mapping table.
- [ ] Repo created at github.com/patterncatalyst/modernizing-enterprise-applications
      + working branch (ONLY after user approval — never push without permission).
- [ ] **No chapters, no code, no scaffolding built.**

### L.2 Eventual build (per chapter / per iteration)
- [ ] Every chapter ≥ 2000 words excluding code/diagrams.
- [ ] Every chapter has a paired SVG+Excalidraw diagram where a figure is cited.
- [ ] Every chapter carries a verification-status footer naming tests/demos run.
- [ ] Every migration step has a runnable example + a `demo-*.sh` + passing tests.
- [ ] Contract-equivalence gate green for each extracted service vs monolith.
- [ ] Every deck pattern accounted for (built, demoted-with-reason, or appendix).
- [ ] Each iteration ships a green CI and an `_rNN.x` tarball.
- [ ] Security-by-design: SBOM + CVE scan in CI; Camel secure-by-default posture.

---

## M. Risks + detection

| Risk | Likelihood | Detection | Mitigation |
|---|---|---|---|
| Scope creep from chasing all deck §6 gaps (Wasm, FinOps, serverless) | High | Chapter count > ~16; new topics without a migration driver | Hard "further horizons" boundary (C.2); scope-discipline review per iteration |
| Monolith over-built (gold-plating the "before") | Med | >9 files/module; features no extraction exercises | Match DataMesh scale (§D); build only smells the narrative uses |
| ADLC apparatus over-engineered | Med | ADLC becomes its own part/toolkit vs a thread | Keep it as sidebar + the repo's own `_plans/` (§F); no bespoke tooling |
| Docker/Podman mixing (reuse-map warning) | Med | compose files reference docker; Dev Services tag mismatch | Podman-only decision in decisions.md; pin image tags once in `.env` (reuse-map §3) |
| Reuse drift — adapted chapters still read like the source book | Med | Shipping-generic examples not retargeted to the migration | Retarget-domain checklist; reconciliation.md tracks alignment |
| Example reactor pom collisions in parallel authoring | Med | Merge conflicts on `examples/pom.xml` | Single-writer rule (§K); serialize reactor-root edits |
| Contract-equivalence gate too strict/flaky | Low-Med | CI red on environment flakiness not behavior | Pin seed data (§D.4); CDC tests via Dev Services not shared env |
| Pushing upstream prematurely | Low | Any commit/MR before approval | Standing constraint: never push without permission; r01 creates nothing remote until approved |
| Cross-sibling inconsistency | Low | Extracted services diverge from DataMesh "after" shape | Deliberately target the exact DataMesh domain shape (§6) for cross-reference |

---

## Open questions for the user
1. **Accent color** — propose green (migration/growth) to stay distinct from the
   three siblings (amber/red/teal). Confirm or pick another.
2. **ADLC scope** — confirm ADLC as a thread + single anchor chapter (this
   plan's recommendation) vs a dedicated part.
3. **Podman-only confirmation** — decisions.md will record Podman dev-loop +
   minikube substrate, explicitly *not* inheriting DataMesh's Docker choice.
   Confirm.
4. **Companion .docx** — deferred past iteration 1; acceptable?
