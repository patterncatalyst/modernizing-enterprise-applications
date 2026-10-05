---
title: "The ADLC Safety Net"
order: 7
part: "The AI Development Lifecycle (ADLC)"
description: "Tests, contracts, and reconciliation as the guardrails that make agentic generation trustworthy, with a full loop run end-to-end on a trivial change."
---

Chapter 5 named the seven phases and the two gates. Chapter 6 named the agents
and tools that carry each phase out. Neither chapter proved the loop actually
holds together under real work, with a real monolith and a real extraction on
the other end of it. This chapter does. Every example in it is the Review
extraction that produced `examples/00-monolith/`, `examples/01-strangler-proxy/`,
and `examples/02-review-service/` in this project's own r02 walking skeleton —
not a staged illustration, but the literal commits, files, and test runs that
built the chapter you are about to read two chapters from now. And because the
loop was run against the actual project rather than narrated in the abstract, it did what a
process-under-construction does: it broke, twice, in instructive ways, and the safety net caught both breaks before either one reached a
reader or a production system. That is the chapter's actual subject. The ADLC
is not safe because an agent is careful. It is safe because the loop has a
behavior-equivalence suite wired into it as an automated gate, two places
where a human has to stop and decide, and a habit of writing down what
happened even when — especially when — it didn't match the plan.

There is no new runnable example directory to point at here; the evidence
trail already exists. `examples/02-review-service/MIGRATION.md` and
`examples/01-strangler-proxy/CUTOVER.md` are this chapter's primary sources,
written at the moment the work happened rather than reconstructed after the
fact, and `tooling/newman/` is the suite itself. What follows walks that
evidence phase by phase against the Chapter 5 model, pulling in the actual
captured output — never re-run live, per the project's own DRQ-025 — so you
can check every claim against a file that is sitting in this repository right
now.

## Frame: stating intent, on the record

The Frame phase is where a human writes down what "done" means before any
tool touches a keyboard, because a plan that is only in someone's head cannot
be approved, audited, or disagreed with. For this slice of work, the frame
statement was not a chapter-sized essay — it was a single decision-log row,
already quoted in Chapter 5, that fixed the one fact everything downstream
depends on: how "behaves the same" would be checked.

```
Captured — decisions.md, DRQ-014

The monolith's Newman collection is the behavior-equivalence suite (the
Newman collection captured against the monolith), run unchanged against
each extracted service and gating every extraction in CI via the
equivalence gate.
```

Notice what that one row buys. It does not say "write good tests" — it names
a specific artifact (one Postman collection), a specific invariant (it never
gets edited to make a later service pass), and a specific enforcement point
(CI). Every later phase in this chapter is downstream of that sentence. If the
Frame phase had instead said "the agent will judge whether the extraction
looks right," there would be no automated gate at all — just a model's
self-assessment, which is precisely the failure mode Chapter 5 warned against.
Framing the acceptance criterion as a re-runnable artifact, not a judgment
call, is what makes every later phase checkable rather than merely plausible.

## Map: reconnaissance before a single line changes

The Map phase answers "what exactly am I touching" before anything is
generated, and for Review the answer mattered more than it looked. The
monolith's six bounded contexts shared one Postgres database, one Spring
Security realm, and one deployable JAR; the question Map had to settle was
whether Review was actually independent enough to cut first, or only looked
that way. The reconnaissance — reading the monolith's `review` package, its
dependents, and its data shape — found that Review was REST-only, had no
synchronous call into or out of it from any other context, and touched
exactly one table (`reviews`) that nothing else in the schema joined against.
That is what made Review the walking-skeleton slice rather than an arbitrary
first pick (`_plans/decisions.md`, DRQ tension T1): it let the very first pass
through the loop exercise the *entire* seam machinery — strangler proxy, ACL,
Quarkus scaffold, equivalence gate, flag-gated cutover, monolith-module
decommission — without a second bounded context's behavior confounding the
result.

Map also surfaced a fact that matters for the rest of
this chapter: Review's extracted form was scoped to arrive on Quarkus via the
Spring-compatibility lift first (Phase A), and only move onto its own schema
in a later chapter's data-ownership pass (shared data to owned data is Part
6's subject, not Part 5's). For the duration of this walking skeleton, the
extracted service and the monolith it was being peeled away from would keep
reading and writing the *same* `reviews` table. File that fact; it is the
single biggest reason one of this chapter's two bugs went undetected for as
long as it did.

## Plan: the first human gate

Map produces understanding; Plan produces a commitment, and commitments are
where the ADLC inserts its first stop sign. The step plan for Review — what
became steps S6 through S10 of the r02 iteration plan — laid out the exact
sequence this chapter narrates: build the equivalence suite against the
running monolith, stand up a flag-gated strangler proxy defaulting every
request to the monolith, lift Review onto Quarkus via the Spring-compatibility
extensions, refactor it to idiomatic Quarkus with measured before/after
numbers, then flip the flag and decommission the monolith's copy. Every one of
those five steps carries its own acceptance criterion in `build-plan.md` and
its own checkpoint commit, and none of them began until a human read the plan
and approved it — not a rubber stamp on a diff, because at this point there
was no diff yet to stamp, only a sequence of intended diffs.

This is the gate Chapter 5 called the cheapest point to catch a wrong
assumption, and it is worth being concrete about why that is true here
specifically. Had the plan instead said "extract Review and give it its own
database from the start," the walking skeleton would have taken longer to
reach its first green equivalence-gate run, and — as the Verify section below
will show — it would also have hidden the very bug the shared-table decision
later exposed. The plan that was actually approved deferred schema ownership
as a scoping decision, not an oversight; a human agreeing to that
trade-off before any code existed is exactly the kind of judgment call Chapter
5 argued cannot be delegated to the agent that drafted the plan.

## Generate: Phase A, then Phase B

With the plan approved, Generate is the part of the loop that actually
produces code, and the two-phase migration strategy from DRQ-029 turns it into
two separate Generate-then-Verify passes rather than one.

**Phase A** lifted Review's Spring MVC controller, Spring Data repositories,
and Spring-stereotype service onto Quarkus essentially unchanged, using the
Quarkiverse Spring-compatibility extensions (`quarkus-spring-web`,
`quarkus-spring-di`, `quarkus-spring-data-jpa`) as a translation layer. The
quarkus-agent MCP server's `migrate-spring-to-quarkus` process drove this
pass rather than the agent improvising its own migration plan — exactly the
Map-and-Generate tooling split Chapter 6 described, where version-matched,
tool-grounded knowledge replaces a model's possibly-stale memory of a
framework's migration path. The result is checked into this project's git
history at commit `5479d49` (`feat(r02): extract Review to Quarkus — Phase A
lift via Spring-compat (equivalence 16/16 green)`), and that commit message is
not decoration: it names the exact Verify-phase result that let Phase A be
trusted, which is the subject of the next section.

**Phase B** is the slower pass Chapter 5 promised: with Phase A already
proven safe, Phase B removes the compatibility shim entirely and rewrites the
same behavior in idiomatic Quarkus — Jakarta REST (`@Path`/`@GET`/`@POST`)
instead of Spring MVC, Panache repositories instead of Spring Data JPA
interfaces, a `@ServerExceptionMapper` instead of a `@RestControllerAdvice`.
`examples/02-review-service/MIGRATION.md` documents the mapping component by
component — HTTP layer, data access, entities, service, exception mapping,
DTOs, security, config, build, tests — and the headline fact in that table is
how little had to change beyond framework vocabulary: the JPA entities needed
zero edits, because Panache's repository pattern (as opposed to its
active-record alternative) works against ordinary `@Entity` classes. Commit
`ad5a1c1` (`refactor(r02): Review Phase B — idiomatic Quarkus (JAX-RS/Panache/
CDI; native 0.048s startup; equivalence 16/16)`) is where this pass landed —
and, again, the commit message names a Verify result, because in this
project's convention a Generate-phase commit is never trusted on its own
authority.

## Verify: the gate that caught bug #1 — native image's closed world

Verify is where the behavior-equivalence suite actually runs, and it is also
where this chapter's first real "the net caught it" story happened. Phase B's
first native-image build did not pass cleanly on the first try — it **failed**
one assertion of the equivalence suite, and the way it failed is the whole
point.

```
Captured — the equivalence gate, Phase B, first native-image run
(examples/02-review-service, tooling/newman/mea.postman_collection.json,
"Review Context Contract" folder, 16 requests / 16 assertions)

newman run mea.postman_collection.json --folder "Review Context Contract" \
    --env-var baseUrl=http://localhost:8081

  ✔ 1a GET /api/reviews?sku=... -> 200, non-empty array
  ✔ 1b GET /api/reviews/{id} -> 200, same shape
  ✔ ...
  ✖ 4e POST /api/reviews (authenticated) with invalid rating ->
       expected 400, got 500

  15/16 assertions passed, 1 failed
```

The failing request posted a review with an out-of-range rating, expecting
the handler's `@ServerExceptionMapper` to return `400 VALIDATION_FAILED`. On
the JVM build this had worked every time, including the full equivalence-gate
run that had already gone green on Phase A. It worked on the JVM because
Jackson falls back to ordinary runtime reflection there — it can discover how
to serialize `ApiError` at the moment it is needed, no matter what the method
signature on the exception mapper declares. GraalVM's native image has no
such fallback: its closed-world assumption means every type that gets
serialized has to be registered for reflection at *build* time, from a static
scan of the code, and `GlobalExceptionMapper`'s methods were declared to
return the generic `jakarta.ws.rs.core.Response` rather than a type
parameterized with `ApiError`. The build-time scanner never saw `ApiError` as
a resource method's return type, never registered it, and the native
executable crashed trying to serialize an error body it had no reflective
metadata for — a 500 standing in for what should have been a 400, discovered
by nothing except actually invoking the compiled binary.

This is the chapter's first concrete argument for why the behavior-equivalence
suite has to be an automated gate and not a one-time test pass: the defect was
completely invisible to every JVM-mode check that had already run, including
the full equivalence-gate pass on Phase A, and it was invisible for a
structural reason — the JVM's reflection fallback — not a flaky-test reason
that a re-run would fix. The fix itself was small — `@RegisterForReflection`
added to the `ApiError` record, left in the code with a documented comment
rather than silently patched away — but finding it required the gate to be
cheap enough to re-run against the native artifact specifically, not just the
JVM one. A test suite that only ever runs once, against whichever build
happens to be convenient, would have shipped this bug.

```
Captured — the equivalence gate, Phase B, native image, post-fix
(same collection, rebuilt native executable)

newman run mea.postman_collection.json --folder "Review Context Contract" \
    --env-var baseUrl=http://localhost:8081

  16/16 assertions passed, 0 failed
```

And the measured payoff for having pushed all the way to native in the first
place is itself worth capturing, because it is the concrete "why Quarkus"
evidence Chapter 5 promised would come out of Phase B rather than being
asserted up front:

```
Captured — examples/02-review-service/MIGRATION.md, before/after metrics
(packaged artifacts run directly against the same podman-stack Postgres,
profile `prod`)

| Build                                   | Startup | RSS     | Artifact size | Features |
|------------------------------------------|--------:|--------:|---------------:|---------:|
| Phase A — JVM, Spring-compat (5479d49)    | 1.492s  | ~316 MB | 46 MB          | 17       |
| Phase B — JVM, idiomatic                  | 1.431s  | ~304 MB | 45 MB          | 14       |
| Phase B — native image                    | 0.048s  | ~73 MB  | 87 MB          | 14       |
```

JVM-to-JVM, Phase B's win over Phase A is modest — a few percent less memory
from no longer loading the three Spring-compatibility translation extensions
at boot, startup time within run-to-run noise. The real payoff is JVM to
native: roughly thirty times faster startup and a little over four times less
resident memory, at the cost of the closed-world reflection gotcha just
described and a build that takes markedly longer than a JVM package step.
Both rows are real measurements from a real machine, not projected figures —
the MIGRATION.md note is explicit that native was attempted, not deferred,
precisely so this table could be cited rather than estimated.

## The second human gate: signing off on equivalence

Finding and fixing the native-image bug did not, by itself, close the Verify
phase. The ADLC's second gate sits exactly here: a human has to look at
"16/16 green" and decide that it is sufficient evidence to trust the service,
rather than treating a green suite run as self-certifying. That distinction
is not cosmetic. The suite can only check what it was written to check, and
its 16 assertions were captured against the monolith's *observable* HTTP
contract — status codes, response shapes, content types — which is exactly
why a defect that produces the *wrong* status code under one specific build
mode is within its power to catch, while a defect in something the suite
never asserts would not be. Signing off on equivalence means a person taking
responsibility for the judgment that this suite's coverage is the right
coverage for this step, not merely confirming that an exit code was zero. For
Review's Phase B, that sign-off happened once against the native 16/16 result
above — and, as the next section shows, it was not the last time a human
judgment call mattered more than a green suite run in this loop.

## Operate: flipping the flag — and catching bug #2

Operate is where the extracted service stops being a side-by-side comparison
and starts actually serving traffic, gated by a feature flag rather than a
redeploy. `examples/01-strangler-proxy/` fronts the monolith with a Camel
route that inspects the incoming path and a flag,
`strangler.review.enabled`, that decides which backend answers `/api/reviews`
requests. With the flag at its pre-cutover default of `false`, the full
behavior-equivalence suite — now exercised through the proxy rather than
pointed directly at a service, 16 requests expanding to 49 assertions once
the full order/inventory/payment scenarios are included — ran clean:

```
Captured — demos/demo-equivalence.sh http://localhost:8888, flag OFF
(examples/01-strangler-proxy/CUTOVER.md, timeline step 1)

49/49 assertions passed, 0 failed.
Review served by: the monolith, reached through the proxy.
```

That result was expected — the proxy was not supposed to change anything
with the flag off. The actual cutover check came next: flip
`strangler.review.enabled` to `true`, expecting Review traffic to now reach
`examples/02-review-service` instead of the monolith, and re-run the same
suite.

```
Captured — demos/demo-equivalence.sh http://localhost:8888, flag ON
(first attempt, before the routing defect was found)

49/49 assertions passed, 0 failed.
```

Forty-nine green assertions, and every one of them was reporting a false
positive. The routing predicate inside the Camel route checked whether
`${header.CamelHttpPath}` started with `/reviews`; but the `platform-http`
consumer underneath it, configured with `matchOnUriPrefix=true`, sets
`CamelHttpPath` to the *full* incoming path — `/api/reviews`, not `/reviews`
relative to the route's own `/api` prefix. The predicate never matched
anything, the route's `choice()` fell through to `otherwise()` on every
request, and the flag — true or false — never had the slightest effect on
where Review traffic went. All 49 assertions came back green because the
Quarkus service, still in its Phase A shared-schema form, was reading and
writing the *exact same `reviews` rows in the exact same Postgres database*
as the monolith it was supposed to be replacing. Checking the externally
observable response — the only thing a black-box contract suite can ever
check — could not distinguish "the correct service answered" from "the wrong
service answered with identical data," because, for this one slice of the
walking skeleton, both statements described the same rows.

This is the chapter's second and sharper lesson: a behavior-equivalence suite
verifies the *response*, not the *route the request took to produce it*, and
when two backends share state, a routing defect and a correct cutover are
observationally identical from outside. Catching it required asking a
different, more adversarial question than "does the suite pass": does the
proxy's *decision* survive a condition under which the two backends cannot
possibly agree? The check that answered that question was blunt and
effective — stop the monolith outright, so there is no longer any process on
the other side of a misrouted request, and re-try the Review route through
the proxy.

```
Captured — differential monolith-down test
(examples/01-strangler-proxy/CUTOVER.md, timeline step 2)

monolith process stopped; proxy left running with the flag at `true`

GET /api/reviews?sku=SKU-WIDGET-001 -> 200 (served by review-service;
                                             monolith is down)
GET /api/orders                     -> 500 (still targets the dead
                                             monolith, as expected)
```

That first attempt actually returned the same failure every other `/api/**`
path returned with the monolith down — proof that the routing defect had been
silently sending *every* request, Review included, to the monolith all along,
regardless of the flag. The fix was a one-line predicate correction —
matching the full `/api/reviews` path the consumer actually presents, instead
of a path relative to a prefix that had already been stripped elsewhere — and
the differential test was re-run with the monolith still down to confirm the
new result was a real decision, not another coincidence:

```
Captured — differential monolith-down test, post-fix
(examples/01-strangler-proxy/CUTOVER.md, timeline step 2, after the
predicate correction)

GET /api/reviews?sku=SKU-WIDGET-001 -> 200 (served by review-service;
                                             monolith remains down)
```

Only after that differential result — a proof that the proxy's routing
*decision* was correct, not merely that two backends happened to agree — was
the monolith's Review module actually decommissioned and the full suite
re-run through the proxy once more as final confirmation:

```
Captured — demos/demo-equivalence.sh http://localhost:8888, post-decommission
(examples/01-strangler-proxy/CUTOVER.md, timeline step 4)

49/49 assertions passed, 0 failed.
Review served by: examples/02-review-service (Quarkus, sole owner).
Everything else served by: the monolith (now five contexts).
```

The lesson to draw from these two stories together, not separately, is the
chapter's thesis in concentrated form. The native-image bug shows what the
equivalence gate is *for*: a cheap, automated, re-runnable check that catches
a defect no amount of JVM testing would ever surface, simply by running
against a build mode the JVM tests don't exercise. The routing bug shows what
the equivalence gate is *not*: it cannot distinguish a correct decision from
a coincidental agreement when the two backends it is comparing happen to
share state, and 49 green assertions proved exactly nothing about whether the
flag worked. An equivalence suite is necessary — without it, the native-image
regression ships. It is not sufficient — without an adversarial check
designed to break the suite's blind spot (take away the shared state, or in
this case take away one backend entirely), the routing defect ships anyway,
hidden behind a passing report. Both gates matter for the same underlying
reason: a green test run is evidence, not proof, and the discipline the ADLC
asks for is to keep asking "what would make this pass by accident" until the
answer is "nothing I can think of" — which is a question a scripted
assertion cannot ask of itself, and exactly the kind of skepticism the
second, human/Opus-tier gate exists to supply.

## Reconcile: the ledger closes the loop

Reconcile is the phase most lifecycles skip, and it is the one that makes the
two stories above legible to anyone who was not in the room when they
happened. Nothing about either bug was left as a verbal anecdote: the native-
image finding is a dated section of `examples/02-review-service/MIGRATION.md`
with the exact root cause, the exact fix, and a javadoc note left in the
`ApiError` source itself; the routing finding is a dated section of
`examples/01-strangler-proxy/CUTOVER.md` with the exact predicate change and
the differential test that proved it. Both documents record not just what was
fixed but what was deliberately *not* fixed yet — the `reviews` table was not
split into its own schema during this cutover, even though doing so would
have prevented the routing bug from hiding as long as it did, because true
per-context data ownership is Part 6's subject, not Part 5's, and reopening
that scope here would have meant re-litigating a decision the approved plan
had already made. That is a real drift from an idealized "do it right the
first time" sequence, and the ADLC's answer to drift is not to pretend it
didn't happen — it is to write it down, scoped and dated, so the chapter that
does address it (ch.18/19, shared data to owned data) inherits an accurate
starting point instead of a surprise.

The git history is the same ledger, one layer down. Reading it in order is
reading the actual plan-execute-verify sequence this chapter just narrated,
not a cleaned-up retelling of it:

```
Captured — git -C modernizing-enterprise-applications log --oneline
(the real r02 ledger, Frame through Operate for Review)

ee54b1f chore(r01): add round-1 planning artifacts (build-plan, decisions, research)
a668511 site(r02): scaffold Jekyll site (green #3d7a4e theme, 11 parts, 33 chapter stubs)
c1cdf3b chore(r02): add local podman stack (Postgres, Kafka KRaft, LGTM/OTel)
a0088a5 feat(r02): add reference Spring Boot monolith (6 contexts, 6 tagged smells, Testcontainers smoke test)
e6d62bc chore(r02): add three-tier JUnit test layer to monolith (45 tests, mvn verify green)
6cb113e feat(r02): add behavior-equivalence suite (Newman; 49 assertions green on monolith)
4927e1b feat(r02): add Camel strangler proxy (flag-gated; equivalence suite green through proxy)
5479d49 feat(r02): extract Review to Quarkus — Phase A lift via Spring-compat (equivalence 16/16 green)
ad5a1c1 refactor(r02): Review Phase B — idiomatic Quarkus (JAX-RS/Panache/CDI; native 0.048s startup; equivalence 16/16)
e11f53e feat(r02): strangler cutover + decommission monolith Review (routing bugfix; equivalence 49/49 via proxy)
```

Every commit message in that list names a Verify-phase result, not just a
feature. That is not a style preference — it is the Reconcile phase done at
the smallest possible grain, so that six months from now, nobody has to trust
a chapter's retelling of what happened; they can read the commit that did it
and the file it changed. `_plans/build-plan.md` and `_plans/decisions.md`
carry the same discipline at a coarser grain — the step plan, the acceptance
criteria, the DRQ-014 decision that made "equivalence" a checkable word in
the first place. This project's own ledger is not a teaching prop built to
illustrate the ADLC after the fact; it is the actual record this chapter has
been quoting from the start.

## The lesson: necessary, not sufficient

Pull back from the specifics and the shape is simple enough to state in one
sentence: the ADLC is only as safe as its safety net, and that net has three
layers, not one. The behavior-equivalence suite is the automated layer — fast
enough to re-run after every candidate, which is what caught the native-image
regression the instant a native artifact was actually exercised. Adversarial
verification — the differential test that removed the monolith instead of
trusting the suite's agreement — is the layer that compensates for the first
layer's specific blind spot, shared state masking a routing defect. And the
two human gates, plan approval and equivalence sign-off, are the layer that
decides, on a case the agent produced, whether the first two layers' coverage
was actually the right coverage for the stakes involved. Remove any one
layer and a specific one of this chapter's two bugs gets through: remove the
equivalence gate and the native-image regression ships silently; remove the
willingness to distrust a green suite run and design an adversarial check and
the routing defect ships, 49-for-49, straight past a human who read the
report and saw no reason to doubt it.

This is also the concrete answer to a question Chapter 5 left open: what,
precisely, does "verify" mean once the suite has already run? It means a
human or an Opus-tier validator asking whether the suite's specific coverage
could be fooled by the specific shape of *this* change — here, two backends
sharing one table — and, when the answer is yes, reaching for a check that
does not share that blind spot. That is a skill, not a checklist item, and it
is the reason the second gate sits with a person rather than inside the suite
itself.

## What's next

This chapter ran the loop once, on real work, and showed its net catch two
real bugs. Part 3 builds the system that loop will spend the rest of this
book strangling — the reference monolith, its six bounded contexts, and the
deliberate smells planted into it, so that every later pattern
arrives because the monolith actually needs it, not because a chapter outline
said so. Part 4 then goes looking for the seams those smells mark, using
domain-driven design and event storming to decide where the next cut goes
before the ADLC runs this same loop again, at rising difficulty, five more
times.

> **Further reading** — Chapter 5 mapped Daniel Oh's **Enterprise Agentic AI
> Workshop** onto this book's model in general terms; this chapter is where
> that mapping gets cashed in. Its use of `AGENTS.md` to govern agent
> behavior is the direct analog of the three-artifact ledger this chapter
> just walked — `decisions.md`, `build-plan.md`, and the per-example
> `MIGRATION.md`/`CUTOVER.md` records — as the lightweight, agent-maintained
> memory a human can audit without re-deriving it from a transcript. Michael
> Albada's *Building Applications with AI Agents* remains the deeper
> treatment of why that kind of externalized, auditable memory matters for
> any agent architecture, not just this one.

---

*Verification status: the two findings this chapter centers on are real,
dated, and already recorded in the repository this book is built from —
`examples/02-review-service/MIGRATION.md` (the native-image
`@RegisterForReflection` gap) and `examples/01-strangler-proxy/CUTOVER.md`
(the routing predicate false positive) — and the commits cited above
(`5479d49`, `ad5a1c1`, `e11f53e`) are the actual checkpoints that produced
them. What remains unverified at the time of writing is only the claim that
the deferred per-context schema split (Part 6) will fully retire the
shared-table blind spot this chapter documents; that claim is tracked, not
assumed, and gets its own verification pass when ch.18/19 ship.*
