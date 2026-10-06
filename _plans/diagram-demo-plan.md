---
title: "Diagram + Demo Alignment Plan"
status: draft-for-review
author: opus-planner
scope: authored chapters 00-26 (ch.27-32 are "Coming soon" stubs — EXCLUDED)
---

# Diagram + Demo Alignment Plan

A content-aligned review pass that fills the figure gaps in the tutorial and
audits the demos against the final (post-decommission) topology. **Plan only —
no diagrams are created and no chapters/demos are edited here.** Execution runs
through the relay after user review (§5).

## Guiding rules (from the user)

- Multiple diagrams per page are fine, especially early on (architecture,
  outcome, before/after). Embed several individual figures per page rather than
  one combined image.
- **Never combine figures into one image** unless the figure *is* a required
  side-by-side comparison (orchestration-vs-choreography, before/after,
  leaked-entity-vs-contract, dual-write-vs-outbox). Those are called out
  explicitly below; everything else is an individual figure.
- Diagrams are targeted to THIS project. Where a sibling repo has a usable
  shape/style, **adapt** it (re-target names, strip codenames) — never copy.
- House style is fixed: `scripts/generate_diagram.py` compiler + a per-diagram
  `name.py` spec importing `assets/diagrams/_lib.py`, paired `name.svg` +
  `name.excalidraw`, `'Red Hat Text'`, white background, grey `#555` arrows with
  the migration-green accent arrow, accent green `#3d7a4e` / edge-highlight
  `#2f5f3d`. Names are **descriptive kebab-case, no numeric prefix**. Embed with
  the Jekyll include (see below). Append one catalogue row per figure to
  `assets/diagrams/README.md` (single writer, serialized — r02-plan §K).

{% raw %}
```
{% include excalidraw.html file="NAME" alt="..." caption="Figure N.M — ..." %}
```
{% endraw %}

## Current state (verified)

**21 figures exist.** Distribution: ch.05 has 4; ch.08 and ch.12 have 1 hero
figure each; the extraction chapters (14/15/17/19/23/24/26) carry 2-3 each. The
`transactional-outbox-sequence` asset is embedded in **ch.17 (Fig 17.2), not
ch.20** — ch.20 embeds nothing. **Chapters with ZERO figures: 00, 01, 02, 03,
04, 06, 07, 09, 10, 11, 13, 16, 18, 20, 21, 22, 25.** The conceptual/
architecture/before-after chapters are the gap; the extraction chapters are
well served.

**Final topology** (the end-state the per-chapter map must respect): the
strangler-proxy (`:8888`) is now a permanent REST + GraphQL **edge router**, all
`strangler.*.enabled` flags retired. Six extracted Quarkus services (review
`:8081`, notification `:8083`, inventory `:8084`/gRPC `:9004`, payment `:8085`,
order `:8087`, shipping `:8088`) + the GraphQL aggregation gateway (`:8090`) =
seven app processes behind the edge router. The Spring **monolith is
decommissioned** from the running topology and kept **frozen in-repo**
(`examples/00-monolith`, branch `reference/monolith-before`, tag `v0-monolith`)
as the "before" picture and the golden baseline the contract suite was captured
from. Shared infra (Postgres/Kafka/Kafka-Connect/LGTM) runs via `podman compose`
(`compose.yaml`); the monolith was never a compose service.

---

## 1. Per-chapter diagram map (authored chapters 00-26)

Legend for each proposed figure: **[NEW]** author from scratch · **[REUSE]**
existing asset, embed here · **[ADAPT:repo]** adapt a sibling shape · **[SIDE]**
the rare *justified* side-by-side comparison (one image) · **[multi]** placed
several-per-page with sibling figures. Category tags: **ARCH** (architecture/
overview) · **B/A** (before→after/outcome) · **FUNC** (specific-functionality).

### Part 0-1 — Setting Up / Why Modernize

**Ch.00 Introduction** — *embeds: none.*
- `monolith-to-microservices-outcome` — **B/A [NEW][SIDE][ADAPT: datamesh `01-monolith-to-mesh`]** — the whole arc: LEFT one JVM + one shared schema; RIGHT six services + owned data + gateway + edge router + mesh. The headline figure of the book. Top of page.
- `extraction-sequence-ladder` — **ARCH [NEW]** — rising-difficulty staircase review→notification→inventory→payment→shipping→order, each rung tagged with its chapter. Mid-page.
- `equivalence-gate-loop` — **FUNC [NEW]** — one unchanged suite pointed at the monolith vs. an extracted service; the gate blocks decommission. (Teaser; the full lifecycle lives in ch.10.) Lower on page. *Secondary.*

**Ch.01 Prerequisites / Ch.02 Project Ledger** — *embeds: none.* Low priority.
- `toolchain-and-repo-layout` — **ARCH [NEW]** (ch.01) — SDKMAN/JDK25/Maven/Quarkus+Camel CLI + podman stack + repo tree. *Optional.*
- `ledger-files-to-adlc-phases` — **FUNC [NEW]** (ch.02) — decisions.md/build-plan.md/reconciliation.md mapped to Frame…Reconcile; how an interrupted session resumes. *Optional.*

**Ch.03 Modernization as Engineering** — *embeds: none.*
- `microservice-traits` — **ARCH [NEW]** — the ten traits as a labelled grid/radial (currently a wall of prose). Top.
- `rewrite-vs-strangler-risk` — **FUNC [NEW][SIDE]** — one big irreversible bet vs. many small reversible steps (risk-shape contrast).
- `migration-metrics-review` — **B/A [NEW]** — Phase A / Phase B / native startup + RSS as a simple comparison bar figure (~30× startup, ~4× memory). **Shared asset, reused in ch.07.** (See decision D3 — charting approach.)
- `coupling-cost-compounds` — **FUNC [NEW]** — cost-over-time curve (3 FKs = a weekend vs. 30 FKs = a quarter). *Secondary; see D3.*

**Ch.04 Strategies & Assessment** — *embeds: none.*
- `modernization-evolution-arc` — **ARCH/B/A [NEW][ADAPT: datamesh `01-architecture-evolution`]** — Monolith→SOA/ESB→MS1.0→Cloud-Native timeline + the "pattern persisted, implementation moved" mapping (Eureka→K8s Service, Hystrix→SmallRye, Zuul→edge router, Zipkin→OTel). Top.
- `migration-strategy-spectrum` — **ARCH [NEW]** — the 7 Rs plotted on risk-taken-at-once vs. time-to-value.
- `strategy-value-change-quadrant` — **FUNC [NEW]** — the strategic-value × change-frequency 2×2, with the book's monolith placed.
- `strangler-fig-variants` — **FUNC [NEW][SIDE]** — Proxy / Redirection / Shared-DB, three panels in one comparison figure (variants ARE a comparison → justified side-by-side). *See D4.*

### Part 2 — The ADLC

**Ch.05 SDLC→ADLC** — *embeds 4 (sdlc-vs-adlc, adlc-tooling, adlc-local-vs-hosted, agentic-sdlc-to-adlc). Saturated — no new figures.* (The relay sequence belongs in ch.06.)

**Ch.06 Agents, Skills & MCP** — *embeds: none.*
- `plan-execute-validate-relay` — **ARCH [NEW]** — the three-tier sequential relay with the adversarial validate role and the two human gates. The chapter's central concept; currently prose-only. Top.
- `structural-vs-behavioral-gates` — **FUNC [NEW]** — Camel-MCP structural gate vs. equivalence behavioral gate (which layer catches which mistake).
- `building-blocks-per-phase` — **ARCH [NEW]** — phase × (agent / skill / MCP) matrix. *Secondary.*

**Ch.07 The ADLC Safety Net** — *embeds: none. Pivotal proof chapter.*
- `shared-table-routing-defect` — **FUNC [NEW][SIDE]** — the book's most diagram-hungry moment: request→proxy→(monolith OR review-service)→**same `reviews` table**, so a black-box suite can't tell a routing bug from a correct cutover; then the monolith-down differential test that disambiguates. **Top priority net-new.** Top.
- `adlc-safety-net-layers` — **ARCH [NEW]** — three-layer defense (automated suite / adversarial verification / two human gates), "remove any one layer and a specific bug gets through."
- `migration-metrics-review` — **B/A [REUSE from ch.03]** — the Phase A/B/native bar figure, re-embedded here where the two bugs' metrics land.

### Part 3 — The Reference Monolith

**Ch.08 Designing the Monolith** — *embeds 1 (monolith-architecture 8.1).*
- `place-order-transaction-sequence` — **FUNC [NEW]** — the single-`@Transactional` call chain across five contexts (customer→reserve→save→charge→confirm→dispatch→notify) with the rollback-on-decline arrow. The structural heart of the book; reused conceptually by ch.09/12/22/23/24. **High priority.**
- `monolith-shared-schema-er` — **FUNC/ARCH [NEW]** — ER of the 7 tables with cross-context FKs colour-coded by owning context (orders→customers, order_items→inventory_items, payments/shipments/notifications→orders, reviews→customers+inventory_items). Makes Smell 1 visceral; reused by ch.09/11/13/18. **High priority.**

**Ch.09 The Deliberate Smells** — *embeds: none.*
- `smell-map` — **ARCH [NEW]** — the 5 remaining smells overlaid on the monolith architecture, each annotated with its curing chapter (the visual form of SMELLS.md). **High priority.** Top.
- `smell-cure-dag` — **FUNC [NEW]** — dependency DAG of smell→cure→prerequisite (why order is cut last). *Secondary.*
- Per-smell mini B/A schematics are intentionally **deferred** to the chapters that cure each smell (16/17/18/22-24) to avoid duplicating later figures.

**Ch.10 Testing the Monolith** — *embeds: none.*
- `test-pyramid` — **ARCH [NEW]** — three tiers with counts and what each proves. Top.
- `whitebox-pyramid-vs-blackbox-equivalence` — **FUNC [NEW][SIDE]** — pyramid calling Java internals vs. the Newman suite seeing only HTTP, re-pointable monolith→Quarkus via `baseUrl`. The chapter's conceptual pivot.
- `equivalence-gate-lifecycle` — **FUNC [NEW]** — the one collection re-run across monolith→each extraction, folders added never rewritten. *Secondary (overlaps ch.00 teaser).*

### Part 4 — Finding the Seams

**Ch.11 DDD & Hexagonal** — *embeds: none.*
- `context-map` — **ARCH [NEW][ADAPT: datamesh topology style]** — the 6 contexts with relationship types on each edge (shared-kernel-with-no-edges, conformist order→inventory, the missing ACL). **High priority.** Top.
- `hexagonal-ports-adapters` — **FUNC [NEW]** — domain core + inbound/outbound ports + driving adapters (REST/gRPC/consumer) + driven adapters (repo/publisher), dependencies pointing inward. **High priority.**
- `review-adapter-swap` — **B/A [NEW][SIDE]** — Phase A→B: adapters change (Spring MVC→JAX-RS, Spring Data→Panache), domain core unchanged.
- `subdomain-classification-grid` — **ARCH [NEW]** — core/supporting/generic × the 6 contexts, mapping onto extraction order. *Secondary.*

**Ch.12 Event Storming** — *embeds 1 (event-storm-checkout 12.1).*
- `event-storming-legend` — **FUNC [NEW]** — the 7 sticky-note colour grammar as a legend card. *Secondary.*
- `storm-wall-to-backlog` — **B/A [NEW]** — event-boundary → extraction-chapter → mechanism (CDC/saga/outbox) mapping. *Secondary (12.1 covers the two readings).*

**Ch.13 Coupling & the Modular Monolith** — *embeds: none.*
- `coupling-ladder` — **FUNC [NEW]** — the Constantine ladder (content→common→control→stamp→data→message→API) with each live monolith example pinned to its rung. **High priority.** Top.
- `context-dependency-graph-cace` — **ARCH [NEW]** — directed dependency graph annotated with Ca/Ce per context, sorted into the extraction order ("a sorted list, not a narrative choice"). **High priority.**
- `distributed-monolith-trap` — **FUNC [NEW]** — Khononov strength×distance×volatility: distance up, strength not down. *Secondary.*
- `modular-monolith-spectrum` — **B/A [NEW]** — one shared schema → modular monolith (midpoint) → one-DB-per-service. *Secondary.*

### Part 5 — The Strangler Fig in Practice

**Ch.14 Strangler Fig Pattern** — *embeds 1 (strangler-review-extraction 14.1).*
- `strangler-four-moves` — **FUNC [NEW]** — intercept→route→replace→retire lifecycle (ordering is the lesson; retiring early = big-bang in disguise). Medium.
- (The reversibility-window bug is better served by ch.07's `shared-table-routing-defect`; cross-reference, do not duplicate.)

**Ch.15 Extraction 1 — Review** — *embeds 2 (strangler-review-extraction 15.1 reused, review-two-phase-migration 15.2). Well served — no new.* (Optional native-image reflection-failure path; low priority, skip.)

**Ch.16 Content-Based Routing & ACL** — *embeds: none. Five patterns, zero figures — top gap. ADAPT from the EIP-Camel repo.*
- `cbr-vs-acl-seam` — **ARCH [NEW][ADAPT: EIP `content-based-router` stencil]** — CBR picks *which* backend answers; ACL decides *what is safe to carry back* — orthogonal concerns at the same physical seam. Top.
- `acl-enricher-translator-flow` — **FUNC [NEW][ADAPT: EIP `12-transformation-flow` + `content-enricher`/`message-translator` stencils]** — exchange arrives knowing only a SKU → `enrich()` fetches from the flag-selected backend → `AggregationStrategy` translates reply→`StockDto` → merges; leaves knowing the stock fact.
- `leaked-entity-vs-stockdto` — **B/A [NEW][SIDE]** — raw `@Entity` (compile-time dependency on inventory) vs. `StockDto` contract crossing the seam.
- `decorating-collaborator` — **FUNC [NEW]** — wrapper beside the legacy call path, touching nothing inside. *Secondary.*

**Ch.17 Extraction 2 — Notification** — *embeds 2 (notification-async-topology 17.1, transactional-outbox-sequence 17.2). Well served — no new.* (Optional RED-on-kill/GREEN-on-restart proof-pair; secondary, skip.)

### Part 6 — Data Across the Seam

**Ch.18 From Shared Data to Owned Data** — *embeds: none.*
- `monolith-shared-schema-er` — **[REUSE from ch.08]** — re-embed the FK web here ("six tables, five contexts, one schema, eight crossing seams"). Quick win.
- `shared-db-to-database-per-service` — **B/A [NEW][SIDE]** — one shared DB with an FK join → two owned DBs with a snapshot copy + an event feed. **High-ish.**
- `fk-to-snapshot` — **FUNC [NEW]** — OrderItem FK → point-in-time snapshot; truth-as-of-the-call (gRPC) vs. truth-as-of-the-moment (snapshot). Medium.

**Ch.19 CDC & Extraction 3 — Inventory** — *embeds 2 (inventory-extraction-topology 19.1, inventory-reserve-compensation-sequence 19.2). Well served.*
- `cdc-debezium-backfill-pipeline` — **FUNC [NEW]** — WAL→Debezium connector→Kafka→`InventoryCdcConsumer`, with the replication-slot/publication lifecycle (the CDC mechanism itself, distinct from 19.1's steady state). Medium.

**Ch.20 The Outbox Pattern, Done Right** — *embeds: none. Richest outbox content in the book, entirely figure-less — HIGHEST single-chapter gap.*
- `transactional-outbox-sequence` — **[REUSE from ch.17]** — re-embed here (the quick win; the same reuse pattern as strangler-review-extraction across ch.14/15). Top.
- `outbox-four-piece-pipeline` — **ARCH [NEW]** — row → write-in-txn → relay poll → broker → idempotent consumer ("four pieces carry this pattern end to end"). **High.**
- `dual-write-vs-outbox` — **B/A [NEW][SIDE]** — two independent writes to two systems over two network paths vs. one commit + a decoupled at-least-once publish. **High.**
- `publish-then-stamp-crash-window` — **FUNC [NEW]** — publish-then-stamp ordering; the crash window that makes reversing the two lines a silent lost event. Medium.
- `partition-by-aggregate-key` — **FUNC [NEW]** — null scatters / constant serializes / aggregateId is correct. Medium.
- `polling-vs-log-based-relay` — **FUNC [NEW][SIDE]** — polling relay vs. log-based CDC relay (operational weight; DRQ-034 polling-first default). Medium.

**Ch.21 Event Sourcing & CQRS** — *embeds: none.*
- `cqrs-write-read-split` — **ARCH [NEW]** — write model → event → read model(s), with notification-service labelled as the concrete instance. High-ish. Top.
- `cqrs-lite-vs-event-sourcing` — **B/A [NEW][SIDE]** — event-fed read model (the book's choice) vs. log-is-the-record + replay. High-ish.
- `log-as-derived-data` — **FUNC [NEW]** — the log as a totally-ordered fact stream; every view/index/cache a deterministic function of it. *Secondary.*

**Ch.22 ACID → ACD** — *embeds: none. The conceptual spine — HIGH.*
- `acid-to-acd` — **B/A [NEW][SIDE]** — one `@Transactional`/one WAL spanning four contexts (ACID, free rollback) → four commits on four DBs + saga compensation (ACD, isolation dropped). **High.** Top.
- `two-phase-commit-rejected` — **FUNC [NEW]** — 2PC prepare/commit phases + its three failure modes (coordinator SPOF, blocking locks, re-centralized ownership) — why it is rejected. Medium.
- `three-extractions-three-answers` — **ARCH [NEW]** — the calibration matrix: notification (full async, no compensation) / inventory (sync reserve — overselling unrepairable) / payment+shipping (full saga). Medium.

### Part 7 — Coordinating Across Services

**Ch.23 Saga & Extraction 4 — Payment** — *embeds 3 (payment-choreographed-saga-sequence 23.1, checkout-sync-async-contract 23.2, payment-compensation-choreography 23.3). Fully served — no new.*

**Ch.24 Orchestrated Sagas & Extraction 5 — Shipping** — *embeds 3 (shipping-orchestrated-saga-sequence 24.1, orchestration-vs-choreography 24.2, shipping-saga-compensation-flow 24.3). Fully served — no new.*

**Ch.25 Failure Modes & the Resilience Chassis** — *embeds: none.*
- `circuit-breaker-states` — **FUNC [NEW]** — closed/open/half-open state machine (generic; no strong sibling — hand-draw in house style). **High.** Top.
- `resilience-scorecard` — **ARCH [NEW]** — implemented-and-proven vs. deferred-by-design matrix (the chapter literally structures itself as a scorecard). Medium.
- `timeout-three-outcomes` — **FUNC [NEW]** — return / throw / never-return across the in-JVM→gRPC boundary; the cascading-failure (thread-pool exhaustion) path. Medium.

### Part 8 — Communication & Contracts (authored portion)

**Ch.26 Communication & Extraction 6 — Order + Gateway** — *embeds 3 (strangler-completes-final-topology 26.1, order-cqrs-split 26.2, graphql-aggregation-gateway 26.3). Fully served — no new.* (Optional god-service placeOrder → single-context command-handler B/A; low priority, skip.)

**Ch.27-32** — "Coming soon" stubs, **EXCLUDED** from this pass (figures authored with the chapters). See §6 for the sibling sources earmarked for them.

---

## 2. Prioritized gap list (highest value first)

The user emphasized **early architecture + before/after**, so those dominate the
top. Top 10 are the recommended first wave.

| # | Chapter | Figure(s) | Why first |
|---|---|---|---|
| 1 | 00 | `monolith-to-microservices-outcome` (+ `extraction-sequence-ladder`) | The book's headline before→after arc; the single most-requested figure. |
| 2 | 20 | `transactional-outbox-sequence` [REUSE] + `outbox-four-piece-pipeline` + `dual-write-vs-outbox` | Richest content, zero figures; the reuse is a true quick win. |
| 3 | 08 | `place-order-transaction-sequence` + `monolith-shared-schema-er` | Structural heart; both reused across 09/11/12/13/18/22-24. |
| 4 | 09 | `smell-map` | The planted-smells overlay; anchors every later "which chapter cures it." |
| 5 | 16 | `cbr-vs-acl-seam` + `acl-enricher-translator-flow` + `leaked-entity-vs-stockdto` | Five named patterns, zero figures; ADAPT from EIP repo. |
| 6 | 22 | `acid-to-acd` | The conceptual spine of Parts 6-7, before→after. |
| 7 | 04 | `modernization-evolution-arc` + `migration-strategy-spectrum` + `strategy-value-change-quadrant` | Early migration-strategy chapter; architecture + outcome. |
| 8 | 11 | `context-map` + `hexagonal-ports-adapters` | DDD bounded contexts + hexagonal — load-bearing concepts, pure diagrams. |
| 9 | 13 | `coupling-ladder` + `context-dependency-graph-cace` | Coupling/modular-monolith — entirely about rankable/graphable structure. |
| 10 | 18 | `shared-db-to-database-per-service` (+ reuse ch.08 ER) | Shared DB → database-per-service, before→after. |
| 11 | 07 | `shared-table-routing-defect` + `adlc-safety-net-layers` | The book's pivotal proof moment; currently all prose. |
| 12 | 10 | `test-pyramid` + `whitebox-pyramid-vs-blackbox-equivalence` | Test tiers + the white/black-box pivot. |
| 13 | 25 | `circuit-breaker-states` (+ scorecard) | Resilience patterns. |
| 14 | 06 | `plan-execute-validate-relay` | The agent-machinery chapter has no picture of the machinery. |
| 15 | 21 | `cqrs-write-read-split` + `cqrs-lite-vs-event-sourcing` | ES/CQRS comparison. |

Lower tiers: ch.03 (traits/metrics/cost), ch.12 supplements, ch.14
four-moves, ch.19 CDC pipeline, ch.01/02 setup figures.

---

## 3. Sibling-adaptation table

Shared spine across siblings: the **Python `generate_diagram.py` spec engine**
(upstream in the `lgtm-diagram-generator` skill; EIP and observability vendor a
copy; optimizing-java imports the skill copy). Datamesh is a separate JS lineage
(`svglib.js`, Helvetica, multi-colour) — adapt its *layouts* as inspiration, not
its generator. **Always re-target to this project's green accent `#3d7a4e` and
`'Red Hat Text'`; strip all codenames before reuse.**

| Target chapter(s) | Sibling source | Adapt (shape/style) | Re-target to | Do NOT carry over |
|---|---|---|---|---|
| 16 | `enterprise-integration-patterns-with-camel`: `eip-stencils/svg/` (73 icons: content-based-router, content-enricher, message-translator), `09-routing-patterns`, `12-transformation-flow` | CBR `choice()` fan + enricher→translator pipeline shape; inline the stencil icons | `InventoryAclRoute`, `StockDto`, `strangler.inventory.enabled`, order↔inventory seam | amber accent (`#e8870c`); loan-broker/bond-trading/FedEx/USPS names; `NN-` numeric prefix |
| 00, 04, 11, 13 | `datamesh-reference-arch-quarkus`: `01-monolith-to-mesh`, `01-architecture-evolution`, `08-reference-architecture`, `13-orchestration-styles` | monolith→mesh before/after layout; evolution timeline; topology/context-map composition | this book's 6 contexts + edge router + gateway; the 7-Rs / evolution arc | Helvetica font + multi-colour palette (use house green); data-mesh domain; `patterncatalyst`/`capstone.order.v1` codenames; measured Quarkus-vs-Spring numbers |
| 03, 07 | `optimizing-java/spring-boot-optimization/diagrams`: `08-spring-boot-startup-breakdown` | bar-comparison layout for the Phase A/B/native metrics | Review-extraction startup/RSS numbers | the **dark theme** (keep light house style); Spring-vs-Quarkus tuning numbers; order-service naming |
| 25 | `optimizing-java`: `06-anti-patterns-vs-fixes` | anti-pattern→fix two-column shape (for the scorecard) | the resilience scorecard rows | dark theme; perf-specific content |
| **Deferred** 27 | `optimizing-java` perf set (JIT/AOT/GC/startup/native) | — | Quarkus chassis chapter when authored | (plan later) |
| **Deferred** 30 | `observability-python-otel-lgtm`: `fig-02-otel-data-path`, `fig-03-service-topology`, `fig-07-context-propagation` | three-signals/OTel pipeline/trace-propagation | this book's mesh + LGTM on minikube | RED accent + `fig-` naming; Python/OTel-SDK specifics; Loki/Grafana/Tempo/Mimir product-name framing |

The EIP `eip-stencils/svg/` 73-icon library is the one genuinely **portable
asset set** (if the generator can inline them) — see decision D2.

---

## 4. Demo review

**Inventory:** `demos/demo-equivalence.sh` (the gate), `demo-cutover.sh` (Review,
ch.15), `demo-payment-cutover.sh` (ch.23), `demo-shipping-cutover.sh` (ch.24),
`demo-order-cutover.sh` (ch.26), plus `demos/lib/run-order-newman.js` and
`run-shipping-newman.js` (in-memory collection-variable patching for the
gate-variable scope gotcha).

**Alignment with the final topology — findings:**

1. **No demo is broken, but every cutover demo deliberately runs the monolith.**
   Each one reconstructs an *intermediate* migration state (cutover +
   reversibility + negative checks) and so brings up `examples/00-monolith`
   on `:8080`. That is pedagogically correct — the whole point is to show the
   strangler cutover and the reversibility window against the monolith — and the
   monolith is kept frozen-but-runnable in-repo, so these demos still run. They
   are **staged snapshots, not stale.** Recommendation: add a one-line header
   note to each that it reconstructs an intermediate state and depends on the
   frozen `examples/00-monolith` (break-glass `reference/monolith-before`).
2. **`demo-equivalence.sh` defaults `baseUrl` to `:8080`** (the monolith). Post-
   decommission the monolith is no longer in the running topology, so the
   default now points at a process a fresh user must bring up on purpose. It is
   still valid as the *golden-baseline referent*; just document that the default
   requires the frozen monolith, and that the live default for the finished
   system is the edge router `:8888`.
3. **Coverage gaps:** there is **no** `demo-notification-cutover.sh` (ch.17) and
   **no** `demo-inventory-cutover.sh` (ch.19) — the first two extractions have no
   dedicated cutover demo. Most importantly, **there is no final-topology demo**:
   nothing runs the end-state (six services + gateway + edge router, monolith
   unwired) that the book culminates in. This is the biggest demo gap.
4. **Two known CI/dev gotchas (both already handled in-code, keep in the
   walkthrough narration):**
   - *Fresh-DB seed retarget* — `burn_in_past_historical_max` burns a service's
     order-id sequence past the historical high-water mark to avoid colliding
     with idempotency guards keyed on the monolith's shared sequence, and
     `order_service.customers` is forward-filled (DRQ-073, store starts empty).
   - *Consumer-group waits* — `wait_for_order_consumer_caught_up` polls for
     lag=0 after a (re)start, and `stop_named` does a graceful SIGTERM so the
     Kafka client sends a `LeaveGroupRequest` (a SIGKILL stalls the next
     consumer's rebalance for `session.timeout.ms`).

**Proposed "walk the demos" sequence (live walkthrough, chapter order):**

1. `demos/demo-equivalence.sh http://localhost:8080` — establish the golden
   baseline against the frozen monolith; this is the contract suite everything
   is measured against (ch.10).
2. `demos/demo-cutover.sh` — Review extraction: content-based routing
   discriminates, the monolith 404s on `/api/reviews`, suite green through the
   proxy (ch.14/15).
3. *(gap)* notification (ch.17) + inventory (ch.19): no dedicated demo today —
   narrate via the equivalence suite pointed at the proxy, **or** add the two
   demos below.
4. `demos/demo-payment-cutover.sh` — choreographed-saga cutover + reversibility +
   negative check + net-zero decline (ch.23).
5. `demos/demo-shipping-cutover.sh` — orchestrated-saga cutover + reversibility +
   two negative checks + net-zero SHIP-FAIL (ch.24).
6. `demos/demo-order-cutover.sh` — last extraction + GraphQL gateway + CQRS
   non-vacuity + the last reversibility before the irreversible decommission
   (ch.26).
7. *(gap — RECOMMEND NEW)* `demos/demo-final-topology.sh` — bring up the six
   services + gateway + edge router **without** the monolith; run the contract
   suite through `:8888` (now a pure edge router, all flags retired) + a GraphQL
   aggregation query; assert the monolith is unwired (no route reaches `:8080`).
   The capstone the book's final state deserves and currently lacks.

**Recommended demo work (separate from the diagram pass; confirm appetite):**
add `demo-final-topology.sh` (strongly recommended); optionally add
`demo-notification-cutover.sh` and `demo-inventory-cutover.sh` for completeness;
add the header notes and the `baseUrl` documentation from findings 1-2.

---

## 5. Execution shape (via the relay)

Run as a standard plan→generate→validate relay (`lgtm-relay`), parallelized by
chapter-group. **This document is the plan phase; it gates on user review before
anything is generated.**

**Phase A — generate (parallel `lgtm-diagram-generator` agents, one per
chapter-group, disjoint spec sets).** Each agent authors its chapters' `name.py`
specs and runs `scripts/generate_diagram.py` to emit paired `name.svg` +
`name.excalidraw` into `assets/diagrams/`. Suggested groups (single-writer per
spec; the `README.md` catalogue append is serialized to **one** writer at the
end of the phase, per r02-plan §K):
- G1 — Part 0-1: ch.00, 03, 04
- G2 — Part 2: ch.06, 07
- G3 — Part 3: ch.08 (add), 09, 10
- G4 — Part 4: ch.11, 13 (+ ch.12 supplements)
- G5 — Part 5: ch.14 (add), 16 (EIP-adapted)
- G6 — Part 6: ch.18, 19 (add), 20, 21, 22
- G7 — Part 7: ch.25
Rules enforced in this phase: green `#3d7a4e` accent; **do not combine figures**
except the explicit `[SIDE]` figures; adapt-not-copy for `[ADAPT]`/`[REUSE]`
figures; descriptive kebab-case names.

**Phase B — embed (Opus-gated).** For each chapter, insert the Jekyll includes
at the prose moment each figure serves (multiple-per-page is expected), with
`lgtm-professional-voice` applied to every `caption=` and `alt=` string. Append
one catalogue row per new figure to `assets/diagrams/README.md` (single writer).
Figure numbering follows the chapter (e.g. 8.2, 8.3; 20.1-20.6).

**Phase C — one Jekyll build at the very end** (not per chapter) to validate
rendering across the whole site.

**Opus gate (before merge):** every figure renders cleanly (no clipping/
overflow in the SVG); captions/alt-text pass the professional-voice check; each
figure is accurate against its chapter prose **and** the referenced code/config;
the no-combine rule is respected; accent green and kebab-case naming are
consistent; reused assets (`transactional-outbox-sequence` in ch.20,
`monolith-shared-schema-er` in ch.18) embed correctly.

---

## 6. Deferred

- **Presentations (build LAST, after all chapter figures land and ch.27-32 are
  authored).** Two decks mirroring the datamesh structure: a **101**
  philosophy/patterns deck (landscape → why modernize → the ADLC → the pattern
  language) and a **201** implementation deck (the monolith → the six extractions
  → data/saga/communication patterns → chassis → operating → delivering). The
  datamesh 101/201 section skeletons
  (`datamesh-reference-arch-quarkus/presentation/`) are the structural
  reference. **Slides are NOT planned here** — only noted as the final step.
- **Figures for ch.27-32** (chassis, contracts/registry, deployment, mesh/
  observability, CI/CD, pattern-language) are authored *with* those chapters;
  the sibling sources earmarked for them are in §3 (optimizing-java for ch.27;
  observability-python-otel-lgtm for ch.30).

---

## Decisions for the user to confirm before generation

- **D1 — Scope of the first wave.** Generate all gap chapters now, or just the
  top-10 (§2) first and the rest in a second wave? *Recommend: top-10 first.*
- **D2 — EIP stencil reuse (ch.16).** Inline the EIP repo's `eip-stencils/svg/`
  icons into the ch.16 figures, or hand-draw equivalents in the plain house
  box/band style for visual consistency with the rest of the book?
  *Recommend: hand-draw in house style; the stencils are a different visual
  register.*
- **D3 — Charts vs. box-diagrams.** The generator is a box/band/edge engine, not
  a charting library. For the metrics bars (ch.03/07) and the cost/curve figures
  (ch.03) — build them as simple comparison figures in the generator, or use the
  `dataviz` skill for true charts? *Recommend: simple in-generator comparison
  figures, for one consistent look.*
- **D4 — Side-by-side vs. individual.** Confirm the `[SIDE]` figures
  (`monolith-to-microservices-outcome`, `rewrite-vs-strangler-risk`,
  `strangler-fig-variants`, `whitebox-pyramid-vs-blackbox-equivalence`,
  `review-adapter-swap`, `leaked-entity-vs-stockdto`, `dual-write-vs-outbox`,
  `polling-vs-log-based-relay`, `cqrs-lite-vs-event-sourcing`, `acid-to-acd`,
  `shared-db-to-database-per-service`) should be single comparison images;
  everything else stays individual.
- **D5 — Reuse policy.** OK to reuse `transactional-outbox-sequence` in ch.20 and
  `monolith-shared-schema-er` in ch.18 rather than author dedicated variants?
  *Recommend: yes — matches the existing strangler-review-extraction reuse.*
- **D6 — Figure volume.** Several chapters propose 3-6 figures (ch.04, 16, 20).
  Confirm appetite — the user said multi-per-page is fine, especially early, so
  this is expected, but ch.20's six is the heaviest.
- **D7 — Demo work.** Approve adding `demo-final-topology.sh` (and optionally the
  notification/inventory cutover demos), plus the header notes / `baseUrl`
  documentation? This is demo work beyond the diagram pass.
