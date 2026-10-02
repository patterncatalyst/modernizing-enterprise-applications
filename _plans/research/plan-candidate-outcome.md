---
title: "Plan Candidate — User-Outcome-First"
description: "Round-1 planning candidate for Modernizing Enterprise Applications: a Spring monolith strangled to Quarkus + Camel microservices, driven by an AI Development Lifecycle (ADLC), with testing throughout."
status: "round-1 planning only — nothing built or pushed until user approves"
candidate_lens: "user-outcome-first (design the learning arc, then fit everything to serve it)"
---

# Plan Candidate: Modernizing Enterprise Applications (User-Outcome-First)

> **Framing.** This candidate designs the *reader's journey* first — the believable
> through-line of one shipping application evolving from a relatable Spring monolith
> into a Quarkus + Camel system, driven by an agentic workflow the reader is taught
> to *do* — and then fits the monolith, the decomposition, the patterns, and the
> infrastructure to serve that arc. Every trade-off below is resolved in favor of
> pedagogical coherence and motivation ("why this pattern, why now") over coverage
> for its own sake.

---

## The Learning Arc (designed first — everything else serves this)

A professional reader arrives with a real pain: a Spring Boot monolith that is
slow to change, risky to deploy, and hard to reason about. The book carries them
through six emotional/competence beats:

1. **"I recognize this."** Meet a believable shipping/e-commerce monolith with
   real smells they have in their own code. (Running code, day one.)
2. **"I know when *not* to do this."** Strategy and assessment — modernization as
   a disciplined, reversible, business-justified exercise, not résumé-driven design.
3. **"I have a new way to work."** The ADLC — an AI Development Lifecycle the reader
   is taught to *operate* (plan/generate/verify with human gates), which will drive
   every subsequent migration step. This is the book's differentiator and its
   "aha": modernization is not a heroic manual slog; it is a repeatable,
   agent-assisted loop with a safety net.
4. **"I can see the seams."** DDD, event storming, and coupling analysis turn the
   monolith's modules into candidate service boundaries — with the ADLC doing the
   reconnaissance.
5. **"I can pull one strand safely."** The strangler fig in practice: each extraction
   introduces exactly the patterns that extraction *forces you to confront* — never
   a pattern before its motivating problem. Difficulty rises monotonically:
   REST leaf → event consumer → synchronous gRPC with a decomposed DB → choreographed
   saga → orchestrated saga → the core aggregate with CQRS.
6. **"I can run and operate the result."** Deployment patterns, mesh, observability,
   CI/CD for the cutover, and an honest reckoning with anti-patterns and what's next.

**The "aha" moments, placed deliberately:**
- *Tests are the safety net that makes agentic migration safe* (Part 3/4 boundary):
  the Newman contract suite captured against the monolith becomes the equivalence
  oracle for every extracted service.
- *The ADLC's three planning artifacts (decisions / build-plan / reconciliation)
  ARE the migration's memory* — the reader keeps the same ledger the book keeps.
- *The first strangle is anticlimactic on purpose* (review-service): the point is the
  loop, not the difficulty — so the reader trusts it before the hard extractions.
- *ACID quietly became ACD* (Part 6): the reader feels the loss of isolation as a
  consequence of a decision they made, not as an abstract warning.

**Where each reference book deepens understanding (mapped to the arc, not bolted on):**
- Newman, *Building Microservices 2e* — strategy, seams, strangler mechanics,
  incremental decomposition (Parts 1, 4, 5).
- Ozkaya, *Design Microservices Architecture with Patterns & Principles* —
  pattern-selection discipline, decomposition heuristics (Parts 4, 5, 8).
- Joshi, *Patterns of Distributed Systems* — the lower-level invariants behind
  outbox, log tailing, CDC, leader/replication (Part 6).
- Kleppmann, *Designing Data-Intensive Applications 2e* — the deep "why" of data
  consistency, event logs, CQRS, and the ACID→ACD shift (Part 6, cited throughout).
- Gough, *Mastering API Architecture* — communication styles, gateway, contracts,
  registry, API evolution (Part 8).
- Albada, *Building Applications with AI Agents* — the ADLC itself: agent design,
  tool use, human-in-the-loop gates, evaluation (Part 2, cited in every
  "ADLC in Action" callout).

Each reference is introduced *at the moment of need* with a one-paragraph "deepen
here" sidebar, not front-loaded.

---

## A. Approach (and rejected alternatives)

### A.1 Chosen approach

**A single, continuous shipping-application narrative**, built as a Spring Boot
monolith first, then strangled to Quarkus + Camel microservices, with the ADLC
taught once as a dedicated part and then *demonstrated* in every migration chapter.

Three framing decisions the brief asked us to make:

1. **ADLC: a dedicated part AND a woven thread (both).**
   - A dedicated **Part 2 ("The ADLC")** *teaches* the lifecycle so the reader learns
     to DO it (phases, agents, MCP tools, skills, human gates, the three ledger
     artifacts). This is required because requirement #8/#F is that the ADLC
     *replaces* the SDLC — that demands explicit instruction, not osmosis.
   - A recurring **"ADLC in Action" callout** in every migration chapter shows the
     exact phase/agent/tool/skill/gate used for *that* step, with the real
     `decisions.md` / `build-plan.md` entries produced. This makes the ADLC a
     practiced habit, not a read-once chapter.
   - *Rejected: thread-only* (reader never learns the method as a whole) and
     *dedicated-part-only* (reader reads about it but never sees it carry weight).

2. **Monolith/decomposition sequencing: build-first, then decompose, as narrative
   backbone.** The monolith is authored and runnable (Part 3) *before* any
   decomposition (requirement #19). The decomposition then follows a strict
   strangler sequence (Part 5–7), each step a chapter, each step runnable.
   - *Rejected: pattern-catalog ordering* (patterns grouped by category, monolith as
     backdrop). It covers the deck faithfully but kills the through-line and the
     motivation — the reader never feels *why this pattern, now*. We instead let the
     *extraction sequence* dictate pattern order, and use a coverage matrix
     (Section C) to guarantee nothing from the deck is dropped.
   - *Rejected: decompose-as-you-go with no finished monolith* — violates #19 and
     removes the equivalence oracle that makes the whole method safe.

3. **Reuse-vs-fresh ratio: ~60% adapt/reuse, ~40% fresh.**
   - **Reuse-as-is / adapt (the ~60%):** theory chapters from CNDP (DDD/hexagonal,
     sagas, coupling, failure modes, feature flags, caching), Camel routing &
     transformation and the full testing strategy from EIP-Camel, the Quarkus
     primer + Spring-twin from DataMesh-Quarkus, the saga test harness + dashboards
     from DDD-Obs, and the whole house-convention set (config, CSS, front-matter,
     diagram workflow, PRD/decision-log patterns).
   - **Fresh (the ~40%):** the reference monolith and its deliberate smells; the
     strangler decomposition *narrative* and per-step code; **Part 2 (the ADLC)**;
     **Part 10 (CI/CD for the migration)**; and all the connective "why now" tissue
     that turns four sibling reference projects into one coherent journey.
   - This ratio is a *means*, not a goal: we reuse wherever reuse serves the arc and
     write fresh wherever the arc needs motivation, sequencing, or the migration
     story that no sibling project tells.

### A.2 Standing constraints honored
Scope discipline (no speculative infrastructure beyond the arc's needs),
conceptual coherence (consistent abstraction level per part), security-by-design
(OWASP/CIS woven into contracts, registry, mesh, CI/CD, and the ADLC's verify
gate), and **never push upstream without explicit permission** (round-1 is
planning only; repo/branch creation happens only after approval — see Section J).

---

## B. Site Architecture

Jekyll/GitHub-Pages site in the inherited house skeleton (`_docs`, `_parts`,
`_plans`, `examples/`, `_layouts`, `assets/`), authored with **lgtm-tutorial**,
scaffolded consistent with **lgtm-jekyll**. Accent color proposed: **blue or green**
(distinct from the three amber/red/teal siblings) — *open question for the user*.

Chapters are **minimum 2000 words excluding code and diagrams**; every chapter
carries a runnable example and a verification-status footer.

### B.1 Parts (`_parts/`)

| # | Part | Blurb |
|---|---|---|
| 0 | Setting Up | Orientation, prerequisites, podman/minikube toolchain, how to run the examples, the ADLC ledger artifacts. |
| 1 | The Case for Modernization | Why modernize, strategies, when microservices are a bad idea, assessing migration complexity. |
| 2 | The ADLC — An AI Development Lifecycle | The agentic workflow the reader will operate; how it replaces the SDLC; agents, MCP tools, skills, human gates. |
| 3 | Meet the Monolith | The reference Spring Boot shipping monolith: domain, modules, persistence, API, seed data, deliberate smells, its tests. |
| 4 | Finding the Seams | DDD (strategic + tactical), event storming, coupling analysis, modular monolith, decomposed databases. |
| 5 | The Strangler Fig in Practice | The strangler family and the first extractions, each a real migration step with an anti-corruption layer and test equivalence. |
| 6 | Data Without a Shared Database | Shared data, log tailing, CDC, outbox, event sourcing, CQRS, and the ACID→ACD shift. |
| 7 | Coordinating Across Services | Distributed transactions, choreographed and orchestrated sagas, failure modes, the resilience chassis. |
| 8 | Communication & Contracts | REST/gRPC/GraphQL, the Quarkus/MicroProfile chassis, schema/API/service registry. |
| 9 | Operating the Modernized System | Deployment patterns, service mesh, observability/OTel/LGTM, distributed tracing, caching. |
| 10 | CI/CD for the Migration | Pipelines for strangler cutover and deployments, GitOps, progressive delivery, supply-chain security. |
| 11 | Reflection & What's Next | Anti-patterns of over-decomposition, Richardson's pattern map, the chassis recap, serverless/platform-engineering/AI-ML horizons. |
| A+ | Appendices | Reuse-heavy deep dives (see B.3). |

### B.2 Ordered chapter list (title + synopsis)

**Part 0 — Setting Up**
- **00. Introduction & How to Use This Book** — the shipping-app through-line, the
  professional-reader bar, the arc overview, conventions (codetabs, callouts,
  verification footers), the reference-book map.
- **01. Prerequisites & Toolchain** — JDK/Maven/Quarkus/Camel CLI via SDKMAN;
  **podman default** (lgtm-podman-stack); minikube (lgtm-minikube-stack) reserved
  for Part 9–10; how to run the monolith and each extracted service.
- **02. The Project Ledger** — the three ADLC artifacts (`decisions.md`,
  `build-plan.md`, `reconciliation.md`) introduced as the reader's own working
  memory for the migration; the `DRQ-NNN` decision-log convention.

**Part 1 — The Case for Modernization**
- **03. What Modernization Is (and Is Not)** — modernization as disciplined
  engineering; "microservices should not be the goal"; the 10 microservice traits;
  goals and anti-goals. *Deepen: Newman ch.1–2.*
- **04. When Microservices Are a Bad Idea** — unclear domains, small orgs, packaged
  software, weak Agile/automation maturity, résumé-driven design; reversibility as
  a design property.
- **05. Modernization Strategies** — Lift & Shift, Modernize & Extend, Rip &
  Rewrite (+ Repurchase/Retire/Retain, Container-Native Virtualization); the
  strategic-value × change-frequency 2×2; architecture-evolution arc
  (Monolith → SOA/ESB → Microservices 1.0 → Cloud-Native 2.0), reframed for 2026.
- **06. Assessing Migration Complexity** — the ease-of-migration rubric (code,
  config, data, secrets, network, installation, licensing, type); least-complex /
  highest-ROI first; applied to *our* monolith to produce the extraction order.

**Part 2 — The ADLC**
- **07. From SDLC to ADLC** — why the traditional lifecycle breaks under
  agent-assisted work; the ADLC phases (Frame → Map → Plan → Generate → Verify →
  Operate → Reconcile); human-in-the-loop gates. *Deepen: Albada.*
- **08. Agents, Tools, and Skills** — the lgtm-relay tiering (Opus plan → Sonnet
  execute → Opus validate); MCP tooling (camel-mcp, quarkus-agent); the lgtm-*
  skill map; when the human must decide.
- **09. The ADLC Safety Net** — tests, contracts, and reconciliation as the
  guardrails that make agentic generation trustworthy; the verify gate in detail;
  evaluation and drift detection.
- **10. Operating the ADLC on This Project** — a full worked loop end-to-end on a
  trivial change, so the reader has done the whole cycle before Part 5 raises the
  stakes. Establishes the "ADLC in Action" callout format used thereafter.

**Part 3 — Meet the Monolith**
- **11. The Shipping Domain** — order/inventory/payment/shipping/notification/review
  as one business; the ubiquitous language; seed data and personas.
- **12. Inside the Monolith** — the Spring Boot modules, layered architecture, the
  single shared schema, the REST API surface; build and run it.
- **13. The Smells** — the *deliberate* coupling, god-service, shared-table, and
  transaction-scope smells, each tied to a future extraction pain. *Deepen:
  Newman ch.3.*
- **14. Testing the Monolith (the Oracle)** — unit tests, the Newman contract suite
  captured against the monolith's API, Testcontainers integration tests; this suite
  becomes the equivalence oracle for every extraction. *Reuse: EIP-Camel testing
  strategy + CNDP Newman appendix.*

**Part 4 — Finding the Seams**
- **15. Strategic DDD & Event Storming** — core/supporting/generic subdomains,
  bounded contexts, context mapping; event storming the shipping domain to find the
  seams — run as an ADLC reconnaissance step. *Reuse/adapt: CNDP DDD appendix +
  DDD-Obs event-storming addendum. Deepen: Ozkaya.*
- **16. Tactical DDD & Hexagonal Architecture** — aggregates, entities, value
  objects, domain events; keeping protocols out of the domain core so extraction is
  mechanical. *Reuse/adapt: CNDP hexagonal appendix.*
- **17. Coupling: What to Split and What to Leave** — Khononov's strength/distance/
  volatility model; why some modules must *not* be split; the modular monolith as a
  legitimate destination. *Reuse: CNDP coupling appendix.*
- **18. The Modular Monolith & Decomposed Databases** — restructuring the monolith
  into modules with owned schemas *before* going distributed; the "distributed
  monolith" trap. *Deepen: Newman ch.4.*

**Part 5 — The Strangler Fig in Practice**
- **19. The Strangler Fig Pattern** — Fowler's pattern, the three steps, and the
  proxy / redirection / shared-database variants; the Camel-based strangler proxy we
  will use for cutover.
- **20. Extraction 1 — Review Service (the easy strand)** — REST-only, OIDC,
  no synchronous dependency; prove the full loop: ACL, Quarkus scaffold, test
  equivalence against the oracle, cutover via the proxy, decommission the monolith
  module. *The deliberately anticlimactic first win.*
- **21. Content-Based Routing & the Anti-Corruption Layer** — Camel content-based
  router, message translator, content enricher as the translation layer at the seam;
  the decorating-collaborator pattern for untouchable legacy. *Reuse: EIP-Camel
  routing + transformation chapters.*
- **22. Extraction 2 — Notification Service (going event-driven)** — introduces the
  Kafka backbone, the **outbox pattern** in the monolith for reliable publication,
  and content-based routing of events; first taste of eventual consistency.

**Part 6 — Data Without a Shared Database**
- **23. From Shared Data to Owned Data** — shared-data pattern and when it is still
  fine; the cost of the shared schema; planning the data split. *Deepen:
  Kleppmann ch.1–3.*
- **24. Transaction Log Tailing & CDC** — Debezium-style change data capture to
  bridge the monolith's tables to the new service during transition; log tailing as
  the primitive. *Deepen: Joshi (write-ahead log, replication); Kleppmann ch.11.*
- **25. Extraction 3 — Inventory Service (synchronous, decomposed DB)** — gRPC-first
  low-latency stock checks; decomposed database with CDC backfill; decorating
  collaborator retiring the monolith's inventory calls.
- **26. The Outbox Pattern, Done Right** — transactional outbox vs dual-write;
  idempotent consumers; ordering and deduplication. *Deepen: Joshi.*
- **27. Event Sourcing & CQRS** — event log as source of truth; separating read and
  write models; the GraphQL read model as a CQRS query side; trade-offs (benefits
  vs operational cost). *Reuse/adapt: CNDP; Deepen: Kleppmann ch.11–12.*
- **28. ACID → ACD: Living Without Isolation** — the consistency the reader just
  gave up; dirty reads, lost updates, non-repeatable reads; where it bites and how
  to bound it. *Deepen: Kleppmann ch.7.*

**Part 7 — Coordinating Across Services**
- **29. Distributed Transactions & the Saga** — why 2PC is off the table;
  compensating transactions; the travel-booking-style saga reframed to our
  order→payment→shipping flow.
- **30. Extraction 4 — Payment Service (choreographed saga)** — event choreography
  (`order.placed` → `payment.captured`); each service owns its compensation; no
  central coordinator; failure-path tests from the saga harness. *Reuse: DDD-Obs
  failure-path Newman payloads.*
- **31. Extraction 5 — Shipping Service (orchestrated saga)** — the same business
  outcome via a central orchestrator using the **Camel Saga EIP**; choreography vs
  orchestration trade-offs made concrete on one codebase. *Reuse/adapt: CNDP saga
  appendix.*
- **32. Failure Modes & the Resilience Chassis** — partial failure taxonomy
  (partition, split-brain, gray, cascading); timeouts, retry+jitter, circuit
  breaker, bulkhead, load shedding; SmallRye Fault Tolerance as the Quarkus chassis.
  *Reuse/adapt: CNDP failure-modes appendix.*

**Part 8 — Communication & Contracts**
- **33. Communication Styles: REST, gRPC, GraphQL** — when each fits; the mixed
  topology we ended up with; API evolution and compatibility. *Deepen: Gough.*
- **34. The Quarkus / MicroProfile Chassis** — Config, Fault Tolerance, Health,
  Metrics, OpenAPI, REST Client, JWT; build-time optimization, native image,
  dev-mode inner loop — "what you migrated *to*." *Reuse: DataMesh Quarkus primer.*
- **35. Extraction 6 — Order Service & the GraphQL Gateway** — the core aggregate
  extracted last; the gateway as the synchronous read-aggregation surface; monolith
  order module decommissioned; the strangler completes.
- **36. Contracts & the Service Registry** — schema/API/shared-data-type registry
  use cases; Apicurio with Avro/Protobuf/JSONSchema/OpenAPI/AsyncAPI; self-
  documenting schemas citing decision IDs. *Deepen: Gough.*

**Part 9 — Operating the Modernized System**
- **37. Deployment Patterns** — fixed, rolling update, breaking-schema-change, and
  blue-green; which fits synchronous vs event-driven services.
- **38. Service Mesh & mTLS** — Istio sidecar model, traffic routing, security,
  fault injection; on minikube. *Reuse: DataMesh k8s/istio manifests.*
- **39. Observability with OpenTelemetry & LGTM** — logs/metrics/traces via the OTel
  Collector into Loki/Grafana/Tempo/Mimir; the saga dashboards. *Reuse: DDD-Obs
  dashboards + lgtm-podman/minikube stacks.*
- **40. Distributed Tracing in Practice** — spans, causality, context propagation
  across the extracted services; debugging across bounded contexts. *Reuse/adapt:
  DDD-Obs cross-context-debugging chapter.*
- **41. Caching** — local/clustered/remote/data-grid; consistency and failure
  stories; where caching belongs after decomposition. *Reference: CNDP caching
  appendix.*

**Part 10 — CI/CD for the Migration**
- **42. Pipelines for the Strangler** — CI that runs the equivalence oracle on every
  extraction; building and containerizing Quarkus services (UBI); gating on the
  Citrus/Newman suites.
- **43. Progressive Delivery & Feature-Flag Cutover** — deploy-vs-release split;
  OpenFeature/flagd; percentage rollouts as the literal mechanism for shifting
  traffic monolith→service. *Reuse/adapt: CNDP + EIP-Camel feature-flag appendices.*
- **44. GitOps & Deployments** — declarative deploys to minikube; promotion flow;
  rollback; podman→minikube parity.
- **45. Supply-Chain & Platform Security** — SBOM, dependency/CVE scanning,
  secure-by-default (Camel deserialization filters, URI allow-lists), secrets, mesh
  zero-trust; OWASP/CIS mapped to the pipeline. *Reuse: EIP-Camel security appendix.*

**Part 11 — Reflection & What's Next**
- **46. Anti-Patterns of Over-Decomposition** — distributed monolith, nanoservices,
  premature splitting; when to stop; the cost ledger.
- **47. The Pattern Map & the Chassis** — Richardson's microservices pattern-language
  map; the chassis recap (1.0 libraries vs 2.0 platform); what we covered vs what we
  chose to leave.
- **48. What's Next** — serverless/Knative, platform engineering/IDP, event-driven
  architecture at scale, AI/ML pipelines, and the ADLC's own evolution. *Honest
  successor to the deck's "There's More!" slide.*

### B.3 Appendix list (reuse-as-is / adapt / new)

| Appendix | Source & verdict |
|---|---|
| A. The Reference Monolith — full module map | **New** (companion to Part 3). |
| B. The Target Reactor — module & contract map | **New** (companion to Parts 5–8). |
| C. DDD & Hexagonal Architecture | CNDP `19-appendix-f` — **adapt** (if not fully promoted into Part 4). |
| D. Sagas — state, persistence, compensation | CNDP `17-appendix-d` — **adapt**. |
| E. Coupling (Khononov model) | CNDP `20-appendix-g` — **reuse-as-is**. |
| F. Failure Modes & Defensive Toolkit | CNDP `26-appendix-m` — **adapt**. |
| G. Feature Flags | CNDP `27-appendix-n` + EIP-Camel `26` — **adapt (merge)**. |
| H. Caching Patterns | CNDP `25-appendix-l` — **reference/light-adapt**. |
| I. Camel CLI & TUI | EIP-Camel `39`/`40` — **reuse-as-is**. |
| J. Citrus Testing | EIP-Camel `41` — **reuse-as-is** (load-bearing). |
| K. Three-Tier Testing Strategy | EIP-Camel `37` — **reuse-as-is**. |
| L. Newman as Executable Contracts | CNDP `28-appendix-o` — **reuse-as-is**. |
| M. Kafka Fundamentals & Tuning | EIP-Camel `20`/`32–36` — **reuse-as-is (subset)**. |
| N. Quarkus Dev Services & Testcontainers | EIP-Camel `23` + DataMesh wire-compat gotcha — **reuse-as-is**. |
| O. Quarkus vs Spring Boot (measured) | DataMesh `12` + `spring-boot-compare` — **reuse-as-is**. |
| P. AI/MCP for Integration | EIP-Camel `42` — **adapt** (runtime AI vs ADLC-time AI, cross-ref Part 2). |
| Q. Kubernetes Deploy on Minikube | EIP-Camel `38` + DataMesh `k8s/` — **adapt**. |
| R. Observability Economics | DDD-Obs `05` — **reference**. |
| S. Worked EIP Case Studies (Loan Broker, Bond Trading) | EIP-Camel `28`/`29` — **reference (further reading)**. |

---

## C. Pattern-Coverage Matrix

Every pattern catalogued in the deck inventory (categories A–I, ~45 patterns) is
accounted for below: mapped to a chapter/appendix, **demoted** (with reason), or
flagged where a **new** topic extends beyond the deck.

### C.1 Deck patterns → location

| Deck category | Pattern | Home |
|---|---|---|
| A. Strategy | Lift & Shift / Modernize & Extend / Rip & Rewrite | Ch.05 |
| A | Repurchase/Retire/Retain; Container-Native Virtualization | Ch.05 (contextual) |
| B. Decomposition | Monolith / Modular Monolith / Decomposed-DB monolith | Ch.12, 18 |
| B | Strangler Fig (+ Proxy / Redirection / Shared-DB variants) | Ch.19 |
| B | Content-Based Routing | Ch.21 |
| B | Decorating Collaborator | Ch.21, 25 |
| C. Data | Shared Data | Ch.23 |
| C | Transaction Log Tailing | Ch.24 |
| C | Event Sourcing | Ch.27 |
| C | CQRS | Ch.27, 35 |
| C | Simple CDC | Ch.24 |
| C | Outbox | Ch.22, 26 |
| C | Distributed Transactions (ACID vs ACD) | Ch.28 |
| D. Dist-txn | Saga (general) | Ch.29 |
| D | Saga — Choreographed | Ch.30 |
| D | Saga — Orchestrated (Camel Saga EIP) | Ch.31 |
| E. Chassis | Circuit Breaker / Service Discovery / Health Checks | Ch.32, 34, 38 |
| E | Microservices Chassis | Ch.34, 47 |
| F. Deployment | Fixed / Rolling / Breaking-Schema / Blue-Green | Ch.37 |
| G. Registry | Schema / API / Shared-Data-Type registry; Apicurio | Ch.36 |
| H. Observability | Service Mesh | Ch.38 |
| H | Observability (platform) / Kiali | Ch.39 |
| H | Distributed Tracing | Ch.40 |
| H | Caching | Ch.41 |
| H | API Gateway & Service Discovery (evolution) | Ch.33, 35, 38 |
| H | Communication: REST / gRPC / GraphQL | Ch.33 |
| H | MicroProfile | Ch.34 |
| I. Testing | Unit / End-to-end / Microservice (sidecar) / Testing Data | Ch.14 + per-extraction (woven) + App. J/K/L |
| I | Event-driven topologies / Reduction functions as test concerns | Ch.14, 22 |

### C.2 Demoted (kept, but not a full chapter) and why
- **Netflix OSS "Microservices 1.0" stack (Eureka/Hystrix/Ribbon/Zuul/Zipkin,
  Spring Cloud Config):** demoted to *historical framing* in Ch.05 and Ch.47. Rationale:
  effectively legacy/maintenance-mode in 2026 (per deck-inventory §6); teaching it as
  current would mis-serve the reader. Replaced by Kubernetes-native discovery, OTel,
  SmallRye/Resilience4j, Istio.
- **SOA/ESB containerization detail:** demoted to a single arc-setting section in
  Ch.05; the centralization critique is retained as motivation, not expanded.
- **Loan Broker / Bond Trading EIP case studies:** demoted to **Appendix S**
  (further reading) — excellent but not migration-specific (per reuse-map).
- **Caching:** kept as Ch.41 but intentionally lighter (**reference** appendix H) —
  only load-bearing if the monolith's caching layer is worth migrating; we add a
  minimal one so the chapter has a real referent.

### C.3 NEW topics beyond the deck (filling deck-inventory §6 gaps)
- **The ADLC / agentic modernization** (entire Part 2 + woven thread) — the deck's
  "AI/ML pipelines" was an acknowledged gap; here it is the organizing method.
- **Quarkus as the concrete runtime** (Ch.34, App. O) — deck named MicroProfile but
  no runtime, no native image / build-time / dev-loop.
- **Apache Camel as the integration runtime** (Ch.21, 22, 31) — deck treated EIPs
  abstractly; we bind them to Camel/Camel-on-Quarkus.
- **OpenTelemetry + LGTM stack** (Ch.39–40) — deck covered tracing conceptually only.
- **GitOps / modern CI/CD / progressive delivery** (Part 10) — deck listed CI/CD as
  out-of-scope.
- **Supply-chain & platform security** (Ch.45) — SBOM, CVE scanning, secure-by-default.
- **Explicit DDD + event storming** (Part 4) — implicit in the deck, foregrounded here.
- **Reference books as a deliberate "deepen here" layer** (all parts).

---

## D. Reference Monolith Design

A **fresh Spring Boot 3.x monolith**, authored first, kept permanently in the repo
as `examples/00-monolith/` (the common ancestor the sibling projects already treat
as their "after" — see reuse-map §6). It must be *believable* and *runnable*, with
*deliberate* smells that each foreshadow an extraction.

- **Domain (one business, six bounded contexts as packages/modules):**
  `order`, `inventory`, `payment`, `shipping`, `notification`, `review` — the exact
  shape DataMesh-Quarkus/DDD-Obs/EIP-Camel converge on, so cross-references work.
- **Spring modules / architecture:** classic layered Spring Boot
  (`controller` → `service` → `repository`) with the six domains as packages inside
  one deployable; Spring MVC REST controllers, Spring Data JPA, Bean Validation,
  Spring Security for the review endpoints.
- **Persistence:** a **single shared PostgreSQL schema** — one database, cross-domain
  foreign keys and joins (the central smell that makes the data-split chapters bite).
  Flyway migrations; seed data loaded at startup.
- **API surface:** REST for all six contexts (orders CRUD + checkout, inventory
  stock queries/adjustments, payment capture, shipping dispatch, notification
  history, review CRUD), documented via springdoc OpenAPI — this OpenAPI doc seeds
  the Newman equivalence oracle.
- **Seed data & personas:** a product catalog, customers, and a scripted
  happy-path checkout plus two failure paths (out-of-stock, payment-decline) — the
  same scenarios DDD-Obs already has Newman payloads for, so they port directly.
- **Deliberate smells (each mapped to a future pain):**
  1. *Shared mutable schema / cross-domain joins* → forces CDC + outbox + data split
     (Part 6).
  2. *A god `OrderService` reaching into inventory, payment, shipping directly* →
     forces the hardest, last extraction (Ch.35) and motivates sagas.
  3. *One in-process `@Transactional` spanning order+inventory+payment* → the ACID
     that quietly becomes ACD (Ch.28).
  4. *Synchronous notification send inside the checkout transaction* → forces the
     outbox + event-driven extraction (Ch.22).
  5. *Review module tangled into the same security/context but genuinely
     independent* → the easy first strand (Ch.20).
- **Testing (the oracle):** unit tests per domain service; a **Newman contract
  collection** captured against the running monolith (happy + two failure paths);
  Testcontainers-backed integration tests over the real Postgres. This suite is the
  equivalence oracle re-run against every extracted service (Ch.14, reused in every
  extraction and in CI, Part 10).

Scale discipline (per reuse-map): keep each module intentionally small (a handful of
files), matching the datamesh "3–9 files per service" bar — the monolith should be
*realistic*, not sprawling.

---

## E. Decomposition Roadmap

Extraction order follows the deck's own guidance — *least complex / highest ROI
first* (p.18) — and the rising-difficulty curve of the learning arc. Each step is a
chapter, each produces a `DRQ-NNN` decision and a `build-plan.md` row, each is
cut over behind the Camel strangler proxy with feature-flagged traffic, and each is
validated against the oracle before the monolith module is decommissioned.

| # | Service | Seam / mechanism | Data & transaction handling | Target (Quarkus + Camel) | Chapters |
|---|---|---|---|---|---|
| 1 | **review** | REST leaf, no sync deps; strangler proxy redirect by URI; ACL | Own schema from day one; no shared-txn entanglement | Quarkus REST + OIDC | Ch.19–20 |
| 2 | **notification** | Event consumer; content-based routing; decorating collaborator | **Outbox** in monolith → Kafka; idempotent consumer | Quarkus + Camel (Kafka consumer, WebSockets.Next) | Ch.21–22 |
| 3 | **inventory** | Synchronous gRPC; decomposed DB; decorating collaborator | **CDC (Debezium)** backfill from shared schema → owned DB; then cut writes | Quarkus gRPC server | Ch.24–25 |
| 4 | **payment** | Event choreography (`order.placed`→`payment.captured`) | Choreographed **saga**; compensations; ACID→ACD realized | Quarkus + Camel (Kafka) | Ch.29–30 |
| 5 | **shipping** | Completes the chain; alternative orchestration | **Orchestrated saga** via Camel Saga EIP | Quarkus + Camel (Saga EIP) | Ch.31 |
| 6 | **order** (+ gateway) | Core aggregate, extracted last; GraphQL read side | **CQRS** read model; event sourcing option; monolith decommissioned | Quarkus REST/Kafka + GraphQL gateway | Ch.27, 35 |

**Seam toolkit** (consistent across all six): a Camel **strangler proxy** routing by
URI/content; an **anti-corruption layer** (Camel message translator + content
enricher) at each boundary; **feature flags** (OpenFeature/flagd) as the cutover
mechanism; the **Newman oracle** as the go/no-go gate.

**Data trajectory** (one continuous story, not isolated patterns):
shared schema → outbox for reliable publication → CDC for transition backfill →
per-service owned databases → sagas for cross-service consistency → CQRS for the
read side. Each stage is *forced* by the extraction that reaches it, satisfying
"why this pattern, why now."

---

## F. ADLC / Agentic-Workflow Design

The ADLC is the book's method and its differentiator. It is taught in Part 2 and
*demonstrated* in every migration chapter via a fixed "ADLC in Action" callout.

### F.1 Phases (replacing the SDLC's requirements→design→build→test→deploy→maintain)

| ADLC phase | What happens | Human gate? | Agents / tools / skills |
|---|---|---|---|
| **1. Frame** | Human states the outcome for this step (e.g., "extract review-service with behavior equivalent to the monolith"). | Human authors intent | — (human) |
| **2. Map** | Agent reconnaissance: classify the legacy module, map dependencies, find the seam. | — | camel-mcp `migration_analyze`; quarkus-agent `migrate-spring-to-quarkus` skill; Explore subagent |
| **3. Plan** | Opus produces the step plan, `DRQ-NNN` decision, and `build-plan.md` rows. | **GATE: human approves plan before any code** | lgtm-relay (Opus plan) |
| **4. Generate** | Sonnet scaffolds and writes code + tests. | — | lgtm-relay (Sonnet); lgtm-quarkus; lgtm-camel; camel-mcp route scaffold; quarkus-agent `create`/`skills` |
| **5. Verify** | Opus validates: run Citrus/Newman/Testcontainers, check the oracle, write `reconciliation.md`. | **GATE: human signs off on equivalence** | lgtm-relay (Opus validate); camel-mcp `validate_route`; Newman/Citrus |
| **6. Operate** | Deploy behind a flag, observe via LGTM, shift traffic. | Human controls rollout % | lgtm-podman/minikube-stack; feature flags |
| **7. Reconcile** | Append decision outcomes, update build-plan status, record drift. | — | the three ledger artifacts |

### F.2 How it replaces the SDLC
The SDLC passes artifacts between *roles* over *weeks*; the ADLC cycles one engineer
plus model tiers over *hours*, with the two human gates (Plan approval, Verify
sign-off) replacing stage-gate committees. The three ledger artifacts
(`decisions.md`, `build-plan.md`, `reconciliation.md`) replace the heavyweight
design doc + test plan + traceability matrix with a lightweight, append-only,
agent-maintained memory. **This repo's own `_plans/` is the worked example** — the
book is built the way it teaches.

### F.3 How each chapter demonstrates it
Every migration chapter (Ch.20, 22, 25, 30, 31, 35) ends with an **"ADLC in Action"**
callout showing: the Frame statement, the actual `DRQ-NNN` entry, the agent tier and
MCP tool used, the gate decisions, and the reconciliation result. The reader sees the
same loop six times at rising difficulty — which is how a method becomes a habit.
Part 2's Ch.10 runs the loop once on a trivial change so the stakes are low the first
time.

### F.4 Security in the ADLC
The Verify gate includes the security checks (dependency/CVE scan, Camel
secure-by-default validation, secrets hygiene) so security-by-design is *part of the
loop*, not a late audit — consistent with OWASP/CIS standing constraints.

---

## G. Testing Strategy (per pattern, woven throughout)

Testing is not a part — it is a property of every chapter (requirement #11). The
spine is EIP-Camel's **three-tier strategy** (reuse-as-is): MockEndpoint/AdviceWith
unit tests, Dev Services + REST Assured integration tests, Newman black-box contract
tests; plus **Citrus** for end-to-end route/integration tests and **Testcontainers /
Quarkus Dev Services** for self-provisioning ITs.

| Pattern / step | Primary test mechanism |
|---|---|
| Monolith baseline (the oracle) | Newman contract suite + Testcontainers ITs (Ch.14) |
| Strangler cutover | Oracle re-run against extracted service; flag-gated canary |
| Content-based routing / ACL | Camel MockEndpoint + AdviceWith unit tests |
| Outbox / event-driven | Citrus against real Kafka (Testcontainers); idempotency tests |
| CDC | Testcontainers Debezium + data-equivalence assertions |
| gRPC inventory | REST/gRPC contract tests + Dev Services |
| Saga (choreographed & orchestrated) | DDD-Obs failure-path Newman payloads (out-of-stock, payment-decline) + Citrus |
| CQRS / read model | Eventual-consistency read-after-write tests with bounded waits |
| Resilience chassis | Fault-injection tests (timeouts, circuit-breaker trips) |
| Deployment patterns | Smoke + rollback tests in CI (Part 10) |

Every chapter carries the inherited **verification-status footer** naming exactly
which tests/demos were run — the strongest professional-grade signal across the
sibling projects, and the concrete output of the ADLC Verify gate.

---

## H. CI/CD Design

Covered in Part 10, but pipeline *stubs* appear from Ch.14 onward (the oracle must
run in CI before the first extraction).

- **Strangler-migration pipeline:** on every PR, build the affected service, run its
  three-tier tests, then run the **Newman oracle** against it and **fail if behavior
  diverges from the monolith**. This is the automated equivalence gate.
- **Deployment pipeline:** build UBI-based Quarkus container images; deploy to
  minikube; run smoke tests; support rolling / blue-green / breaking-schema flows
  (Ch.37); feature-flag-gated progressive rollout (Ch.43).
- **GitOps:** declarative manifests promoted through environments; rollback as a
  first-class, tested path (Ch.44).
- **Supply-chain security:** SBOM generation, CVE/dependency scanning, Camel
  secure-by-default checks, secrets scanning — wired into the ADLC Verify gate
  (Ch.45).
- **Podman/minikube fit:** dev loop on **podman** (lgtm-podman-stack); CI builds
  images; **minikube** (lgtm-minikube-stack) is the deployment substrate for Parts
  9–10 only. GitHub Actions is the CI host (lgtm-github). Decision recorded as a
  `DRQ-NNN`: **no Docker inheritance** despite DataMesh's Docker choice — podman
  throughout, minikube for k8s (per project decisions).

---

## I. Presentation Plan + Deck Outline

Rebuild the original 75-slide deck with **lgtm-presentation** (Red Hat-branded
pptx), modernized to match the book and the 2026 gaps. The deck is a *companion*,
not a reduction — it mirrors the book's parts.

Proposed deck outline (one section per book part, ~40–55 slides):
1. **Title + Why Modernize** (Part 1) — strategies, when-not-to, the 2×2.
2. **The ADLC** (Part 2) — the phase diagram, agents/tools/gates, the ledger — *the
   new centerpiece absent from the original deck.*
3. **Meet the Monolith** (Part 3) — domain, smells, the oracle.
4. **Finding the Seams** (Part 4) — DDD/event-storming/coupling.
5. **Strangler Fig in Practice** (Part 5) — the proxy, the six-step sequence.
6. **Data & Consistency** (Part 6) — outbox/CDC/CQRS/ACID→ACD.
7. **Sagas & Resilience** (Part 7) — choreography vs orchestration, failure modes.
8. **Communication, Contracts, Chassis** (Part 8).
9. **Operating & CI/CD** (Parts 9–10) — deployments, mesh, observability, pipelines.
10. **Reflection** (Part 11) — pattern map, chassis, what's next.

Reuses the original deck's strong diagrams (strangler variants, saga worked example,
chassis hexagon, Richardson map) regenerated via **lgtm-diagram-generator** to the
house style; replaces dated tooling slides (Netflix OSS) with Kubernetes-native /
OTel / Quarkus / Camel equivalents. Deck is built in the **final iteration** once the
book content is stable (so the deck never drifts from the chapters).

---

## J. Iteration / Release Plan

**Round-1 = planning only.** No repo, no branch, no code, no push until the user
approves this plan (honoring "never push upstream without permission"). Each
subsequent iteration is independently shippable (`_rNN.x` tarball via lgtm-github)
and leaves the site buildable.

| Iter | Scope | Resumable boundary |
|---|---|---|
| **r01** | **This plan** + PRD + decisions.md seed + CLAUDE.md skeleton. *Planning only.* | Approved plan |
| r02 | Site scaffold (lgtm-jekyll), Part 0 + Part 1, **the reference monolith** (Part 3 code) + the Newman oracle (Ch.14), CI baseline. | Monolith runs; oracle green in CI |
| r03 | Part 2 (the ADLC) + Part 4 (Finding the Seams). | ADLC taught; seams mapped |
| r04 | Part 5 (strangler + extractions 1–2: review, notification). | 2 services extracted, oracle green |
| r05 | Part 6 (data: inventory extraction, CDC, outbox, CQRS). | Inventory extracted; data split |
| r06 | Part 7 (payment + shipping sagas, resilience). | Choreography + orchestration done |
| r07 | Part 8 (communication, contracts, order extraction, gateway). | Monolith decommissioned |
| r08 | Part 9 (deployments, mesh, observability on minikube). | Running on minikube |
| r09 | Part 10 (CI/CD for migration, progressive delivery, security). | Full pipeline |
| r10 | Part 11 + appendices finalize + **the deck** + polish/validation pass. | Book + deck shippable |

**Proposed: 9 build iterations (r02–r10) after r01 planning.**

---

## K. Execution Mapping

Per-stream skill + subagent tier, with collision risks called out.

| Stream | Skill | Tier (lgtm-relay) | Parallelizable? |
|---|---|---|---|
| Chapter prose | lgtm-tutorial | Opus plan → Sonnet write → Opus validate | **Yes** — distinct `_docs/*.md` can be forked in parallel within an iteration |
| Diagrams | lgtm-diagram-generator | Sonnet | Yes, but the catalogue (`assets/diagrams/README.md`) is single-writer |
| Monolith code | (Spring, manual) + quarkus-agent migrate skill for later | Sonnet execute | Serialize within the one Maven reactor |
| Quarkus services | lgtm-quarkus + quarkus-agent MCP | Sonnet execute | Serialize reactor edits; parallel across *independent* new modules only |
| Camel routes | lgtm-camel + camel-mcp MCP | Sonnet execute | Serialize within a module |
| Dev infra | lgtm-podman-stack | Sonnet | Single-writer compose files |
| K8s substrate | lgtm-minikube-stack | Sonnet | Parts 9–10 only |
| Deck | lgtm-presentation | Opus plan → Sonnet build | Final iteration only |
| Repo/release | lgtm-github | — | Per-iteration, serial |

**File-collision risks (single-writer — serialize writes):** `_config.yml`,
`assets/css/site.css`, the Maven reactor `pom.xml`, the shared `domain-model` /
`contracts` modules, `assets/diagrams/README.md` catalogue, `_example_pages/index.md`,
and the three `_plans/*` ledger files. **Safe to parallelize:** independent
`_docs/*.md` chapter prose, independent diagram SVG pairs, and independent new
service modules that do not touch the shared modules in the same change.

**Overall orchestration:** lgtm-relay governs every non-trivial stream (plan→execute
→validate), gated on user review of each iteration's plan before code — the ADLC the
book teaches is the ADLC the book is built with.

---

## L. Acceptance Criteria

### L.1 Round-1 planning (this deliverable)
- [ ] Plan written to the output path and covers Sections A–M.
- [ ] Learning arc designed first and explicitly drives the structure.
- [ ] Full ordered chapter list (title + synopsis) + appendix verdicts.
- [ ] Pattern-coverage matrix accounts for **every** deck pattern (mapped / demoted
      with reason / extended as new).
- [ ] Monolith design, decomposition roadmap, ADLC design, testing, CI/CD, deck,
      iteration plan, execution mapping, risks all specified.
- [ ] Standing constraints honored; **nothing built or pushed**; open questions
      surfaced for the user.

### L.2 Eventual build
- [ ] Every chapter ≥ 2000 words excluding code/diagrams; professional bar
      (datamesh-reference-arch-quarkus quality).
- [ ] Reference monolith runs; the Newman oracle is green.
- [ ] Each extracted service passes the oracle (behavior-equivalent) before its
      monolith module is decommissioned.
- [ ] Every chapter has a runnable example + a verification-status footer naming the
      tests/demos run.
- [ ] Every deck pattern appears in a chapter or appendix per the matrix.
- [ ] The ADLC is demonstrated ("ADLC in Action") in all six migration chapters and
      operated to build the book (the `_plans/` ledger is real).
- [ ] Full system runs on minikube; CI runs the oracle + deploy pipeline.
- [ ] Deck rebuilt and consistent with the final chapters.
- [ ] Security-by-design evidence (SBOM/CVE scan/secure-by-default) in CI.

---

## M. Risks + Detection

| Risk | Impact | Detection | Mitigation |
|---|---|---|---|
| **Scope sprawl** — book tries to cover everything in the deck's gaps | Never ships; incoherent | Iteration review vs this plan's matrix | Hold to the matrix; demotions are decisions, not omissions; scope-discipline memory |
| **Monolith too big/small** — unbelievable or unteachable | Weak through-line | Peer read of Part 3; file-count vs datamesh bar | Match the 3–9-files-per-module scale; smells must each map to a later chapter |
| **Oracle gaps** — Newman suite misses behavior, extractions silently diverge | Broken "equivalence" promise | CI diff monolith vs service; coverage review | Capture the oracle in Ch.14 *before* any extraction; expand per extraction |
| **ADLC feels like narration, not instruction** | Reader can't DO it | Beta-reader can they run the loop? | Ch.10 hands-on loop; fixed "ADLC in Action" format; the repo's own ledger as proof |
| **Toolchain drift** — podman vs Docker, Dev Services image-tag mismatch | Examples don't run | `mvn verify` in CI; wire-compat IT | Record `DRQ` for podman-throughout; pin image tags once in `.env` (inherited gotcha) |
| **Reuse mismatch** — adapted chapters keep source book's running example | Narrative incoherence | Editorial pass per adapted chapter | Retarget every reused example to the shipping monolith before merge |
| **Parallel-authoring collisions** on shared files | Lost work, broken build | Git status; build break in CI | Single-writer list in Section K; serialize those files |
| **Pattern-before-motivation** — a pattern taught before its pain | Kills "why now" | Arc review per iteration | Extraction sequence dictates pattern order; matrix is a checklist, not an outline |
| **Premature upstream push** | Violates standing constraint | Pre-commit review | Round-1 planning only; repo/branch/push only on explicit approval |
| **Deck drift** from chapters | Companion contradicts book | Build deck last | Deck in final iteration only, regenerated from stable chapters |

---

## Major Open Questions for the User
1. **Accent color** for the site (reuse-map suggests blue or green to stay distinct
   from the amber/red/teal siblings) — confirm the choice.
2. **Keep the monolith permanently** in-repo as `examples/00-monolith/` (recommended,
   as the living "before" and the oracle's referent) vs delete at decommission?
3. **Live MCP vs narrated** in the ADLC chapters — do we run camel-mcp / quarkus-agent
   live against the examples (stronger, heavier) or narrate the loop with captured
   output? Recommendation: live where cheap, captured for long-running steps.
4. **Iteration count:** confirm **9 build iterations (r02–r10)** after r01 planning,
   or compress (e.g., combine Parts 9+10).

---

*Round-1 planning only. No repository, branch, code, or push will be created until
the user approves this plan.*
