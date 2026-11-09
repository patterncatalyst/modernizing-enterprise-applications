---
title: "Modernization as Engineering, Not Fashion"
order: 3
part: "Why Modernize"
description: "Modernization goals, the ten microservice traits, 'microservices are not the goal,' and when microservices are a bad idea."
---

## Learning objectives

By the end of this chapter you can:

- Justify a modernization with delivery and structural problems you can name and measure.
- List the ten traits that define a microservice, and say what each one costs.
- Recognize the situations where microservices are the wrong call.
- Explain why a big-bang rewrite fails and what the strangler fig refuses to bet.
- Show a modernization step as constant behavior plus a measured improvement.

## Why this matters

Modernization pays for itself when it solves a problem you can name and measure, and it should run as engineering with a safety net, not as a leap toward a fashionable architecture. Two corollaries follow. Microservices aren't the goal: a project that treats them as one produces a distributed system with all of a monolith's coupling and none of its simplicity. And a rewrite ships nothing until it ships everything or quietly dies, while incremental, strangler-based extraction under a behavior-equivalence suite ships value at every step.

## Why modernize at all

A system that works, that nobody touches under duress and that the business can still staff and extend, doesn't need modernizing because its stack is unfashionable. The problems that justify the risk are concrete, measurable and getting worse. They fall into two families, and an effort that can't point at symptoms from at least one of them is chasing a trend.

**Delivery performance** shows up in two metrics a team already tracks. **Lead time for changes** is how long a line of code takes from commit to production. **Change-failure rate** is the fraction of shipped changes that cause an incident, rollback or hotfix. With one build, one test suite, one deployable and one release train, both degrade as the system grows, and faster than linearly, because coupling between modules grows faster than the modules. A change to shipping that can't go out without re-running the review tests, redeploying the payment container and waiting for a release window shared by six teams has an inflated lead time for reasons unrelated to the change. A bug that can corrupt shared in-process state for every module makes the blast radius, and so the failure rate, scale with the whole system.

**Structural problems** move slower and cost more over a long horizon:

| Problem | How it shows up |
|---|---|
| Scaling limits | One hot path, such as reviews during a flash sale or inventory lookups during a restock, forces scaling the whole monolith and paying for idle capacity in five modules |
| Team autonomy | Conway's Law cuts both ways: one shared artifact forces the cross-team coordination that separate teams were meant to avoid, as a negotiation over a shared release window |
| Stack decay | A framework nobody upgrades because it means re-testing everything, a security patch that can't ship independently, and engineers who know the stack retiring faster than new ones learn it |

A newer language or trendier framework solves none of these. Changing the boundaries does: where one deployable ends and another begins, where one team's change ships without another's sign-off, where one schema ends and another team owns its own. That is why the reference monolith has those boundary problems built in instead of merely running on an old Java.

Kleppmann's *Designing Data-Intensive Applications* names the property underneath: reliability, scalability and **maintainability**, which breaks into operability, simplicity and **evolvability**, the ability to accommodate change as requirements shift. Every pattern from the strangler proxy onward is a maintainability intervention that aims at evolvability: make the next change cheaper than the last.

## The ten microservice traits

"Microservices" used loosely enough to mean "any service" is no target for an extraction. These ten traits, from the deck this material descends from, define it:

1. **Independently deployable**: it ships on its own schedule, not the monolith's release train.
2. **Modeled around a business domain**: its boundary matches a bounded context, not a tier such as data access or UI.
3. **Communicates over the network**, not through in-process calls, which is the same change that turns a stack trace into a distributed trace.
4. **A form of opinionated service-oriented architecture**: SOA's integration ideas with much stronger defaults about size and ownership.
5. **Technology agnostic**: one team's service can use a different language or framework than its neighbor's.
6. **A distributed system as a set**, with partial failure and no single transaction boundary.
7. **API-focused**: internals hidden behind a service boundary, with no shared library or shared table, as the monolith has today.
8. **Decentralized governance**: the owning team decides how the service is built.
9. **Decentralized data management**: each service's data is owned by that service.
10. **Designed for failure**: any service can be unavailable at any moment, unlike the monolith's implicit assumption that if the process is up, every module is reachable.

None is a goal alone. Together they are what the strangler sequence aims at: each of the six extractions, review through order, bets that one more bounded context can gain independent deployability, owned data and a real API boundary without losing the behavior the monolith already gets right.

{% include excalidraw.html file="microservice-traits" alt="A ten-box grid of the microservice traits: independently deployable, modeled around a business domain, communicate over the network, a form of opinionated service-oriented architecture, technology agnostic, a distributed system as a set, API-focused, decentralized governance, decentralized data management, and designed for failure." caption="Figure 3.1: The ten microservice traits the extractions are built toward" %}

## The counter-case: when modernization is the wrong call

The rule from the source deck: "Microservices should not be the goal of building microservices." A team that can't answer "which of the four problems above does this solve, and how will we measure the improvement" is solving a fashion problem, which doesn't justify the operational cost of a distributed system.

Newman, in *Building Microservices*, frames it as a trade: a simpler deployment and operational model for independent scalability and deployability. The trade pays only when the domain is complex enough that independent evolution of its parts is worth network calls, eventual consistency and distributed debugging that used to be a stack trace. It's a bad trade in these situations:

- **Unclear domain boundaries.** Splitting along a guessed line produces services that call back and forth across the seam. That's worse than the same coupling in a monolith, because now the coupling crosses a network.
- **An organization too small to carry the operational cost.** Microservices multiply what can fail independently: deployments, databases, brokers, certificates, on-call rotations. A five-person team with one database and one deployable can out-ship a twenty-service architecture that spends its capacity keeping itself alive.
- **Packaged or vendor software with no internal seams.** You can't strangle code you don't own and can't change. The story for a COTS system is integration and encapsulation, not decomposition.
- **No continuous delivery, automated testing or infrastructure as code yet.** Ozkaya, in *Design Microservices Architecture with Patterns and Principles*, makes the point that microservices amplify whatever delivery discipline exists, so adopting them first compounds the problem.
- **Resume-driven design.** Rearchitecting a working system because it looks good in a portfolio, not because a measured problem demands it, takes on risk the business didn't agree to.

The reference monolith has distinct domains (orders aren't reviews), assumes an organization that already runs CI and automated tests, and ties every extraction to a named problem among its planted smells. A system that fails more of these tests than it passes should stay a monolith, or pick a lighter strategy than extraction. Strategy assessment turns these tests into a rubric over Lift and Shift, Modernize and Extend, and Rip and Rewrite.

## Why big-bang rewrites fail

Stopping feature work, rewriting in the new stack and cutting over once has the worst track record in the industry, and the failure is structural. A rewrite makes three bets at once:

- **Full behavioral understanding.** The old system accumulated years of bug fixes, edge cases and undocumented rules. Nobody wrote them as a specification because the running system was the specification.
- **Outrunning decay.** A system of comparable scope has to be built faster in the new stack than the old one decayed, which is rarely true once the new one reaches the old one's complexity.
- **Business patience.** The business must tolerate quarters or years with almost no new value shipped, because nothing is releasable until everything is.

Any one failing sinks the project, and the third usually kills it: a rewrite that isn't feature-complete can't be partially shipped, and a business promised a cutover date for eighteen months runs out of patience first. Newman's guidance is consistent: migrate incrementally, keep old and new running side by side, and never schedule a moment where everything must work for the first time at once. One bounded context extracted at a time, each proven equivalent before its old code is deleted, is that guidance as a repeatable procedure.

## Pattern: Strangler Fig

The strangler fig is the engineering answer, and its value is in what it refuses to do:

- **It refuses to require a complete rewrite before anything ships.** The first extraction, Review, is a small slice, in production behind a flag before any other context is touched.
- **It refuses to require advance understanding of the legacy behavior.** Each extraction captures the monolith's actual behavior as a contract, so nobody re-derives it from memory or a stale design document.
- **It refuses to bet the business on one cutover date.** The monolith and the new service run side by side behind a flag until the service is proven equivalent, so one extraction can pause, slow or reverse without touching anything else in flight.

This doesn't remove risk. It spreads it over many small, independently reversible steps instead of one irreversible moment at the end.

{% include excalidraw.html file="rewrite-vs-strangler-risk" alt="A side-by-side risk-shape contrast. Left, the big-bang rewrite: three bets made at once (full behavioral understanding, outrunning decay, business patience for zero shipped value) feeding one irreversible cutover, with a late, all-or-nothing payoff. Right, the strangler fig: small steps starting with the review service, each proven equivalent before the next starts, running side by side with the monolith so any one step can pause, slow, or reverse without threatening the rest, with value shipping continuously." caption="Figure 3.2: One irreversible bet versus many reversible steps: the same migration, two risk shapes" %}

## Modernization as a measurable activity

The discipline has a checkable form: behavior stays constant while the properties you care about improve, and both halves are demonstrated, not asserted. Constant behavior is the job of the **behavior-equivalence suite**, a contract collection captured against the running monolith and re-run unchanged against every extracted service, with an **equivalence gate** in the pipeline that blocks a module's retirement until it passes. The improving properties (latency, startup time, memory footprint, deploy independence) are named up front and measured before and after, like any other optimization. A project that can't show both halves for one completed step isn't yet disciplined engineering, whatever its diagrams look like.

The Review extraction is the completed example; `examples/02-review-service/MIGRATION.md` has the full record. Phase A lifted the Spring code onto Quarkus with minimal changes through the Quarkiverse Spring-compatibility extensions, so the equivalence gate passed quickly and the extraction was de-risked before any elegance rewrite. Phase B refactored the same service to idiomatic Quarkus:

| Build | Startup time | Resident memory | Equivalence suite |
|---|---|---|---|
| Phase A: JVM, Spring-compat | 1.492 s | ~316 MB | 16/16 green |
| Phase B: JVM, idiomatic Quarkus | 1.431 to 1.437 s | ~304 MB | 16/16 green |
| Phase B: native image | 0.048 to 0.049 s | ~73 MB | 16/16 green |

The JVM-to-JVM gain is modest: startup is within run-to-run noise and memory drops about four percent from no longer loading three translation extensions at boot. The payoff is native image: roughly thirty times faster startup and just over four times less resident memory, the difference between a service that can scale to zero and back in the time a user notices a page load and one that can't.

The same unmodified 16-assertion suite passed against all three builds. The native build passed only after a closed-world reflection defect was found and fixed, one the JVM's more forgiving runtime reflection had hidden. Three runtimes, identical observable behavior, and a measured improvement in startup and memory (and, once the flag flips and the monolith's review module is decommissioned, deploy independence) that a team can defend with numbers instead of adjectives. Every later extraction repeats the shape: hold behavior constant, measure what changed.

{% include excalidraw.html file="migration-metrics-review" alt="A two-band comparison figure for the Review extraction's measured metrics. Top band, startup time: Phase A JVM Spring-compat at 1.492 seconds, Phase B JVM idiomatic Quarkus at 1.431 to 1.437 seconds, and Phase B native image at 0.048 to 0.049 seconds, with the native bar roughly 31 times shorter than Phase A's. Bottom band, resident memory: Phase A at about 316 MB, Phase B JVM at about 304 MB, and Phase B native at about 73 MB, with the native bar roughly 4 times shorter than Phase A's. Every row carries the same 16 of 16 green equivalence-suite result." caption="Figure 3.3: Startup time and memory across Phase A, Phase B JVM, and Phase B native, the same 16/16 equivalence suite throughout" %}

## The cost of inaction: coupling compounds

"Modernize eventually" is a decision with a price, and the price compounds. Constantine's observation is the mechanism: "a structure is stable if cohesion is high, and coupling is low." In a system where that balance has tipped, every feature added under deadline and every shortcut taken because the proper fix would touch three other teams' code adds coupling faster than cohesion, because the path of least resistance is to couple new code to what's already there.

The reference monolith makes this concrete. It carries six planted smells, catalogued in `examples/00-monolith/SMELLS.md`; on `main` all six are cured and the context packages are gone, and the monolith as designed is on the `reference/monolith-before` branch. Five of the six bear on coupling between contexts:

- **A shared schema** with cross-context foreign keys and joins, which makes inventory and order data inseparable at the database.
- **A god `OrderService`** that reaches directly into payment, shipping and inventory instead of calling them through a boundary.
- **One in-process ACID `@Transactional`** wrapping inventory, order, payment, shipment and notification into a single all-or-nothing commit across five bounded contexts.
- **A synchronous notification call** inside that same checkout transaction, so a slow notification provider can fail an order that had nothing to do with notifications.
- **No anti-corruption layer**, so every caller depends on the monolith's internal shapes.

The sixth is Review tangled into the monolith's single shared security configuration although it has no runtime dependency on any other context, which is why it can be extracted first.

None was an accident. They're the defensible shortcuts a real team takes under a real deadline, each cheap the day it's introduced. Cumulatively the god service and the shared schema make order placement nearly impossible to change safely without touching four other contexts, which is the lead-time and change-failure cost described above, now visible in source. Measuring coupling as a dimension, not a feeling, lets you see the compounding before a migration becomes urgent and expensive.

Each smell that survives another release cycle acquires code written against it and widens its blast radius. A schema with three cross-context foreign keys can be split with a weekend's migration script. The same schema with thirty foreign keys and a dozen reports built directly on it takes a dedicated team a quarter, if it can be split at all without a rewrite. "Modernize later" bets that the cost of cutting the seam stays flat while you wait, and that bet has historically lost. Treat the cost curve of accumulating coupling as a measurable quantity, and act while the cut is cheap.

> **Decision**
>
> Modernize when you can name the problem (lead time, change-failure rate, scaling limits or team autonomy) and measure it. Extract incrementally behind a strangler, hold behavior constant with the equivalence suite, and require a measured improvement from every step. If the domain boundaries are unclear, the team can't carry the operational cost, or you don't own the code, don't decompose.

> **🤖 Explore with your agent**
>
> "Read examples/00-monolith/SMELLS.md and, for each of the five coupling smells (shared schema, god OrderService, one ACID transaction, synchronous notification, no anti-corruption layer), name which of lead time, change-failure rate, scaling limits or team autonomy it hurts and the metric you would measure before and after fixing it. Then read examples/02-review-service/MIGRATION.md and say which measured property each build improved. Reject any smell you can't pair with a named problem and a metric."
>
> Check: every smell in the reply has a problem and a metric, and the numbers for the three Review builds (1.492 s, 1.431 s and 0.048 s startup) match the table in MIGRATION.md.

## For deeper coverage

- Kleppmann, *Designing Data-Intensive Applications*: reliability, scalability and maintainability.
- Newman, *Building Microservices*: the cost trade and incremental migration.
- Ozkaya, *Design Microservices Architecture with Patterns and Principles*: delivery discipline as a precondition.
- `examples/02-review-service/MIGRATION.md`: the full Phase A, Phase B and native measurement record.
- `examples/00-monolith/SMELLS.md`: the planted smells and where each is cured.
