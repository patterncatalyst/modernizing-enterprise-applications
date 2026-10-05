---
title: "The Project Ledger"
order: 2
part: "Setting Up"
description: "The ADLC's ledger — the decision log, the build plan, the iteration records, and the reconciliation log it still owes — introduced as the reader's own migration memory."
---

Chapter 1 got the toolchain running: SDKMAN, JDK 25, Maven, the Quarkus and
Camel CLIs, and a local podman stack standing in for the services this book's
monolith will eventually depend on. Everything from here forward assumes that
toolchain works. But before any of it touches the monolith this book builds in
Part 3, there is one more piece of setup, and it is not a tool at all — it is
a small set of plain-text files that this project keeps under `_plans/` and
updates continuously, every time a decision gets made or a step of work
finishes. This chapter introduces that **project ledger** using the actual one
this repository keeps for itself: not a hypothetical example constructed for
the page, but the real `_plans/decisions.md`, `_plans/build-plan.md`, and
`_plans/iterations/` files sitting in this project right now, mid-build, as
you read this. By the end of this chapter you will know what each file is
for, why an agent-driven modernization effort cannot safely proceed without
them, and where to look if you ever need to pick this project's own work back
up from wherever it was left off.

## Why a ledger, and why now

Every chapter from Part 5 onward in this book narrates the same basic shape of
work: a human states what should happen, an agent does reconnaissance,
produces a plan, writes code against that plan, verifies the result, rolls it
out, and records the outcome. Part 2 names that shape precisely — the
**Frame → Map → Plan → Generate → Verify → Operate → Reconcile** cycle this
book calls the ADLC, the AI Development Lifecycle — and Part 2 is where you
will see it defined phase by phase. This chapter is not that explanation. It
exists because the ADLC's last phase, Reconcile, is not an abstraction; it is
three concrete files that a human can open, read, and trust, and you need to
know those files exist and what they contain before you watch them get used.

Here is the problem a ledger solves, independent of any
particular framework or phase model. An agent-assisted modernization effort
is not one long conversation. It is dozens of short ones, spread across days
or weeks, often by different people, sometimes interrupted by something as
mundane as a laptop reboot. A single Claude Code session has no memory of a
session from three days ago. A human reviewing a pull request six weeks from
now has no memory of the exact reasoning that led to a choice made in week
one. And an agent picking up a half-finished extraction has to somehow know
not just what the code currently looks like, but what was *already decided*
and must not be silently re-decided differently this time. Without a durable,
external record, every one of those gaps becomes an opportunity for drift: a
decision quietly gets reversed because nobody remembered it was made, a step
gets redone because nobody could tell it was already done, or — worse — a step
gets skipped because nobody could tell it was *not* done. A ledger is simply
the answer to "where does the context live when no single conversation is
long enough to hold it."

This matters more for a modernization project than for most other kinds of
software work, for a reason Part 1 develops in full: a modernization effort is
refactoring under the hardest possible constraint, that the system must keep
behaving the way it already behaves, applied to code someone else wrote, for
reasons nobody fully remembers, that real customers depend on today. An agent
can retry a risky change as many times as it likes with no human watching —
that is exactly what makes agent-assisted work fast. But an agent cannot be
trusted to also decide, on its own authority, that one of those retries is
safe to ship against a production system. That is the job of the two human
gates Part 2 introduces, Plan approval and Verify sign-off, and both gates
only work if there is something durable on each side of them: a record of
what was proposed, and a record of what was actually confirmed. The ledger is
that record. It is how a human stays in control across many agent steps
without personally holding every detail in their head, and it is how the
project survives the gap between "the agent that did this work" and "the
human or agent that has to trust it later."

## The decision log: `_plans/decisions.md`

The first ledger artifact is an append-only decision log, one row per
decision, each with an identifier of the form `DRQ-NNN` ("decision request").
Every row names the decision, the reasoning behind it, and a status —
`fixed` for something the user set and that nothing downstream is allowed to
relitigate, `accepted` for a synthesis decision a later step could in
principle revisit but hasn't, and `proposed` for a recommended default still
awaiting confirmation. The numbering never resets and rows are never deleted;
if a decision changes, a *new* row records the change and says what it
supersedes, so the history of how the project arrived at its current state
stays intact rather than being overwritten.

Three real rows from this project's own `_plans/decisions.md` make the
pattern concrete. The first is about as small a decision as a project makes,
and it is exactly the kind of thing that would otherwise get re-litigated by
accident three weeks later:

```
DRQ-001 | Podman is the default toolchain (lgtm-podman-stack); minikube
(lgtm-minikube-stack) only for k8s-specific chapters (ch.29–31). No Docker
inheritance from DataMesh. | User-fixed. Single compose source avoids Dev
Services image-tag mismatch; one dev-loop/CI substrate. | fixed
```

Nothing about that row is dramatic, which is the point. Without it written
down, a later contributor — human or agent — reaching for container tooling
has no way to know that Docker was deliberately excluded rather than simply
never considered, and the project's own risk register (`build-plan.md` §M,
risk R5) names exactly this failure mode: "someone pulls `lgtm-docker-stack` /
DataMesh compose; Dev Services tag mismatch." The decision log is what makes
that risk detectable before it becomes a broken build instead of after.

The second row is a vocabulary correction, and it is worth including here
precisely because this book's own prose follows it everywhere except in this
one quoted row:

```
DRQ-031 | Rename "equivalence oracle" → "behavior-equivalence suite" (the
Newman collection) and "equivalence gate" (its CI check), dropping the word
"oracle" to avoid confusion with Oracle Database (the stack uses
PostgreSQL). | User-requested clarity. | accepted
```

That rename touched more than a glossary entry. The retired term had already
been used in earlier planning language for the Newman collection that gets
replayed, unchanged, against every extracted service to prove it behaves the
same as the monolith it replaced — the mechanism Part 3 builds and Part 5
onward enforces as a gate on every extraction. Once the project's database is
PostgreSQL, the old term read as a product-name collision waiting to confuse
a reader, so it was retired everywhere this book and its examples use it: the
**behavior-equivalence suite** is the artifact, the **equivalence gate** is
the CI check that runs it. The decision log is what makes a rename like this
safe to execute consistently rather than half-finished — anyone touching a
chapter, a test file, or a CI workflow after DRQ-031 lands can grep for the
retired word and know exactly which row explains why every hit needs fixing.

The third row is a strategy decision, and it is the one that shapes
every extraction chapter from Part 5 forward:

```
DRQ-029 | Spring→Quarkus extractions use a two-phase strategy: Phase A lift
onto Quarkus via Quarkiverse Spring-compatibility extensions
(spring-web/di/data-jpa/security/etc.), Phase B refactor to idiomatic
Quarkus, measuring before/after. | User-provided; de-risks each extraction
(fast equivalence-suite-passing bridge) and yields a measurable
idiomatic-migration teaching moment; repeatable per-service template. |
accepted
```

This is a case where a decision row does real design work, not just
record-keeping. It commits every future extraction to the same two-pass
shape — a fast, low-risk lift first, an idiomatic refactor second, with
measured numbers captured as the teaching payoff — before a single one of
those extractions has been written. Chapter 7 shows exactly what that
commitment bought on the walking-skeleton extraction: Phase A got Review
onto Quarkus and through the equivalence gate quickly using the
Spring-compatibility extensions, and only after that was proven safe did
Phase B remove the compatibility shim and capture the native-image numbers
that make the "why Quarkus" case with evidence instead of assertion. Without
DRQ-029 on record, a later contributor extracting, say, the payment service
in Part 7 would have no obligation to follow the same shape — and the
project's whole premise, that one repeatable template produces six
comparable extractions, would quietly stop being true.

## The build plan: `_plans/build-plan.md`

If the decision log is the project's memory of *what was decided*, the build
plan is its memory of *what is being built and in what order*. This
project's `build-plan.md` is a single long document — sections labeled A
through M — that covers the chosen approach and the alternatives it rejected,
the full chapter-by-chapter site architecture, the reference monolith's
design (including every deliberate flaw planted in it and which later chapter
cures each one), the strangler-fig decomposition sequence service by service,
the ADLC's own phase table, the testing strategy, the CI/CD design, and an
iteration-by-iteration release plan with explicit risks and how each is
detected. It is the closest thing this project has to a single source of
truth for "what are we building and why," and its own front matter is blunt
about its authority: it is marked `status: "round-1 planning only — nothing
built, scaffolded, or pushed until the user approves"`, which is itself a
ledger entry — a plan does not get to silently promote itself into an
in-progress build.

The section of the build plan most directly relevant to this chapter's
argument is its phase table, because it is where the ADLC's seventh phase —
Reconcile — gets tied explicitly to the ledger you are reading about right
now:

```
| 7. Reconcile | Append decision outcomes, update build-plan status, record
drift | — | the three ledger artifacts | change log / traceability |
```

Part 2 will unpack what "Reconcile" means as a phase in the loop a human and
an agent run together. What matters here is narrower and more concrete: the
table names its mechanism, and the mechanism is these files. Reconcile is not
a separate ceremony bolted onto the end of a step — it *is* the act of
writing an outcome into `decisions.md`, updating a status row in
`build-plan.md`, and noting any drift from what was planned. The phase and
the artifact are the same thing, which is exactly why this chapter precedes
Part 2 rather than following it: you need to recognize these three files on
sight before you watch a chapter narrate the loop that writes to them.

The build plan also keeps an iteration-by-iteration release table — this
project's actual backlog, not a hypothetical one — that assigns each
`rNN` iteration a theme, a concrete shippable deliverable, and the specific
risks that iteration is designed to retire. The walking-skeleton iteration
this project is mid-way through right now, `r02`, is defined there as
proving "the four biggest risks at once": whether the monolith actually
demonstrates a pattern worth curing, whether the strangler mechanism
works end to end, whether the ADLC is a real practice and not a
diagram, and whether a chapter can hit this book's two-thousand-word bar with
running code behind it. That table is what keeps a thirty-three-chapter,
nine-iteration project from turning into an unbounded wish list: the build
plan is the only backlog, and anything not in it is explicitly out of scope
for the iteration in flight.

## Per-iteration plans and resume records: `_plans/iterations/`

The decision log and the build plan both operate at the scale of the whole
project. Day to day, the unit of work is one iteration, and each iteration
gets its own pair of files under `_plans/iterations/`: an `rNN-plan.md` that
breaks the iteration into numbered, acceptance-gated steps before any of them
start, and an `rNN-status.md` that is updated as those steps complete —
including, critically, at the exact moment someone has to stop.

`r02-plan.md`, this project's actual iteration plan for its walking-skeleton
release, lays out fifteen steps (`S1` through `S14`, plus a CI step), each
with a stated goal, the files it touches, which other steps it depends on,
an acceptance check, and — for the steps where getting it wrong matters most
— an explicit note that the step additionally requires a second, more
skeptical model pass before its work is trusted. Steps are also marked for
which ones can run in parallel and which ones must serialize because they
touch a shared file, which is its own small but real control: two agents
editing the same reactor `pom.xml` at the same time is exactly the kind of
collision a plan written down in advance prevents, and a collision a plan
only discovered after the fact would not.

The status file is where the ledger's "work survives interruption" promise
becomes visible rather than theoretical. This project's own `r02-status.md`
was written and committed specifically because work was about to pause —
its very first line says so:

```
# r02 "Walking Skeleton" — status & resume record

_Last updated: 2026-10-02. Saved before a user break/possible reboot._

## Where we are

Branch `r02-walking-skeleton`, merged to `main` at this checkpoint. The
walking skeleton's code is complete and gated: the Spring monolith runs and
is tested, the Review context is fully extracted to Quarkus (Phase A lift →
Phase B idiomatic → flag cutover → monolith decommission), and the
behavior-equivalence suite gates it both locally and in green GitHub
Actions CI.

## Remaining in r02 (resume here)

- S13 — author ch.15 "Extraction 1: the Review service" to the ≥2000-word
  bar, embedding the two §15 figures (already committed) and the real Phase
  A/B + cutover story.
- S14 — reconcile: pin the version matrix in `_plans/decisions.md`; tick
  the r02 EXIT CHECKLIST; set r02 status DONE.
```

Read that excerpt as what it actually is: not documentation written
after the fact to describe a finished piece of work, but a note one
collaborator left for whoever — human or agent, possibly the same person a
week later with no memory of the session — picks the project back up next.
It names exactly which step is next, exactly which file that step touches,
and exactly what "done" means for it, because that information was already
sitting in the companion `r02-plan.md`. Nobody has to re-read the whole
project's history or re-derive the current state from the code alone; they
read one status file and start at the line marked "resume here." That is the
entire difference, in practice, between a project an interruption merely
delays and a project an interruption quietly corrupts.

The same file also records something a ledger has to keep even when it is a
little embarrassing, rather than quietly dropping it: open items nobody has acted on
yet, like GitHub Pages not being enabled for the site, or a module left as
dead code after a decommission because an automated permission guard
correctly refused to let an agent remove security configuration on its own
authority. A ledger that only records successes is not a ledger a human can
actually trust to stay in control with; this one writes down the loose ends
too, by name, so they are a known, tracked gap rather than a silent one.

## Governing the agents: `AGENTS.md` and `CLAUDE.md`

The three files above govern the *project* — what was decided, what is being
built, where to resume. A fourth kind of file in this project's example
directories governs something narrower and more immediate: how an agent is
supposed to behave while it is actually working inside a given piece of
code. `examples/02-review-service/` — the extracted Quarkus service this
book's Part 5 narrates in depth — carries its own `AGENTS.md`, and a
one-line `CLAUDE.md` that exists purely to point Claude Code at it:

```
See AGENTS.md for project instructions.
```

`AGENTS.md` itself is a short, specific rulebook for that one project: search
for a Quarkus extension before hand-writing anything it could replace,
present every matching extension to a human rather than silently picking
one, load the extension's usage patterns before writing code against it,
drive tests and reloads through the project's own tooling rather than
invoking a build tool by hand, and summarize what changed at the end of every
piece of work. None of that is decision history — it is standing operating
procedure, scoped to this one codebase, that every agent session working in
that directory inherits automatically rather than having to be re-explained
each time. It is a narrower kind of ledger than `decisions.md` or
`build-plan.md`, but it is the same underlying idea: write the thing down
once, durably, so it does not have to live only in one person's memory or one
session's context window. Chapter 7 draws this parallel explicitly when it
compares this project's ledger to a closely related workshop's use of
`AGENTS.md` to govern agent behavior across an entire codebase — two
different granularities of the same discipline, project-wide decisions in one
place and per-codebase operating rules in another.

## The ledger and the two human gates

Part 2 names two points in the ADLC where a human, not an agent, has to stop
the loop and decide: approving a plan before any code gets written against
it, and signing off that a verification result is sufficient evidence to
trust a change before it ships. Both gates are only meaningful if there is
something concrete for a human to look at, and the ledger is that something.
A plan-approval gate without a written plan is just a conversation nobody can
audit afterward; this project's gate is a human reading `r02-plan.md`'s
numbered steps, with their stated acceptance criteria, before a single line
of the monolith was written. A verify-sign-off gate without a recorded result
is just trust on faith; this project's gate is a human reading a decision row
or a status-file line that says, concretely, which suite ran and what it
reported, not merely that an agent claims it passed.

This is also why certain steps in `r02-plan.md` are marked as needing a
second, more skeptical pass before their checkpoint commit lands — the steps
where getting it wrong is expensive, like the behavior-equivalence suite
itself, each phase of the Review extraction, and the flag-gated cutover that
retires a piece of the monolith. That marking is not decoration either; it is
part of the plan, written down in advance, specifying exactly which steps
carry enough risk to need a second opinion before they are trusted — which
means the question "did this step get properly checked" has a yes-or-no
answer sitting in a file, instead of depending on someone's memory of whether
they meant to double-check it.

## Decisions that don't get silently reversed

An append-only log has one more property worth naming directly, because it
is easy to undervalue until the alternative actually happens: a decision,
once recorded, cannot be quietly un-made. If `build-plan.md`'s own account of
how this project's overall approach was chosen is read carefully, it records
not just the decisions that were made but the specific alternatives that were
considered and rejected at each branch point — which first service to extract,
how many chapters the book should have, how deep each migration should go —
each resolved with a stated reason, not just a stated outcome. A later
contributor tempted to revisit one of those choices does not have to
re-litigate it from nothing; they read the row, see the reasoning that was
already weighed, and either find it still holds or write a *new* row
explaining specifically what changed and why. Nothing gets overwritten in
place. That is a small discipline with an outsized effect on a long-running,
multi-session, multi-agent effort: it converts "I think we decided this
already, but I'm not sure" — the exact uncertainty that lets a bad decision
sneak back in unchallenged — into "here is the row, here is why, here is
whether anything has changed since."

## Reconcile, and the third artifact still to come

Part 2's phase table names three ledger artifacts, and this chapter has so
far walked through two of them in detail plus the per-iteration plan and
status pair that sit alongside them. The third — a reconciliation record that
tracks every reused or adapted artifact against the source it came from, so
that drift from a cited reference is a tracked fact rather than a silent one
— is not fabricated for this chapter, because it does not exist
in this project yet. It is scoped as one of the walking skeleton's own
remaining steps, visible in the resume excerpt above: closing out the current
iteration is exactly the moment this project commits to writing it. That is
worth sitting with rather than glossing over, because it is itself a small,
concrete demonstration of the discipline this chapter has been describing —
the ledger does not pretend an artifact exists before it does, it names the
step that will produce it and tracks that step like any other, and you are
reading this chapter at the point in this project's own timeline where that
step has not yet run.

## This book's own proof

Every claim in this chapter is checkable against a file sitting in this
project's repository right now, not an illustration built to make a point.
That is deliberate, and it is the same idea Part 2 returns to directly: the
`_plans/` ledger behind this book is not a teaching prop constructed to
demonstrate the ADLC after the fact — it is the actual record of frame
statements, approved plans, and verification results that produced the very
chapters you are reading, including this one. Chapter 7 goes further and
narrates the loop that writes to this ledger in detail, on a real extraction,
including two real defects the loop's safety net caught before either one
reached a reader. This chapter's job was narrower: make sure you know what
the ledger's pieces are called, what each one is for, and where to find them,
before you watch them get used two parts from now.

## What's next

Part 1, "Why Modernize," picks up a different question before any ledger
entry gets written against this project's own monolith: not how the work
gets recorded, but why a modernization effort — strangling a monolith into
services one seam at a time — is worth doing as disciplined engineering
rather than following fashion, and when it is not. Part 2, "The AI
Development Lifecycle (ADLC)," is where the phase model this chapter has only
gestured at — Frame, Map, Plan, Generate, Verify, Operate, and the Reconcile
phase that writes to the files you just read about — gets defined properly,
with the ledger as its running, working example rather than a diagram. Chapter
3, "Modernization as Engineering, Not Fashion," is next.

---
*Verification status: not applicable — this is a conceptual chapter about this
project's own planning artifacts, not a runnable example under `examples/`.
Every claim above is directly checkable against this repository's
`_plans/decisions.md`, `_plans/build-plan.md`, `_plans/iterations/r02-plan.md`,
and `_plans/iterations/r02-status.md` as they exist today. The one claim that
is explicitly *not yet* true — a populated `reconciliation.md` — is named as
such above rather than asserted; Chapter 7 shows the loop that produces
ledger entries like these running live, and this project's own exit step for
its current iteration is where that third artifact gets written.*
