---
title: "The Pattern Language, Revisited"
order: 32
part: "Delivering & Reflection"
description: "The microservices pattern catalog re-walked against the completed migration: which patterns earned their place, which were deferred and why, over-decomposition, the 2026 chassis, and the method retrospective."
duration: 25 minutes
---

## Learning objectives

By the end of this chapter you can:

- Judge each adopted pattern (strangler fig, bounded context and anti-corruption layer, database per service, transactional outbox, CDC, CQRS, both saga styles, GraphQL composition, schema registry, the chassis) against the pain it solved.
- Explain why a big-bang rewrite, full event sourcing, LRA/Narayana, the circuit breaker and a GitOps operator were deferred, and what pain would justify each.
- Recognize over-decomposition (nanoservice sprawl collapsing into a distributed monolith) and apply the rule that a context splits further only when a real seam demands it.
- Explain why the behavior-equivalence suite was the Verify-phase artifact that made agent-driven change safe to merge.
- Read the scorecard by concern as "used", "used with a scoping note" or "deferred with a stated reason".
- List the remaining horizons as a bounded set: delivery loop, durable sagas, observability gaps, security past the supply-chain gate.

## Why this matters

The monolith is decommissioned. It's frozen in-repo as a reference, no request reaches it, each of the six bounded contexts owns its data and its deployable, and the strangler proxy routes unconditionally to the six services. With the system finished, each pattern can be judged: should it have been adopted, and for the ones put down, why?

The playbook was Sam Newman's *Monolith to Microservices*: decompose one seam at a time, strangle incrementally instead of rewriting, and let each move prove itself before the next. Chris Richardson's pattern map, organized by concern, is the fuller catalog to judge against. Each pattern was introduced when the migration hit the problem it solves. Walking them backward turns the catalog into judgments you can defend.

{% include excalidraw.html file="pattern-language-scorecard" alt="A four-column scorecard grouping the patterns by concern. Decomposition: Strangler Fig, Bounded Context, Anti-Corruption Layer (used), Branch by Abstraction (adapted). Data: Database per Service, Transactional Outbox, Change Data Capture (used), Event Sourcing and CQRS (CQRS used, full event sourcing deferred). Communication and contracts: choreographed Saga, orchestrated Saga, API Composition with GraphQL (used), Schema Registry (used, isolated). Reliability, operations and chassis: Microservice Chassis, Service Mesh and tracing, supply-chain and CI gates (used), reliability patterns (used where earned, circuit breaker deferred). A bottom row of dashed boxes marks what was left on the shelf: big-bang rewrite, full event sourcing, LRA/Narayana saga, GitOps operator and canary." caption="Figure 32.1: The pattern language re-walked: where each pattern landed and the verdict" %}

## Decomposition

The decision everything else depends on was strangler fig, not rewrite. A rewrite is a flag day: one cutover, with no proof the new system behaves like the old until both carry production traffic. The strangler turned it into six small, independently reversible cutovers, each gated by the behavior-equivalence suite, with the system running throughout. The rewrite sits in the deferred row not because rewrites are never right, but because for a system that must keep serving traffic while it changes, incrementalism makes the risk payable.

Bounded contexts kept the six seams from being arbitrary. Event storming found them in the domain (order, inventory, payment, shipping, notification, review) instead of inventing them to hit a service count. The check is the anti-corruption layer: at every extraction the data crossing the seam was the data the domain already exchanged, so the layer had little to translate. When an ACL has nothing to translate, the seam is real. All six were.

## Data

Decomposition set the shape; data made the migration hard. Database per service is the pattern the whole migration is built around: six contexts that shared one schema each ended as the sole owner of theirs.

- **Transactional outbox.** You can't atomically write your database and publish to Kafka, so the event goes to an outbox table in the same local transaction and is relayed afterward. This replaces the dual write that looks fine until the process dies between the two writes.
- **Change data capture.** Used, then retired. Debezium backfilled the inventory service's data from the monolith during the cutover window and was torn down once the monolith stopped writing inventory, with the retirement scripted in `scripts/retire-debezium.sh`. CDC was a transition tool, not a standing replication dependency.
- **CQRS, but not full event sourcing.** The order service split into a write model and a rebuildable read model because the read and write shapes diverged. Full event sourcing, with the log as system of record, costs a rebuildable log, versioned events and snapshots. You pay that for temporal queries or an audit log as source of truth, and this system needed neither.

## Communication and contracts

Coordinating a workflow that spans six services took both saga styles, because you'll use both in different places.

| Style | Where | Strength | Cost |
|---|---|---|---|
| Choreographed | payment extraction | loosely coupled, resilient | no single place shows the whole workflow |
| Orchestrated (Camel Saga EIP) | shipping extraction | steps and compensations are legible in one route | the coordinator is in the critical path |

The GraphQL gateway is API composition at the edge: one query stitches order, inventory, payment, shipping and review into one response instead of five calls. The schema registry (Avro and Apicurio) keeps event-driven services from breaking each other silently. It carries a scoping note: it shipped as an isolated demonstrator, because retrofitting every topic would have been a migration of its own.

## Reliability and the chassis

Reliability patterns were used where a real failure mode earned them: deadlines on the gRPC calls across the inventory seam, and idempotent handling in the Kafka consumers because at-least-once delivery demands it. The circuit breaker was deferred, since without a real cascading-failure load there is nothing to tune it against. The service mesh follows the same restraint: Istio provides mTLS and tracing and configures no traffic policy, because the system has no traffic-shaping need.

The microservice chassis (configuration, health checks, metrics, structured logging, graceful shutdown) has changed character. With Quarkus and MicroProfile a service acquires it by declaring a dependency instead of building it: `@ConfigProperty` is configuration, `quarkus-smallrye-health` gives probes Kubernetes can read, and `quarkus-opentelemetry` plus three properties pointing at a collector gives distributed tracing, with no change to business logic. Native compilation, with sub-50ms startup measured on the review service, is part of the same opt-in. All six services run on it.

## Over-decomposition

{% include excalidraw.html file="over-decomposition-spectrum" alt="A left-to-right spectrum from one monolith (too coupled) through a modular monolith, to a highlighted right-sized band of six context-aligned services plus a graphql-gateway where this project landed, to finer-grained services, to nanoservice sprawl and distributed monolith (too chatty) at the far right. An arrow labelled more, smaller services runs beneath." caption="Figure 32.2: The over-decomposition spectrum: the goal is context-aligned services, not maximal splitting" %}

Both ends of the spectrum are bad. The left end is the starting monolith, too coupled to change one part without risking all of it. The right end is worse and more seductive because it looks like progress: a service per entity or class, each too small to own a capability and so chatty that the result is a distributed monolith. You get the operational cost of microservices and the coupling of a monolith, with a network between method calls. The tell is that you can't change one service without coordinating a release across several.

This project stopped at six services plus a gateway because the count came from the six bounded contexts found by event storming before any code moved. A seventh service would need a boundary inside one of the six where data and lifecycle diverge, and there wasn't one.

> **Decision**
>
> Adopt a pattern when the migration hits the pain it solves, and defer it, with the pain named, when it doesn't. Split a context further only when a real seam inside it demands it, never to hit a number.

## The method: an ADLC retrospective

A second pattern language ran underneath the migration: the Agentic Development Lifecycle of Frame, Plan, Generate, Verify, Reconcile. The completed migration supports a claim made early: agent-driven change is safe to the degree that its verification is automated and behavioral.

The essential element was behavior equivalence, the Newman collection that had to stay green across every cutover, in both flag positions, before any monolith module was decommissioned, and that CI runs on every push. It made it safe to let an agent lift a service onto Quarkus, refactor it to idiomatic code and cut traffic over, and the safety came from a check that goes red the instant behavior drifts, not from trust in the agent. Verify is what makes the work mergeable. Without that collection, every step is a leap of faith; with it, agent-generated change is as reviewable as any other.

Reconcile is the least glamorous phase and the one most often skipped. Each migration step ended with a verification status that said what had been run and what hadn't, such as the Loki gap and the untraced Kafka saga hops in the service-mesh step. That keeps the gap between what was claimed and what was verified visible, so documentation doesn't drift into fiction.

## Further horizons

The system is coherent and verified at the scope it claims, not production-complete. The deferred row of the scorecard plus these items map what a production push would add:

- **Close the delivery loop.** An image-registry push, a GitOps operator (Argo CD or Flux) reconciling `deploy/k8s`, and Istio weighted canary. Deferred until there is a cluster worth the operator's upkeep.
- **Durable, distributed sagas.** `InMemorySagaService` is correct for a single-JVM demonstrator and wrong for production. LRA/Narayana is the durable, crash-recoverable coordinator a real deployment needs.
- **Observability's remaining corners.** A node-level log-shipping DaemonSet so Loki receives application logs, and Kafka-aware instrumentation so saga hops that ride Kafka topics appear as spans instead of vanishing at the L7 boundary Istio can see.
- **Security past the supply-chain gate.** The SBOM, scan and policy gate is proven. Production adds secrets management, image signing, provenance attestation and the secure-by-default hardening that was scoped but shown only in part.

Each is a bounded increment, the only kind of future work a strangler mindset should produce.

## For deeper coverage

- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the incremental, strangler-first playbook the migration followed; see the [Reading List](/bibliography/).
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the fuller pattern catalog (saga, CQRS, API composition, outbox) walked by concern against the finished system.
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): bounded contexts and the anti-corruption layer.
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the pattern that made the risk of a live migration payable, six cutovers in a row.
- Sam Newman, *Building Microservices* (2nd ed., O'Reilly, 2021): service boundaries and independent deployability, the standard the six-service stopping point is measured against.
- Michael Nygard, *Release It!* (2nd ed., Pragmatic Bookshelf, 2018): the reliability-pattern restraint (circuit breaker deferred, no traffic-shaping Istio policy) behind the reliability column.
