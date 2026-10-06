---
title: "The Pattern Language, Revisited"
order: 32
part: "Delivering & Reflection"
description: "The microservices pattern catalog re-walked against the completed migration, in the spirit of Sam Newman's incremental, strangler-first approach: which patterns earned their place, which were deliberately deferred, the anti-patterns of over-decomposition, the chassis in 2026, and the ADLC retrospective."
duration: 25 minutes
---

The monolith is gone. Not deleted — Chapter 26 froze it in-repo as a reference —
but decommissioned: no request reaches it, every one of the six bounded contexts
owns its own data and its own deployable, and the strangler proxy that once
chose between old and new on every request now routes unconditionally to the six
services it grew over. This closing chapter does not add a pattern or an example.
It does something the first thirty-one chapters were too busy to do: it stands
back from the finished system and asks, of each pattern the book picked up,
*did it earn its place?* — and, just as important, of each pattern the book put
down, *why?*

The playbook we have followed since Part 1 is Sam Newman's: *Monolith to
Microservices* — decompose a seam at a time, prefer incremental strangling to a
big-bang rewrite, and let each move prove itself before the next. To take stock
of the finished system it helps to lay that work against the fuller pattern
catalog the field has accumulated — Chris Richardson's pattern map is the most
complete — organized by concern: decomposition, data management, communication,
reliability, observability, deployment, and the cross-cutting chassis. We walked
those patterns forward, one pain at a time, each introduced the moment the
migration hit the problem it solves. Walking them backward, with the whole
system built, is where a catalog stops being a catalog and becomes a set of
judgments you can actually defend.

{% include excalidraw.html file="pattern-language-scorecard" alt="A four-column scorecard grouping the patterns the book walked by concern. Decomposition: Strangler Fig, Bounded Context/DDD, Anti-Corruption Layer (all used in anger), Branch by Abstraction (adapted). Data: Database per Service, Transactional Outbox, Change Data Capture (used), Event Sourcing/CQRS (CQRS used, full ES deferred). Communication & Contracts: choreographed Saga, orchestrated Saga, API Composition/GraphQL (used), Schema Registry (used, isolated). Reliability/Ops/Chassis: Microservice Chassis, Service Mesh + Tracing, Supply-chain + CI gates (used), Reliability patterns (used where earned, circuit breaker deferred). A bottom row of dashed ghost boxes marks what was deliberately left on the shelf: rewrite/big-bang, full event sourcing, LRA/Narayana saga, GitOps operator + canary." caption="Figure 32.1 — The pattern language re-walked: where each pattern landed and the verdict; every box is a claim verified in its own chapter" %}

## Decomposition: the patterns that set the shape

The whole migration rests on one decision, made in Part 1 and never revisited:
**strangler fig, not rewrite.** The scorecard's top-left box is the one that
earned its place most emphatically, because everything else in the book is only
possible because of it. A rewrite would have meant a flag day — a single
terrifying cutover from an old system to a new one, with no way to prove the new
one behaved like the old until the moment both were load-bearing. The strangler
fig turned that one flag day into six small, independently reversible cutovers,
each one gated by the behavior-equivalence suite, each one leaving the system
running the whole time. The rewrite box sits in the deferred row not because
rewrites are never right, but because for a system that must keep serving traffic
while it changes, the strangler's incrementalism is the pattern that makes the
risk payable.

**Bounded contexts** (Part 4) are what kept the six seams from being arbitrary.
The event-storming session in Chapter 12 found the seams in the domain —
order, inventory, payment, shipping, notification, review — rather than
inventing them to hit a service count. That distinction is the whole difference
between the scorecard and a slide of buzzwords: the services exist because the
business has those boundaries, and the proof is that every extraction's
anti-corruption layer (Chapter 16) was *honest* — the data crossing each seam
was the data the domain already exchanged, not a translation layer papering over
a boundary drawn in the wrong place. When an ACL has nothing to translate, you
found a real seam. All six did.

## Data: where the hard problems actually lived

If decomposition set the shape, **data** is where the migration got genuinely
hard, and the scorecard's second column is the densest for a reason.
**Database-per-service** is the pattern the whole book is secretly about —
six contexts that started life sharing one schema, each ending as the sole owner
of its own. Everything in Part 6 and Part 7 exists to make that ownership
possible without losing data or breaking behavior in the handoff.

The **transactional outbox** (Chapters 17 and 20) earned its place by solving the
problem every "just publish an event" tutorial skips: you cannot atomically write
your database *and* publish to Kafka, so you write the event to an outbox table in
the same local transaction and relay it afterward. The book spent a full chapter
on getting it right rather than demonstrating the dual-write that looks fine until
the process dies between the two writes. **Change data capture** (Chapter 19)
earned a more interesting verdict: used, and then deliberately *retired*. Debezium
backfilled the inventory service's owned data from the monolith during the cutover
window and was torn down the moment the monolith stopped writing inventory —
CDC as a transition tool, not a permanent architecture, with the retirement
scripted (`scripts/retire-debezium.sh`) rather than left running as a standing
replication dependency nobody remembers the reason for.

The honest partial verdict in this column is **event sourcing and CQRS**. The book
used CQRS — Chapter 26 split the order service into a write model and a
rebuildable read model — because the read and write shapes genuinely diverged.
It did *not* adopt full event sourcing, where the event log becomes the system of
record, and Chapter 21 said why: the complexity of a rebuildable event log,
versioned events, and snapshotting is a cost you pay when you need temporal
queries or an audit log as the source of truth, and this system needed neither.
CQRS-lite sufficed; full event sourcing sits in the deferred row, named and
costed rather than cargo-culted.

## Communication and contracts: coordinating without a conductor

Once six services own their data, the question becomes how they coordinate a
workflow — like a checkout — that spans all of them. The book deliberately showed
**both** saga styles, because the choice between them is one of the real
judgments this field demands. The payment extraction (Chapter 23) used a
**choreographed saga**: services react to each other's events with no central
coordinator, which is loosely coupled and resilient but diffuse — no single place
tells you what the whole workflow is. The shipping extraction (Chapter 24) used an
**orchestrated saga** via the Camel Saga EIP, where a route explicitly sequences
the steps and their compensations, which is legible and centralized but puts the
coordinator in the critical path. Showing both, on the same domain, is worth more
than picking one and declaring it correct: the scorecard marks both as used in
anger because the *lesson* is that you will use both, in different places, for
different reasons.

The **GraphQL aggregation gateway** (Chapter 26) is API composition done at the
edge — one query surface stitching order, inventory, payment, shipping, and review
into a composed response, so a client makes one call instead of five. And the
**schema registry** (Chapter 28, Avro + Apicurio) is the contract discipline that
keeps event-driven services from breaking each other silently: it earned its place
but carries a scoping note on the scorecard — it shipped as an isolated
demonstrator rather than being retrofitted across every topic, because adding it
everywhere at once would have been a migration of its own, and the book chose to
show the pattern cleanly rather than half-wire it into six services.

## Reliability, operations, and the chassis in 2026

The right-hand column is where the modernized system becomes *operable*, and it is
where the book was most disciplined about not over-building. **Reliability
patterns** (Chapter 25) carry the most careful verdict in the whole scorecard:
timeouts, retries, and idempotency were used where a real failure mode earned
them — the gRPC calls across the inventory seam got deadlines, the Kafka consumers
got idempotent handling because at-least-once delivery demands it — but the
circuit breaker was deliberately deferred, because a circuit breaker without a
real cascading-failure load to tune it against is a guess wearing the costume of
rigor. That restraint is the same discipline the service mesh chapter showed:
Chapter 30 installed Istio for mTLS and tracing and pointedly configured *no*
traffic policy, because this system had no traffic-shaping need to justify one.

The **microservice chassis** (Chapter 27) is the pattern that, in 2026, has the
most changed character from when it was first named as a pattern. The chassis is the
cross-cutting foundation every service needs — configuration, health checks,
metrics, structured logging, graceful shutdown — and the book's thesis, repeated
in Chapter 27 and shown again in Chapter 30, is that with Quarkus and MicroProfile
the chassis has become something a service *acquires by declaring a dependency*
rather than something a team builds and maintains. `@ConfigProperty` is config.
Adding `quarkus-smallrye-health` is health probes Kubernetes can read. Adding
`quarkus-opentelemetry` and pointing three properties at a collector is
distributed tracing — Chapter 30 instrumented three services that way without
touching a line of their business logic. The 2026 chassis is not a library you
write; it is a platform you opt into, and native compilation (sub-50ms startup,
shown on the review service in Chapter 15) is part of what you opt into. The
scorecard marks it used in anger because every one of the six services runs on it.

{% include excalidraw.html file="over-decomposition-spectrum" alt="A left-to-right spectrum from one monolith (too coupled) through a modular monolith, to a highlighted right-sized band of six context-aligned services plus a graphql-gateway where this project landed, to finer-grained services, to nanoservice sprawl / distributed monolith (too chatty) at the far right. A spectrum arrow labelled 'more, smaller services' runs beneath, with 'too coupled' at the left end and 'too chatty' at the right." caption="Figure 32.2 — The over-decomposition spectrum: the goal is context-aligned services, not maximal splitting; past the sweet spot lies the distributed monolith" %}

## The anti-patterns of over-decomposition

The scorecard is a list of patterns that earned their place. It is just as
important to name the failure mode that comes from applying them too
enthusiastically, because it is the single most common way a microservices
migration goes wrong — and the book avoided it by construction rather than by
luck. The spectrum in Figure 32.2 has a sweet spot, and both ends are bad.

The left end is the monolith the book started from: too coupled to change one
part without risking all of it. But the right end is worse *and more seductive*,
because it looks like progress: **nanoservice sprawl**, a service per entity or
per class, each too small to own a meaningful capability, all of them so chatty
with each other that you have built a **distributed monolith** — all the
operational cost of microservices with all the coupling of a monolith, and a
network between every method call to make it slower and less reliable than either.
The tell is that you cannot change one service without coordinating a release
across several, which is precisely the pain decomposition was supposed to cure,
now made worse by distribution.

This project stopped at six services plus a gateway, and the scorecard's
discipline is why: the count came from the six bounded contexts the domain
actually has, found by event storming before any code moved. The book never asked
"how can we split this further"; it asked "where are the real seams," and stopped
when it ran out of them. A seventh service would have needed a seventh context —
a real boundary inside one of the six where the data and the lifecycle genuinely
diverge — and there wasn't one. That is the whole governing rule of the right-hand
side of the spectrum: **split a context further only when a real seam inside it
demands it, never to hit a number.** Finer-grained is a valid destination; it is
just not a goal.

## The method: an ADLC retrospective

There is a second pattern language running underneath this book, and it is the one
the title has been pointing at since Part 2: the **AI Development Lifecycle**.
Every migration chapter carried an "ADLC in Action" trace — Frame, Plan, Generate,
Verify, Reconcile — and the completed migration is the evidence for a claim the
book made early and has now earned the right to assert plainly: *agent-driven
change is safe exactly to the degree that its verification is automated and
behavioral.*

The load-bearing element was never the code generation. It was the
**behavior-equivalence suite** — the Newman collection that asserted the system's
observable behavior and had to stay green across every single cutover, in both
flag positions, before any monolith module was decommissioned. That suite is what
made it safe to let an agent lift a service onto Quarkus, refactor it to idiomatic
code, and cut traffic over to it: not trust in the agent, but a gate that would go
red the instant behavior drifted, run in CI (Chapter 31) on every push. The ADLC's
Verify phase is not a step that happens after the work; it is the thing that makes
the work mergeable. Strip the equivalence suite out of this book and every
remaining chapter becomes a leap of faith. Keep it, and agent-generated change
becomes as reviewable as any other change — more so, because the gate is objective.

The Reconcile phase deserves its own closing note, because it is the least
glamorous and the one most often skipped. Every chapter ended with a
verification-status footer that said, specifically and often uncomfortably, what
had actually been run and what had not — Chapter 30 naming its Loki gap and its
untraced Kafka saga hops, Chapter 31 marking its CI incarnation unverified because
nothing was pushed. That honesty is not a disclaimer; it is the Reconcile phase
doing its job, keeping the gap between what was claimed and what was verified
visible rather than letting it rot into the quiet drift that turns documentation
into fiction. A method that produces confident prose about code that was never run
is worse than no method. This one produces footers that tell you exactly how far
to trust each chapter.

## Clearly-scoped further horizons

A book that ended by claiming the system is production-complete would be breaking
its own honesty discipline on the last page. It is not. The deferred row of the
scorecard, plus a handful of things beyond it, is the real map of what a
production push would add — each named with the same specificity the chapters
used, so "future work" is a scoped list and not a shrug:

- **Close the delivery loop.** Chapter 31's dashed boxes — an image-registry push,
  a GitOps operator (Argo CD or Flux) reconciling `deploy/k8s` into the cluster,
  and Istio weighted canary for progressive rollout at the infrastructure layer —
  are the honest completion of "CI/CD," deferred under the no-speculative-
  infrastructure rule until there is a cluster worth the operator's upkeep.
- **Durable, distributed sagas.** Chapter 24's `InMemorySagaService` is correct
  for a single-JVM demonstrator and wrong for production; LRA/Narayana (named
  there) is the durable, crash-recoverable coordinator a real deployment needs.
- **Observability's remaining corners.** Chapter 30 named them: a node-level
  log-shipping DaemonSet so Loki actually receives application logs, and
  Kafka-aware instrumentation so the saga hops that ride Kafka topics show up as
  spans instead of vanishing at the L7 boundary Istio can see.
- **Security past the supply-chain gate.** Chapter 31 proved the SBOM/scan/policy
  gate; a production push adds secrets management, image signing and provenance
  attestation, and the secure-by-default hardening DRQ-020 scoped but this book
  showed only in part.

None of these is a gap in the book's argument; each is a clearly-bounded next
increment, which is the only kind of future work a strangler-fig mindset should
ever produce. The system you have is coherent and verified at the scope it claims.
The next increments are named, costed, and waiting — exactly as every seam in this
migration was, right up until the moment the pain of leaving it undone finally
exceeded the cost of doing it.

## What you learned

- **A pattern earns its place by solving a pain you actually have** — the
  strangler fig, database-per-service, the outbox, and both saga styles were used
  because the migration hit the problems they solve; the rewrite, full event
  sourcing, LRA, and the circuit breaker were deferred because the pains that
  justify them weren't present. The scorecard is a set of defensible judgments,
  not a checklist to complete.
- **Over-decomposition is the seductive failure mode** — nanoservice sprawl and
  the distributed monolith have all the cost of microservices and all the coupling
  of a monolith. Six services came from six bounded contexts found in the domain,
  and the migration stopped when it ran out of real seams, not when it ran out of
  ambition.
- **The 2026 chassis is a platform you opt into** — with Quarkus and MicroProfile,
  config, health, metrics, tracing, and native compilation are acquired by
  declaring dependencies, not by building and maintaining a framework.
- **The equivalence suite is what made agent-driven change safe** — the ADLC's
  Verify phase, automated and behavioral and run on every push, is the load-
  bearing element; the Reconcile phase's honest status footers are what keep the
  method's claims from drifting into fiction.

That is the book. A Spring Boot monolith, strangled one seam at a time into six
Quarkus services that own their data, coordinate without a conductor, run on a
chassis they opted into, and prove on every push that they still behave — built
by a method that is safe precisely because it verifies everything it claims, and
honest about everything it hasn't.

---

*Verification status: <span class="status status--verified">synthesis</span> —
this chapter introduces no new code or runnable example; it re-walks and
cross-references claims each verified in its own chapter. Each scorecard verdict
(Figure 32.1) and spectrum position (Figure 32.2) is traceable to the chapter
and example named in it: strangler/ACL (ch.14-16, `examples/01-strangler-proxy`);
bounded contexts (ch.11-13); database-per-service/outbox/CDC (ch.17-20,
`examples/03`,`04`; `scripts/retire-debezium.sh`); CQRS (ch.21,26,
`examples/07-order-service`); choreographed/orchestrated sagas (ch.23-24,
`examples/05`,`06`); GraphQL composition (ch.26, `examples/08-graphql-gateway`);
schema registry (ch.28, `examples/09-schema-registry-demo`); chassis (ch.27);
reliability (ch.25); mesh + tracing (ch.30, verified live on minikube);
supply-chain + CI gates (ch.31, verified live). Deferred items (rewrite, full
event sourcing, LRA/Narayana, GitOps operator + canary, Loki log shipping,
Kafka-aware tracing, image signing) are each named as deferred in the chapter
cited above, consistent with the no-speculative-infrastructure discipline
(DRQ-001) and the security-by-design scope (DRQ-020). No claim in this chapter
should be read as newly verified here; re-confirm any specific verdict against
its source chapter's own verification footer.*
</content>
</invoke>
