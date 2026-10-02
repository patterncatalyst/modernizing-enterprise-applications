---
title: Reuse Map
description: Cross-project inventory of reusable chapters, infrastructure, examples, and house conventions for modernizing-enterprise-applications
---

# Reuse Map — modernizing-enterprise-applications

Surveyed four sibling projects under `~/Dev` to find what can be carried into the
new "Modernizing Enterprise Applications" tutorial (Spring monolith →
Quarkus + Camel microservices, cloud-native patterns, an AI-agentic
development lifecycle ("ADLC"), testing throughout). All four are Jekyll
sites in the same house skeleton (`_docs`, `_parts`, `_plans`, `examples/`,
`_layouts`, `assets/css/site.css`), so the reuse is unusually direct —
mostly copy-and-adapt, not reinvention.

---

## 1. Per-project summary

### `~/Dev/cloud-native-design-patterns` (CNDP)
- **Purpose:** a patterns book — cloud-native principles, communications,
  composition, data, event-driven, stream-processing, workflows, API
  management/registry/metadata, observability, security, anti-patterns,
  plus 17 deep-dive appendices (A–Q).
- **Structure:** `_docs/00…13` core chapters, `14…30` appendices
  (lettered A–Q via `marker`/`label` front matter), `_parts/` groups into
  5 parts (Setting up; Foundations & the system; The operational
  platform; Security & anti-patterns; Deep-dive appendices).
  `examples/` per chapter, `_example_pages/` for example write-ups.
- **Site config:** `title`, `description`, `brand_emoji` ("🧭"),
  `github_username/repo`, `baseurl`. Collections: `docs`, `plans`,
  `example_pages`, `parts`. `exclude: examples/, scripts/` (code lives
  outside the Jekyll build).
- **Theme:** Red Hat fonts, amber accent (`--accent: #e8870c`, "eBPF
  amber" — inherited from the `lgtm-jekyll` house style).
- **Quality bar:** highest conceptual density of the four — long,
  carefully argued appendices (600–1400 lines) with one clear
  reframe per chapter, each backed by an Excalidraw diagram. This is the
  best source for *theory* chapters (DDD/hexagonal, sagas, coupling,
  feature flags, caching, failure modes, monolith-to-microservices).

### `~/Dev/enterprise-integration-patterns-with-camel` (EIP-Camel)
- **Purpose:** the 65 Enterprise Integration Patterns implemented with
  Apache Camel on three runtimes (Quarkus, Spring Boot, YAML DSL), backed
  by Kafka/Pulsar/Redis, runnable on Podman. **Uses a shipping domain
  already** — directly reusable domain language for the new project.
- **Structure:** `_docs/00…18` core chapters (integration styles,
  messaging systems, channels, routing fundamentals/composed/advanced,
  transformation, consumer/producer patterns, endpoint management,
  observability, testing), `19…43` appendices (A–Y, including two full
  worked case studies — Loan Broker, Bond Trading). `_parts/` groups into
  9 parts ending in "Appendices". `examples/<NN-name>/{quarkus,spring-boot,yaml-dsl}/`
  triple-runtime layout with Citrus `test/` dirs per example.
- **Site config:** `brand_emoji: "🔀"`, same collection/defaults pattern
  as CNDP, `exclude:` also drops `presentation/`.
- **Theme:** same Red Hat fonts + amber accent as CNDP (`#e8870c`) —
  visually identical house style, different book.
- **Quality bar:** very high and *very current* — appendices cover Camel
  4.21/4.22 features (CLI/TUI promoted to Stable, deserialization
  hardening, AI/MCP/LangChain4j integration) that didn't exist when CNDP
  was written. This is the best source for *Camel-specific* and
  *testing* material (Citrus, Newman, three-tier testing strategy, Kafka
  tuning, security-by-default).

### `~/Dev/datamesh-reference-arch-quarkus` (DataMesh-Quarkus)
- **Purpose:** the "gold-standard" professional template per the task
  brief — a working Quarkus + Camel data-mesh reference on the exact
  **order/inventory/payment/shipping/notification/review** domain the new
  monolith should reuse, with a Spring Boot twin service for a measured
  comparison.
- **Structure:** has `PRD.md` (13-section template — see §5 below),
  `CLAUDE.md`, `compose.yaml`, `_docs/00…21` (concepts → k8s substrate →
  services/data products → contracts/catalog → data planes → progressive
  delivery/mTLS → elastic/resilient → observability → anti-patterns →
  summary → Quarkus capability tour → Quarkus-vs-Spring-Boot → ... →
  appendices A1–A6), `_parts/` (foundations, data-products,
  operating-the-mesh, lessons-and-close, quarkus-deep-dive, appendices),
  `examples/` (one Maven reactor: `domain-model`, `contracts`,
  `order-service`, `inventory-service`, `payment-service`,
  `shipping-service`, `notification-service`, `review-service`,
  `graphql-gateway`, `ai-mcp-service`, `ai-rules-service`,
  `spring-boot-compare`), `demos/demo-*.sh` (one script per capability,
  1:1 with the deck), `infra/`, `k8s/` (base + istio + keda + minikube
  overlay), `scripts/` (bootstrap/setup-*/teardown), `tooling/`
  (Newman collection, load scripts), `presentation/`.
- **Site config:** `brand_emoji: "⚛️"`, same collections pattern,
  `exclude:` also drops `demos/` and `presentation/`.
- **Theme:** Red Hat fonts, **red accent** (`--accent: #ee0000`) — a
  deliberate palette swap from the amber books, still the same CSS
  skeleton (`site.css` structure identical to CNDP/EIP-Camel).
- **Toolchain note:** explicitly **Docker / docker compose, NOT podman**
  (`lgtm-docker-stack`, called out in `CLAUDE.md` and `compose.yaml`
  header comments) — differs from EIP-Camel, which is Podman-based. The
  new project should decide explicitly rather than assume.
- **Quality bar:** this is the template to imitate structurally — a real
  `PRD.md`, a `_plans/decisions.md` decision log (`DRQ-NNN` entries), a
  `_plans/build-plan.md` step-status table with a demo↔slide↔capability
  matrix, a `_plans/reconciliation.md` tracking drift against a sibling
  Python repo, verified-status footers on every chapter, and a `CLAUDE.md`
  with a per-task skill/MCP mapping table (reproduced in §5).

### `~/Dev/domain-driven-design-observability-workshop` (DDD-Obs)
- **Purpose:** a hands-on DDD + OpenTelemetry workshop, Quarkus primary
  with Python/C# twins, built around a **checkout saga** on the same
  order/inventory/payment/shipping/notification domain shape.
- **Structure:** `_docs/00…06` (intro/setup → domain landscape → domain
  events & spans → structured observability → cross-context debugging →
  observability economics → wrap-up) plus two addenda (event storming,
  advanced patterns) and `architecture.md`/`prerequisites.md`/
  `troubleshooting.md`. `exercises/{quarkus,python,dotnet}/` — a real
  multi-service Maven reactor per language
  (`shared-observability`, `order-service`, `inventory-service`,
  `payment-service`, `shipping-service`, `notification-service`), each
  with its own `Dockerfile`, plus a `compose.yaml` and a devcontainer.
  `infrastructure/_infra/compose-*.yaml` (one compose file per concern:
  kafka, postgres, tempo, prometheus, loki, grafana, otel-collector).
  `tests/` — Postman/Newman collections for a **checkout happy-path and
  failure-path saga**, plus load-test and debugging-exercise collections,
  with `environments/{local,codespaces}.json`.
- **Site config:** no `brand_emoji`, no `example_pages`/`plans`
  collections (simpler — workshop, not a reference architecture), adds
  `jekyll-feed`/`jekyll-sitemap`/`jekyll-seo-tag` plugins.
- **Theme:** different font stack (Inter for headings, system fonts for
  body) and a **teal accent** (`--accent: #0891b2`) — the one project
  that diverges from Red Hat fonts, confirming the accent/font pair is
  meant to be swapped per project while keeping the CSS skeleton.
- **Quality bar:** the most directly reusable *infrastructure and test
  harness* for a checkout-style saga — real multi-language services,
  real Grafana dashboards (`01-service-health`, `02-checkout-saga`,
  `03-trace-explorer`, `04-observability-cost`,
  `05-transport-comparison`), real Postman collections modeling a
  multi-step saga with explicit failure-path payloads
  (`checkout-out-of-stock.json`, `checkout-payment-decline.json`).

---

## 2. Directly reusable appendices/chapters

Legend: **reuse as-is** (copy, light rename) · **adapt** (rewrite framing
around the monolith/migration narrative, keep the technical core) ·
**reference** (cite/link rather than duplicate; too tied to the source
book's running example).

| Source project | File | Covers | Mapping into the modernization book |
|---|---|---|---|
| CNDP | `_docs/24-appendix-k-monolith-to-microservices.md` | Monolith as deployment property, modular monolith as legit destination, strangler-fig family (identify/move/redirect), content-based routing for cutover, decorating collaborator for untouchable legacy, reversibility as the design property | **Adapt — becomes a core chapter, not an appendix.** This is the spine of the new book's migration narrative; promote it out of "appendix" status and expand with the actual Spring monolith's decomposition steps |
| CNDP | `_docs/19-appendix-f-ddd-hexagonal.md` | Strategic DDD (core/supporting/generic subdomains, bounded contexts), tactical DDD (aggregates, entities, value objects, domain events), hexagonal architecture keeping protocols out of the domain core | **Adapt — core chapter.** Use directly to justify where the monolith's module boundaries become microservice boundaries; its worked example already uses order/pricing/inventory/shipping/auth/notification, matching the target domain |
| CNDP | `_docs/17-appendix-d-sagas.md` | Saga state machine persistence (DB-backed, crash-survivable), compensation ordering, threading step-B-needs-step-A-output without coupling | **Adapt.** Pairs with DDD-Obs's checkout saga exercises for a concrete saga chapter with both theory and runnable code |
| CNDP | `_docs/20-appendix-g-coupling.md` | Khononov's three-dimension coupling model (strength, distance, volatility) — coupling is necessary, balance not eliminate | **Reuse as-is (adapt framing).** Directly explains *why* some monolith modules should NOT be split; strong fit for an "anti-patterns of over-decomposition" section |
| CNDP | `_docs/27-appendix-n-feature-flags.md` | Deploy-vs-release split, four flag types, OpenFeature/flagd, targeting/percentage rollouts, flag lifecycle discipline | **Adapt.** Essential for "strangler fig cutover" mechanics — flags are literally how traffic shifts from monolith to service during migration |
| CNDP | `_docs/25-appendix-l-caching.md` | Six caching patterns (cache-aside, read/write-through, write-around/back, refresh-ahead) with consistency/failure stories | **Reference or light-adapt appendix.** Useful but generic; keep as an appendix rather than core content unless the monolith has a caching layer worth migrating |
| CNDP | `_docs/26-appendix-m-failure-modes.md` | Partial failure taxonomy (partition, split-brain, gray failure, cascading), defensive toolkit (timeouts, retry+jitter, circuit breaker, bulkhead, load shedding, quorum) mapped to each mode | **Adapt — core or near-core chapter.** The monolith's in-process calls become network calls after extraction; this is the "what you now have to defend against that you didn't before" chapter |
| CNDP | `_docs/28-appendix-o-newman.md` | Postman→Newman as an executable-contract test gate, one collection run against multiple implementations, CI wiring | **Reuse as-is.** Same pattern needed to test monolith vs. extracted-service behavior equivalence during migration |
| CNDP | `_docs/21-appendix-h-shutdown.md`, `22-appendix-i-l7-routing.md` | Graceful shutdown; L7/content-based routing | **Reference.** Useful supporting appendices, not migration-specific |
| EIP-Camel | `_docs/09-routing-fundamentals.md`, `10-composed-routing.md`, `11-advanced-routing.md` | Content-Based Router, Filter, Splitter, Recipient List, Scatter-Gather, Routing Slip, Dynamic Router, Wire Tap | **Reuse as-is (retarget domain).** Core Camel routing vocabulary the new book needs regardless of monolith theme; retarget examples from "shipping" generic domain to the specific monolith-decomposition story |
| EIP-Camel | `_docs/12-transformation-fundamentals.md`, `13-structural-transformation.md` | Message Translator, Content Enricher, Content Filter, Normalizer, Aggregator | **Reuse as-is.** Needed for the anti-corruption-layer / translation-at-the-seam pattern when wrapping legacy monolith calls |
| EIP-Camel | `_docs/41-appendix-citrus-testing.md` | End-to-end integration testing against real Kafka/HTTP/DB via Testcontainers, Camel CLI `camel test` plugin | **Reuse as-is — load-bearing for "testing throughout."** This is the most mature testing-strategy content across all four projects |
| EIP-Camel | `_docs/37-appendix-testing-strategies.md` | Three-tier pyramid: MockEndpoint/AdviceWith unit tests, Dev Services+REST Assured integration tests, Newman black-box API tests | **Reuse as-is — core "testing throughout" chapter.** Directly answers the new project's "testing throughout" requirement |
| EIP-Camel | `_docs/39-appendix-camel-cli.md`, `40-appendix-camel-tui.md` | Camel CLI prototyping/management/export-to-Maven lifecycle; terminal dashboard for live route monitoring | **Reuse as-is.** Useful "day in the life of migrating a route" appendix |
| EIP-Camel | `_docs/42-appendix-ai-mcp.md` | LangChain4j Chat component, `ai-tool` + agent tool-calling, embedded/catalog MCP servers, RAG, guardrails, Wanaku | **Adapt — core to the ADLC story.** This is the closest existing material to "AI-agentic workflow" content; reframe around using AI agents *during* the migration (classification of legacy code, automated route generation) rather than only as a runtime feature |
| EIP-Camel | `_docs/43-appendix-security.md` | Camel 4.21/4.22 secure-by-default changes (deserialization filters, header boundaries, dynamic URI allow-lists) | **Reuse as-is.** Security posture chapter, current as of Camel's latest stable line |
| EIP-Camel | `_docs/20-appendix-kafka.md`, `32…36-appendix-kafka-*-tuning/diagnostics` | Kafka fundamentals through consumer/producer tuning, share groups, diagnostics, Connect offsets | **Reuse as-is.** Needed wherever the migration introduces an event backbone between extracted services |
| EIP-Camel | `_docs/28-appendix-loan-broker.md`, `29-appendix-bond-trading.md` | Two full worked EIP case studies (Scatter-Gather; real-time market data distribution) | **Reference.** Good "further reading" case studies; not migration-specific enough to adapt directly |
| EIP-Camel | `_docs/38-appendix-kubernetes-deploy.md` | Maven-based container builds + K8s deploy on Minikube with Strimzi/Redis, no custom operators | **Adapt.** Template for "and now deploy the extracted service to Kubernetes" chapter |
| EIP-Camel | `_docs/26-appendix-feature-flags.md` | Feature flags specifically in a Camel/EIP context | **Adapt alongside CNDP's feature-flags appendix** — EIP-Camel's version is Camel-route-specific (e.g., flag-gated routes), CNDP's is conceptual; use both |
| DataMesh-Quarkus | `_docs/11-quarkus-capability-tour.md` | Panache, gRPC, GraphQL, Reactive Messaging, WebSockets.Next, Vert.x unification, continuous testing/Dev Services, native compilation, OIDC, JBang | **Reuse as-is as a Quarkus primer chapter.** The new book needs this exact content as "what you're migrating *to*" |
| DataMesh-Quarkus | `_docs/12-quarkus-vs-spring-boot.md` + `examples/spring-boot-compare/` | A real runnable Spring Boot twin service measured against the Quarkus equivalent (startup time, memory, native build) | **Reuse as-is — this is nearly the exact artifact the new book needs**, since the new book's whole premise is Spring→Quarkus. The twin-service pattern (same domain, same API shape, two runtimes) is directly transplantable |
| DataMesh-Quarkus | `examples/ai-rules-service/` (+ its chapter content, `_docs/14-ai-rules-triage.md`) | Drools rule engine + AI (LangChain4j) order-triage, `OrderTriageWorkflow`/`OrderTriageRoute`/`TriageService` | **Adapt — strong ADLC material.** Shows an AI-assisted decision/classification pipeline sitting inside a Camel route; good model for "AI agent assists the migration decision" content |
| DataMesh-Quarkus | `_plans/decisions.md`, `_plans/build-plan.md`, `_plans/reconciliation.md`, `CLAUDE.md` | Decision log format (`DRQ-NNN`), step-status build plan with a skill/MCP mapping table, drift-reconciliation discipline | **Reuse as a process template, not book content** — see §5 (house conventions) and consider documenting this AS the ADLC artifact pattern itself: decisions.md + build-plan.md + reconciliation.md *is* a lightweight ADLC |
| DDD-Obs | `_docs/02-domain-events-spans.md`, `03-structured-observability.md` | Domain events correlated to OTel spans, structured observability built from DDD concepts | **Adapt.** Good bridge chapter between "DDD gives you the domain events" and "observability needs those same events" |
| DDD-Obs | `_docs/04-cross-context-debugging.md` | Debugging across bounded-context boundaries once a monolith has been split | **Adapt — directly on-topic.** This is "what debugging looks like after you've done the thing this book teaches" |
| DDD-Obs | `_docs/05-observability-economics.md` | Cost/cardinality tradeoffs of observability at scale | **Reference.** Good supporting appendix |
| DDD-Obs | `_docs/addendum-a-event-storming.md` | Event storming as the strategic-DDD discovery technique | **Adapt — strong fit for an early "how do you find the seams in the monolith" chapter**, pairs with CNDP's strategic DDD material |
| DDD-Obs | `tests/collections/01-checkout-happy-path.json`, `02-checkout-failure-paths.json`, `03-domain-events-validation.json` | A full saga tested via Postman/Newman, happy path + explicit failure payloads (`out-of-stock`, `payment-decline`) | **Reuse as-is (retarget service names if needed).** Ready-made "testing throughout" artifact for a saga-shaped migration target |

---

## 3. Reusable infrastructure & tooling

| Artifact | Location | What it is | Porting note |
|---|---|---|---|
| `compose.yaml` (Docker) | `datamesh-reference-arch-quarkus/compose.yaml` | Postgres 18 + Kafka (KRaft, `apache/kafka-native`) + Apicurio 3 (schema registry) + LGTM-all-in-one + opt-in `kafka-ui`/`ollama` profiles. Image tags pinned once in `.env` to match Quarkus Dev Services defaults exactly (documented rationale for every pin — postgres:18 volume-layout change, Alpine healthcheck quirks, Grafana datasource-file collision). | Directly portable to a **`lgtm-docker-stack`**-based new project. If the new project instead wants podman (EIP-Camel's choice) or a minikube-first substrate, the K8s manifests below are the better starting point — decide Docker-vs-Podman explicitly, don't default silently. |
| Podman-based per-example infra | `enterprise-integration-patterns-with-camel/examples/<NN>/_infra`, `scripts/setup-stack.sh` | Kafka + Pulsar + Redis + PostgreSQL stack via Podman compose, scoped per-chapter | Use if the new project follows EIP-Camel's Podman convention instead; **do not mix both toolchains in one project** (datamesh explicitly calls out "NOT podman" for a reason — Dev Services image-tag matching is toolchain-specific) |
| `infra/` (Grafana datasources, OTel Collector config, Postgres init) | `datamesh-reference-arch-quarkus/infra/{grafana,otelcol,db}/` | Minimal, working Grafana datasource provisioning + OTel Collector config tuned for the LGTM-all-in-one image | Reuse as-is for a Docker-based new project |
| `infrastructure/_infra/compose-*.yaml` (split per concern) | `domain-driven-design-observability-workshop/infrastructure/_infra/` | One compose file per infra piece (kafka, postgres, tempo, prometheus, loki, grafana, otel-collector) instead of one monolithic compose | Good pattern if the new project wants to let readers opt into pieces incrementally (e.g., "chapter 3 only needs Kafka+Postgres") |
| Grafana dashboards for a saga | `domain-driven-design-observability-workshop/infrastructure/grafana/dashboards/{01-service-health,02-checkout-saga,03-trace-explorer,04-observability-cost,05-transport-comparison}.json` | Pre-built dashboards specifically for a checkout-saga, multi-service, multi-transport system | **Reuse as-is** if the new project's demo domain does a checkout/order saga — these are ready to import |
| `k8s/` (base + kustomize overlays + Istio + KEDA) | `datamesh-reference-arch-quarkus/k8s/{base,overlays/minikube,istio,keda}/` | Raw manifests + kustomize for app Deployments/Services, Istio VirtualService/DestinationRule/PeerAuthentication for mTLS+canary, KEDA ScaledObject/HTTPScaledObject for Kafka-lag and HTTP autoscaling | Ports directly to **`lgtm-minikube-stack`** for the "and now run it on Kubernetes" chapters — this is the most complete K8s example among the four projects |
| `scripts/bootstrap.sh`, `setup-istio.sh`, `setup-keda.sh`, `setup-kafka-operator.sh`, `setup-postgres-operator.sh`, `setup-lgtm.sh`, `teardown.sh`, `cluster-status.sh` | `datamesh-reference-arch-quarkus/scripts/` | Per-component minikube substrate bootstrap scripts (Istio, KEDA, Strimzi, CloudNativePG, LGTM), a status checker, and a clean teardown | Reuse as-is or thin-adapt; matches the `lgtm-minikube-stack` skill's expected shape already |
| Citrus test harness + `test/*.citrus.it.yaml` | `enterprise-integration-patterns-with-camel/examples/*/yaml-dsl/test/` | Per-route Citrus integration tests runnable via `camel test run`, with `_infra` Testcontainers config and templates | Reuse the pattern (one `.citrus.it.yaml` per route/example) for every extracted-microservice example in the new project |
| Newman collection + environments | `datamesh-reference-arch-quarkus/tooling/newman/{datamesh.postman_collection.json,local.postman_environment.json}` and `domain-driven-design-observability-workshop/tests/{collections,environments,payloads}` | Executable API-contract tests, with explicit happy-path and failure-path payloads, environment files for local vs. Codespaces | Reuse both patterns: DataMesh's single-collection-many-environments shape for cross-service contract tests, DDD-Obs's payload-library shape for saga failure-path testing |
| Testcontainers / Dev Services wiring | `enterprise-integration-patterns-with-camel/_docs/23-appendix-quarkus-dev.md`, DataMesh's `OrderPlacedAvroWireIT` (self-provisioning failsafe IT, no compose needed) | Quarkus Dev Services auto-provisioning Kafka/Postgres/Apicurio for `mvn test`/`mvn verify`, with a documented wire-compat gotcha (image tags must match compose pins exactly) | Reuse the "pin once in `.env`, confirm Dev Services uses the same tag" discipline — this is a real, previously-hit gotcha worth inheriting verbatim |
| Devcontainer | `datamesh-reference-arch-quarkus/.devcontainer/`, `domain-driven-design-observability-workshop/exercises/quarkus/.devcontainer/{devcontainer.json,post-create.sh,post-start.sh}` | Codespaces-ready dev environment per exercise module | Reuse if the new project wants a "try it in Codespaces, no local setup" path (DDD-Obs explicitly supports this — see its `tests/environments/codespaces.json`) |

**Recommendation:** since the new project's reference monolith will almost
certainly end up Kubernetes-deployed by the end of the book, lead with
DataMesh-Quarkus's Docker-compose-for-dev + minikube-for-substrate split
(the most complete pairing), and decide up front — in a `decisions.md`,
mirroring DataMesh's `DRQ-003` — whether the dev-loop compose stack is
Docker or Podman. Do not inherit EIP-Camel's Podman choice and
DataMesh's Docker choice into the same repo.

---

## 4. Reusable example/demo patterns

- **Per-chapter example directory**, numbered to match the chapter
  (`examples/09-routing-fundamentals/`, `examples/24-appendix-k-...` has
  no direct example dir in CNDP since it's prose-only, but EIP-Camel
  numbers every example to its chapter). The new project should keep
  **chapter-number-prefixed example directories** so the mapping from
  prose to runnable code is unambiguous.
- **Multi-runtime example layout** (EIP-Camel):
  `examples/<NN-name>/{quarkus,spring-boot,yaml-dsl}/`, each a standalone
  Maven (or YAML-DSL/JBang) project, selected in prose via
  `{% include codetabs.html langs="Quarkus|Spring Boot" %}` followed by
  one fenced code block per tab **in the same order as the labels**, and
  only tabifying code that actually differs per runtime. This is exactly
  the shape the new project needs for "here's the Spring monolith code,
  here's the Quarkus equivalent."
- **One runnable Maven reactor for a whole mesh** (DataMesh-Quarkus):
  `examples/{domain-model,contracts,<service>...}` as sibling reactor
  modules sharing one `pom.xml`, with `domain-model` holding
  framework-agnostic DTOs (`OrderDto`, `OrderStatus`, `StockDto`,
  `ReviewDto`, `NotificationDto`, `Topics`) consumed by every service
  module. Use this shape for the target microservices once extracted;
  use the EIP-Camel per-chapter-triple shape for isolated pattern demos.
  A Spring Boot twin module (`spring-boot-compare/`) sits in the *same*
  reactor, same domain, same API shape — directly the template for the
  new book's before/after comparison.
  **Note:** the datamesh repo's Java source is substantial but
  intentionally minimal per service (3–9 files each) — do not over-build
  example services; match that scale.
  **Note:** avro schemas for Kafka events (`contracts/src/main/avro/*.avsc`)
  are flat records (8–9 fields), documented inline with `"doc"` fields
  citing the decision IDs that motivated them — reuse this
  self-documenting-schema convention.
- **One demo script per capability, 1:1 with slides** (DataMesh-Quarkus):
  `demos/demo-<capability>.sh` (e.g., `demo-order.sh`, `demo-kafka.sh`,
  `demo-ai-mcp.sh`, `demo-keda-kafka.sh`), each narrated in its own
  header comment (what it proves, sharp edges hit wiring it up), plus one
  `demos/walkthrough.sh` that runs them all, and a `demos/jbang/` folder
  for prototyping snippets outside the Maven reactor. Adopt this for the
  new project's "migration step N, demonstrated" sequence — one demo
  script per migration milestone (extract-module, strangle-route,
  cut-over, decommission) pairs naturally with the strangler-fig
  narrative.
- **Verification footer on every chapter** (all four projects, strictest
  in DataMesh-Quarkus and EIP-Camel): a closing line,
  `*Verification status: <span class="status status--verified">verified</span>. ...*`
  naming exactly which tests/demos were run to confirm the chapter's
  claims. Inherit this verbatim — it is the single strongest
  "professional-grade" signal across all four projects and dovetails
  with "testing throughout."
- **Example index page** (EIP-Camel's `_example_pages/index.md`): one
  table mapping every example directory to its patterns, infra
  dependencies, and chapter number. Reuse this as the new project's
  example-tree map, with a "migration phase" column added (monolith /
  strangling / extracted / decommissioned).

---

## 5. House conventions to inherit

**Site (`_config.yml`) pattern**, identical across all four — copy
verbatim and fill in identity fields:
```yaml
title: "..."
description: "..."
brand_emoji: "..."        # one emoji in the header (optional for DDD-Obs-style workshops)
github_username: "patterncatalyst"
github_repo: "..."
baseurl: "/<repo-name>"
collections:
  docs:          { output: true, permalink: /docs/:name/ }
  plans:         { output: false }            # living docs, not published
  example_pages: { output: true, permalink: /examples/:name/ }
  parts:         { output: true, permalink: /parts/:name/ }
defaults:   # layout: tutorial for docs/example_pages, layout: part_index for parts, layout: plan for plans
exclude:
  - Gemfile, Gemfile.lock, vendor/, node_modules/
  - examples/      # runnable code, not site content — IMPORTANT trailing slash
  - scripts/, demos/, presentation/
  - README.md, LICENSE, CLAUDE.md, PRD.md, _plans/archive/
```

**CSS/theme:** `assets/css/site.css` is structurally identical across
all four (Red Hat Display/Text/Mono fonts in three of four; DDD-Obs swaps
to Inter+system fonts). The only per-project change is the accent triad:
```css
--accent:        /* amber #e8870c (CNDP, EIP-Camel) | red #ee0000 (DataMesh) | teal #0891b2 (DDD-Obs) */
--accent-hover:  /* darker shade */
--accent-soft:   /* light wash, used for .chip--accent and callout backgrounds */
```
Pick one distinct accent for the new project (none of the four used
blue/green — a natural "modernization/migration" color like a blue or
green would keep it visually distinguishable from its three siblings).

**Chapter front matter** (tutorial layout):
```yaml
---
title: "Chapter Title"
order: N
part: "Part Name"          # or marker/label for lettered appendices, e.g. marker: "K", label: "Appendix K"
description: "One-sentence chapter summary used in nav/cards."
duration: NN minutes       # or "NN minutes" string form (EIP-Camel)
---
```

**Part front matter** (`_parts/*.md`):
```yaml
---
title: "Part Name"
order: N
part_name: "Part Name"
blurb: "One paragraph describing what this part covers and why it's grouped."
---
```

**Diagram workflow** (CNDP's `assets/diagrams/README.md` + all four
projects): every figure is a paired `name.svg` (committed, embedded via
`{% include excalidraw.html file="..." alt="..." caption="..." %}`) plus
`name.excalidraw` (editable source), generated via the
`lgtm-diagram-generator` skill's `scripts/generate_diagram.py`. Maintain
a catalogue table in `assets/diagrams/README.md` mapping diagram → chapter
→ what it shows.

**Commit conventions** (DataMesh-Quarkus's `CLAUDE.md`, the most explicit):
Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, scoped e.g.
`feat(order):`), **no Co-authored-by or other attribution trailers** —
already consistent with the user's global "no upstream without
permission" / "simple git commands" memory preferences.

**PRD style** (DataMesh-Quarkus's `PRD.md`, 13 sections — use this
template):
1. Summary (one sentence + one paragraph)
2. Problem statement (who's the reader / what's their pain / why now)
3. Goals and non-goals (testable goals; deliberate exclusions)
4. Audience details (primary / secondary / explicitly not served)
5. Scope
6. Runnable examples
7. Diagrams
8. Success metrics (project-controlled verification metrics + indicative adoption metrics)
9. Constraints and dependencies (technical / editorial / dependencies)
10. Risks and mitigations
11. Timeline and milestones
12. Open questions
13. Decision log pointer
14. How this PRD is used

**Planning-document / ADLC-adjacent conventions** (DataMesh-Quarkus's
`_plans/`, the most mature "process as artifact" pattern found — worth
treating as the seed of the new project's own ADLC documentation):
- `_plans/decisions.md` — a flat decision log with stable IDs
  (`DRQ-NNN`), each decision one paragraph, including a version matrix
  at the top. Append-only; never rewritten.
- `_plans/build-plan.md` — a step-status table (`# | Step | Phase |
  Skills/MCP | Status`) plus a demo↔slide↔capability matrix. This *is* a
  lightweight ADLC artifact already — ties every build step to the skill
  used and its current status (DONE/WIP/pending), which is exactly the
  kind of agent-workflow transparency an "ADLC" chapter would want to
  showcase.
- `_plans/reconciliation.md` — tracks drift between this repo and a
  sibling reference (there, the Python twin; here, potentially the
  original monolith's behavior/API surface as the ground truth to
  reconcile extracted services against).
- `CLAUDE.md`'s **per-task skill/MCP mapping table** (project overview →
  version matrix → key conventions → build/test commands → skill/MCP
  table → structure tree) is the single best artifact to copy verbatim
  as the new project's own `CLAUDE.md` skeleton, substituting the
  monolith-to-microservices toolchain (Spring Boot source, Quarkus
  target, Camel for the integration seams, lgtm-* skills for
  infra/diagrams/testing).

---

## 6. The shipping/e-commerce domain model to reuse

DataMesh-Quarkus (and, independently, DDD-Obs and EIP-Camel) converge on
the **same shipping/order domain shape** — the new monolith should use
this exact shape so all four sibling projects stay mutually consistent
and cross-referenceable.

**Services / bounded contexts:**
| Service | Role | Protocol surface (DataMesh-Quarkus) |
|---|---|---|
| `order-service` | The template data product — owns the order aggregate, the write path (validate → persist → publish) | REST + gRPC client (to inventory) + Kafka producer |
| `inventory-service` | Stock levels; gRPC-first because its one real consumer (order-service) needs low-latency synchronous checks | gRPC server |
| `payment-service` | Event-driven choreography step — consumes `order.placed`, emits `payment.captured` | Kafka consumer/producer |
| `shipping-service` | Event-driven choreography step — consumes `payment.captured` (or similar), emits `shipment.dispatched` | Kafka consumer/producer |
| `notification-service` | Consumes order/shipment events, pushes to a live client via WebSockets.Next | Kafka consumer + WebSocket |
| `review-service` | REST-only, no synchronous dependency, OIDC-protected endpoints | REST |
| `graphql-gateway` | Synchronous read aggregation surface over the mesh | GraphQL, calls order/inventory REST |
| `ai-mcp-service` / `ai-rules-service` | AI-assisted order classification/triage (LangChain4j + Drools), MCP tool lookup | Camel routes + MCP server |
| `spring-boot-compare` | A twin of `order-service` in Spring Boot — same domain, same API shape, for a measured comparison | REST, same shape as the Quarkus original |

**Shared domain model** (`examples/domain-model/`): `OrderDto`,
`OrderStatus`, `StockDto`, `ReviewDto`, `NotificationDto`, `OrderCreate`,
`Topics` (Kafka topic name constants) — framework-agnostic DTOs shared
across every service module in the reactor.

**Events (Avro contracts in `examples/contracts/src/main/avro/`):**
- `order-placed.avsc` → `capstone.order.v1.OrderPlaced` — emitted by
  order-service after durable persistence; fields: `event_type`,
  `order_id` (Kafka key), `customer_id`, `item_sku`, `quantity`,
  `amount` (decimal-as-string), `status`, `created_at`.
- `payment-captured.avsc` → emitted by payment-service.
- `shipment-dispatched.avsc` → emitted by shipping-service.

Each schema's `"doc"` fields cite the decision IDs that motivated them —
worth imitating as a self-documenting-contract convention.

**Choreography chain:** `order.placed` → payment-service captures
payment → emits `payment.captured` → shipping-service dispatches →
emits `shipment.dispatched` → notification-service pushes to the
client. DDD-Obs additionally models this as a **saga** with explicit
compensation/failure paths (`checkout-out-of-stock`,
`checkout-payment-decline` Postman payloads) — pair DataMesh-Quarkus's
happy-path choreography code with DDD-Obs's failure-path test payloads
and CNDP's saga-compensation appendix (`17-appendix-d-sagas.md`) for a
complete picture.

**For the new project's Spring monolith:** model it as a single
deployable owning all six of these bounded contexts as packages/modules
(order, inventory, payment, shipping, notification, review) sharing one
database and one JVM — i.e., literally the "before" picture that
DataMesh-Quarkus, EIP-Camel, and DDD-Obs all independently arrived at as
the "after." This makes the new book's monolith a believable common
ancestor of all three sibling projects' target architectures, and lets
worked examples cite them as "here's what this looks like once fully
decomposed" cross-references.

---

## Summary of reuse volume

- **4 projects surveyed** in full (config, structure, representative
  chapters, examples, infra, scripts).
- **~35 chapters/appendices** catalogued with an explicit reuse verdict
  (reuse as-is / adapt / reference) in §2.
- **~12 infrastructure/tooling artifacts** (compose stacks, K8s
  manifests + kustomize + Istio/KEDA, bootstrap scripts, Testcontainers
  patterns, Grafana dashboards, Newman/Citrus harnesses) catalogued in
  §3.
- **1 domain model** (6 services + gateway + 2 AI services + 1 Spring
  twin, 3 Avro event contracts, 1 shared DTO module) fully mapped in §6,
  directly reusable as the monolith's "after" picture and already
  shared by 3 of the 4 sibling projects.
- **1 full house-convention set** (Jekyll config, CSS/theme skeleton,
  front-matter schemas, diagram workflow, commit conventions, PRD
  template, and an ADLC-adjacent planning-doc pattern) ready to clone
  into this project's own `_config.yml`, `CLAUDE.md`, and `PRD.md`.
