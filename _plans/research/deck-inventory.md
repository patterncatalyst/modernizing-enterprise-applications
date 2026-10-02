# Deck Inventory: "Refactoring for Application Modernization: Strategies, Patterns and Practices"

**Source:** `_source/refactoring-for-app-modernization-original.pdf` (75 slides, Red Hat deck by Robert Sedor, Chief Architect Application Development)

---

## 1. Deck Overview (narrative arc)

- The deck frames modernization as a disciplined engineering exercise, not an end in itself: "Microservices should not be the goal of building Microservices" (p.6) — every move must trace back to a business reason.
- It opens by defining Application Modernization and Microservices characteristics, then immediately interrogates *when microservices are a bad idea* (unclear domains, small orgs, packaged software, no Agile/automation maturity, "résumé driven design") before endorsing them.
- It presents three core **Strategies** — Lift and Shift, Modernize and Extend, Rip and Rewrite — plus Repurchase/Retire/Retain as non-targets, and ties strategy choice to a 2x2 of strategic value vs. change frequency and to an "ease of migration" rubric (code/config/data/secrets/network/installation/licensing/type).
- It traces an architecture evolution arc: Monolith → SOA/ESB → Microservices 1.0 (Netflix OSS stack: Eureka, Hystrix, Ribbon, Zuul, Zipkin, Spring Cloud Config) → Kubernetes-solved concerns → Microservices 2.0 / Cloud Native (service mesh, messaging, serverless, API gateway).
- The **Patterns** section is the architectural core: it walks incremental decomposition (Monolith → Modular Monolith → Modular Monolith w/ decomposed DBs → Strangler Fig variants), then data-consistency patterns (Shared Data, Transaction Log Tailing, Event Sourcing, CQRS, CDC, Outbox), then distributed-transaction patterns (Saga choreographed vs. orchestrated), grounding the shift from ACID to ACD (eventual consistency, lack of isolation).
- The **Practices** section covers the operational concerns needed to run microservices safely: API communication styles (REST/gRPC/GraphQL), testing (unit, end-to-end, microservice/sidecar testing, test-data strategies), deployment patterns (fixed, rolling update, breaking schema change, blue-green), registries (schema/API/service), service mesh, observability (Kiali/Istio), distributed tracing, caching, and API gateway + service discovery evolution.
- The **Conclusion** ties everything back into Richardson's microservices pattern language map and a "microservices chassis" (cross-cutting capabilities every service needs — circuit breaker, discovery, tracing, metrics, logging, health checks, security, config) and explicitly notes what's *not* covered ("There's More!": UI refactoring, DB refactoring, org/team refactoring, Kubernetes patterns, event-driven architecture patterns, CI/CD, multi-cloud PaaS vs CaaS, serverless/functional design, AI/ML pipelines, API design/documentation).
- Overall framing is pragmatic and risk-reducing: "Eliminate guesswork and create predictable results" (p.4), start with least-complex/highest-ROI migrations first, and treat strategy choice as continuous assessment rather than one-time decision.
- The deck leans heavily on vendor-neutral, pattern-catalog style (name → one-liner → diagram → usage note), citing Martin Fowler (Strangler Fig), Chris Richardson (Microservices Patterns book), Larry Constantine (coupling/cohesion), and Red Hat developer resources/e-books as source material.

---

## 2. Section-by-Section Outline

| Section | Pages | Content |
|---|---|---|
| Title | 1 | Title slide |
| Agenda | 2 | Application Modernization, Strategies, Patterns, Practices, Conclusion |
| **Application Modernization** | 3–8 | What is App Modernization; What are Microservices (10 traits); What are the Goals; Modernizing with Microservices (or not); When Might Microservices Be a Bad Idea |
| **Strategies** | 9–20 | Section divider; Modernization Strategies (Lift&Shift / Modernize&Extend / Rip&Rewrite); Monolith Lift-and-Shift (automation, containerization x2); SOA/ESB containerization; Microservices 1.0 (Netflix OSS stack); Cloud Native / Microservices 2.0 objective; Modernization Options decision tree; How Do They Compare (2x2 quadrant); Modernization Options (complexity vs transformation diagram incl. Container Native Virtualization); Complexity of an Existing Application to Migrate (ease-of-migration table) |
| **Patterns** | 21–43 | Section divider; What are Monoliths; What are Modular Monoliths; Modular Monoliths w/ Decomposed Databases; Strangler Fig Pattern (+3 example variants: Proxy, Redirection, Shared Database); Content-based Routing; Decorating Collaborator; Shared Data; Transaction Log Tailing; Event Sourcing; CQRS (x3 slides incl. trade-offs); Simple CDC; Outbox; Distributed Transactions (ACID vs ACD); Saga \| Choreographed (x2); Saga \| Orchestrated (x2) |
| **Practices** | 44–64 | Section divider; Communications (REST/gRPC/GraphQL) x2; MicroProfile landscape; Factoring for Testing; End-to-end Testing; Microservice Testing; Testing Data; Microservices Deployments (Fixed, Rolling Update); Event-Driven Microservices Deployments (Breaking Schema Change); Microservices Deployments (Blue-Green); A Service Registry (use cases); Service Registry (requirements); Schema management with service registry; Service Mesh; Observability (Kiali); Distributed Tracing x2; Caching; API Gateway and Service Discovery |
| **Conclusion** | 65–68 | Section divider; Microservices architecture pattern-category map (citing Chris Richardson); Loosely Coupled Architecture / Microservices Chassis; "There's More!" (explicit list of out-of-scope topics) |
| **Additional Information** | 69–75 | Section divider; free e-book plugs (Modernizing Enterprise Java, OpenShift for Developers, Knative Cookbook); Resources (developers.redhat.com links); Q&A; Thank you |

---

## 3. PATTERN INVENTORY (exhaustive)

### A. Modernization Strategy Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Lift and Shift | Automate infra/middleware, containerize existing workloads, deploy on PaaS, keep external integrations/data on legacy; fastest ROI; best for low-strategic-value, low-change-frequency, simpler apps | 10, 11–13, 14, 17–19 |
| Modernize and Extend | Legacy remains intact; add a new layer with new capabilities on PaaS; new integration points between legacy and new layers (needs Agile Integration) | 10, 17–19 |
| Rip and Rewrite | Legacy totally replaced; new interfaces/data; some data/features re-wrapped, mostly retired; used for highly scaled/high-rate-of-change apps (mainframe, large monolith, COTS) | 10, 17–19 |
| Repurchase / Retire / Retain as-is | Non-target modernization "options" — explicitly called out of scope for migration effort | 17 |
| Container Native Virtualization | Running existing VM workloads container-native (least complex, fastest cost benefit) as an alternative/complement to Lift and Shift | 19 |

### B. Decomposition / Migration Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Monolith | Unit of deployment; code packed into a single process; multi-tier app; any change requires whole-system redeploy | 22 |
| Modular Monolith | Single system of independently-developed modules; still one deployable unit; good when boundaries are well-defined and org wants to avoid distributed-system complexity; DB is still monolithic | 23 |
| Modular Monolith with Decomposed Databases | Multiple services with decomposed DBs but still a "distributed monolith"; low cohesion/high coupling across service boundaries forces whole-system redeploys; quotes Larry Constantine on cohesion/coupling | 24 |
| Strangler Fig Pattern | Martin Fowler-inspired pattern (named after a type of fig); migrate monolith-to-microservice via 3 steps: identify functionality to move, implement it in a new microservice, reroute calls from the monolith | 25 |
| Strangler Fig — Proxy variant | Insert a proxy in front of the monolith, migrate functionality behind it, then redirect calls through the proxy to the new service (3-step diagram) | 26 |
| Strangler Fig — Redirection variant | Dedicated proxy redirects by URI path (e.g., REST resources); incremental rollout combined with delivery work, staged/stories-based cutover | 27 |
| Strangler Fig — Shared Database variant | Proxy pattern variant where services still share a decomposed-internally database, enabling incremental decomposition | 28 |
| Content-based Routing | From Enterprise Integration Patterns; intercepts and filters/routes messages to a new location without touching the monolith; can reduce latency by selectively ignoring messages | 29 |
| Decorating Collaborator | Proxy decides whether functionality is served by the monolith or an external "collaborator" service via the decorator pattern; avoids changing the monolith but puts logic in the proxy; new service may need to fetch extra data from the monolith | 30 |

### C. Data Consistency / Data Access Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Shared Data | Appropriate when domain model isn't complex and sharing a data store across services doesn't hurt performance/complexity | 31 |
| Transaction Log Tailing | Tail the database transaction log and publish each change as an event to a message broker so other apps/services can react | 32 |
| Event Sourcing | All state changes stored as an ordered sequence of events; state can be recreated or actions replayed; writes stored as an event "log"; event handlers manage propagation; eventual consistency | 33 |
| CQRS (Command Query Responsibility Segregation) | Separates read (query) and write (command) models/data stores to avoid chatty cross-database joins in microservices; event handlers propagate data between write/read stores; eventual consistency. Trade-offs: benefits = loose coupling, independent scaling, optimized schemas, evolvability, query simplification, focus on user intent; considerations = more moving parts, distributed transactions, distributed data management | 34, 35, 36 |
| Simple Change Data Capture (CDC) | Detect/collect DB changes so other applications can act on them; used to replicate data, feed data warehouses/caches/analytics/search/monitoring, propagate to CQRS read models or across microservices, and aid strangler migrations | 37 |
| Outbox Pattern | A change event is published to an "outbox" table/queue so downstream/external processes can act on it reliably; event handlers manage propagation | 38 |
| Distributed Transactions (ACID vs ACD) | Monolith transactions are strongly-consistent ACID (atomicity, consistency, isolation, durability); microservice distributed data is eventually-consistent and lacks isolation (ACD), risking dirty reads, lost updates, non-repeatable reads | 39 |

### D. Distributed Transaction / Resilience Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Saga pattern (general) | Minimizes impact of lacking isolation via compensating transactions (e.g., book ticket → book insurance → payment, with cancel/compensate counterparts on failure); two strategies: Choreography and Orchestration | 39 |
| Saga — Choreographed | Each service publishes/reacts to events via a message broker (e.g., ticket/order/payment topics) without a central coordinator; each participating service owns its own compensating logic; transaction logic is distributed | 40, 41 |
| Saga — Orchestrated | A central Saga Orchestrator/Transaction Manager (e.g., JBoss Narayana, Camel Saga EIP, BPMN2 compensation flow with Process Automation Manager) directs steps and compensations; transaction logic is centralized | 42, 43 |

### E. Cross-Cutting / "Chassis" Resilience Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Circuit Breaker | Part of early Microservices 1.0 resilience toolkit (Netflix Hystrix); later a platform-provided "microservices chassis" capability | 15, 67 |
| Service Discovery | Microservices 1.0 used Eureka; post-Kubernetes/Istio this becomes platform-native (Service Discovery via Istio) | 15, 64, 67 |
| Health Checks | Kubernetes-provided capability (health probes/self-healing); also listed as a chassis capability | 15, 67 |
| Microservices Chassis | A packaged set of cross-cutting capabilities (circuit breaker, service discovery, distributed tracing, app metrics, logging, health checks, security, config management) that monoliths used to build in-app and that Microservices 1.0 provided via language libraries (Netflix OSS) and Microservices 2.0 provides via the platform (OpenShift/Kubernetes/Istio/Prometheus/EFK/OAuth) | 67 |

### F. Deployment Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Fixed Deployment Pattern | Container deployment requiring pre-deployment validation (event stream/schema validation), stopping instances and cleanup (rebuilding state/resetting consumer groups), and startup wait/rollback handling | 52 |
| Rolling Update Deployment Pattern | Requires no breaking changes to state stores, microservice topology, or internal schemas; suited to additive field changes, new input streams, or non-reprocessing bug fixes | 53 |
| Breaking Schema Change Pattern | For event-driven microservices; breaking schema changes are inevitable and require producer/consumer coordination, migration coordination, and reprocessing downtime; entity schema changes are harder than non-entity events; two options: eventual migration with old+new schema running in parallel, or synchronized migration to a single new stream | 54 |
| Blue-Green Deployment Pattern | Goal is zero downtime for synchronous request/response microservices; full "blue" copy brought online, traffic switched over gradually by a router (e.g., 10%/90% split shown) | 55 |

### G. Registry / Schema Management Patterns
| Name | Deck's framing | Page(s) |
|---|---|---|
| Service Registry — Schema Registry use case | Registry for Kafka serializer/deserializer schemas | 56 |
| Service Registry — API Designs use case | API specification registry for API consumers | 56 |
| Service Registry — Shared Data Types use case | Shared schemas across API and event-driven architectures | 56 |
| Service Registry (enterprise requirements) | Flexibility (Kafka, 3scale, Fuse/EIPs), inclusive artifacts (Avro, Protobuf, JSONSchema, OpenAPI, AsyncAPI), API usage (OpenAPI, AsyncAPI, REST CRUD, UI, search), enterprise-readiness (lifecycle mgmt, monitoring/metrics, operators, pluggable storage, validation) — references Apicurio Registry | 57 |
| Schema Management with Service Registry | Producer/consumer retrieve/register schema by ID from a central registry (e.g., Apicurio) around Kafka topics with mixed formats (Avro/JSON/Proto) | 58 |

### H. Observability & Communication Practices
| Name | Deck's framing | Page(s) |
|---|---|---|
| Service Mesh | Capabilities: traffic routing (A/B, staged rollouts), resilience (retries, circuit breakers, connection limits, health checks), security (authN/authZ, mTLS), observability (metrics, tracing), testing (fault injection, traffic mirroring); platform-independent, polyglot, runtime-configurable (Istio sidecar proxy model shown) | 59 |
| Observability (platform-level) | Proper component (Kubernetes) + communication (Istio) description lets any admin discover app architecture and inner workings via logs/traces/metrics; illustrated with Kiali graph | 60 |
| Distributed Tracing | Tracks which services were touched, when, in what order, and why; measures units of work ("spans") with causality (span references) and context propagation; illustrated with a service topology and a trace/span waterfall | 61, 62 |
| Caching | Concerns: data locality/distribution, where to place data in hybrid cloud, data consistency, efficient query strategy; types: local cache, clustered cache, remote cache, data grid | 63 |
| API Gateway and Service Discovery (evolution) | Pre-Kubernetes/mesh: API Gateway + Eureka-based service discovery with direct mTLS between services; Post-Kubernetes/mesh: API Gateway + Istio-based service discovery/mTLS | 64 |
| Communication styles: REST | Representational State Transfer; standard HTTP verbs (GET/POST/PUT/DELETE) on resource URIs | 45, 46 |
| Communication styles: gRPC | Google spec implemented across languages; uses Protocol Buffers binary format instead of text | 45, 46 |
| Communication styles: GraphQL | Open-source spec; queries/mutations executed via HTTP POST; language-agnostic | 45, 46 |
| MicroProfile | Eclipse MicroProfile spec landscape (Config, Fault Tolerance, Health, Metrics, OpenAPI, OpenTracing, REST Client, JWT Propagation, CDI, JSON-P/B, JAX-RS, plus standalone Reactive Messaging/Streams Operators/Context Propagation/GraphQL) as the Java standard for microservice chassis concerns | 47 |

### I. Testing Practices
| Name | Deck's framing | Page(s) |
|---|---|---|
| Unit Testing | Ability to test the smallest bits of code; basis for larger/more complex test suites | 48 |
| Event-Driven Topologies (as a testing concern) | Event-driven topologies typically transform, aggregate, map, and route data — a factor to account for when structuring tests | 48 |
| Reduction Functions | Code created/refactored to eliminate code amount/duplication, improve performance/readability, or decouple code (refactoring toward event-based noted as a separate topic) | 48 |
| End-to-end Testing | Growing scope as microservice architecture grows: testing across multiple servers, separate deployments per test scenario, false negatives from environment flakiness; solutions: limit automated functional test scope, consumer-driven contract tests (CDCs), automated release remediation/progressive delivery | 49 |
| Microservice Testing (sidecar/dependency simulation) | May require multiple dependencies loaded via sidecars or other patterns to simulate side-by-side instances; may need a testing event broker; illustrated with driver/controller/worker/task/event-broker/schema-registry test harness | 50 |
| Testing Data strategies | Copy from production (replication tooling, replicate specific event streams), curate a testing source (known properties/values/relationships, shared durable store), mocking (schema registry-driven event generation), shared environment, or use production directly | 51 |

---

## 4. Code / Diagrams / Examples

| Page(s) | Type | Description |
|---|---|---|
| 4 | Diagram | Circular "what is app modernization" wheel: cross-functional leader, DevOps orientation, operational expertise, security expertise, application architecture |
| 5 | Diagram | Microservices architecture box diagram (UI, 4 services each with own config/DB) illustrating the 10 listed traits |
| 10 | Diagram | 3-card comparison: Lift and Shift / Modernize and Extend / Rip and Rewrite |
| 11–13 | Architecture diagram (3 slides, progressive) | Monolith (load balancer → web UI/SOA API → business logic components → data access → DB) transformed via (a) automation into a generic cloud implementation, (b) containerization into a Kubernetes StatefulSet/Service/PVC model, (c) same pattern applied to a concrete banking example (deposits/payment/quotes/trading) |
| 14 | Architecture diagram | Classic SOA/ESB architecture (channels → ESB → services → retail/wealth DBs → integration → SaaS/back end) |
| 15 | Architecture diagram + list | "Microservices 1.0" architecture with API Gateway/caching and per-service sidecars; lists early-microservices tooling: Eureka, Spring Cloud Config, Zipkin, Hystrix, Ribbon, Zuul, vs. what Kubernetes solves natively; small diagram contrasting app code bundled with Netflix OSS libraries+runtime vs. app code + Kubernetes platform |
| 16 | Architecture diagram | "Cloud Native Architecture" (Microservices 2.0) showing service mesh sidecars per service, event-based distributed integration bus, API Gateway |
| 17 | Decision-tree diagram | Existing App → Assess/review/prioritize → 6 branches (Lift and Shift, Modernize and Extend, Rip and Rewrite, Repurchase, Retire, Retain as-is) |
| 18 | 2x2 scatter/quadrant diagram | Strategic Value vs Change Frequency quadrants mapping Modernize&Extend / Rip&Rewrite / Lift&Shift / Outsource-or-Buy |
| 19 | Icon diagram | Container Native Virtualization, Lift and Shift, Modernize and Extend (monolith→microservice arrow), Rip and Rewrite (monolith vs. full microservice mesh) |
| 20 | Table | "Complexity of an Existing Application to Migrate" — Easy/Moderate/Difficult rows for Code, Configuration, Data, Secrets, Network, Installation, Licensing, Type (cites a Red Hat blog on architecting containers) |
| 22–43 | Pattern diagrams | Each named pattern in Section C has a dedicated box-and-arrow diagram (monolith/service/database/queue/proxy shapes) — see Pattern Inventory above for which pages |
| 42–43 | Worked example / diagram | Saga orchestrated worked through a concrete "Book Ticket → Book Ticket Insurance → Payment" travel-booking saga with a Transaction Manager coordinator and Confirm/Cancel compensation states; names concrete Red Hat products (Fuse + Narayana Transaction Manager, Fuse Saga EIP with Camel, BPMN2 with Process Automation Manager) |
| 40–41 | Worked example / diagram | Saga choreographed worked through the same ticket-booking example via Ticket/Order/Payment topics and Ticket/Insurance/Payment services with their own tables |
| 45–46 | Diagram | REST/gRPC/GraphQL comparison cards, then a topology diagram showing a UI and API fanning out to services over mixed REST/gRPC/GraphQL |
| 47 | Diagram | MicroProfile 1.0 → 3.3 spec evolution chart with standalone/outside-umbrella specs |
| 50 | Diagram | Local integration test harness: Driver → Controller/Worker/Task container with Event Broker (event streams) and Schema Registry |
| 52–55 | Diagrams | Fixed, Rolling Update, Breaking Schema Change, and Blue-Green deployment pattern diagrams using ReplicaSet pod-state iconography (stopped/starting/running) |
| 58 | Diagram | Schema management with service registry: Spring producer + Golang consumer around Kafka topics (Avro/JSON/Proto), registry in the middle |
| 59 | Diagram | Istio sidecar proxy pattern (Pod/Service A & B each with Istio proxy, Istio control plane) |
| 60 | Screenshot | Kiali service graph UI screenshot (bookinfo/productpage example) with traffic/error metrics |
| 61 | Diagram | Distributed tracing example: app.example.com → v1/v2 frontend-app (canary split) → backend-app (Java/Go) → MySQL |
| 62 | Diagram | Span tree (A→B/E→C/D) and corresponding trace/span waterfall timeline |
| 63 | Diagram | Three caching topologies: local cache per service, clustered cache, remote/data-grid cache with client/server split |
| 64 | Diagram | Pre- vs post-Kubernetes/service-mesh API Gateway + service discovery (Eureka vs Istio) comparison |
| 66 | Diagram | Pattern-category map of microservices architecture concerns (Decomposition, Database Architecture, Querying, Maintain Data Consistency, Testing, Cross-Cutting Concerns, Security, Transactional Messages, Communication Style, Reliability, Observability, Discovery, Deployment, External API) — explicitly attributed to "Microservices Patterns, Chris Richardson, Manning Publications" |
| 67 | Diagram | Microservices Chassis hexagon diagram (Service Name/Code wrapping Circuit Breaker, Service Discovery, Distributed Tracing, App Metrics, Logging, Health Checks, Security, Configuration Management) with Microservices 1.0 (Netflix OSS) vs 2.0 (OpenShift/Kubernetes/Istio/Prometheus/EFK/OAuth) side notes |
| No literal source code | — | The deck contains **no code listings** (no YAML, Java, or shell snippets) — all content is conceptual/architectural diagrams and bullet text |

---

## 5. Notable Quotes / Framings

- "Microservices should not be the goal of building Microservices." (p.6)
- "Fundamentally, we have to have a reason to move to Microservices." (p.8)
- Reasons microservices can be a bad idea: "when domains are not clear," "when reuse is a goal," "when the organization is too small," packaged software with unclear operational ownership, "when the organization doesn't do Agile or automation well," and — pointedly — "because we 'need' to re-architect the system and résumé driven design." (p.8)
- "A structure is stable if cohesion is high, and coupling is low." — Larry Constantine (p.24)
- "Change Data Capture (CDC) is a design pattern where changes to data in a database are detected and collected so that they can be acted upon by other applications." (p.37)
- "Start migration effort with the least complex application. With experience shift focus effort on strategic business." (p.18)
- "Eliminate guesswork and create predictable results." — framed as one of the goals of using field-tested strategies/patterns/practices (p.4)
- On SOA/ESB: "The main issue with SOA and ESBs is centralization, from both architectural and organizational points of view." (p.14)
- On Saga: "Saga - Minimize impact of lack of Isolation" via "Countermeasures: Compensating Transactions to minimise lack of Isolation." (p.39)
- Explicit microservices definition via 10 traits: independently deployable, modeled around a business domain, communicate via networks, "a form of opinionated service oriented architecture," technology agnostic, a distributed system, API-focused ("everything is 'hidden' behind a service boundary"), decentralized governance, decentralized data management, design for failure. (p.5)

---

## 6. Gaps for a Modern (2026) Re-telling

- **Dated tooling baseline**: "Microservices 1.0" stack (Eureka, Hystrix, Ribbon, Zuul, Spring Cloud Config, Zipkin) is presented as the pre-Kubernetes norm — all now effectively legacy/maintenance-mode. A 2026 book would need to reframe around current defaults (Kubernetes-native discovery, OpenTelemetry instead of Zipkin/standalone tracing, resilience libraries like Resilience4j/SmallRye Fault Tolerance, Istio/ambient mesh or eBPF-based meshes).
- **No mention of Quarkus** despite MicroProfile being covered in depth — the deck discusses the MicroProfile *specification* landscape generically but never names a runtime (Quarkus, Helidon, etc.), and never discusses build-time optimization, native image, or dev-mode inner-loop productivity, all central to a modern Quarkus-centric retelling.
- **No mention of Apache Camel or integration-routing frameworks by name** — Content-Based Routing is covered as an EIP concept, but no Camel (or any specific integration runtime) is cited, despite the author's broader Camel/EIP interests; a modern retelling would likely tie EIPs directly to Camel/Camel K/Kamelets.
- **No OpenTelemetry / modern observability stack** — tracing/observability are covered conceptually (spans, causality, context propagation, Kiali) but there's no mention of OpenTelemetry, the OTel Collector, Prometheus/Grafana/Loki/Tempo/Mimir (LGTM stack), or standardized semantic conventions.
- **AI/ML and agentic workflows are only named, not covered.** The "There's More!" slide (p.68) lists "AI/ML pipelines and microservices" as an acknowledged gap — the deck itself flags this as out of scope. A 2026 book would need a chapter on AI-assisted/agentic modernization (code-migration copilots, agentic refactoring workflows, LLM-driven dependency/CVE remediation) which has no precedent in this deck at all.
- **No GitOps / modern CI/CD tooling** — CI/CD is listed only as a bullet in "There's More!" (p.68); no ArgoCD, Tekton, progressive delivery tooling (Flagger, Argo Rollouts), or platform engineering / internal developer platform (Backstage-style) concepts appear anywhere.
- **No security/supply-chain coverage** — no SBOM, CVE/dependency scanning, policy-as-code (OPA/Kyverno), zero-trust beyond mTLS mention, or secrets-management patterns beyond a one-line complexity-table entry ("Dynamic Generation of Certificates").
- **No WebAssembly, platform engineering, FinOps/cost optimization, or sustainability/green-computing angles** — none of these contemporary modernization concerns appear.
- **Domain-Driven Design is implicit, not explicit** — "modeled around a business domain" appears as a microservices trait (p.5), but there's no explicit DDD vocabulary (bounded contexts, aggregates, context mapping, ubiquitous language) anywhere in the deck, which a modern book would likely foreground much more heavily for decomposition guidance.
- **Serverless is only named**, not explained — "Serverless - Rapid scaling up and down" appears as a one-line bullet (p.16) and again in "There's More!" (p.68); no Knative, FaaS platform, or event-driven scale-to-zero pattern detail despite the closing slide plugging a Knative Cookbook.

---

**Pattern count:** ~45 distinct named patterns/practices/principles catalogued above across 9 categories (A–I), drawn from all 75 slides.
