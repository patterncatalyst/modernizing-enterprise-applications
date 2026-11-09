---
title: "Introduction & How to Use This Book"
order: 0
part: "Setting Up"
description: "The thesis, the shipping running example, the extraction order, the part map, and where the code lives: modernize one Spring Boot monolith with an agentic lifecycle and a behavior-equivalence gate."
---

This material modernizes a running system instead of describing patterns in the abstract. It builds a Spring Boot monolith with six bounded contexts (order, inventory, payment, shipping, notification, review), the shape of most shipping or e-commerce backends, and takes it apart one seam at a time. Each pattern arrives when the migration forces it. At the end the monolith is gone, six Quarkus and Apache Camel services stand in its place, and every step is in this repository as runnable code, a passing test suite and a written record of why.

{% include excalidraw.html file="monolith-to-microservices-outcome" alt="Side-by-side before/after: a single Spring Boot deployable with six in-process bounded contexts sharing one PostgreSQL schema and one ACID checkout transaction spanning five of them, versus six independent Quarkus services plus a GraphQL gateway, each owning its own schema, reached through one Camel REST edge router with a Kafka event backbone carrying the async and saga traffic." caption="Figure 0.1: The starting monolith and the six-service system it becomes" %}

## Who this is for

The reader is an engineer or architect accountable for a production system's behavior during a migration. You know what a saga and a circuit breaker are; the hard problems are where to cut a system nobody fully understands, proving the cut preserved behavior nobody wrote down, and doing it fast enough that the business case still holds.

Green-field instincts are often wrong against a decade of shared tables and implicit contracts between modules. If you maintain a monolith you didn't write, the reference monolith will look familiar: it's ordinary, with planted smells, so the cures transfer to your own system. The JDK, Maven, the Quarkus and Camel CLIs and a local observability stack are set up once in the next chapter.

## The central thesis

The argument has two levels.

**Architecture.** A monolith becomes microservices by strangling it: cut one bounded context free at a time, behind a proxy, with a translation layer at the seam, instead of stopping the world to rewrite. Fowler's Strangler Fig is decades old, and the anti-corruption layer, the outbox, the saga and CQRS are documented in more depth elsewhere. What's rarely shown is a continuous run through all of them against one system, in the order a migration forces, with code that proves each step.

**Execution.** The work doesn't follow a requirements document, design review, sprint, QA pass and change board handed between roles over weeks. It runs through an **Agentic Development Lifecycle (ADLC)**: a few coding agents inside a seven-phase loop with exactly two places a human stops and decides. An agent can read the legacy module, propose the seam, scaffold the extracted service and write its tests in one sitting. What makes that output trustworthy is a fast, automated, re-runnable check between every candidate change and "done", not a human rereading every line.

That check is the **behavior-equivalence suite**: a contract collection captured against the running monolith, covering every endpoint, status code and edge case the system actually produces, including the ones nobody would have specified from scratch. The collection is never edited to make a service pass. It's re-run unchanged against every extracted service, and the **equivalence gate** built from it blocks a service from being trusted, and the monolith module from being decommissioned, until the service answers exactly as the monolith did. During the first extraction the equivalence gate caught two defects, one of them a bug an entirely green test run had missed. A passing suite is evidence, not proof, so keep probing for what would make it pass by accident.

Stated once: take a Spring Boot monolith and modernize it into Quarkus and Apache Camel services, one pattern at a time, in the order the migration demands, driven by an agentic ADLC, with a behavior-equivalence gate proving at every step that the new code behaves like the old.

## The running example

One shipping and e-commerce platform runs through every chapter. It starts as a single Spring Boot deployable: one JVM, one shared PostgreSQL schema, REST endpoints for all six contexts, and an order-placement flow that reaches into inventory, payment, shipping and notification inside one ACID transaction. The design is what a team under deadline would have built, with planted smells:

- a shared schema with cross-context joins;
- a god `OrderService` that knows too much about every other context;
- one in-process ACID transaction spanning five contexts;
- a synchronous notification inside the checkout transaction;
- no anti-corruption layer anywhere;
- a review feature tangled into shared infrastructure although it's independent of everything else.

Each of the six smells is tagged in the code to the extraction that cures it. The extractions run in order of rising difficulty, not domain importance:

| Order | Context | What it forces |
|---|---|---|
| 1 | Review | REST only, no synchronous dependency: the cheapest proof that the extraction mechanism works end to end |
| 2 | Notification | An event backbone and the outbox |
| 3 | Inventory | A decomposed database and change data capture |
| 4 | Payment | A choreographed saga, because one ACID transaction is gone |
| 5 | Shipping | An orchestrated saga |
| 6 | Order, with a GraphQL gateway | CQRS; the core aggregate and hardest coupling go last |

{% include excalidraw.html file="extraction-sequence-ladder" alt="The six extractions in the order executed, rising in difficulty left to right: review (REST leaf), notification (async outbox), inventory (CDC plus a gRPC seam), payment (choreographed saga), shipping (orchestrated saga), and order with its GraphQL gateway (CQRS plus GraphQL)." caption="Figure 0.2: The extraction order: rising difficulty, not rising domain importance" %}

Each extraction gets its own chapter, its own equivalence-gate run and its own "ADLC in Action" callout with the trace that produced it. The end state is six services talking REST, gRPC and GraphQL as each relationship calls for, in a service mesh, traced end to end and delivered by a CI/CD pipeline that runs the equivalence gate on every change. It mirrors a production-grade Quarkus reference architecture, cited wherever an example is adapted from it.

## How it is organized

Eleven parts, numbered 0 to 10, in the chronology of a migration:

| Part | Name | Subject |
|---|---|---|
| 0 | Setting Up | Toolchain, project ledger, running every example |
| 1 | Why Modernize | The business and engineering case, including when microservices are the wrong answer |
| 2 | The ADLC | The seven phases, two human gates, the agents and MCP tools, and a worked run on a trivial change |
| 3 | The Reference Monolith | The "before" system and the tests that become the behavior-equivalence suite |
| 4 | Finding the Seams | Domain-driven design, event storming and coupling analysis as decision tools for the six boundaries |
| 5 | The Strangler Fig in Practice | The strangler proxy, the review and notification extractions, the anti-corruption layer and content-based routing |
| 6 | Data Across the Seam | Change data capture, the outbox done correctly, the CQRS read side, and what eventual consistency gives up |
| 7 | Coordinating Across Services | Choreographed and orchestrated sagas for payment and shipping, and a resilience chassis |
| 8 | Communication & Contracts | The order extraction and GraphQL gateway, the Quarkus and MicroProfile chassis, and a schema and contract registry |
| 9 | Operating the Modernized System | Deployment patterns, a service mesh and distributed tracing on Kubernetes |
| 10 | Delivering & Reflection | The CI/CD workflows, including the job that runs the equivalence gate, and a walk back through the pattern language with its edges stated |

## How to read the code

Every chapter that touches code has the same shape: the one new idea, the code that implements it, a Build, run, observe section with the commands, and a cross-check against an independent source where one exists. Conceptual chapters, including this one, say so and carry no token example.

Each extraction points at a directory under `examples/`. The monolith lives at `examples/00-monolith/`, the strangler proxy at `examples/01-strangler-proxy/`, and each extracted service beside them. Each has a run script that builds, starts and exercises it, and a `README.md`. The behavior-equivalence suite lives under `tooling/newman/`, versioned with the monolith it was captured from, and is the same collection re-run in every later gate.

Some quoted code exists only at one point in the migration. `main` is the finished system: six services, the GraphQL gateway and the Camel edge router, with `examples/00-monolith/` reduced to the shell left after its last context left. The runnable monolith as it stood before any extraction is on the `reference/monolith-before` branch (tagged `v0-monolith`). The repository right after each extraction is tagged `stage/01-review-extracted` through `stage/06-order-extracted`. When a chapter quotes code a later extraction moved or removed, such as the transactional outbox and its relay or the monolith's gRPC inventory client, check out `reference/monolith-before` or the matching `stage/NN` tag. Each citation's `examples/...` path is the directory the code lived in at that point.

## What you will be able to do

- **Find the seams** in a real monolith, using strategic and tactical domain-driven design, event storming and coupling analysis, so the order of cuts is deliberate.
- **Execute an extraction** with the behavior-equivalence suite as the acceptance gate: capture the contract suite against today's system, build the strangler seam, migrate the code in two phases, and prove with a re-runnable check that nothing observable changed before retiring the old code.
- **Run the process through an agentic ADLC:** frame intent precisely enough for an agent to act on, know which two moments need your judgment, and keep a ledger the next person can trust.

## What to expect

Everything that can be checked against an artifact is checked against one, and each chapter's verification-status footer says what the check covered and what it didn't. That includes failures: two defects surfaced during the first extraction, and the record names them and shows the fix.

## Where to go next

**Prerequisites & the Toolchain** sets up the JDK, Maven, the Quarkus and Camel CLIs and the local observability stack, and ends with a gate you can run to confirm the setup. **The Project Ledger** introduces the ledger and the reconciliation record the ADLC's final phase still owes, as a template for your own migration. The next part then covers when the work pays off and when it doesn't.
