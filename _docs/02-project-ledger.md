---
title: "The Project Ledger"
order: 2
part: "Setting Up"
description: "The ADLC's ledger: the decision log, the build plan, the iteration records, and the reconciliation log that completes it, kept as the migration's memory across sessions, agents and people."
---

Past the toolchain, one more piece of setup isn't a tool. It's a small set of plain-text files under `_plans/`, updated every time a decision is made or a step finishes: the **project ledger**. `decisions.md`, `build-plan.md` and `iterations/` are the migration's durable memory.

## Why a ledger

Agent-assisted modernization follows one shape: a human states what should happen, an agent does reconnaissance, plans, writes code, verifies, rolls out and records the outcome. That is the **Frame, Map, Plan, Generate, Verify, Operate, Reconcile** cycle called the Agentic Development Lifecycle (ADLC). Its last phase, Reconcile, is three concrete files a human can open and trust.

The work is dozens of short sessions across days or weeks, often by different people. A Claude Code session has no memory of one from three days ago, and a reviewer six weeks out doesn't remember why a choice was made in week one. An agent picking up a half-finished extraction needs to know what the code looks like and also what was already decided and mustn't be silently re-decided. Without an external record, a decision gets reversed because nobody remembered it, a step gets redone because nobody could tell it was finished, or a step gets skipped because nobody could tell it wasn't.

Modernization raises the stakes. The system must keep behaving as it does today, for customers who depend on it. An agent can retry a risky change as often as it likes, but it can't decide on its own authority that a retry is safe to ship. Two human gates do that, Plan approval and Verify sign-off, and each needs something durable on both sides: a record of what was proposed and a record of what was confirmed. The ledger is that record. A plan-approval gate without a written plan is a conversation nobody can audit. A sign-off without a recorded result is trust on faith.

## The decision log: `_plans/decisions.md`

The log is append-only, one row per decision, each with a numbered identifier, the decision, the reasoning and a status:

| Status | Meaning |
|---|---|
| `fixed` | Set by the owner; only the owner can reopen it, on the record |
| `accepted` | A synthesis decision a later step could revisit but hasn't |
| `proposed` | A recommended default awaiting confirmation |

Numbers never reset and rows are never deleted. A changed decision gets a new row that names what it supersedes, so the history stays intact. Alternatives considered and rejected at each branch (which service to extract first, how deep each migration goes) are recorded with the reason, so a contributor tempted to revisit one reads the weighed reasoning and either finds it still holds or writes a new row saying what changed.

A small fixed row shows why this matters. The first row set the container toolchain and excluded Docker. A later row moved every path to Docker Engine and the `docker compose` plugin, kept the original rationale (one engine and one set of image pins for the dev loop, the tests and CI) and marked the first row superseded rather than editing it. The original rationale was that a single compose source avoids a Dev Services image-tag mismatch, and the build plan's risk register names that failure mode: someone pulls another project's compose and the tags drift. Without the log, a contributor reaching for container tooling couldn't tell whether the project excluded a tool by decision or by oversight.

A vocabulary correction shows how far a row reaches:

```
031 | Rename "equivalence oracle" → "behavior-equivalence suite" (the
Newman collection) and "equivalence gate" (its CI check), dropping the word
"oracle" to avoid confusion with Oracle Database (the stack uses
PostgreSQL). | User-requested clarity. | accepted
```

The retired term had been used for the Newman collection that is replayed unchanged against every extracted service to prove it behaves like the monolith. With PostgreSQL as the database it read as a product-name collision, so the **behavior-equivalence suite** is now the artifact and the **equivalence gate** the CI check that runs it. After row 031, anyone touching a chapter, test file or workflow can grep for the retired word and find the row that explains each fix.

A strategy row does real design work:

```
029 | Spring→Quarkus extractions use a two-phase strategy: Phase A lift
onto Quarkus via Quarkiverse Spring-compatibility extensions
(spring-web/di/data-jpa/security/etc.), Phase B refactor to idiomatic
Quarkus, measuring before/after. | User-provided; de-risks each extraction
(fast equivalence-suite-passing bridge) and yields a measurable
idiomatic-migration teaching moment; repeatable per-service template. |
accepted
```

It commits every extraction to the same two passes: a fast, low-risk lift, then an idiomatic refactor with before and after numbers. On the Review extraction, Phase A got the service through the equivalence gate quickly on the Spring-compatibility extensions, and only then did Phase B remove the shim and capture the native-image numbers that argue for Quarkus with evidence. Without the row, the payment extraction would have no obligation to follow the same shape, and the premise that one template yields six comparable extractions would quietly stop being true.

## The build plan: `_plans/build-plan.md`

The decision log records what was decided; the build plan records what is being built and in what order. It's one document, sections A through M:

- the chosen approach and the alternatives rejected;
- the per-chapter site architecture;
- the reference monolith's design, every planted flaw and the extraction that cures it;
- the strangler-fig sequence, service by service;
- the ADLC phase table, the testing strategy and the CI/CD design;
- an iteration-by-iteration release plan with risks and how each is detected.

The release table gives each iteration a theme, a shippable deliverable and the risks it retires. The first proving iteration retires the biggest at once: whether the monolith shows a pattern worth curing, whether the strangler mechanism works end to end, and whether the ADLC is a practice and not a diagram. The build plan is the only backlog: anything not in it is out of scope for the iteration in flight. Its front matter marks it `status: "planning only"`, so a plan doesn't silently promote itself into a build. The phase table ties Reconcile to the ledger:

```
| 7. Reconcile | Append decision outcomes, update build-plan status, record
drift | none | the three ledger artifacts | change log / traceability |
```

Reconcile isn't a ceremony bolted onto the end of a step. It is writing an outcome into `decisions.md`, updating a status row in `build-plan.md` and noting drift from the plan.

## Iteration plans and resume records: `_plans/iterations/`

The unit of daily work is one iteration, with a plan file and a status file. The plan breaks the iteration into numbered steps, each with a goal, the files it touches, its dependencies and an acceptance check. Steps where an error is expensive carry a note requiring a second, more skeptical model pass before the work is trusted. Steps are also marked parallel or serial: two agents editing the same reactor `pom.xml` collide, and a plan written in advance prevents it.

The status file is written when work is about to pause:

```
# Iteration status & resume record

_Last updated: <date>. Saved before a break/possible reboot._

## Where we are

The current branch is merged to `main` at this checkpoint. This iteration's
code is complete and gated: the Spring monolith runs and is tested, the first
context is fully extracted to Quarkus (Phase A lift → Phase B idiomatic →
flag cutover → monolith decommission), and the behavior-equivalence suite
gates it both locally and in green GitHub Actions CI.

## Remaining (resume here)

- Author the extraction's chapter to the ≥2000-word bar, embedding the
  already-committed figures and the real Phase A/B + cutover story.
- Reconcile: pin the version matrix in `decisions.md`; tick the iteration
  exit checklist; set the iteration status to DONE.
```

It's a note left for whoever picks the project up next, human or agent, with no memory of the session. It names the next step, the file it touches and what done means, so nobody re-derives the state from code. The same file records open items nobody has acted on, such as GitHub Pages not being enabled, or a module left as dead code after a decommission because a permission guard correctly refused to let an agent remove security configuration on its own authority. A ledger that records only successes can't be trusted, so loose ends are named as tracked gaps.

{% include excalidraw.html file="ledger-files-to-adlc-phases" alt="The ledger files mapped onto the ADLC's seven phases: the build plan and the iteration plan file are produced during Plan, the iteration status file is updated through Generate and Verify, and the decision log plus the reconciliation record are written during Reconcile, which also loops back to update the build plan's own status line. Below that mapping, the resume mechanism: the status file's own 'Remaining (resume here)' line, read by the next session alongside the plan file's step definitions." caption="Figure 2.1: The ledger files mapped onto the ADLC phases, and how an interrupted session resumes from them" %}

## Governing the agents: `AGENTS.md` and `CLAUDE.md`

The files above govern the project. `AGENTS.md` governs how an agent behaves inside one codebase. `examples/02-review-service/` carries one, plus a one-line `CLAUDE.md` that points Claude Code at it:

```
See AGENTS.md for project instructions.
```

Its rules are standing procedure, not decision history:

- search for a Quarkus extension before hand-writing what it could replace;
- present every matching extension to a human instead of silently picking one;
- load the extension's usage patterns before writing code against it;
- drive tests and reloads through the project's own tooling, not a hand-run build;
- summarize what changed at the end of every piece of work.

Every session in that directory inherits them without re-explanation. It's the same discipline at a narrower grain: project-wide decisions in one place, per-codebase operating rules in another.

## The ledger and the two human gates

The plan-approval gate is a human reading the plan file's numbered steps and acceptance criteria before any code is written. The sign-off gate is a human reading a decision row or status line that says which suite ran and what it reported. The plan also marks, in advance, the steps that need a second pass before their checkpoint commit lands: the behavior-equivalence suite itself, each phase of the Review extraction, and the flag-gated cutover that retires a piece of the monolith. "Was this step properly checked" then has a yes-or-no answer in a file.

## Reconcile and the third artifact

The phase table names three ledger artifacts. Two are covered above, with the per-iteration plan and status pair beside them. The third is a reconciliation record that tracks every reused or adapted artifact against its source, so drift from a cited reference is a tracked fact. When the Review iteration closed it didn't exist yet: the resume excerpt lists reconciliation as a remaining step, and the first three extractions were reconciled inline in their iteration plans and the decision log. `_plans/reconciliation.md` was first written at the payment extraction's reconcile step, with the earlier extractions backfilled at summary level. It is append-only, one section per extraction, and each reused artifact gets its source, what was reused, what diverges and a verdict. Divergence is allowed as long as it is named there and traces to an accepted decision row. The ledger names the step that will produce an artifact and tracks it like any other; it doesn't pretend the artifact exists before it does.

## For deeper coverage

The next section covers why modernization counts as disciplined engineering, and when it doesn't. The ADLC section defines the phase model in full, with this ledger as its working example.
