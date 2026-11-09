---
title: "Strategies & Assessment"
order: 4
part: "Why Modernize"
description: "Lift and shift, modernize and extend, rip and rewrite, the strategic-value by change-frequency quadrant, the ease-of-migration rubric, the evolution arc reframed for 2026, and five criteria for choosing an extraction order."
---

## Learning objectives

By the end of this chapter you can:

- Place a system on the strategy menu (retain, retire, repurchase, rehost, replatform, refactor, rebuild) and say what each option buys and risks.
- Use the strategic-value by change-frequency quadrant and the ease-of-migration rubric to pick a strategy.
- Explain why incremental strangler-fig re-architecture, gated on behavioral equivalence, beats rehosting and rebuilding for this system.
- Choose among the Proxy, Redirection and Shared-Database variants of the Strangler Fig.
- Score bounded contexts on coupling, change frequency, risk, data entanglement and team ownership to set an extraction order.

## Why this matters

Modernization fails in two ways. A team picks the wrong strategy and spends a year modernizing the wrong thing. Or it picks the right strategy but the wrong sequence, and spends the first three months proving only that the hardest part of the system is hard. Answer them in order: which strategy fits, then in what sequence to cut.

## The strategy menu

Every modernization effort chooses a point on one spectrum: how much of the system's shape survives, and how much of its behavior must. At one end you move where the system runs and touch nothing inside. At the other you discard the shape and rebuild the behavior. The stops along it are often called the Rs:

| Strategy | What changes | Fits when |
| --- | --- | --- |
| **Retain** | Nothing. The explicit decision not to modernize this system now. | Strategic value and change frequency are both low. |
| **Retire** | The system is decommissioned. | The capability is no longer needed, or another system already provides it. |
| **Repurchase** | A commercial or SaaS product replaces the custom code. | The capability isn't a differentiator and a vendor solves it well. |
| **Rehost** (lift and shift) | The system moves to a container platform, another data center or a cloud, with its architecture, code, data model and integrations untouched. Container-native virtualization sits at the same point: it runs an existing VM workload on a container platform. | The system doesn't justify deeper investment and a measurable cost benefit is enough. The least complex option on the menu. |
| **Replatform** | A rehost that adopts a few platform-native capabilities: managed data stores, platform logging, a managed broker. Architecture and domain boundaries stay. | Modest strategic value, with some platform benefit to take. |
| **Refactor / re-architect** (modernize and extend) | Internal structure, module boundaries, data ownership and deployment topology change while external behavior is preserved. The legacy system stays intact while a new layer grows beside it, with integration points between old and new, cut over incrementally. | High strategic value, high change frequency, and an architecture that fights the team. |
| **Rebuild / replace** (rip and rewrite) | Most or all of the code is discarded. Legacy interfaces and data are replaced, a few elements are re-wrapped for continuity, and the rest is retired. | The required rate of change has outgrown incremental modernization: a mainframe platform, a monolith too accreted for safe change, or off-the-shelf software nobody can refactor because nobody owns its source. |

{% include excalidraw.html file="migration-strategy-spectrum" alt="Four migration strategies compared side by side: big-bang rewrite (highest risk, one long cutover), the strangler fig (incremental and independently verifiable), branch-by-abstraction (an in-process seam toggle), and parallel-run (a live comparison of two running paths)." caption="Figure 4.1: The migration-strategy spectrum, from big-bang rewrite to the strangler fig" %}

Each option trades two currencies: how much risk you take on at once, and how long you wait before the system is better.

- **Retain and repurchase** take almost no technical risk because they avoid the code. Retain buys nothing, and repurchase trades a code problem for a vendor-dependency problem.
- **Rehost and replatform** are cheap and fast: lower operating cost, a supported platform and a smaller data-center footprint in weeks, without understanding a codebase nobody understands. They buy nothing architectural. The shared schema is still shared, the single deployable is still single, and every structural reason the system was expensive to change is still there on newer hardware.
- **Rebuild** gives the cleanest result on paper at the highest risk. For a long stretch the new system doesn't yet do what the old one does, partial progress can't be checked against production behavior, and the rewrite quietly grows scope to fix everything wrong with the original (the second-system effect).

Refactor and re-architect sit in the middle. Plot a candidate's **strategic value** against its **change frequency**. High on both is where incremental re-architecture pays, because every future feature lands faster once the architecture stops fighting the team. Low on both is retain or rehost territory, with no return on deeper investment.

The second axis is an **ease-of-migration rubric** scored across code, configuration, data, secrets, network topology, installation, licensing and system type, each rated easy, moderate or difficult. It's orthogonal to strategic value: a system can be strategically critical and difficult to touch, and that combination is where sequencing, not strategy, becomes the hard problem. The shipping monolith here sits in exactly that quadrant. It's high in strategic value because it's the order-taking and fulfillment path of a shipping business. On the rubric it's difficult on data (one shared schema with cross-context foreign keys) and moderate everywhere else.

{% include excalidraw.html file="modernization-value-vs-change-2x2" alt="A strategic-value by change-frequency quadrant: Quick wins, Strategic re-architecture, Retain, and Reconsider/Repurchase, with the monolith plotted in the high-value, difficult-on-data quadrant that favors re-architecture." caption="Figure 4.2: The strategic-value by change-frequency quadrant the monolith sits in" %}

## The evolution arc, reframed for 2026

Modernize and extend lands on a well-worn arc: **monolith**, then **SOA/ESB**, then **microservices 1.0**, then **cloud-native (microservices 2.0)**. SOA's promise was integration through a central bus, and its failure mode was centralization: "the main issue with SOA and ESBs is centralization, from both architectural and organizational points of view." That's the coupling problem of a single monolith moved up to the integration layer.

Microservices 1.0 decentralized deployment, but every capability a monolith or ESB had provided by default (discovery, routing, resilience, configuration, tracing) had to be rebuilt as application code. The answer for several years was a named stack: Netflix Eureka for discovery, Hystrix for circuit breaking, Ribbon for load balancing, Zuul for edge routing, Zipkin for tracing and Spring Cloud Config for configuration. All of them are dated in 2026, several formally in maintenance mode. The problems didn't go away. They moved.

Cloud-native relocates each piece from an in-process library to the platform underneath the service:

| Microservices 1.0 library | Where it lives now |
| --- | --- |
| Eureka (discovery) | Kubernetes Service and Endpoints: a pod is discoverable by existing, not by calling a registry client. |
| Ribbon and Zuul (load balancing, edge routing) | A service mesh such as Istio, as a sidecar no application code links against. |
| Zipkin (tracing client) | OpenTelemetry, a vendor-neutral client the platform exports. |
| Hystrix (circuit breaker) | SmallRye Fault Tolerance, an annotation in Quarkus. |
| Spring Cloud Config | MicroProfile Config, an `application.properties` entry. |

The pattern persisted and only its implementation moved, from something every service bundled to something the platform provides by default. That's why the target here is Quarkus on Kubernetes: the chassis exists, so the effort goes to domain boundaries and behavioral equivalence instead of re-deriving discovery, circuit breaking and tracing.

## Pattern: Strangler Fig

The strategy is **incremental re-architecture executed through the Strangler Fig, gated at every step by a behavior-equivalence suite.** It names the mechanism (strangler-style incremental replacement), the direction (independently deployable services with owned data) and the safety property (every cut is proven behaviorally equivalent before the old code is retired). Against the alternatives:

- **Rehosting** doesn't touch the actual cost. The monolith isn't expensive to run; it would sit on one modest container indefinitely. Its cost is structural: a god `OrderService` that reaches directly into four other contexts' internals, a shared schema with cross-context foreign keys, and one checkout transaction spanning five bounded contexts. A platform move fixes none of that. It carries the problems unchanged onto new infrastructure: a fast win on operating cost and no answer to the modernization goal.
- **Rebuilding** has the wrong risk shape. Sam Newman's case in *Building Microservices* (2nd ed.) is that a big-bang rewrite defers every check of whether the new system behaves like the old one to the single moment of cutover, when a defect is most expensive to find and least reversible. Seam-by-seam migration inverts that: each seam is a small, independently verifiable bet, wrong guesses are cheap to find and reverse, and the business keeps getting value throughout. The argument is about incremental migration as a strategy, not about Quarkus or Camel, and it's why a system whose current behavior the business depends on never gets a rewrite.

"Incremental" is lower-risk than a rewrite only if you can prove at each step that the extracted piece behaves like the one it replaced. Otherwise it's the same risk spread out and harder to notice. The monolith's own contract-test collection does that job. It's captured once against the running "before" system, never edited to make a later service pass, and re-run unchanged against every extracted service. That gate must go green before the matching monolith module is retired. Ozkaya's treatment of decomposition (*Design Microservices Architecture with Patterns & Principles*) makes the same point from the decomposition side: splitting by business capability or subdomain pays off only if each piece can be validated against the behavior it replaces, not just deployed independently.

Martin Fowler's pattern is named for a fig that grows around a host tree and eventually replaces it while the tree keeps standing. It applies three steps repeatedly: identify a piece of functionality, implement it in a new service, and reroute calls so the new service answers them. Each step is small and independently reversible. Three variants answer different questions about how the rerouting happens and how much data separation is acceptable along the way:

| Variant | How it works | Use it here |
| --- | --- | --- |
| **Proxy** | A proxy goes in front of the monolith before anything moves. Every call passes straight through. Migrating a piece means standing up a new service and flipping the proxy's routing for just that piece, and the monolith never knows the caller moved. | The strangler proxy is the first piece of new infrastructure every extraction touches, before any domain code. |
| **Redirection** | The Proxy variant with a routing rule by URI path (or an equivalent resource identifier for non-HTTP traffic). It maps onto REST resource boundaries and lets a migration proceed one endpoint, resource or bounded context at a time, with no atomic cutover of a subsystem. | The variant used for all six extractions: each reroutes one resource's traffic once its replacement passes the equivalence gate. |
| **Shared-Database** | The extracted service and the shrinking monolith keep reading and writing the same database while code is decomposed, and data ownership is untangled afterward. | A transitional state, not a destination. The Review service is served behind the proxy while its table stays in the monolith's shared schema, because the service boundary and the data boundary are separable decisions and forcing both in one step buys nothing. |

{% include excalidraw.html file="strangler-evolution-arc" alt="Four snapshots of the strangler-fig execution: the monolith alone, the proxy-fronted monolith with every call still passing through, the incrementally strangled state with review and notification live behind the proxy, and the fully extracted end state where the proxy is a flagless edge router in front of all six services and the monolith sits decommissioned and unwired." caption="Figure 4.3: The strangler fig's four stages, mapped onto the six extractions" %}

## Assessing the monolith: choosing an order

A strategy answers how to cut. It doesn't say what to cut first. Get that wrong and the migration technically succeeds while teaching the team the wrong lessons in the wrong order, because the hardest seam was tackled before anyone practiced on an easy one. Five criteria, applied together, turn "where do we start" from a guess into an answer:

- **Coupling and cohesion.** Constantine's observation that "a structure is stable if cohesion is high, and coupling is low" becomes a measurable question per bounded context: how many other contexts does it call synchronously, and how many call into it? A context with no synchronous collaborators can be extracted and verified in isolation. One wired into four others' service and repository layers can't move until those four relationships are untangled or proxied. Coupling is the strongest predictor of how much other work an extraction drags along.
- **Change frequency.** A context the business rarely touches is a low-stakes place to validate a new mechanism, because nobody is shipping features a migration could collide with. A context under constant development has the highest payoff but the most damage from a botched extraction, so tackle it after the mechanism is proven elsewhere.
- **Risk and blast radius.** Ask what breaks, and for whom, if the extraction goes wrong. Does it sit on the revenue path? Does a failure cascade into contexts not yet touched? Can the cutover be reversed cheaply if a regression appears after rollout starts? A context that fails loudly and in isolation is a far safer first bet than one that fails silently and spreads.
- **Data entanglement.** A shared schema, cross-context foreign keys and JPA joins across context boundaries are a different axis from code coupling. Two contexts can be decoupled in their service layer and still welded together at the database. A context with its own schema, or one that can get one without a data migration, is cheaper to extract.
- **Team boundaries.** Conway's Law cuts both ways. A context one team owns end to end, from domain logic to data to on-call, extracts without cross-team coordination cost on top of the technical cost. A context that needs three teams to agree before anything changes will cost that coordination during extraction, however clean its code.

No criterion is sufficient alone. A context can be low-coupling and still high-risk on the revenue path, or rarely changed and deeply data-entangled. Score every candidate on all five and order them from lowest combined score (lowest coupling, risk and entanglement, single-team ownership) to highest. Ozkaya's decomposition by business capability works as a sequencing decision only when capabilities are scored against structural signals like these, not against which one feels most important. Strategic importance decides whether to modernize a system at all. It's the wrong signal for which seam to cut first, because the most important context is almost never the least risky one to practice on.

Gough's case for **API-led seams** (*Mastering API Architecture*) adds a lens that correlates with the other five: design and stabilize the contract at a boundary before deciding how either side implements it. A context that already exposes a clean, REST-shaped surface, with no collaborators reaching past it into its internals, is low-coupling, low-risk and seam-shaped before any extraction. The contract needs enforcing, not inventing. A context whose "API" is whatever method signature an internal caller finds convenient has high coupling and high data entanglement almost by definition, because nothing ever made it state its boundary. Assessing a monolith for extraction order is largely assessing how API-shaped each candidate seam already is.

## Applying it: the order for this system

The criteria were applied to the six bounded contexts, and the sequence is the one the rest of the work executes: **review, then notification, then inventory, then payment, then shipping, then order together with its GraphQL gateway.**

- **Review goes first.** It's a REST-only leaf. Nothing in order, inventory, payment, shipping or notification calls it synchronously, and it calls none of them. Its only entanglement was incidental: it shared the monolith's single global security configuration despite no runtime dependency on anything that configuration protected. That's about as close to zero coupling as a context gets. It can be extracted, routed through the Redirection variant of the proxy and verified against the equivalence suite with nothing else able to confound the result. If it fails, the cause is the mechanism itself (the proxy, the anti-corruption layer, the equivalence gate, the cutover flag) and not a tangled dependency. The full mechanism is proven once, end to end, on the lowest-risk subject. Nobody is impressed that reviews moved, and that's the point: the second extraction is where the mechanism starts paying for itself, because it already works.
- **Notification is second.** It's the next-lowest coupling, but it sat inside the checkout transaction, called synchronously from order. That difference forces the next new mechanism: a transactional outbox in the monolith publishes the event reliably, and notification becomes the first event-driven, Kafka-consuming service instead of another REST leaf.
- **Inventory is third.** Order needs it synchronously at checkout, which nothing earlier required, and its data lives in the shared schema. Data entanglement, not coupling, makes it harder than notification. Extracting it forces change data capture to backfill a newly owned database before writes cut over, the first time the sequence solves a data-ownership problem and not only a code-boundary problem.
- **Payment and shipping are fourth and fifth.** Extracting either removes a piece of the monolith's single ACID checkout transaction, the point where the system can no longer get distributed-transaction safety by default. Payment forces a choreographed saga and shipping an orchestrated one, so both coordination styles are taught back to back on one codebase.
- **Order, with its GraphQL gateway, is last.** It's the highest-coupling, highest-risk context by a wide margin. The god `OrderService` reaches directly into inventory's, payment's, shipping's and notification's services and repositories, and each of the five earlier extractions removes one of those dependencies before order has to move. When its turn comes, the proxy, the anti-corruption layer pattern, the equivalence gate and the agentic loop that produces each extraction have been exercised five times. The hardest, highest-value seam goes when the mechanism extracting it is the most proven, not the newest.

Rising difficulty, not rising domain importance, is the ordering principle.

> **Decision**
>
> Rehost when the architecture isn't the cost, rebuild only when the rate of required change has outgrown incremental migration, and otherwise re-architect through the Strangler Fig with an equivalence gate at every cut. Order the cuts by combined score on coupling, change frequency, risk, data entanglement and team ownership, lowest first, never by business importance.

> **🤖 Explore with your agent**
>
> "Read `examples/01-strangler-proxy/CUTOVER.md` and the README of each service from `examples/02-review-service` to `examples/07-order-service`. Score the six bounded contexts on coupling, change frequency, risk, data entanglement and team ownership, order them from lowest combined score to highest, and for every place your order differs from review, notification, inventory, payment, shipping, order, name the criterion that moves it and cite the file that supports your score."
>
> Check: every score cites a file or a recorded measurement, review (the REST-only leaf with no synchronous collaborators) ranks first, and order (which called inventory, payment, shipping and notification) ranks last.

## For deeper coverage

- Sam Newman, *Building Microservices* (2nd ed.): incremental migration versus big-bang rewrite.
- Mehmet Ozkaya, *Design Microservices Architecture with Patterns & Principles*: decomposition by business capability and subdomain.
- Matthew Gough, *Mastering API Architecture*: API-led seams.
- Martin Fowler, "Strangler Fig Application": the original pattern.
