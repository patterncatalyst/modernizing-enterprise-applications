---
title: "From SDLC to ADLC"
order: 5
part: "The AI Development Lifecycle (ADLC)"
description: "Why the traditional lifecycle breaks under agent-assisted work; the ADLC phases; which SDLC steps agents replace or augment; the human gates."
---

Part 1 made the case for modernizing this system and chose a strategy for
doing it. This chapter changes a different variable: not *what* we build, but
*how* the work itself gets done. Every pattern from here forward — the
strangler proxy, the anti-corruption layer, the saga, the gateway — gets
produced through a specific lifecycle, and that lifecycle is not the one most
of us learned. It is faster, it looks different on a whiteboard, and it needs
its own discipline to stay safe. This chapter names that lifecycle, shows why
the old one doesn't fit agent-assisted work, and introduces the seven-phase,
two-gate model — the **ADLC** — that every migration chapter from Part 5
onward will put to work.

## The SDLC, and why it looks the way it does

The software development lifecycle most engineering organizations run today —
whatever their flavor of Scrum, Kanban, or stage-gate process — still traces
a recognizable shape: requirements and planning, system design,
implementation, testing and QA, code review, deploy and release, monitoring,
and feedback that loops back to the top. A single pass through that loop
typically costs weeks for a well-scoped feature and months for anything that
touches a shared schema or crosses a team boundary.

That shape is not an accident of bureaucracy; it is a rational response to a
specific constraint: **writing code is slow relative to the cost of being
wrong.** When a line of code takes minutes of human attention to produce and
hours to properly review, you economize on attempts. You front-load thinking
into requirements and design so you don't discover a wrong assumption after
three sprints of implementation. You specialize roles — an analyst gathers
requirements, an architect designs, a developer implements, a tester
verifies, a reviewer gates, an operator deploys — because no one person can
hold the whole stack of concerns in their head at production quality, and
because specialization lets the organization scale past what one generalist
could carry. You insert a code-review stage not because developers write bad
code but because a second pair of eyes is cheap insurance against a mistake
that is expensive to ship. And you document the handoffs — tickets, design
docs, test plans, a traceability matrix — because the handoffs themselves are
where knowledge gets lost, and an audit trail is how a regulated or
contract-bound organization proves the work was actually done, not just
claimed.

Every one of those stages is a queue with a context switch at both ends. The
requirements doc sits in a backlog until a designer picks it up; the design
sits in review until an architect signs off; the pull request sits until a
reviewer has a free hour. None of those delays reflect laziness — they
reflect that each role is a scarce, specialized resource being
time-sliced across many pieces of work at once. That is why a modernization
effort under the traditional SDLC is measured in quarters per extracted
service: not because the code is hard to write, but because the code has to
pass through six expensive, serially-scheduled human gates to be trusted.

## What agentic coding tools change

A coding agent — a tool that can read a codebase, reason about a change, write
the code, write the tests, and explain what it did, inside a single
conversational session — does not remove any of those eight conceptual
stages. It collapses the *time* each one takes, and it moves several of them
inside one continuous loop instead of across a chain of separate people.
"Requirements and planning" becomes **expressing intent** to the agent in a
sentence or a paragraph. "System design" and "implementation" become the
agent **reading the surrounding code and proposing a change** in the same
breath, because for an agent there is no handoff cost between designing a
function and writing it. "Testing and QA" and "code review" become the agent
**writing and running tests alongside the code**, with a human reviewing a
finished diff rather than an abstract plan. What used to take weeks because
of queueing delay between specialized roles now takes minutes to hours
because one engineer, working with one model, carries the work through every
stage without waiting for anyone else's calendar.

{% include excalidraw.html
   file="sdlc-vs-adlc"
   alt="Two parallel eight-step cycles. Left, the traditional SDLC: requirements and planning (days-weeks), system design (weeks), implementation and coding (weeks-months), testing and QA (days-weeks), code review (days), deploy and release (days), monitoring and observability (ongoing), feedback and iteration (continuous) — looping back to step one. Right, the agentic SDLC: express intent (minutes), agent understands (seconds), agent implements (minutes), agent tests and docs (minutes), human review (minutes-hours), deploy and ship (minutes), monitoring and observability (continuous), learn and iterate (ongoing) — looping back to step one. A transformation arrow connects the two loops. Below, a Key Differences table: sequential handoffs becomes fluid agent flow; human codes everything becomes human guides, agent executes; docs as afterthought becomes docs generated inline; manual incident response becomes agent-assisted remediation."
   caption="Figure 5.1 — The traditional SDLC (weeks to months, handoff-driven) and the agentic SDLC it is becoming (hours to days, human-guides/agent-executes)" %}

Read the "Key Differences" table in Figure 5.1 carefully, because it says more
than "faster." **Sequential handoffs become fluid agent flow** — the queueing
delay between stages mostly disappears because one actor (human plus agent)
now owns several stages at once. **Human codes everything becomes human
guides, agent executes** — the engineer's job shifts from typing every line to
stating intent, setting constraints, and judging output, which is a different
skill than the one most of us were trained in. **Docs as afterthought becomes
docs generated inline** — because an agent that just wrote the code can
narrate it as it goes, documentation debt stops accumulating by default rather
than needing a separate backlog item. And **manual incident response becomes
agent-assisted remediation** — when something breaks in production, the same
tool that wrote the code can read the logs, propose the fix, and draft the
patch, collapsing the diagnose-to-patch loop the same way it collapsed the
design-to-implementation loop.

None of this removes risk — it relocates it. The traditional SDLC's slowness
was also, incidentally, where a lot of its safety lived: six serial human
gates are a crude but real form of error-correction. Collapse the stages
without replacing that error-correction with something else, and you have
simply made it faster to ship a wrong answer. That is the problem this book's
lifecycle exists to solve.

## The ADLC: the agentic SDLC, disciplined

The agentic SDLC in Figure 5.1 describes what becomes *possible* once a
coding agent is in the loop. It does not, by itself, say where human judgment
re-enters, what gets checked before a change is trusted, or how a team keeps
a traceable record of why a given change was made — the things the old SDLC's
slowness used to provide implicitly. This book's answer is the **AI
Development Lifecycle (ADLC)**: a disciplined instance of the agentic SDLC
with seven named phases and exactly two places where a human must stop the
loop and decide.

1. **Frame** — a human states the outcome and acceptance criteria for this
   step of work, as a short statement of intent plus an entry in the
   project's decision log. This is "express intent," made concrete and
   recorded rather than said once and forgotten.
2. **Map** — the agent does reconnaissance: it classifies the legacy module
   in play, maps its dependencies, and locates the seam this step will cut
   along. No code changes yet; this phase answers "what exactly am I
   touching."
3. **Plan** — the agent produces a concrete step plan and a decision-log
   entry describing the approach. **Human gate: the plan is approved before
   any code is written.** This is the point where a wrong assumption is
   cheapest to catch — before it is embodied in a diff.
4. **Generate** — the agent scaffolds and writes the code and its tests
   against the approved plan.
5. **Verify** — the agent (often a second, more skeptical pass) runs the test
   suite, checks the change against the **behavior-equivalence suite**, and
   runs a security scan. **Human gate: a human signs off on equivalence**
   before the change is trusted. This is the second and last point where the
   change depends on a human's judgment.
6. **Operate** — the change rolls out behind a feature flag, observed through
   the platform's telemetry, with the human controlling what percentage of
   traffic sees it and how fast that percentage grows.
7. **Reconcile** — the decision log, the build plan, and the reconciliation
   record are updated with the outcome, including any drift from what was
   planned. Nothing here is thrown away; it becomes the next step's "Map"
   input.

{% include excalidraw.html
   file="agentic-sdlc-to-adlc"
   alt="Two stacked bands. Top band, labelled Deck: agentic SDLC (8 steps): express intent, agent understands, agent implements, agent tests and docs, human review, deploy and ship, monitoring and observability, learn and iterate. Bottom band, labelled Book: ADLC (7 phases): Frame, Map, Plan, Generate, Verify, Operate, Reconcile. Dashed lines map each top step down to the phase or phases it corresponds to — express intent to Frame, agent understands to Map, agent implements spanning both Plan and Generate, agent tests and docs to Generate, human review to Verify, deploy and ship and monitoring and observability both to Operate, learn and iterate to Reconcile. Two dark gate markers sit between the bands: GATE: plan approval between agent implements and Plan/Generate, and GATE: equivalence sign-off between human review and Verify."
   caption="Figure 5.4 — Mapping the agentic SDLC's eight steps onto this book's seven-phase ADLC, with the two human gates marked where they land" %}

Figure 5.4 is worth reading phase by phase against Figure 5.1. "Express
intent" is Frame. "Agent understands" is Map. "Agent implements" is where the
plan gate sits — it spans both Plan and Generate because the agent's first
pass at implementing *is* the plan, and nothing past that pass happens until
a human has read and approved it. "Agent tests and docs" lands inside
Generate. "Human review" is where the equivalence gate sits, inside Verify —
not a rubber stamp on a diff, but a check against a concrete, re-runnable
suite. "Deploy and ship" and "monitoring and observability" both land in
Operate, because rollout and the telemetry that watches it are one
continuous activity, not two. "Learn and iterate" is Reconcile.

The three ledger artifacts this book keeps — `decisions.md`, `build-plan.md`,
and `reconciliation.md` — are the ADLC's substitute for the SDLC's
heavyweight design document, test plan, and traceability matrix. They do the
same job those documents did — recording *why* a decision was made, *what*
was planned, and *whether* the outcome matched the plan — but they are
lightweight, append-only, and largely agent-maintained, so keeping them
current costs almost nothing compared to the ceremony they replace.

## The tooling realization

Every phase in that loop needs an actor to carry it out, and naming those
actors in generic terms clarifies what is actually new here. It is not "a
model writes code" — it is a small cast of roles, each filling a gap the
traditional SDLC filled with a person or a ticket queue.

{% include excalidraw.html
   file="adlc-tooling"
   alt="An eight-step cycle, each step annotated with the generic tool role that carries it out: express intent — coding assistant (e.g. Claude Code); agent understands — local agent runtime; agent implements — local coding agent; agent tests and docs — local coding agent; human review — human; deploy and ship — internal developer platform (IDP); monitoring and observability — hosted AI platform; learn and iterate — issue tracker. Arrows connect each step to the next, cycling back to step one."
   caption="Figure 5.2 — The agentic loop annotated with generic tool roles at each step" %}

Figure 5.2 deliberately stays at the level of roles rather than product
names, because the roles are what matter and the specific tools filling them
will change faster than this book's print run: a **coding assistant** that
takes the human's stated intent; a **local agent runtime** that gives it
access to the filesystem, a shell, and the project's tools; a **local coding
agent** that does the actual reading, writing, and testing; a **human** who
reviews; an **internal developer platform (IDP)** that deploys what was
approved; a **hosted AI platform** that can watch production telemetry and
surface anomalies; and an **issue tracker** that captures what was learned so
the next pass through the loop starts from more context than the last one
did.

One design choice that changes the shape of a team's workflow is
*where* that local coding agent actually runs — on the engineer's own
machine, or inside a managed, shared execution environment.

{% include excalidraw.html
   file="adlc-local-vs-hosted"
   alt="A shared outer path (express intent, learn and iterate, monitoring and observability, deploy and ship) flanked by two parallel inner bands covering agent understands through human review. The local band runs each step through a local agent runtime, a local coding agent plus Podman, and a cloud dev environment. The hosted band runs the same steps through a hosted AI platform end to end. Both bands converge back onto the shared deploy-and-ship step."
   caption="Figure 5.3 — The same agentic loop, run two ways: a local execution path (local coding agent plus a local container runtime) versus a hosted path (a managed, hosted AI platform)" %}

The **local** path in Figure 5.3 keeps the agent on the developer's own
machine (or a cloud dev environment configured to feel like one), running
code and tests against a local container runtime. It gives the engineer full
visibility into every file the agent touches and every command it runs, at
the cost of each engineer needing a properly provisioned machine. The
**hosted** path moves the "understands / implements / tests / review" steps
into a managed, hosted AI platform — easier to standardize and govern across
a large organization, at the cost of a network hop and a shared execution
environment the engineer does not fully control. Neither path is "more
agentic" than the other; they are two deployment topologies for the identical
loop, and a team can reasonably run both — local for day-to-day development,
hosted for scheduled or fleet-wide tasks.

### What this book actually uses

Generic roles are useful for teaching the shape of the loop; this book is
concrete about which real tools fill those roles for the modernization work
ahead, because the ADLC in Action callouts in every migration chapter
reference them by name. The **coding assistant** is Claude Code, running
**locally**. Its **Plan** and **Verify** phases are carried out through the
**plan → execute → validate relay**: one model tier drafts the step plan (the
human gate sits immediately after this draft), a second tier executes it
against the approved plan, and the first tier returns to validate the result
against the behavior-equivalence suite before the second human gate is
offered. The **Map** and **Generate** phases lean on two **MCP servers** that
give the agent version-matched, tool-grounded knowledge instead of a
model's possibly-stale memory of a framework: a **Quarkus Agent MCP server**
that drives the `migrate-spring-to-quarkus` process, starts and inspects a
running Quarkus instance, and searches version-matched documentation; and a
**Camel MCP server** that validates routes, checks migration compatibility,
and queries the EIP and component catalogs. **Podman** is the local container
runtime underneath both — Dev Services, Testcontainers, the behavior-
equivalence suite's Postgres and Kafka — and **Quarkus** is the concrete
target runtime every extraction migrates onto.

A small, concrete artifact makes the traditional-versus-agentic contrast
sharper than the abstract loop does. Here is the same health-check wiring —
the mechanical, easy-to-get-wrong part of standing up a new service — written
by hand for Spring Boot, next to the Quarkus equivalent an agent scaffolds
during the Generate phase of a Phase A lift (the Quarkiverse Spring-
compatibility bridge introduced in Part 5's extraction chapters):

{% include codetabs.html langs="Spring Boot (hand-written)|Quarkus (agent-generated, Phase A lift)" %}

```java
// Written by hand, reviewed in a pull request, merged after a day's
// turnaround: a readiness contributor wired into Spring Boot Actuator.
// application.yml
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate.enabled: true
    readinessstate.enabled: true

@Component
public class DatabaseReady implements HealthIndicator {
    private final JdbcTemplate jdbc;
    public DatabaseReady(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Health health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Health.up().build();
        } catch (DataAccessException e) {
            return Health.down(e).build();
        }
    }
}
```

```java
// Scaffolded by the agent in the Generate phase, using the
// quarkus-spring-di compatibility extension so the lift stays mechanical;
// the equivalence gate is what confirms it behaves the same as the
// hand-written version above, not a second human re-reading every line.
// application.properties
quarkus.application.name=review-service

@Readiness
public class DatabaseReady implements HealthCheck {
    @Inject PgPool db;

    @Override public HealthCheckResponse call() {
        return db.query("SELECT 1").executeAndAwait() != null
            ? HealthCheckResponse.up("database")
            : HealthCheckResponse.down("database");
    }
}
```

The two fragments do the same thing by design. The point is not that the
agent-generated version is cleverer — it is nearly a direct translation — but
that it exists in minutes rather than a sprint, and that trusting it does not
rest on a second engineer re-reading it line by line. It rests on the
behavior-equivalence suite running against it unchanged, which is exactly the
subject of the next section.

## Why this matters specifically for modernization

Not every kind of software work needs this much ceremony around an agent's
output. A throwaway prototype can skip straight from Generate to Operate and
nobody is worse off if it is wrong. Modernizing a production system by
strangling pieces off a monolith is close to the opposite case: it is
refactoring under a hard constraint — **the system must keep behaving the way
it already behaves** — applied to code a different team wrote, for reasons
nobody fully remembers, that real customers depend on today. That is the
highest-stakes category of change there is, and it is exactly the category
the agentic SDLC's raw speed makes *most* dangerous without a verify loop
tight enough to keep up with it.

The ADLC's answer is to make the Verify phase cheap enough to run after every
candidate change, not just before a release: the monolith's own Newman
collection, captured once against the running "before" system, becomes the
**behavior-equivalence suite**, and the **equivalence gate** re-runs it
unchanged against every extracted service before that service is trusted and
before the corresponding monolith module is decommissioned. Because an agent
can produce several candidate approaches to a tricky extraction in the time a
human would take to produce one, the thing that makes that speed into
leverage rather than recklessness is a check cheap enough to run against
every candidate — which is precisely what a scripted, automated contract
suite is and a human code review is not. Speed without a tight gate is just a
faster way to ship a regression; speed *with* one turns the agent's many
attempts into free experiments, because every failed attempt is caught before
anyone ships it, not after.

This is also why the two-phase migration strategy used throughout Part 5
onward — Phase A, a fast lift onto Quarkus via Spring-compatibility
extensions; Phase B, a slower refactor to idiomatic Quarkus — fits the ADLC
so naturally. Both phases are Generate-then-Verify passes through the same
gate. Phase A de-risks the extraction quickly, because "does this behave like
the monolith did" is cheap to check and doesn't require the code to be
beautiful yet. Phase B is where the "why Quarkus" case gets made with
measured numbers, and it gets to take that slower, more careful pass
*because* Phase A has already proven the extraction is safe.

## The limits: where humans stay in control

None of this is a claim that the loop runs itself. The two gates in Figure
5.4 are not bureaucratic leftovers from the old SDLC — they are the two
places in this entire lifecycle where a human's judgment cannot be
delegated, for two reasons. An agent can
produce a plan, but it cannot be trusted to decide that *its own* plan is the
right one to execute against a production system someone else depends on —
that decision needs a human who understands the business consequences of
being wrong. An agent can run a test suite and report green, but it cannot be
the one who decides that "green" is sufficient evidence to retire the
monolith module it just replaced — that is a sign-off, not a computation, and
it stays with a person.

Two further limits apply. First, every "ADLC in Action"
callout in this book's migration chapters shows **pre-captured, reproducible
tool output** — narrated transcripts checked into the repository — rather
than tool calls re-run live at build or read time. That trade favors
determinism and reviewability over the marginal novelty of a live
demonstration: a reader six months from now sees the exact loop that produced
the chapter's code, unaffected by a tool's version drift between when the
chapter was written and when it is read. Second, this book is not exempt from
its own argument. The `_plans/` ledger behind it — `decisions.md`,
`build-plan.md`, this project's own reconciliation record — is not a
teaching prop; it is the actual record of frame statements, approved plans,
and verify results that produced the chapter you are reading, gated by the
same two checkpoints described above. The book is built the way it teaches.

> **Further reading** — Two outside references deepen this chapter without
> expanding its scope. Michael Albada's *Building Applications with AI
> Agents* is a useful general treatment of agent architectures underneath
> the specific loop this book uses. Daniel Oh's **Enterprise Agentic AI
> Workshop** (Quarkus- and Java-native agentic patterns) is a closer
> cousin, and four of its exercises map directly onto this book's model:
> its **human-gate-plus-tracing** exercise maps to our two human gates and
> the observability built into the Verify phase; its **plan-and-execute**
> dynamic re-planning maps to our Plan→Generate→Verify loop and its repair
> rounds; its **supervisor-orchestration** exercise — a coordinator
> delegating to specialist sub-agents — is the direct analog of the
> plan→execute→validate relay this book uses, covered in depth in the next
> chapter; and its use of `AGENTS.md` to govern agent behavior is the
> analog of this project's own `_plans/` ledger, covered in depth in
> Chapter 7.

## What's next

This chapter named the lifecycle and its seven phases; it did not yet open up
*how* the agent side of that loop is actually organized — which model does
which phase, what the MCP surface looks like in practice, and when a human
must step in to decide rather than review. **Chapter 6, "Agents, Skills &
MCP Tools in the Loop,"** takes that apart in detail. And because a phase
model is easy to describe and easy to leave abstract, **Chapter 7, "The ADLC
Safety Net,"** runs the entire loop once, end to end, on a trivial change —
establishing the exact "ADLC in Action" callout format that every migration
chapter from Chapter 15 onward will reuse at rising difficulty.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. The codetab above illustrates a Phase A
lift pattern described in Part 5; it is not itself a standalone runnable
artifact. The claims that are checkable are the ones in this book's own
`_plans/` ledger, which Chapter 7 shows being produced live by the loop this
chapter describes.*
