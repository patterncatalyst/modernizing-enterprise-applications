---
title: "Plan Candidate — Risk-First"
description: "A risk-first round-1 plan for the Modernizing Enterprise Applications tutorial site + reference code + deck. Validate the hardest assumptions earliest via a thin walking skeleton."
status: candidate
framing: risk-first
---

# Modernizing Enterprise Applications — Round-1 Plan (Risk-First Candidate)

> **This is PLANNING ONLY.** Nothing in this document builds, scaffolds, or
> pushes anything. Round-1 (this relay) produces planning artifacts only.
> The build does not begin until the user approves a plan.

The organizing bet of this plan: a large multi-iteration book+code+deck build
dies from a small number of predictable failures — scope explosion, a
monolith that doesn't actually *demonstrate* the patterns, an ADLC that is
described but never shown, reuse that silently drifts from its sources, a
podman/docker toolchain conflict, chapters that miss the 2k-word bar or ship
without runnable code, and pattern-coverage gaps. Every trade-off below is
shaped to surface and retire those risks **as early as possible**. The
sequencing rule is: *prove the hard parts once, end-to-end, before scaling
anything.*

---

## A. Approach (and what was rejected)

### Decisions required by the brief

1. **Is ADLC a cross-cutting thread, a dedicated Part, or both? → BOTH.**
   A dedicated Part ("The AI Development Lifecycle") defines the ADLC
   concretely (phases, agent roles, MCP tool fit, SDLC replacement mapping)
   AND *every* decomposition chapter carries a short, mandatory **"Built with
   the ADLC"** panel showing the actual plan→execute→validate trace, prompts,
   MCP calls, and gates used to produce *that chapter's* code. Rationale: the
   single biggest credibility risk is an ADLC that is only described. A
   dedicated Part alone invites hand-waving; a thread alone has no home for
   the full model. Both forces demonstration at every step while giving the
   model one place to live.

2. **How does the monolith + decomposition sequence against the narrative?**
   Strict chronological order that mirrors the real migration:
   *(why) → (how we work = ADLC) → (build the "before" monolith) → (find the
   seams) → (strangle slice by slice) → (data/transactions) → (operate) →
   (observe) → (ship via CI/CD).* The reader builds the monolith first, then
   watches it get strangled one seam at a time, each seam a chapter that
   introduces 1–2 patterns with running code + tests. The narrative *is* the
   migration timeline.

3. **Reuse vs fresh ratio.** Target ~40% adapt-reuse / ~60% fresh-authored.
   Reuse heavily for *theory and appendices* (CNDP's DDD/sagas/coupling/
   failure-modes/caching/feature-flags; EIP-Camel's routing/transformation/
   testing/Camel-CLI/Kafka/security appendices; DDD-Obs's saga test harness;
   DataMesh's Quarkus primer + Spring-vs-Quarkus twin). Author fresh the
   *spine*: the reference monolith, the strangler-fig decomposition
   narrative, the ADLC Part and its per-chapter demonstrations, and the
   CI/CD-for-migration material. Reuse is tracked in a reconciliation log to
   prevent drift (see risk R4).

### Rejected alternatives (one line each)

- **Pattern-catalog structure (mirror the deck's section order).** Rejected:
  describes patterns, doesn't *demonstrate migration*; the deck itself is the
  thing being superseded.
- **ADLC as a cross-cutting thread only.** Rejected: too easy to become
  hand-wavy; no home for the full lifecycle model.
- **ADLC as a dedicated Part only.** Rejected: readers would never see it
  applied to real code; demonstration must recur.
- **Decompose an existing/borrowed monolith.** Rejected: no clean monolith
  exists; a fresh monolith lets us plant *deliberate* smells that each
  pattern then cures — tightening the pattern↔code link.
- **Docker/compose inherited from DataMesh.** Rejected: user decided Podman
  is the deliberate default (`lgtm-podman-stack`); minikube only for
  k8s-specific chapters (`lgtm-minikube-stack`). See risk R5.
- **One mega-reactor built up front.** Rejected: invites scope explosion;
  instead grow the reactor one extracted service at a time behind a walking
  skeleton.
- **Big-bang authoring (write all chapters, then code).** Rejected: defers
  the riskiest proof (does the code actually demonstrate the pattern?) to the
  end. We author ONE chapter end-to-end first.

---

## B. Site architecture

Jekyll/GitHub Pages in the house skeleton (`_docs`, `_parts`, `_plans`,
`examples/`, `_layouts`, `assets/css/site.css`) per `lgtm-jekyll` +
`lgtm-tutorial`. Config cloned from the reuse-map §5 pattern. **Accent: a
blue or green** (distinct from amber CNDP/EIP, red DataMesh, teal DDD-Obs);
proposal: a "migration" green (`--accent: #3d7a4e` or similar) — final choice
is an open question for the user. `brand_emoji` proposal: "🏗️".

### `_parts` (ordered)

| # | Part | Blurb |
|---|---|---|
| 0 | Setting Up | Toolchain (SDKMAN/JDK 25/Maven/Quarkus CLI/Camel CLI), the podman observability stack, the repo, and how to run every example. |
| 1 | Why Modernize | Modernization as disciplined engineering; strategies; when microservices are a bad idea; assessment rubric. |
| 2 | The AI Development Lifecycle (ADLC) | What replaces the SDLC: phases, agent roles, MCP tools, gates, and the planning artifacts that *are* the ADLC. |
| 3 | The Reference Monolith | Build the Spring Boot monolith "before" picture — domain, modules, persistence, API, seed data, deliberate smells, and its test suite. |
| 4 | Finding the Seams | Strategic + tactical DDD, event storming, coupling theory, and the decomposition-pattern family — how you decide *where* to cut. |
| 5 | The Strangler Fig in Practice | The core: strangle the monolith seam by seam into Quarkus+Camel services, one pattern per chapter, code + tests throughout. |
| 6 | Data & Transactions Across the Seam | Shared data → outbox → CDC → event sourcing → CQRS → saga (choreographed + orchestrated); ACID→ACD. |
| 7 | Running Microservices | The chassis, resilience/failure modes, communication styles, service mesh, registries, deployment patterns. |
| 8 | Observability | OpenTelemetry end-to-end, distributed tracing, Kiali, observability economics. |
| 9 | Delivering the Migration (CI/CD & GitOps) | Pipelines for the strangler cutover, progressive delivery, GitOps, supply-chain/SBOM, podman→minikube. |
| 10 | Conclusion | Richardson pattern-language map revisited, the chassis in 2026, what's next. |
| A+ | Appendices | Deep-dive reference material (mostly reuse/adapt). |

### Ordered chapter list (title + synopsis)

**Part 0 — Setting Up**
- **00 Introduction & How to Use This Book** — the thesis (modernize *with* an
  agentic ADLC), the running shipping domain, the before/after, reading paths.
- **01 The Toolchain** — SDKMAN, JDK 25, Maven, Quarkus CLI, Camel CLI/TUI,
  the `lgtm-podman-stack` observability stack; verify-your-setup gate.

**Part 1 — Why Modernize**
- **02 Modernization as Engineering, Not Fashion** — "microservices are not
  the goal"; goals, the 10 traits, when microservices are a *bad* idea.
- **03 Strategies & Assessment** — Lift&Shift / Modernize&Extend /
  Rip&Rewrite (+ Repurchase/Retire/Retain, Container-Native Virtualization);
  the 2x2 (strategic value × change frequency) and the ease-of-migration
  rubric; architecture-evolution arc (Monolith→SOA→MS 1.0→Cloud-Native 2.0)
  reframed for 2026.

**Part 2 — The ADLC**
- **04 From SDLC to ADLC** — concrete lifecycle: phases, which SDLC steps
  agents replace/augment, the plan→execute→validate relay, human gates.
- **05 Agents, Skills & MCP Tools in the Loop** — agent roles, the MCP tool
  surface (Quarkus Agent, Camel MCP, repo/infra skills), guardrails,
  verification; the planning artifacts (`decisions.md` / `build-plan.md` /
  `reconciliation.md`) as the ADLC's living record.

**Part 3 — The Reference Monolith**
- **06 Designing the Monolith** — the six bounded contexts as one Spring Boot
  deployable; domain model, persistence, REST API surface, seed data.
- **07 The Deliberate Smells** — the coupling/data/transaction smells planted
  on purpose, each tagged to the pattern that will later cure it.
- **08 Testing the Monolith** — the baseline test suite (unit + API/Newman)
  that becomes the *behavior-equivalence suite* for every extracted service.

**Part 4 — Finding the Seams**
- **09 Strategic & Tactical DDD** — subdomains, bounded contexts, aggregates,
  domain events; mapping monolith modules to candidate service boundaries.
- **10 Event Storming the Monolith** — discovering seams from behavior, not
  schema; produces the decomposition backlog.
- **11 Coupling, Cohesion & When *Not* to Split** — Khononov's three
  dimensions; anti-patterns of over-decomposition; the distributed monolith.
- **12 The Decomposition Pattern Family** — strangler fig (identify/move/
  redirect) + proxy/redirection/shared-DB variants, content-based routing,
  decorating collaborator; reversibility as a design property.

**Part 5 — The Strangler Fig in Practice** *(the core; each chapter = one
seam extracted to Quarkus+Camel, with code + tests + an ADLC panel)*
- **13 The First Seam: Extracting Inventory** — stand up the proxy/seam,
  extract `inventory-service` (gRPC-first) to Quarkus, route through the
  seam, prove behavioral equivalence against the monolith. *(Walking-skeleton
  slice — authored first; see §J.)*
- **14 Extracting Order** — the template data product; REST + Camel route at
  the seam; anti-corruption layer / translation at the boundary.
- **15 Extracting Payment, Shipping, Notification** — the event-driven
  choreography steps; introduce the Kafka backbone and Camel routes.
- **16 Extracting Review & Cutting Over** — the independent REST service;
  feature-flag-driven cutover; decommissioning strangled monolith code.

**Part 6 — Data & Transactions Across the Seam**
- **17 Shared Data to Owned Data** — from one DB to a DB per service; the
  shared-data pattern and its limits.
- **18 The Outbox Pattern** — reliable event publication at the seam.
- **19 Change Data Capture** — Debezium-style CDC to feed read models and aid
  the strangle.
- **20 Event Sourcing & CQRS** — read/write split, projections, trade-offs;
  ACID→ACD, eventual consistency.
- **21 Sagas: Choreographed & Orchestrated** — compensation, the checkout
  saga with explicit failure paths (reuse DDD-Obs payloads); Camel Saga EIP.

**Part 7 — Running Microservices**
- **22 The Microservices Chassis in 2026** — circuit breaker, discovery,
  config, health, metrics, logging, security via Quarkus/MicroProfile +
  platform (not Netflix OSS).
- **23 Failure Modes & Resilience** — partial failure taxonomy; timeouts,
  retry+jitter, bulkhead, circuit breaker, load shedding — what you now must
  defend against post-extraction.
- **24 Communication Styles** — REST / gRPC / GraphQL; the GraphQL read
  gateway over the mesh.
- **25 Service Mesh, Registries & Schemas** — Istio (mesh), Apicurio (schema/
  API registry); mTLS, traffic routing, schema governance.
- **26 Deployment Patterns** — fixed, rolling, breaking-schema-change,
  blue-green — mapped to the strangler cutover.

**Part 8 — Observability**
- **27 OpenTelemetry End-to-End** — instrumenting the services; the OTel
  Collector; the LGTM stack via podman.
- **28 Distributed Tracing & Service Graphs** — spans/causality/context
  propagation; Kiali; debugging across bounded contexts.

**Part 9 — Delivering the Migration**
- **29 CI/CD for the Strangler** — pipelines that build/test/ship the
  monolith *and* the growing service set; equivalence gates; the cutover
  pipeline.
- **30 GitOps, Progressive Delivery & Supply Chain** — Argo-style GitOps,
  canary/flag rollouts, SBOM + dependency/CVE scanning + policy-as-code;
  podman dev-loop → minikube substrate.

**Part 10 — Conclusion**
- **31 The Pattern Language, Revisited** — Richardson's map re-walked with the
  completed migration; the chassis in 2026; what's next (serverless, Wasm,
  platform engineering, FinOps) — honestly scoped as further reading.

### Appendix list (reuse verdicts)

| Appendix | Source | Verdict |
|---|---|---|
| DDD + Hexagonal | CNDP `19-appendix-f` | adapt → promoted into ch.09 core; keep a condensed appendix |
| Sagas (state/compensation) | CNDP `17-appendix-d` | adapt (pairs with ch.21) |
| Coupling model | CNDP `20-appendix-g` | reuse-as-is (feeds ch.11) |
| Failure modes | CNDP `26-appendix-m` | adapt (feeds ch.23) |
| Caching | CNDP `25-appendix-l` | reference / light-adapt |
| Feature flags | CNDP `27-appendix-n` + EIP `26` | adapt (feeds cutover) |
| Newman as contract gate | CNDP `28-appendix-o` | reuse-as-is |
| Graceful shutdown; L7 routing | CNDP `21/22` | reference |
| Camel routing & transformation | EIP `09–13` | reuse-as-is (retarget domain) |
| Citrus testing | EIP `41` | reuse-as-is (load-bearing) |
| Testing strategies (3-tier) | EIP `37` | reuse-as-is (feeds ch.08/G) |
| Camel CLI / TUI | EIP `39/40` | reuse-as-is |
| AI/MCP (LangChain4j, MCP) | EIP `42` | adapt → ADLC source material |
| Camel security-by-default | EIP `43` | reuse-as-is |
| Kafka fundamentals + tuning | EIP `20,32–36` | reuse-as-is |
| Kubernetes deploy | EIP `38` | adapt (feeds ch.30) |
| Worked EIP case studies | EIP `28/29` | reference |
| Quarkus capability tour | DataMesh `11` | reuse-as-is (feeds ch.22/primer) |
| Quarkus vs Spring Boot twin | DataMesh `12` + `spring-boot-compare/` | reuse-as-is (central) |
| AI rules/triage | DataMesh `14` | adapt (ADLC + saga triage) |
| Observability economics | DDD-Obs `05` | reference |
| Event storming | DDD-Obs addendum-a | adapt (feeds ch.10) |
| **New: ADLC playbook** | — | new (the process-as-artifact appendix) |
| **New: Glossary / pattern index** | — | new |

---

## C. Pattern-coverage matrix

Every deck pattern → home chapter/appendix. "Demoted" = covered but not given
a full runnable example (reason given). New 2026 topics listed after.

| Deck pattern (category) | Home | Runnable? | Note |
|---|---|---|---|
| Lift & Shift / Modernize & Extend / Rip & Rewrite | ch.03 | conceptual | strategy framing |
| Repurchase/Retire/Retain; Container-Native Virtualization | ch.03 | no (demoted) | non-targets; mention only |
| Monolith / Modular Monolith / Modular Monolith w/ decomposed DBs | ch.06, ch.11 | yes (monolith is built) | the "before" |
| Strangler Fig (+ proxy / redirection / shared-DB variants) | ch.12–16 | yes | the spine |
| Content-based routing | ch.12, ch.15 | yes (Camel) | routing at the seam |
| Decorating collaborator | ch.12 | yes | proxy-side decoration |
| Shared data | ch.17 | yes | and its limits |
| Transaction log tailing | ch.19 | yes (CDC) | folded into CDC |
| Event sourcing | ch.20 | yes | |
| CQRS (+ trade-offs) | ch.20 | yes | |
| Simple CDC | ch.19 | yes (Debezium) | |
| Outbox | ch.18 | yes | |
| Distributed transactions ACID→ACD | ch.20/21 | conceptual | framing for sagas |
| Saga — choreographed | ch.21 | yes | + failure paths |
| Saga — orchestrated | ch.21 | yes (Camel Saga EIP) | |
| Circuit breaker / discovery / health (chassis) | ch.22/23 | yes (SmallRye/MP) | reframed off Netflix OSS |
| Microservices chassis | ch.22 | yes | Quarkus+platform |
| Fixed / rolling / breaking-schema / blue-green deploys | ch.26 | yes (manifests) | |
| Service registry (schema/API/shared types + requirements) | ch.25 | yes (Apicurio) | |
| Schema management | ch.25 | yes | |
| Service mesh | ch.25 | yes (Istio, minikube) | |
| Observability (Kiali) | ch.28 | yes | |
| Distributed tracing | ch.28 | yes (OTel) | reframed off Zipkin |
| Caching | App. / ch.17 note | demoted to appendix | generic; only if monolith has a cache layer |
| API gateway + service discovery evolution | ch.24/25 | yes | |
| REST / gRPC / GraphQL | ch.24 | yes | |
| MicroProfile landscape | ch.22 | yes (via Quarkus) | |
| Unit / E2E / microservice / testing-data | ch.08 + G + appendices | yes | testing woven throughout |
| Event-driven topologies / reduction functions (as test concerns) | ch.08 / ch.15 | yes | |

**Demoted patterns & why:** Container-Native Virtualization, Repurchase/
Retire/Retain (non-migration targets — mentioned, not demonstrated); Caching
(generic; appendix unless the monolith carries a cache worth migrating);
Transaction Log Tailing (subsumed by the CDC chapter to avoid redundancy).

**NEW topics added beyond the deck:**
- **Agentic ADLC** (Part 2 + per-chapter panels) — the deck's "There's More!"
  AI/ML gap, made central.
- **OpenTelemetry + LGTM stack** (ch.27–28) — replaces Zipkin/standalone.
- **GitOps + progressive delivery** (ch.30) — the deck's CI/CD gap.
- **Supply chain / SBOM / CVE scanning / policy-as-code** (ch.30) — new
  security surface.
- **Explicit DDD vocabulary + event storming** (ch.09–10) — implicit in deck.
- **Quarkus specifics** (dev mode, Dev Services, native image, Panache) — deck
  named MicroProfile but no runtime.
- **Apache Camel / EIP specifics** (routing, Saga EIP, Kamelets) — deck named
  no integration runtime.
- **Spring→Quarkus measured comparison** (twin service) — the book's premise.

---

## D. Reference monolith design

A **fresh Spring Boot 3.x monolith** (JDK 25), one deployable, one database,
one JVM — the believable common ancestor of the three sibling target
architectures (reuse-map §6).

- **Domain model (six bounded contexts as packages/modules):** `order`,
  `inventory`, `payment`, `shipping`, `notification`, `review`. Reuse the
  shared DTO shapes (`OrderDto`, `OrderStatus`, `StockDto`, `ReviewDto`,
  `NotificationDto`, `OrderCreate`) so extracted services line up 1:1 with
  DataMesh/DDD-Obs/EIP-Camel.
- **Spring modules:** Spring MVC controllers, Spring Data JPA repositories,
  `@Service` domain services, a single shared `@Entity` model. Deliberately
  *not* hexagonal at first (a smell, see ch.07).
- **Persistence:** one PostgreSQL schema, all six contexts sharing tables,
  with cross-context foreign keys and cross-context JPA joins (the data-
  coupling smell). Flyway migrations for seed/version control.
- **API surface:** REST for all contexts (`/orders`, `/inventory`,
  `/payments`, `/shipments`, `/notifications`, `/reviews`); an order-placement
  flow that touches inventory → payment → shipping → notification
  *in-process, in one ACID transaction* (the distributed-transaction smell to
  be cured by saga).
- **Seed data:** deterministic fixtures (customers, SKUs, stock levels,
  sample orders) so the equivalence suite is reproducible.
- **Deliberate "smells" (each tagged to its curing pattern):**
  1. Shared database / cross-context joins → owned data + CDC (ch.17/19).
  2. One big in-process ACID transaction across contexts → saga (ch.21).
  3. Synchronous in-process calls everywhere → async events + resilience
     (ch.15/23).
  4. No anti-corruption boundary; leaky domain model → ACL at the seam
     (ch.14).
  5. Protocol concerns in the domain (controllers calling repos directly) →
     hexagonal at extraction (ch.09/14).
  6. No outbox; events (if any) published non-transactionally → outbox
     (ch.18).
- **How it's tested (becomes the behavior-equivalence suite):** JUnit unit tests per
  service; `@SpringBootTest` slice/integration tests; a **Newman collection**
  exercising the full order flow (happy path + failure paths) that will later
  be run *unchanged* against each extracted service to prove behavioral
  equivalence (reuse CNDP `appendix-o` + DDD-Obs payload library).

---

## E. Decomposition roadmap (strangler-fig sequence → chapters)

The target per service follows reuse-map §6 (Quarkus runtime; Camel at the
integration seams).

| Step | Seam / service | Target | Data/txn handling | Chapter |
|---|---|---|---|---|
| 0 | Insert the seam (proxy in front of monolith) | — | n/a | ch.12/13 |
| 1 | **Inventory** (first seam) | Quarkus, gRPC-first | reads still hit shared DB via ACL; proves the seam mechanics | ch.13 |
| 2 | **Order** (template product) | Quarkus, REST + Camel route | owns order tables; outbox for `order.placed` | ch.14, ch.18 |
| 3 | **Payment / Shipping / Notification** | Quarkus, Kafka consumers/producers | choreography via Kafka; CDC where the monolith still owns data | ch.15, ch.19 |
| 4 | **Review** (independent) | Quarkus, REST, OIDC | owned DB, no sync deps | ch.16 |
| 5 | Convert the order flow to a saga | — | choreographed + orchestrated compensation | ch.21 |
| 6 | Split remaining shared data; retire monolith code | — | DB-per-service complete; event sourcing/CQRS for read models | ch.17, ch.20 |
| 7 | Cut over + decommission | — | feature-flag traffic shift; remove strangled paths | ch.16, ch.26 |

**First seam = Inventory** deliberately: it is the smallest, has one real
consumer, and exercises the full seam machinery (proxy, ACL, equivalence
test, ADLC trace) with the least domain surface — the ideal walking-skeleton
slice.

---

## F. ADLC / agentic-workflow design

**The ADLC (AI Development Lifecycle)** reframes the SDLC around an agent
relay with human gates. Concretely:

| ADLC phase | What happens | Agents / tools | Replaces SDLC step |
|---|---|---|---|
| **Frame** | Capture intent, constraints, acceptance criteria into a PRD + `decisions.md` entry | human + planning agent (Opus) | requirements / design doc |
| **Plan** | Decompose into a `build-plan.md` step table with skill/MCP mapping; gate on human review | `lgtm-relay` plan phase (Opus) | design + task breakdown |
| **Execute** | Generate code/chapters via skills, using MCP tools for ground truth | `lgtm-relay` execute (Sonnet) + Quarkus Agent MCP, Camel MCP, lgtm-* skills | implementation |
| **Validate** | Run tests/demos, check the equivalence gate, reconcile drift; gate | `lgtm-relay` validate (Opus) + Newman/Citrus/Dev Services | code review + QA |
| **Record** | Append decisions, update build-plan status, reconcile | all phases write `_plans/*` | change log / traceability |

- **Where agents + MCP fit:** the Quarkus Agent MCP (`quarkus_create`,
  `quarkus_skills`, `quarkus_searchDocs`, `migrate-spring-to-quarkus` skill)
  drives Spring→Quarkus conversion; the Camel MCP (catalog, validate,
  route-scaffold, migration-analyze) drives route authoring and EIP checks;
  lgtm-* skills drive site/deck/infra/diagrams.
- **How SDLC is *replaced*:** requirements→PRD+decisions; design→plan phase +
  DDD/event-storming chapters; implementation→execute phase; testing→the
  validate gate + equivalence gate; maintenance→reconciliation.md drift
  tracking. Human gates sit at Plan-approval and Validate-sign-off.
- **How each chapter DEMONSTRATES it (not describes):** every Part-5/6
  chapter carries a **"Built with the ADLC"** panel containing (a) the actual
  plan step, (b) the prompts/skills/MCP calls used to produce that chapter's
  code, (c) the validation evidence (tests run, equivalence result), and (d)
  the decision-log IDs touched. The planning artifacts in `_plans/`
  (`decisions.md` / `build-plan.md` / `reconciliation.md`) are themselves the
  ADLC's living record — the book literally ships the process that built it.
  The walking skeleton (§J, r02) demonstrates the full ADLC loop *once*,
  end-to-end, before the pattern is scaled.

---

## G. Testing strategy per pattern

Three-tier pyramid (reuse EIP `37` + `41`, CNDP `28`), applied per pattern:

| Pattern area | Unit | Contract | Integration | E2E |
|---|---|---|---|---|
| Monolith baseline | JUnit per service | — | `@SpringBootTest` | Newman full flow (the **behavior-equivalence suite**) |
| Extracted service (each) | JUnit + Camel `MockEndpoint`/`AdviceWith` | Newman collection run *unchanged* vs monolith | Quarkus Dev Services (Kafka/PG/Apicurio) + REST Assured; Citrus `.citrus.it.yaml` per route | Newman against the whole mesh |
| Routing / transformation (Camel) | MockEndpoint | — | Citrus against real Kafka/HTTP/DB via Testcontainers | — |
| Outbox / CDC | unit on publisher | schema contract (Apicurio) | Dev Services + Debezium IT | event-arrival assertions |
| Saga (choreo + orchestrated) | compensation unit tests | — | Citrus multi-step | Newman happy + failure paths (reuse DDD-Obs `checkout-out-of-stock`, `checkout-payment-decline`) |
| Deployment / mesh | — | — | minikube smoke | canary/blue-green verification |

- **Reused harnesses:** EIP-Camel Citrus pattern (one `.citrus.it.yaml` per
  route), DataMesh single-collection-many-environments Newman, DDD-Obs
  payload library + saga collections, "pin image tags once in `.env`, confirm
  Dev Services matches" discipline.
- **Load-bearing rule:** the monolith's Newman collection is the
  behavior-equivalence suite — an extracted service is "done" only when it passes the *same*
  collection the monolith passed. This is how the book proves each pattern
  preserves behavior, and it is a per-chapter acceptance gate.

---

## H. CI/CD design

- **Pipelines (GitHub Actions, per `lgtm-github`):**
  1. **Site CI** — Jekyll build + static validation (link check, front-matter,
     word-count, verification-footer presence) on every PR.
  2. **Code CI** — Maven reactor build + unit/integration (Dev Services) +
     native-image smoke for the Quarkus services; the **equivalence gate**
     runs the monolith's Newman collection against each extracted service.
  3. **Migration/cutover pipeline** (the chapter-30 subject) — builds the
     monolith *and* the growing service set together, runs equivalence,
     deploys to the minikube substrate, performs flag/canary cutover,
     verifies, and can roll back.
- **GitOps:** declarative manifests in `k8s/` (reuse DataMesh base + kustomize
  + Istio + KEDA overlays, retargeted to podman-built images); Argo-style
  sync described and demonstrated on minikube; progressive delivery via
  feature flags (OpenFeature/flagd) + Istio traffic splitting.
- **Supply chain:** SBOM generation, dependency/CVE scan, and policy-as-code
  gate wired into Code CI (new material; the deck's gap).
- **podman vs minikube fit:** `lgtm-podman-stack` is the *dev inner loop and
  CI* substrate (compose for Postgres/Kafka/Apicurio/LGTM); `lgtm-minikube-
  stack` is the *k8s-specific* substrate for ch.25 (mesh), ch.26 (deploy
  patterns), ch.30 (GitOps). Images are podman-built throughout; minikube
  consumes them. This boundary is stated once in ch.01 and never crossed
  silently (risk R5).
- **What the chapters show:** ch.29 shows the strangler/equivalence pipeline;
  ch.30 shows GitOps + progressive delivery + supply-chain gates.

---

## I. Presentation plan (lgtm-presentation)

Rebuild the deck from the original (`_source/refactoring-for-app-
modernization-original.pdf`, 75 slides) using `lgtm-presentation` (Red Hat
house style, pptxgenjs 16:9). The deck is a *companion* to the book, not a
re-export of it.

**Outline (modernized retelling, ~45–55 slides):**
1. Title + agenda (mirrors book Parts).
2. Why modernize / when microservices are a bad idea (ch.02).
3. Strategies + assessment 2x2 + evolution arc reframed to 2026 (ch.03).
4. **NEW: The ADLC** — modernize *with* agents (Part 2) — the headline
   differentiator vs the original deck.
5. The reference monolith + its smells (Part 3).
6. Finding the seams: DDD + event storming + coupling (Part 4).
7. Strangler fig in practice — the slice sequence (Part 5).
8. Data & transactions: outbox/CDC/ES/CQRS/saga (Part 6).
9. Running microservices: chassis/resilience/mesh/registry/deploy (Part 7).
10. Observability with OpenTelemetry (Part 8).
11. **NEW: Delivering the migration** — CI/CD, GitOps, supply chain (Part 9).
12. Conclusion: pattern map + chassis 2026 + what's next.

Every original slide traces forward via the deck-inventory page references;
the deck is built *after* the chapters it summarizes (r08) so it never drifts
ahead of the content.

---

## J. Iteration / release plan

Risk-first sequencing: **r02 is a thin walking skeleton that proves every hard
part once**, before anything scales. Each `rNN` is a coherent shippable
increment; later relays resume cleanly from the `_plans/build-plan.md` status
table.

| Iter | Theme | Deliverable (shippable increment) | Risks retired |
|---|---|---|---|
| **r01** | **Planning (this relay)** | PRD, `decisions.md` seed, `build-plan.md`, this plan, chosen accent/emoji, confirmed podman decision — **artifacts only, no build** | scope framing, toolchain decision |
| **r02** | **Walking skeleton** | Site scaffold (`lgtm-jekyll`) + podman stack up + the **monolith built + Inventory extracted (ch.13) end-to-end** + **ch.13 authored to the 2k-word bar with runnable code + tests + equivalence pass** + **the ADLC demonstrated once** (full plan→execute→validate trace in `_plans` + chapter panel) | **the four biggest risks at once**: does the monolith demonstrate a pattern? does the strangle work? is the ADLC real? does a chapter hit the bar with running code? |
| **r03** | Monolith + front matter | Finish Part 3 (ch.06–08), Part 0–1 (ch.00–03), Part 2 ADLC (ch.04–05) | monolith completeness; ADLC Part credibility |
| **r04** | Seams + first data patterns | Part 4 (ch.09–12), extract Order (ch.14), outbox (ch.18) | decomposition-pattern coverage; data-ownership mechanics |
| **r05** | Decomposition completion | ch.15–16, CDC (ch.19), ES/CQRS (ch.20), saga (ch.21) | the hardest data/transaction patterns; saga failure paths |
| **r06** | Operate + observe | Part 7 (ch.22–26), Part 8 (ch.27–28); introduce minikube substrate | mesh/registry/deploy on k8s; OTel end-to-end |
| **r07** | Deliver + appendices | Part 9 (ch.29–30), supply chain, GitOps; port/adapt all reuse appendices + reconciliation pass | CI/CD for strangler; reuse drift |
| **r08** | Deck + close | Rebuild deck (`lgtm-presentation`), ch.31 conclusion, cross-linking, final validation, release via `lgtm-github` | deck/content drift; final acceptance |

**Round-1 boundary:** r01 ends at approved planning artifacts. No repo is
created, nothing is pushed, until the user approves (per standing memory: no
upstream without permission). Repo creation + branch happens at the *start of
r02*, after approval.

---

## K. Execution mapping

All work runs through `lgtm-relay` (Opus plan → Sonnet execute → Opus
validate). Per stream, the skill + tier + concurrency:

| Work stream | Primary skill(s) | Executor tier | Parallel? | Collision risk |
|---|---|---|---|---|
| Site scaffold / chapters | `lgtm-jekyll`, `lgtm-tutorial` | Sonnet under Opus validate | chapters parallel *within a part* once the part's examples exist | low (separate `_docs/*.md`) — serialize `_config.yml`/`_parts` edits |
| Monolith code | `lgtm-quarkus` (for twin) + Spring (manual) | Sonnet | sequential (shared reactor `pom.xml`) | **high** — one writer for the reactor root at a time |
| Service extraction | Quarkus Agent MCP + `migrate-spring-to-quarkus` skill, `lgtm-quarkus` | Sonnet | one service at a time (walking skeleton first) | med — isolate per-module dirs; serialize reactor root |
| Camel routes / seams | `lgtm-camel` + Camel MCP | Sonnet | parallel per route after seam exists | low (per-route files + Citrus tests) |
| Infra (dev loop + CI) | `lgtm-podman-stack` | Sonnet | parallel with authoring | **high** — do NOT also pull `lgtm-docker-stack`; single compose source |
| k8s substrate | `lgtm-minikube-stack` | Sonnet | after services exist | med — `k8s/` overlays, one writer |
| Testing harness | Citrus/Newman patterns (reuse) | Sonnet | parallel per example | low |
| Diagrams | `lgtm-diagram-generator` | Sonnet | parallel | low (paired svg/excalidraw per figure) |
| Deck | `lgtm-presentation` | Sonnet | last (r08) | low (own `presentation/`) |
| Repo / release | `lgtm-github` | Sonnet under Opus validate | gated | **gate** — never without explicit user permission |

**Concurrency rule:** parallelize *authoring and per-module code* freely;
**serialize** every edit to shared roots (`_config.yml`, `_parts/`, reactor
`pom.xml`, the single compose file) to one writer per relay. Each relay
updates `build-plan.md` status so the next resumes without re-reading code.

---

## L. Acceptance criteria

### Round-1 (this relay) — checkable now

- [ ] Plan written to the output path, covering all sections A–M.
- [ ] Every one of the ~45 deck patterns appears in the §C matrix with a home
      and a runnable/demoted verdict.
- [ ] ADLC decision stated explicitly (both thread + Part) with a concrete
      phase model and a per-chapter demonstration mechanism.
- [ ] Monolith design names the six contexts, the deliberate smells, and the
      behavior-equivalence-suite test approach.
- [ ] Strangler sequence maps first-seam → full decomposition → chapters.
- [ ] Iteration plan defines r02 as a walking skeleton proving all four top
      risks, with clean resume boundaries.
- [ ] podman-vs-minikube boundary stated; no docker inheritance.
- [ ] Open questions surfaced for the user; nothing built or pushed.

### Eventual build — acceptance bar

- [ ] Every chapter ≥ 2000 words excluding code/diagrams, progressive, with a
      runnable example and a verification-status footer naming the tests run.
- [ ] Every extracted service passes the monolith's Newman equivalence
      collection unchanged.
- [ ] Every Part-5/6 chapter carries a real "Built with the ADLC" panel with
      traceable plan/execute/validate evidence.
- [ ] Site CI (build + static validation) and Code CI (reactor build + tests +
      equivalence gate) green.
- [ ] Reconciliation log shows zero unexplained drift between reused material
      and its source.
- [ ] Deck rebuilt and consistent with the chapters it summarizes.
- [ ] Single toolchain honored (podman dev/CI; minikube only for k8s
      chapters); no mixed compose stacks.

---

## M. Risks (what silently goes wrong + detection)

| ID | Risk | How it silently goes wrong | Detection / mitigation |
|---|---|---|---|
| **R1** | **Scope explosion** | 31 chapters × 2k words × running code balloons; "just one more pattern" creep | r02 walking skeleton caps the pattern before scaling; `build-plan.md` step table is the only backlog; scope-discipline memory enforced; demoted-pattern list is explicit |
| **R2** | **Monolith doesn't demonstrate the patterns** | smells too subtle/absent, so the "cure" chapters have nothing to cure | deliberate-smell table (§D) each tagged to its curing pattern; r02 proves one smell→cure loop before the rest |
| **R3** | **ADLC is hand-wavy** | described in Part 2 but never shown in code chapters | per-chapter "Built with the ADLC" panel is an acceptance gate; r02 demonstrates the full loop once; `_plans/*` is the living evidence |
| **R4** | **Reuse drift** | reused chapters/appendices diverge from their source projects or the source changes underneath | `reconciliation.md` tracks every reused artifact → source; verdict column in §B; r07 reconciliation pass; cite don't fork where possible |
| **R5** | **podman/docker toolchain conflict** | someone pulls `lgtm-docker-stack` / DataMesh compose; Dev Services image-tag mismatch; CI differs from dev loop | single compose source (podman); boundary stated in ch.01 + `decisions.md` (mirror DataMesh DRQ-003 but inverted); "pin tags once in `.env`" discipline; CI uses same stack |
| **R6** | **Chapters miss the 2k-word bar or ship without runnable code** | prose-only or stub-code chapters slip through | static validation in Site CI (word count + example-dir presence + verification footer); equivalence gate in Code CI |
| **R7** | **Pattern-coverage gaps** | a deck pattern quietly never lands | §C matrix is the checklist; validate phase cross-checks matrix vs shipped chapters each relay |
| **R8** | **Equivalence suite rots** | monolith Newman collection not kept in sync as API evolves | collection is versioned with the monolith; CI runs it against every service; failure blocks "done" |
| **R9** | **Reactor / shared-root collisions across parallel relays** | two executors edit `pom.xml`/`_config.yml`/compose simultaneously | §K serialization rule: one writer per shared root per relay; per-module isolation otherwise |
| **R10** | **Premature push / repo creation** | agent creates repo or pushes without approval | standing memory gate; repo creation deferred to start of r02 *after* user approval; `lgtm-github` only on explicit permission |
| **R11** | **Tool/version drift** (JDK 25, Quarkus, Camel 4.2x, Spring 3.x) | examples break against newer releases | version matrix in `decisions.md`; Dev Services + CI pin versions; Camel/Quarkus MCP used for version-matched ground truth |

---

## Open questions for the user (surfaced, not assumed)

1. **Accent color / brand emoji** — propose migration-green `#3d7a4e` + "🏗️";
   confirm or override.
2. **Chapter count tolerance** — 31 chapters is ambitious at ≥2k words +
   running code each. Acceptable, or should some Part-7/8 patterns collapse
   into fewer, denser chapters?
3. **Spring→Quarkus depth** — demonstrate the `migrate-spring-to-quarkus`
   Quarkus-agent skill as the ADLC execute step for *every* service, or show
   it once (Inventory) and summarize for the rest to control scope?
4. **Minikube vs podman for the final "run it on k8s" chapters** — confirm
   minikube is acceptable as the only k8s substrate (no OpenShift/cloud).

---

*Candidate plan — risk-first. Awaiting user approval before any build (r02).*
