---
title: "From SDLC to ADLC"
order: 5
part: "The Agentic Development Lifecycle (ADLC)"
description: "Why the traditional lifecycle breaks under agent-assisted work, the seven ADLC phases, which SDLC steps agents replace or augment, and the two human gates."
---

## Learning objectives

By the end of this chapter you can:

- Explain why the SDLC's stages are queues, and which of them an agent collapses.
- Name the seven ADLC phases and the two places a human must stop the loop.
- Map the agentic SDLC's eight steps onto the phases.
- Choose between a local and a hosted execution path for the coding agent.
- Say why a cheap, scripted equivalence check is what makes agent speed safe on a production system.

## Why this matters

Every pattern in the extractions (the strangler proxy, the anti-corruption layer, the saga, the gateway) is produced through a lifecycle, and it isn't the one most engineers learned. It's faster, it looks different on a whiteboard, and it needs its own discipline to stay safe. The ADLC is that discipline: seven phases and two human gates.

## The SDLC, and why it looks the way it does

Most organizations run a recognizable loop, whatever the flavor of Scrum, Kanban or stage-gate: requirements and planning, system design, implementation, testing and QA, code review, deploy and release, monitoring, and feedback to the top. One pass costs weeks for a well-scoped feature and months for anything touching a shared schema or crossing a team boundary.

That shape answers a specific constraint: **writing code is slow relative to the cost of being wrong.**

- When a line takes minutes to write and hours to review, you economize on attempts and front-load thinking into requirements and design.
- You specialize roles (analyst, architect, developer, tester, reviewer, operator) because no one person holds every concern at production quality.
- You add code review because a second pair of eyes is cheap insurance against an expensive mistake.
- You document handoffs (tickets, design docs, test plans, a traceability matrix) because handoffs are where knowledge is lost, and an audit trail is how a regulated or contract-bound organization proves work was done.

Every stage is a queue with a context switch at both ends. The requirements doc waits for a designer, the design waits for an architect, the pull request waits for a reviewer's free hour. The delay isn't laziness: each role is a scarce specialist time-sliced across many pieces of work. A modernization under the SDLC is measured in quarters per extracted service not because the code is hard to write, but because it passes through six serially scheduled human gates before it's trusted.

## What agentic coding tools change

A coding agent reads a codebase, reasons about a change, writes the code and tests, and explains what it did in one session. It removes none of the eight stages. It collapses the time each takes and moves several inside one loop:

- Requirements and planning become **expressing intent** in a sentence or paragraph.
- System design and implementation become the agent **reading the surrounding code and proposing a change** in one step, with no handoff between designing a function and writing it.
- Testing and code review become the agent **writing and running tests with the code**, and a human reviews a finished diff instead of an abstract plan.

Work that took weeks of queueing takes minutes to hours because one engineer with one model carries it through every stage.

{% include excalidraw.html
   file="sdlc-vs-adlc"
   alt="Two parallel eight-step cycles. Left, the traditional SDLC: requirements and planning (days-weeks), system design (weeks), implementation and coding (weeks-months), testing and QA (days-weeks), code review (days), deploy and release (days), monitoring and observability (ongoing), feedback and iteration (continuous), looping back to step one. Right, the agentic SDLC: express intent (minutes), agent understands (seconds), agent implements (minutes), agent tests and docs (minutes), human review (minutes-hours), deploy and ship (minutes), monitoring and observability (continuous), learn and iterate (ongoing), looping back to step one. A transformation arrow connects the two loops. Below, a Key Differences table: sequential handoffs becomes fluid agent flow; human codes everything becomes human guides, agent executes; docs as afterthought becomes docs generated inline; manual incident response becomes agent-assisted remediation."
   caption="Figure 5.1: The traditional SDLC (weeks to months, handoff-driven) and the agentic SDLC it is becoming (hours to days, human-guides/agent-executes)" %}

The Key Differences table says more than "faster":

- **Sequential handoffs become fluid agent flow.** One actor, human plus agent, owns several stages, so the queueing delay mostly disappears.
- **Human codes everything becomes human guides, agent executes.** The engineer states intent, sets constraints and judges output, a different skill from typing every line.
- **Docs as afterthought becomes docs generated inline.** An agent that just wrote the code can narrate it, so documentation debt stops accumulating by default.
- **Manual incident response becomes agent-assisted remediation.** The tool that wrote the code can read the logs, propose the fix and draft the patch.

This relocates risk and doesn't remove it. The SDLC's slowness was also where much of its safety lived: six serial human gates are a crude but real form of error correction. Collapse the stages without replacing that, and you ship a wrong answer faster.

## The ADLC: the agentic SDLC, disciplined

The agentic SDLC says what becomes possible with an agent in the loop. It doesn't say where human judgment re-enters, what is checked before a change is trusted, or how a team keeps a record of why a change was made. The **AI Development Lifecycle (ADLC)** supplies those: seven named phases and exactly two places where a human must stop the loop and decide.

1. **Frame.** A human states the outcome and acceptance criteria for this step as a short statement of intent plus an entry in the decision log. It's "express intent," recorded instead of said once and forgotten.
2. **Map.** The agent does reconnaissance: it classifies the legacy module, maps its dependencies and locates the seam this step cuts along. No code changes yet.
3. **Plan.** The agent produces a step plan and a decision-log entry describing the approach. **Human gate: the plan is approved before any code is written.** A wrong assumption is cheapest to catch here, before it's embodied in a diff.
4. **Generate.** The agent scaffolds and writes the code and tests against the approved plan.
5. **Verify.** The agent, often in a second and more skeptical pass, runs the tests, checks the change against the **behavior-equivalence suite** and runs a security scan. **Human gate: a human signs off on equivalence** before the change is trusted.
6. **Operate.** The change rolls out behind a feature flag and is observed through the platform's telemetry, with a human controlling what percentage of traffic sees it and how fast that grows.
7. **Reconcile.** The decision log, the build plan and the reconciliation record are updated with the outcome, including drift from the plan. Nothing is thrown away. It becomes the next step's Map input.

{% include excalidraw.html
   file="agentic-sdlc-to-adlc"
   alt="Two stacked bands. Top band, labelled Deck: agentic SDLC (8 steps): express intent, agent understands, agent implements, agent tests and docs, human review, deploy and ship, monitoring and observability, learn and iterate. Bottom band, labelled ADLC (7 phases): Frame, Map, Plan, Generate, Verify, Operate, Reconcile. Dashed lines map each top step down to the phase or phases it corresponds to: express intent to Frame, agent understands to Map, agent implements spanning both Plan and Generate, agent tests and docs to Generate, human review to Verify, deploy and ship and monitoring and observability both to Operate, learn and iterate to Reconcile. Two dark gate markers sit between the bands: GATE: plan approval between agent implements and Plan/Generate, and GATE: equivalence sign-off between human review and Verify."
   caption="Figure 5.4: Mapping the agentic SDLC's eight steps onto the seven-phase ADLC, with the two human gates marked where they land" %}

Read against Figure 5.1: "Express intent" is Frame and "agent understands" is Map. "Agent implements" spans Plan and Generate, because the agent's first pass is the plan and nothing past it happens until a human has approved it. "Agent tests and docs" lands inside Generate. "Human review" is the equivalence gate inside Verify, a check against a concrete, re-runnable suite and not a rubber stamp on a diff. "Deploy and ship" and "monitoring and observability" both land in Operate, because rollout and the telemetry watching it are one activity. "Learn and iterate" is Reconcile.

Three ledger files, `decisions.md`, `build-plan.md` and `reconciliation.md`, replace the SDLC's design document, test plan and traceability matrix. They record why a decision was made, what was planned and whether the outcome matched, but they're append-only and largely agent-maintained, so keeping them current costs almost nothing.

## The tooling realization

Each phase needs an actor. Naming them as roles clarifies what is new: a small cast, each filling a gap the SDLC filled with a person or a ticket queue.

{% include excalidraw.html
   file="adlc-tooling"
   alt="An eight-step cycle, each step annotated with the generic tool role that carries it out: express intent: coding assistant (e.g. Claude Code); agent understands: local agent runtime; agent implements: local coding agent; agent tests and docs: local coding agent; human review: human; deploy and ship: internal developer platform (IDP); monitoring and observability: hosted AI platform; learn and iterate: issue tracker. Arrows connect each step to the next, cycling back to step one."
   caption="Figure 5.2: The agentic loop annotated with generic tool roles at each step" %}

The roles are what matter, because the products filling them change faster than the roles do:

| Role | Job |
| --- | --- |
| Coding assistant | Takes the human's stated intent. |
| Local agent runtime | Gives the agent the filesystem, a shell and the project's tools. |
| Local coding agent | Reads, writes and tests. |
| Human | Reviews. |
| Internal developer platform (IDP) | Deploys what was approved. |
| Hosted AI platform | Watches production telemetry and surfaces anomalies. |
| Issue tracker | Captures what was learned so the next pass starts with more context. |

One choice changes a team's workflow: where the coding agent runs.

{% include excalidraw.html
   file="adlc-local-vs-hosted"
   alt="A shared outer path (express intent, learn and iterate, monitoring and observability, deploy and ship) flanked by two parallel inner bands covering agent understands through human review. The local band runs each step through a local agent runtime, a local coding agent plus Docker Engine, and a cloud dev environment. The hosted band runs the same steps through a hosted AI platform end to end. Both bands converge back onto the shared deploy-and-ship step."
   caption="Figure 5.3: The same agentic loop, run two ways: a local execution path (local coding agent plus a local container runtime) versus a hosted path (a managed, hosted AI platform)" %}

| Path | Where it runs | Gain | Cost |
| --- | --- | --- | --- |
| **Local** | The engineer's machine, or a cloud dev environment configured to feel like one, against a local container runtime. | Full visibility into every file the agent touches and every command it runs. | Each engineer needs a provisioned machine. |
| **Hosted** | A managed AI platform runs understand, implement, test and review. | Easier to standardize and govern across a large organization. | A network hop and a shared execution environment the engineer doesn't fully control. |

Neither is more agentic. They're two topologies for the same loop, and a team can run both: local for day-to-day development, hosted for scheduled or fleet-wide tasks.

### What the extractions use

The coding assistant is Claude Code, running **locally**. Plan and Verify run through the **plan, execute, validate relay**: one model tier drafts the step plan (the human gate sits right after), a second tier executes it against the approved plan, and the first tier returns to validate the result against the behavior-equivalence suite before the second human gate. Map and Generate lean on two **MCP servers** that give the agent version-matched, tool-grounded knowledge instead of a model's possibly stale memory:

- a **Quarkus Agent MCP server** that drives the `migrate-spring-to-quarkus` process, starts and inspects a running Quarkus instance, and searches version-matched documentation;
- a **Camel MCP server** that validates routes, checks migration compatibility and queries the EIP and component catalogs.

**Docker Engine** is the local container runtime underneath both (Dev Services, Testcontainers, the equivalence suite's Postgres and Kafka), and **Quarkus** is the target runtime every extraction migrates onto.

The same health-check wiring written by hand for Spring Boot, next to the Quarkus equivalent an agent scaffolds during the Generate phase of a Phase A lift (the Quarkiverse Spring-compatibility bridge), shows the contrast:

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

The agent's version is nearly a direct translation. What differs is that it exists in minutes instead of a sprint, and trusting it rests on the equivalence suite running against it unchanged and not on a second engineer re-reading it.

## Why this matters for modernization

A throwaway prototype can skip from Generate to Operate, and nobody is worse off if it's wrong. Strangling pieces off a production monolith is the opposite case: refactoring under a hard constraint, **the system must keep behaving the way it already behaves**, on code a different team wrote, for reasons nobody fully remembers, that customers depend on today. An agent's raw speed makes that the most dangerous category of change without a verify loop tight enough to keep up.

The ADLC makes Verify cheap enough to run after every candidate change, not only before a release. The monolith's Newman collection, captured once against the running "before" system, is the **behavior-equivalence suite**. The **equivalence gate** re-runs it unchanged against every extracted service before the service is trusted and before the matching monolith module is decommissioned. An agent can produce several candidate approaches to a tricky extraction in the time a human produces one, and a scripted contract suite is cheap enough to run against every candidate where a human code review isn't. Speed without a tight gate ships regressions faster. With one, failed attempts are free experiments, caught before anyone ships them.

The same logic fits the two-phase migration used throughout the extractions. Phase A is a fast lift onto Quarkus via Spring-compatibility extensions. Phase B is a slower refactor to idiomatic Quarkus. Both are Generate-then-Verify passes through the same gate. Phase A de-risks the extraction quickly, because "does this behave like the monolith" is cheap to check and the code needn't be beautiful yet. Phase B is where the case for Quarkus is made with measured numbers, and it can take its slower, more careful pass because Phase A already proved the extraction safe.

## The limits: where humans stay in control

The loop doesn't run itself. The two gates in Figure 5.4 are the two places where a human's judgment can't be delegated:

- An agent can produce a plan but can't be trusted to decide that its own plan is the right one to execute against a production system someone else depends on. That needs a human who understands the business consequences of being wrong.
- An agent can run a suite and report green but can't decide that green is sufficient evidence to retire the monolith module it just replaced. That's a sign-off, not a computation.

Every "ADLC in Action" callout in the extraction chapters shows **pre-captured, reproducible tool output**, narrated transcripts checked into the repository, and not tool calls re-run live at read time. That favors determinism and reviewability over a live demonstration: a reader sees the exact loop that produced the code, unaffected by tool-version drift.

> **Decision**
>
> Put a human gate before Generate (approve the plan) and another before trust (sign off on equivalence). Between the gates the agent iterates freely, because the equivalence suite makes every attempt cheap to check.

> **🤖 Explore with your agent**
>
> "Run the ADLC through Plan only for one change in `examples/02-review-service`: add a database readiness check. Write the Frame statement and the acceptance criteria, do the Map reconnaissance, then draft the step plan and a decision-log entry. Name the command that will prove equivalence (`demos/demo-equivalence.sh` against the Review service) and stop at the plan gate without writing code."
>
> Check: the reply ends at an approval request, contains no diff, and names `demos/demo-equivalence.sh http://localhost:8081` (the Review service) as the Verify step.

## For deeper coverage

- Michael Albada, *Building Applications with AI Agents*: agent architectures underneath this loop.
- Daniel Oh, **Enterprise Agentic AI Workshop** (Quarkus- and Java-native agentic patterns). Four exercises map onto the ADLC: human-gate-plus-tracing to the two gates and the observability in Verify; plan-and-execute re-planning to the Plan, Generate, Verify loop and its repair rounds; supervisor orchestration, a coordinator delegating to specialist sub-agents, to the plan, execute, validate relay; and `AGENTS.md` to govern agent behavior to the decision-and-plan ledger.
