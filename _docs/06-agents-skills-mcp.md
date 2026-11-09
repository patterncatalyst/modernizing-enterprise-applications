---
title: "Agents, Skills & MCP Tools in the Loop"
order: 6
part: "The Agentic Development Lifecycle (ADLC)"
description: "The agent-tier relay (plan, execute, validate), the skills and governance files that constrain agents, the MCP surface (Quarkus Agent, Camel MCP), and how they compose phase by phase."
---

## Learning objectives

By the end of this chapter you can:

- Describe an agent as a loop, a set of tools and a stopping condition, and say when one agent is enough.
- Run the plan, execute, validate relay so no model grades its own work.
- Use skills and an `AGENTS.md` governance file to make an agent repeat the same choices.
- Say what the Quarkus Agent and Camel MCP servers check, and what a structural gate can't.
- Place agents, skills and MCP tools in each ADLC phase.

## Why this matters

Three building blocks compose into every ADLC phase: **agents** (a model in a loop with tools, deciding what to do next), **skills** (packaged, repeatable instructions an agent loads before it acts) and **MCP tools** (servers that hand an agent a real toolchain to act against, not just a prompt to reason from). Each one closes a gap the others leave open, and knowing which layer catches which kind of mistake is most of what it takes to trust a loop this fast.

## Agents: a model in a loop with tools

An agent is not a single clever prompt. It's a model given three things a prompt lacks:

- a **loop**: it takes an action, observes the result and decides the next step without a human re-prompting after every one;
- **tools**: it can read a file, run a shell command, call an MCP server or launch another agent;
- a **stopping condition**: a task is done, a plan approved, a gate passed.

Without the loop you have a chatbot that describes what it would do. Without the tools you have a model that can talk about the codebase but never touch it. A single agent, one model with one loop and one tool set, is the cheapest way to get a bounded task done: scaffolding a Quarkus project, writing a Camel route, authoring a chapter. A second agent on a task that doesn't need one is coordination overhead with no offsetting benefit.

**Multi-agent orchestration** uses more than one agent, each with a distinct role and tool set, coordinated so one agent's output becomes another's input and no single agent grades its own work. Plan and Verify run as **plan, execute, validate**:

- The *planning* model reads the Frame statement, does the Map reconnaissance and drafts a step plan, which becomes a `decisions.md` entry and a row in `build-plan.md`. The plan waits at a human gate. A second model's enthusiasm for the plan is no substitute for that sign-off.
- Once the plan is approved, the *execution* model carries out Generate: it scaffolds the project, writes the code and tests, and runs them. It doesn't decide on its own authority that its output is correct.
- That question goes back to the planning tier in a third, *adversarial* role. It re-reads the approved plan, re-reads the diff the execution model produced, and checks the two against each other and against the behavior-equivalence suite before recommending the second human gate. The tier that proposed the plan is the one skeptical enough to doubt it was executed faithfully.

{% include excalidraw.html
   file="plan-execute-validate-relay"
   alt="Six boxes in a row: Frame (human, states intent, writes the decision-log entry), Plan tier (the planning model, reads Frame plus Map recon, drafts the step plan), Human gate 1 (plan approval, before any diff exists), Execute tier (the execution model, Generate: scaffolds, writes code and tests, runs them), Validate tier (the planning model again, adversarial: re-reads plan and diff against the equivalence suite), Human gate 2 (equivalence sign-off, Verify phase). A dashed line below the row connects the Plan and Validate boxes, labeled: same model tier, now adversarial, not self-vouching."
   caption="Figure 6.1: The relay: the planning model drafts, the execution model builds, the same planning tier validates adversarially, with a human gate on either side of Generate" %}

```
A Review-extraction plan draft -- the planning tier's output, before the
first human gate

1  Build the behavior-equivalence suite against the running monolith
   (Newman collection; happy path + out-of-stock + payment-decline).
2  Stand up a flag-gated Camel strangler proxy, default every request
   to the monolith.
3  Phase A: lift Review onto Quarkus via Spring-compatibility
   extensions (quarkus-spring-web/-di/-data-jpa); equivalence gate
   must go green before Phase B starts.
4  Phase B: refactor Review to idiomatic Quarkus (JAX-RS/Panache/CDI);
   measure JVM vs native startup/RSS/artifact size.
5  Flip strangler.review.enabled; re-run the equivalence suite through
   the proxy; decommission the monolith's review module only after a
   green re-run.

-- awaiting human approval before step 3 begins --
```

This is supervisor orchestration, the shape Daniel Oh's *Enterprise Agentic AI Workshop* demonstrates under that name: a coordinator delegating bounded subtasks to specialist sub-agents and deciding from the outside whether each result is acceptable before the next starts. The planning tier's validating pass isn't peer review between equals. It holds authority over whether the execution tier's work is accepted. The workshop builds the pattern as the product, and the relay uses it as a means of production.

Orchestration is not parallelism. The relay is strictly sequential: each phase's output gates the next, and nothing in Generate starts before a human approves Plan. Independent agents do run in parallel elsewhere, such as authoring separate chapter files or generating diagrams, because they touch disjoint files, while a shared build artifact or ledger is serialized to one writer. Orchestration is a decision about dependencies between tasks:

| Task shape | Agents |
| --- | --- |
| No shared state, no approval gate between pieces | Parallel agents. |
| One piece's output must be approved before the next begins (Plan before Generate, Generate before Verify) | Sequential relay, however tempting the wall-clock saving. |
| A bounded task with one set of tools | A single agent. |

## Skills: packaged, repeatable instructions

An agent with tools can do almost anything, which is also its liability: "almost anything" includes solving a well-understood problem in a slightly different, slightly wrong way every time. A **skill** is a packaged, named set of instructions an agent loads before acting on a kind of task. It's a discrete, invocable unit with its own name, triggers and worked conventions, not a suggestion buried in a long system prompt, so the fifth Quarkus scaffold makes the same choices as the first.

The project runs on a family of skills:

| Skill | What it encodes |
| --- | --- |
| Tutorial authoring | The skeleton of a chapter, the depth standard, the runnable-example shape and the validation snippets run before it ships. |
| Quarkus and Camel scaffolding | The dev toolchain for a new project (SDKMAN, JDK, Maven, MCP server wiring, test harness), so every extraction starts from the same baseline instead of one that drifts service to service. |
| Diagram generator | Figures generated from a short Python spec of boxes and arrows, so every diagram shares one visual language. |
| Local stack | The observability substrate (Postgres, Kafka, the LGTM stack) that Dev Services and the equivalence suite run against. |
| Release | Release-sync and commit conventions for pushing a finished iteration upstream, gated on the human's explicit go-ahead. |

A skill is also a packaged *boundary*. Context files, `AGENTS.md` and `CLAUDE.md`, live inside a generated project and constrain every agent that works on it afterward, whether or not that agent remembers how the project started. The opening rule of `examples/02-review-service/AGENTS.md`:

```
Condensed excerpt -- examples/02-review-service/AGENTS.md, lines 5-15

## CRITICAL -- Extension-First Rule (NEVER skip this)

**STOP before writing ANY code.** For every feature or capability the
user requests:

1. Search for Quarkus extensions that provide the capability using
   quarkus_searchDocs and quarkus_searchTools query='extension'.
   Do NOT rely on a fixed list of extensions...
2. Present ALL matching extensions to the user with a recommended
   default marked. Wait for the user to choose before proceeding.
3. Load skills with quarkus_skills for the chosen extension BEFORE
   writing any code.
```

The rule doesn't trust the execution agent to pick a library, or to decide silently when more than one extension fits. It forces a stop, a search against version-matched data and a human choice before any code exists. That's the job a style guide or architecture review board does in a traditional SDLC, except the file travels with the repository, applies to every agent that opens it and costs nothing to re-read on the hundredth feature. Oh's workshop uses `AGENTS.md` the same way inside its reference projects, and the mapping runs both directions. The project's `CLAUDE.md` only points back: it reads, in full, "See AGENTS.md for project instructions". A governance file should have one source of truth whichever coding assistant opens it.

## MCP tools: acting against the real toolchain

Skills tell an agent how to approach a task. They give it nothing new to act on. An agent without tools can describe a Quarkus extension's configuration from training data a year stale, or improvise a Camel route's YAML DSL from a half-remembered example, with no way to check either against the version actually running. The **Model Context Protocol (MCP)** closes the gap. An MCP server is a small service exposing a fixed set of callable functions with real inputs and structured outputs, so an agent can get ground truth mid-loop. It matters most where a model's memory is weakest: fast-moving frameworks, version-specific APIs and anything that requires running a program instead of describing one.

The **quarkus-agent** server gives an agent four capabilities over a Quarkus project:

- **Scaffold** a new project (`quarkus_create`) instead of hand-assembling a `pom.xml` from memory.
- **Search version-matched documentation** (`quarkus_searchDocs`) against the exact release the project is pinned to.
- **Start, stop and inspect a running instance** (`quarkus_start`, `quarkus_logs`, `quarkus_callTool` against the Dev UI's tool surface), so the agent reads real compiler errors and stack traces instead of predicting them.
- **Discover the `migrate-spring-to-quarkus` skill** by querying `quarkus_skills` against a Spring project's directory. That's how each of the six extractions found the repeatable, gate-driven migration process instead of the agent inventing a strategy every time.

```
Captured -- quarkus_skills query against examples/02-review-service's
Spring-sourced module, before the Phase A lift began

> quarkus_skills(projectDir=".../review-service", query="spring,migration")

Discovered skill: migrate-spring-to-quarkus
  Modular, gate-driven migration process. Detects Spring Boot source,
  selects a migration strategy (compatibility-extension lift vs full
  rewrite), and sequences module-by-module conversion with a build-and-
  test gate between each module.
  Do NOT plan your own migration approach -- follow this skill's
  sequencing and gates.
```

The **camel-mcp** server does the same for the Camel seams. **Catalog lookups** (`camel_catalog_components`, `camel_catalog_eips`) mean the strangler proxy's routing uses a real, currently supported component and a real Enterprise Integration Pattern instead of a plausible one. **Route validation** (`camel_validate_route`, `camel_configuration_validate`) checks a route against the Camel runtime's rules before deployment: a malformed `choice()`/`when()`/`otherwise()` block, a reference to a component never added as a dependency, a typo'd endpoint parameter.

```
Captured -- camel_validate_route against the strangler proxy route,
during the Generate phase that produced examples/01-strangler-proxy/
(a structural check, run before the route was deployed; it confirms
the route's shape is sound -- it does not, and cannot, evaluate what a
predicate matches against a live request, which is why the differential
monolith-down test remained necessary)

> camel_validate_route(file="strangler-proxy-route.xml")

OK -- 1 route, 3 endpoints (platform-http:*, direct:monolith,
direct:review-service), 1 choice/when/otherwise block, no unresolved
component references, no duplicate route IDs.
```

The two checks sit at different layers:

| Gate | Runs | Confirms | Catches |
| --- | --- | --- | --- |
| **Structural**: MCP route validation | During Generate, before deploy | The route compiles into a legal Camel processing graph. | A defect that would surface as a confusing runtime stack trace. |
| **Behavioral**: the equivalence suite plus its adversarial follow-up | During Verify, against a running service | A routing predicate matches the right requests once real traffic flows. | A defect that surfaces as a misleadingly correct-looking response. |

Neither substitutes for the other. Route validation has no opinion on whether a predicate matches the right requests, which is the gap the differential monolith-down test closes.

{% include excalidraw.html
   file="structural-vs-behavioral-gates"
   alt="Two stacked panels. Top: the structural gate, camel_validate_route and camel_configuration_validate, run during Generate before deploy; it confirms the route compiles into a legal Camel graph, catching a malformed choice/when/otherwise block, an unresolved component reference, or a typo'd endpoint parameter, the class of defect that surfaces as a confusing runtime stack trace. It cannot catch whether a routing predicate matches the right requests once real traffic flows. Bottom: the behavioral gate, the Newman equivalence suite plus its adversarial follow-up, run during Verify against a running service; it catches a routing predicate matching the wrong requests, the class of defect that surfaces as a misleadingly correct-looking response. A dashed line connects what the structural gate cannot catch to the behavioral gate that closes the gap."
   caption="Figure 6.2: The structural gate (camel-mcp route validation) and the behavioral gate (the equivalence suite) catch different classes of mistake; neither substitutes for the other" %}

Both servers matter for the same reason: a model's training data is a snapshot, and a framework, an API or a component catalog isn't. Quarkus ships on a predictable cadence, Camel's catalog grows and occasionally deprecates entries, and a project's Quarkus version is pinned in a file the agent can read but has no reason to have memorized. MCP converts "what does the model remember about Quarkus" into "what does this installed, version-pinned toolchain say right now."

## How the three compose, phase by phase

- **Frame** stays entirely human. No agent, skill or MCP tool substitutes for a person stating intent and writing the decision-log entry, because the outcome is a judgment call, not a retrievable fact.
- **Map** is where an MCP server contributes first. `quarkus_skills` against a Spring module's directory, or `camel-mcp` catalog lookups, ground the reconnaissance in the real code and toolchain before a plan is drafted.
- **Plan** is the planning tier, informed by Map, producing the step plan that waits at the first human gate. It's the supervisor half of the pattern, before it has anything to supervise.
- **Generate** is the execution tier, using the scaffolding skills, the governance rules in `AGENTS.md` and the same MCP servers, now to scaffold, search docs and validate structure.
- **Verify** returns to the planning tier in its adversarial role, checking the diff against the approved plan and the equivalence suite, informed by the structural checks that ran during Generate, before the second human gate.
- **Operate** and **Reconcile** are mostly governance-file and ledger work. `AGENTS.md` and `CLAUDE.md` keep constraining any agent that touches the running service, and the three ledger files record what happened.

Every piece of agent or MCP output quoted here (the plan draft, the `quarkus_skills` discovery, the `camel_validate_route` check) is pre-captured and narrated, checked into the repository when it happened, and never re-run at read time. A reader on a Quarkus or Camel release the project was never tested against sees the exact exchange that produced the code. Reproducibility means reading the same transcript and verifying the same claim, a stronger guarantee than rerunning a live service once tool versions move.

> **Decision**
>
> Use one agent for a bounded task with no approval gate. Use the sequential relay when one piece's output needs approval before the next begins, and never let the tier that wrote a change be the only one to validate it. Put the constraint in `AGENTS.md` and the ground truth in an MCP server, not in the prompt.

> **🤖 Explore with your agent**
>
> "In `examples/02-review-service`, follow `AGENTS.md` to add one capability, a `/q/health/ready` readiness check, as an extension-first change. Search for the matching extension with `quarkus_searchDocs`, list the candidates with a recommended default and stop for my choice, then load `quarkus_skills` for the one I pick. Do not write code until I choose."
>
> Check: the reply lists the matching extensions with a recommended default marked, as `AGENTS.md` step 2 requires, and `git status` in `examples/02-review-service` shows no change before you answer.

## For deeper coverage

Daniel Oh's **Enterprise Agentic AI Workshop** is the hands-on companion for building an agent instead of using one. It teaches how to write your own `@Agent`-annotated supervisor, plan-and-execute re-planning and `AGENTS.md` governance on a Quarkus and Java stack, where the relay here puts a finished agentic workflow to work modernizing a production-shaped system.
