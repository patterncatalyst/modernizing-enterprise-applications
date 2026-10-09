---
title: "Agents, Skills & MCP Tools in the Loop"
order: 6
part: "The AI Development Lifecycle (ADLC)"
description: "The agent-tier relay (plan, execute, validate); the MCP surface (Quarkus Agent, Camel MCP); the reusable skill map; when the human must decide."
---

Chapter 5 named the seven phases of the ADLC and the two places a human has to
stop the loop and decide. It also, in its closing section, named three real
tools that fill those phases for this project's own build: the plan → execute
→ validate relay, a Quarkus Agent MCP server, and a Camel MCP server. This
chapter opens each of those up. Three building blocks compose into every
ADLC phase: **agents** (a model in a loop with tools, deciding what to do
next), **skills** (packaged, repeatable instructions an agent loads before it
acts), and **MCP tools** (servers that hand an agent a real toolchain to act
against, not just a prompt to reason from). None of this is abstract for this
book — the chapter you are reading right now was produced by the exact
machinery it describes, and the next section says so before it says
anything else.

## Agents: a model in a loop with tools

An agent, in the sense this book uses the word, is not a single clever prompt.
It is a model given three things a prompt alone does not have: a loop (the
model can take an action, observe the result, and decide what to do next,
repeatedly, without a human re-prompting it after every step), a set of tools
(the ability to read a file, run a shell command, call an MCP server, or
launch another agent), and a stopping condition (a task is "done," a plan is
"approved," a gate is "passed"). Strip away the loop and you have a chatbot
that can describe what it would do. Strip away the tools and you have a model
that can only talk about the codebase, never touch it. The loop plus the
tools is what turns "the agent understood the request" into "the agent
produced a passing diff."

The simplest version of this is a single agent: one model, one loop, one set
of tools, working a task start to finish. Most of the mechanical work in this
project's build — scaffolding a Quarkus project, writing a Camel route,
authoring a chapter's prose — runs as a single agent, because a single loop
with the right tools is the cheapest way to get a bounded task done, and
adding a second agent to a task that doesn't need one is pure coordination
overhead with no offsetting benefit.

The more interesting case, and the one that actually built this project, is
**multi-agent orchestration**: more than one agent, each with a distinct role
and a distinct set of tools, coordinated so that one agent's output becomes
another's input, and — critically — so that no single agent is trusted to
grade its own work. This book's Plan and Verify phases are carried out by
exactly this pattern, under a name worth being precise about:
**plan → execute → validate**. One model tier (the *planning* model) reads the
Frame statement, does the Map-phase reconnaissance, and drafts a concrete step
plan — what becomes a `decisions.md` entry and a row in `build-plan.md`. That
plan sits in front of a human gate before anything else happens; Chapter 5
called this the cheapest place in the whole lifecycle to catch a wrong
assumption, and a second model's enthusiasm for its own plan is not a
substitute for that human's sign-off. Once the plan is approved, a second
model tier (the *execution* model) carries out Generate: it scaffolds the
project, writes the code, writes the tests, and runs them. It does not get to
decide, on its own authority, that its own output is correct — that question
goes back to the first tier, now acting in a third, *adversarial* role: it
re-reads the approved plan, re-reads the diff the execution model actually
produced, and checks the two against each other and against the
behavior-equivalence suite before recommending the second human gate. The
same model tier that proposed the plan is the one skeptical enough to doubt
whether its own plan was executed faithfully — a different posture than
"wrote it, therefore vouches for it."

{% include excalidraw.html
   file="plan-execute-validate-relay"
   alt="Six boxes in a row: Frame (human, states intent, writes the decisions.md entry), Plan tier (the planning model, reads Frame plus Map recon, drafts the step plan), Human gate 1 (plan approval, before any diff exists), Execute tier (the execution model, Generate: scaffolds, writes code and tests, runs them), Validate tier (the planning model again, adversarial: re-reads plan and diff against the equivalence suite), Human gate 2 (equivalence sign-off, Verify phase). A dashed line below the row connects the Plan and Validate boxes, labeled: same model tier, now adversarial, not self-vouching."
   caption="Figure 6.1 — The relay: the planning model drafts, the execution model builds, the same planning tier validates adversarially, with a human gate on either side of Generate" %}

```
Captured -- the actual Review-extraction plan draft, DRQ-014/T1-adjacent
step plan (the planning tier's output, before the first human gate;
`_plans/build-plan.md`, steps S6-S10)

S6  Build the behavior-equivalence suite against the running monolith
    (Newman collection; happy path + out-of-stock + payment-decline).
S7  Stand up a flag-gated Camel strangler proxy, default every request
    to the monolith.
S8  Phase A: lift Review onto Quarkus via Spring-compatibility
    extensions (quarkus-spring-web/-di/-data-jpa); equivalence gate
    must go green before Phase B starts.
S9  Phase B: refactor Review to idiomatic Quarkus (JAX-RS/Panache/CDI);
    measure JVM vs native startup/RSS/artifact size.
S10 Flip strangler.review.enabled; re-run the equivalence suite through
    the proxy; decommission the monolith's review module only after a
    green re-run.

-- awaiting human approval before S8 begins --
```

Why call this "supervisor orchestration" rather than just "two models talking
to each other"? Because the planning tier's validating pass is not a peer
review between equals — it supervises, in the specific sense of holding
authority over whether the execution tier's work is accepted, exactly the
dynamic Daniel Oh's *Enterprise Agentic AI Workshop* demonstrates under that
name: a coordinator agent delegating bounded subtasks to specialist sub-agents
and deciding, from the outside, whether each subtask's output is acceptable
before the next one starts. Chapter 5's "Further reading" sidebar already drew
this line; it is worth restating here because this is the chapter where the
analogy stops being a reference and starts being this project's actual
execution model. The workshop's reference projects build that supervisor
pattern as the product; this book's relay uses the identical pattern as its
*means of production* — the coordinator-and-specialist shape is the same, only
the thing being coordinated differs; the walkthrough in Chapter 7 shows exactly
what that relay caught, and failed to catch, on the Review extraction.

One more distinction matters here: *orchestration* is not the same
thing as *parallelism*. The plan → execute → validate relay is strictly
sequential — each phase's output gates the next, and the whole point is that
nothing in Generate starts before a human has approved Plan. Elsewhere in this
project's build, independent agents *do* run in parallel — this project's own
build plan lists chapter authoring across `_docs/NN-*.md` files and diagram
generation as safe to parallelize because they touch disjoint files, while the
Maven reactor root and the shared `_plans/` ledger are explicitly serialized
to one writer at a time under its single-writer rule. Multi-agent orchestration, in
other words, is a design decision about *dependencies between tasks*, not a
blanket instruction to "use more agents." A task that has no shared
state and no approval gate between its pieces is a good candidate for
parallel agents; a task where one piece's output must be approved before the
next begins — Plan before Generate, Generate before Verify — is not, no matter
how tempting it is to save wall-clock time by running them together.

## Skills: packaged, repeatable instructions

An agent with tools can do almost anything; that is also its biggest
liability, because "almost anything" includes solving a well-understood
problem in a slightly different, slightly wrong way every single time it is
asked. A **skill**, in the sense this project uses the term, is a packaged,
named set of instructions an agent loads before it acts on a particular kind
of task — not a suggestion buried in a long system prompt, but a discrete,
invocable unit with its own name, its own triggers, and its own worked
conventions, so that the fifth time an agent scaffolds a Quarkus project it
makes the same choices the first time did.

This book's own build runs almost entirely on a family of skills with a
shared naming convention, the **lgtm-\*** family, and naming them concretely
is more useful than describing skills in the abstract:

- **`lgtm-tutorial`** is the skill that wrote this chapter. It encodes the
  chapter skeleton, the depth standard, the runnable-example shape, the
  `unverified` discipline, and the validation snippets this project runs
  before every chapter ships — the house style a reader has been experiencing,
  consistently, since Chapter 0, without ever seeing the skill itself.
- **`lgtm-quarkus`** and **`lgtm-camel`** scaffold the dev toolchain for a new
  Quarkus or Camel project respectively — SDKMAN, the JDK, Maven, the MCP
  server wiring, the testing harness — so that every service extraction in
  this book starts from the same known-good baseline rather than a
  hand-assembled one that drifts service to service.
- **`lgtm-diagram-generator`** produced every figure referenced in this Part,
  including the tooling figure Chapter 5 showed, from a short Python spec of
  boxes and arrows, so the diagrams across 33 chapters share one visual
  language instead of each chapter inventing its own.
- **`lgtm-docker-stack`** stands up the local observability substrate —
  Postgres, Kafka, the LGTM stack — that every extraction's Dev Services and
  behavior-equivalence suite run against.
- **`lgtm-github`** carries the release-sync and commit-convention discipline
  for pushing a finished iteration upstream, gated, per this project's own
  standing rule, on the human's explicit go-ahead.

A skill is not only a packaged *task*; it is also a packaged *boundary*. The
second kind of skill this project leans on hardest is project governance
through context files — `AGENTS.md` and `CLAUDE.md` — that live inside a
generated project and constrain every agent that works on it afterward,
whether or not that agent remembers how the project started. This book's own
`examples/02-review-service/AGENTS.md` is not a teaching prop; it is the
actual file the Quarkus-side execution agent read before touching a single
line of the Review extraction, and its opening rule states the governance
pattern directly:

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

Read that rule for what it actually does: it does not trust the execution
agent's judgment about which library to reach for, and it does not trust the
agent to silently decide on the user's behalf when more than one extension
could work. It forces a stop, a search against real version-matched data, and
a human choice, before a single line of code exists. That is a governance
file doing exactly the job a style guide or an architecture review board does
in a traditional SDLC — except it travels with the repository, applies to
every agent that opens it rather than to whichever reviewer is on call, and
costs nothing to re-read on the hundredth feature the way it did on the
first. This is also the project-level analog of the pattern Chapter 5 flagged
and deferred to here: Daniel Oh's workshop uses `AGENTS.md` the same way, to
govern agent behavior inside its own reference projects, and the mapping runs
both directions — this project's `AGENTS.md` is the workshop's pattern
applied to a Quarkus migration rather than to an agent built from scratch,
and this project's own `CLAUDE.md` sibling file does nothing more than point
back at it -- it reads, in full, "See AGENTS.md for project instructions" --
because a project's governance file should have exactly one source of truth
regardless of which coding assistant opens it.

## MCP tools: acting against the real toolchain, not from memory

Skills tell an agent *how* to approach a task; they do not, by themselves,
give it anything new to act on. An agent without tool access can still
describe a Quarkus extension's configuration from training data that may be a
year stale, or improvise a Camel route's YAML DSL from a half-remembered
example, with no way to check either claim against the version actually
running. The **Model Context Protocol (MCP)** closes that gap: an MCP server
is a small, purpose-built service that exposes a fixed set of callable tools
— not a chat interface, a set of functions with real inputs and real,
structured outputs — that an agent can call mid-loop to get ground truth
instead of a guess. This matters most exactly where a model's memory is
weakest: fast-moving frameworks, version-specific APIs, and anything that
requires actually running a program rather than describing one.

This project uses two MCP servers, and naming what each one actually does is
more useful than describing MCP in the abstract. The **quarkus-agent** MCP
server gives an agent four distinct capabilities over a running Quarkus
project: it can **scaffold** a new project (`quarkus_create`) rather than the
agent hand-assembling a `pom.xml` from memory; it can **search
version-matched documentation** (`quarkus_searchDocs`) against the exact
Quarkus release the project is pinned to, not whatever release was most
common in its training data; it can **start, stop, and inspect a running
instance** (`quarkus_start`, `quarkus_logs`, `quarkus_callTool` against the
Dev UI's own tool surface) so the agent is reading real compiler errors and
real exception stack traces instead of predicting what they might say; and,
specifically for this project's migration work, it can **discover the
`migrate-spring-to-quarkus` skill** by querying `quarkus_skills` against a
Spring project's own directory, which is how every one of this book's six
extractions found the repeatable, gate-driven migration process first
described in Part 5 and run across Parts 5 onward (Chapters 15, 17, 19, 23,
24, and 26) rather than the agent inventing its own migration strategy from
scratch each time.

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

The **camel-mcp** MCP server does the analogous job for the Camel side of
this project's seams: **component and EIP catalog lookups**
(`camel_catalog_components`, `camel_catalog_eips`) so an agent writing the
strangler proxy's routing logic is choosing a real, currently-supported
component and a real Enterprise Integration Pattern rather than a plausible
one, and **route validation** (`camel_validate_route`,
`camel_configuration_validate`) so a route's structure is checked against the
Camel runtime's own rules — a malformed `choice()`/`when()`/`otherwise()`
block, a reference to a component that was never added as a dependency, an
endpoint URI with a typo'd parameter — before the route is ever deployed and
exercised by the behavior-equivalence suite.

```
Captured -- camel_validate_route against the strangler proxy route,
during the Generate phase that produced examples/01-strangler-proxy/
(a structural check, run before the route was deployed; it confirms
the route's shape is sound -- it does not, and cannot, evaluate what a
predicate matches against a live request, which is why the differential
test in Chapter 7 remained necessary)

> camel_validate_route(file="strangler-proxy-route.xml")

OK -- 1 route, 3 endpoints (platform-http:*, direct:monolith,
direct:review-service), 1 choice/when/otherwise block, no unresolved
component references, no duplicate route IDs.
```

That last parenthetical is worth lingering on, because it draws the line
between what each layer of this project's safety net actually checks. The
MCP server's route validation is a *structural* gate: it confirms the route
compiles into a legal Camel processing graph. It is not, and was never meant
to be, a *behavioral* gate — it has no opinion about whether a routing
predicate matches the right requests once real traffic flows through it,
which is exactly the gap the differential monolith-down test in Chapter 7
existed to close. Camel MCP catches the class of defect that would otherwise
surface as a confusing runtime stack trace; the equivalence gate and its
adversarial follow-up catch the class of defect that surfaces as a
misleadingly correct-looking response. Neither one substitutes for the other,
and understanding which layer is responsible for which kind of mistake is
most of what it takes to trust a loop this fast.

{% include excalidraw.html
   file="structural-vs-behavioral-gates"
   alt="Two stacked panels. Top: the structural gate, camel_validate_route and camel_configuration_validate, run during Generate before deploy; it confirms the route compiles into a legal Camel graph, catching a malformed choice/when/otherwise block, an unresolved component reference, or a typo'd endpoint parameter — the class of defect that surfaces as a confusing runtime stack trace. It cannot catch whether a routing predicate matches the right requests once real traffic flows. Bottom: the behavioral gate, the Newman equivalence suite plus its Opus adversarial follow-up, run during Verify against a running service; it catches a routing predicate matching the wrong requests — the class of defect that surfaces as a misleadingly correct-looking response. A dashed line connects what the structural gate cannot catch to the behavioral gate that closes the gap."
   caption="Figure 6.2 — The structural gate (camel-mcp route validation) and the behavioral gate (the equivalence suite) catch different classes of mistake; neither substitutes for the other" %}

The underlying reason MCP matters is the same for both servers: an agent's training
data is a snapshot, and a framework, an API, or a catalog of components is
not. Quarkus ships a new release on a predictable cadence; Camel's component
and EIP catalog grows and occasionally deprecates entries; a project's own
Quarkus version is pinned in a file the agent can read but has no reason to
have memorized. MCP tools convert "what does the model remember about
Quarkus" into "what does *this* installed, version-pinned toolchain actually
say right now" — which is the difference between an agent that is usually
close enough and one whose Generate-phase output is grounded in the same
reality the Verify-phase gate will check it against.

## How the three compose, phase by phase

None of the three building blocks in this chapter does anything alone; the
ADLC's seven phases are where they combine, and naming the combination phase
by phase makes the composition concrete rather than aspirational:

- **Frame** stays entirely human — no agent, skill, or MCP tool substitutes
  for a person stating intent and writing the decision-log entry, because the
  outcome this step is supposed to achieve is a judgment call, not a
  retrievable fact.
- **Map** is where an MCP server contributes first: `quarkus_skills`
  querying a Spring module's directory, or `camel-mcp`'s catalog lookups,
  ground the agent's reconnaissance in the real shape of the code and the
  real toolchain before any plan gets drafted.
- **Plan** is the planning tier of the relay, informed by whatever Map turned
  up, producing the step plan that sits in front of the first human gate —
  the supervisor half of the supervisor-orchestration pattern, before it has
  anything to supervise yet.
- **Generate** is the execution tier, using the project's skills
  (`lgtm-quarkus`, `lgtm-camel`, the governance rules in `AGENTS.md`) and the
  same MCP servers — now invoked to scaffold, search docs, and validate
  structure rather than merely reconnoiter — to produce the actual diff.
- **Verify** returns to the planning tier in its adversarial role, checking
  the diff against the approved plan and the behavior-equivalence suite,
  informed again by whatever MCP-level structural checks already ran during
  Generate, before the second human gate.
- **Operate** and **Reconcile** are mostly governance-file and ledger work —
  `AGENTS.md`/`CLAUDE.md` keep constraining any agent that touches the
  running service, and the same three ledger artifacts Chapter 5 introduced
  record what actually happened, which is this project's Reconcile phase
  whether or not an agent was involved in writing the record down.

One discipline threads through every one of those bullets and is worth
restating by name, because it is the reason this chapter's own captured
transcripts look the way they do: **DRQ-025**. Every piece of agent or MCP
tool output quoted in this book — the plan draft above, the `quarkus_skills`
discovery, the `camel_validate_route` check — is pre-captured and narrated,
checked into the repository at the moment it happened, not re-run live at
build time or read time. The reasoning is the same reasoning Chapter 5 gave
for the ADLC's "ADLC in Action" callouts generally: a reader six months from
now, opening this chapter on a Quarkus or Camel release this project was
never tested against, sees the exact exchange that produced the code in front
of them, immune to whatever drifted in the tools between then and now.
Reproducibility, here, does not mean "run it again and get the same answer
from a live service" — it means "read the same transcript and verify the same
claim," which is a strictly stronger guarantee once tool versions are allowed
to move.

## What's next

This chapter named the actors — the relay's two tiers in a supervisor
relationship, the `lgtm-*` skills and the `AGENTS.md`/`CLAUDE.md` governance
layer, and the two MCP servers that ground Generate and Map in real,
version-matched tools — without yet proving any of it holds together under
real pressure. That proof is Chapter 7's entire job: it runs this exact
machinery once, end to end, on the Review extraction, and narrates two real
defects the safety net caught before either reached a reader. If this chapter
left you wanting to *build* an agent rather than *use* one — to write your
own `@Agent`-annotated supervisor, not just read about the relay this book
runs on — Daniel Oh's **Enterprise Agentic AI Workshop** is the hands-on
companion Chapter 5 pointed to and this chapter has now cashed in twice: it
teaches the construction of exactly the patterns named here (supervisor
orchestration, plan-and-execute re-planning, `AGENTS.md` governance) on a
Quarkus/Java stack. This book's relationship to that material is
complementary, not redundant — that workshop builds the agent; this book
puts a finished agentic workflow to work modernizing a production-shaped
system, and the next chapter is where you watch it do exactly that.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Every captured transcript quoted above is
drawn from this project's own build artifacts (`_plans/build-plan.md`,
`examples/02-review-service/AGENTS.md`, `examples/01-strangler-proxy/`) or
reconstructed in the same narrated-and-checked-in style per DRQ-025; the
underlying claims — the relay tiers, the `lgtm-*` skill names, the two MCP
servers and their tool names — are checkable against this repository and
against `_plans/decisions.md` directly. Chapter 7 is where this chapter's
claims about what the safety net actually catches get their full worked
proof.*
