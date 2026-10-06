---
title: "Modernization as Engineering, Not Fashion"
order: 3
part: "Why Modernize"
description: "Modernization goals, the ten microservice traits, 'microservices are not the goal,' and when microservices are a bad idea."
---

Chapter 2 gave you the ledger you will keep as you work through this book —
the decision log, the build plan, the reconciliation record. This chapter
asks the question that every entry in that ledger ultimately has to answer:
why are you doing any of this at all? Before this book builds a single line
of the reference monolith, before it names a strategy or chooses an
extraction order, it owes you an argument, clear enough to survive
contact with a budget meeting: modernization is worth doing when it solves a
problem you can name and measure, and it should be executed as a disciplined
engineering activity with a safety net, not as a leap of faith toward a
fashionable architecture. That argument has two uncomfortable corollaries.
The first is that microservices are not themselves the goal, and a project
that treats them as one will produce a distributed system with all of a
monolith's coupling and none of its simplicity. The second is that the
discipline this chapter argues for is specifically what makes the
difference between a modernization that ships value continuously and a
rewrite that ships nothing until it either ships everything or quietly dies.
Both corollaries point at the same destination this book spends the rest of
its pages building toward: incremental, strangler-based extraction, with a
behavior-equivalence suite standing guard at every step.

## Why modernize at all

Start with the uncomfortable truth that modernization has no value in the
abstract. A system that works, that nobody has to touch under duress, and
that the business can still staff and extend, does not need modernizing just
because its stack is unfashionable. What makes modernization worth the risk,
cost, and organizational disruption it inevitably causes is a set of
problems that are concrete, measurable, and getting worse over time rather
than better. Those problems split cleanly into two families, and a
modernization effort that cannot point at symptoms from at least one of them
is a modernization effort chasing a trend rather than solving a problem.

The first family is delivery performance, and it shows up as exactly the
metrics a team already tracks (or should): **lead time for changes** — how
long does it take a single line of code to go from committed to running in
production — and **change-failure rate** — what fraction of the changes that
do ship cause an incident, a rollback, or a hotfix. In a monolith where every
change shares one build, one test suite, one deployable, and one release
train, both metrics degrade as the system grows, and not gradually: a
codebase at ten times its original size does not take ten times as long to
build and test, because the coupling between modules grows faster than the
modules themselves do. A change to the shipping module that cannot ship
without re-running the review module's test suite, re-deploying the payment
module's container, and waiting for a release window shared by six teams is
a change with an inflated lead time for reasons that have nothing to do with
the change itself. And a monolith where a bug in one module can corrupt
shared, in-process state for every other module is a monolith where the
blast radius of a mistake — and therefore the change-failure rate — scales
with the size of the whole system, not the size of the change. These are not
abstract concerns invented for this book; they are the two metrics every
delivery-performance framework in wide industry use treats as the clearest
signal of whether an organization's architecture is helping or fighting its
ability to ship safely.

The second family is structural, and it is slower-moving but, over a long
enough horizon, more expensive. **Scaling limits** show up when one
high-traffic concern — in this book's running example, product reviews
during a flash sale, or inventory lookups during a restock — forces you to
scale the entire monolith just to give one hot path more capacity, paying
for idle capacity in five modules that didn't need it to serve the one that
did. **Team autonomy** erodes as more teams own code inside the same
deployable: Conway's Law cuts both ways, and a single shared artifact forces
the coordination the org chart was trying to avoid by having separate teams
in the first place, turning every cross-team change into a negotiation over
a shared release window. And **stack decay** compounds quietly in the
background — a framework version nobody has upgraded in three years because
upgrading means re-testing the entire monolith at once, a security patch
that can't ship independently of a dozen unrelated modules, and a hiring
market where the engineers who know the stack well enough to work in it
safely are retiring faster than new ones are being trained on it. None of
these four problems — lead time, change-failure rate, scaling limits, team
autonomy — is solved by rewriting in a newer language or adopting a trendier
framework for its own sake. They are solved, when they are solved at all, by
changing the *boundaries* in the system: where one deployable ends and
another begins, where one team's change can ship without another team's
sign-off, where one schema ends and another team owns its own. That is what
this book means by modernization, and it is why Part 3 builds a monolith
with those exact boundary problems built in, rather than a
monolith that merely runs on an old version of Java.

Kleppmann names the property underneath all four of these symptoms directly
in *Designing Data-Intensive Applications*: reliability, scalability, and
**maintainability** are the three top-level concerns he argues every data
system has to satisfy, and maintainability itself breaks into three
facets — operability, simplicity, and **evolvability**, a system's ability
to accommodate change easily as requirements shift. Read that way, this
book's entire project is a maintainability intervention aimed squarely at
that third facet: every pattern from the strangler proxy onward exists to
make the next change cheaper than the last one was, not to chase a specific
technology for its own sake.

## The ten microservice traits

Before naming what makes microservices a bad idea for some systems, it is
worth being precise about what the word is actually promising, because
"microservices" used loosely enough to mean "any service" stops being a
useful target for the extraction chapters later in this book. The deck this
book's house style descends from names ten traits that, together, define
what this book is building toward one extraction at a time. A microservice
is **independently deployable** — it ships on its own schedule, not the
monolith's shared release train. It is **modeled around a business
domain**, not a technical layer, so a service's boundary matches a bounded
context rather than a tier like "data access" or "UI." Its instances
**communicate over the network** rather than through in-process calls,
which is the same change that turns a stack trace into a distributed trace.
It is best understood as **a form of opinionated service-oriented
architecture** — the integration ideas SOA introduced, with much stronger
defaults about size and ownership than SOA ever enforced. It is
**technology agnostic**, so one team's service can run a different language
or framework than its neighbor's without coordination. A set of them,
taken together, forms **a distributed system**, with everything that phrase
implies about partial failure and the loss of a single transaction
boundary. Each one is **API-focused**, hiding its internals entirely behind
a service boundary rather than exposing a shared library or a shared table
the way this book's own monolith does today. Governance over how a service
is built is **decentralized**, pushed to the team that owns it rather than
mandated centrally. The data each service holds is under **decentralized
data management**, owned by one service rather than shared across many. And
the whole system is **designed for failure**, built on the assumption that
any one service can be unavailable at any moment, rather than the
monolith's implicit assumption that if the process is up, every module
inside it is reachable.

None of these ten traits is this book's goal in isolation — the sentence
that opened this chapter already warned against that trap. Together, though,
they are the destination the strangler-fig sequence in Chapter 4 is aimed
at: each of the six extractions this book runs, from review through order,
is a bet that one more bounded context can acquire independent
deployability, owned data, and a real API boundary without first losing the
behavior the monolith already gets right.

{% include excalidraw.html file="microservice-traits" alt="A ten-box grid of the microservice traits this chapter enumerates: independently deployable, modeled around a business domain, communicate over the network, a form of opinionated service-oriented architecture, technology agnostic, a distributed system as a set, API-focused, decentralized governance, decentralized data management, and designed for failure." caption="Figure 3.1 — The ten microservice traits this book's extractions are built toward" %}

## The counter-case: when modernization is the wrong call

A book arguing for modernization owes you its strongest counter-argument,
not just its strongest argument, and the strongest counter-argument here is
a line from the deck this book's own house style descends from: "Microservices
should not be the goal of building microservices." Treat that sentence as a
discipline, not a slogan. If a team cannot answer "which of the four
problems above does this solve, and how will we measure the improvement," a
microservices migration is solving a fashion problem, not an engineering
one, and fashion problems do not justify the operational cost a distributed
system imposes.

Sam Newman is blunt about this in *Building Microservices*: microservices
trade a simpler deployment and operational model for independent
scalability and deployability, and that trade is only favorable when the
organization actually needs the things it buys — when the domain is complex
enough that independent evolution of its parts is worth the cost of network
calls, eventual consistency, and distributed debugging that used to be a
stack trace and is now a trace across five services. Several concrete
situations make that trade a bad one, and this book is specific about
naming them rather than treating "modernize" as an unconditional good:

- **Unclear domain boundaries.** If you cannot draw a confident line between
  where one bounded context ends and another begins — the work Part 4 of
  this book spends three full chapters teaching — splitting along a guessed
  boundary produces services that constantly call back and forth across
  the seam, which is strictly worse than a monolith with the same
  coupling, because now the coupling crosses a network.
- **An organization too small to carry the operational cost.** Microservices
  multiply the number of things that can fail independently: deployments,
  databases, message brokers, certificates, on-call rotations. A five-person
  team running one database and one deployable can often out-ship a
  twenty-service architecture that spends most of its capacity keeping the
  distributed system itself alive.
- **Packaged or vendor software with no internal seams to cut.** You cannot
  strangle code you do not own and cannot change; a COTS system's
  modernization story is integration and encapsulation, not decomposition.
- **An organization that has not yet mastered the practices microservices
  assume** — continuous delivery, automated testing, infrastructure as code.
  Ozkaya makes this point directly in *Design Microservices Architecture
  with Patterns and Principles*: microservices amplify whatever delivery
  discipline already exists, for better or worse, so adopting them before
  that discipline exists compounds the underlying problem rather than
  curing it.
- **Résumé-driven design.** The plainest case, and the one worth naming
  without euphemism: rearchitecting a working system because an
  architecture decision looks good in a portfolio, rather than because a
  measured business or engineering problem demands it, is not modernization.
  It is risk taken on for reasons the business did not agree to.

None of these cases describes this book's own reference monolith, and that
is a deliberate choice, not an accident: its six bounded contexts are
distinct domains (orders are not reviews), the organization behind
it is assumed to already run CI and automated tests, and every extraction
in this book is tied to a named engineering problem in the deliberate-smells
catalogue of Chapter 9. But stating the counter-case here, before a single
service is extracted, is what keeps this book's enthusiasm for
the strangler pattern from reading as uncritical. A reader whose own system
fails more of the tests above than it passes should walk away from this
book having learned when *not* to apply it — which Chapter 4 turns into an
actual decision rubric once the strategy menu (Lift and Shift, Modernize and
Extend, Rip and Rewrite) is on the table.

## Why big-bang rewrites fail

Granting that modernization is sometimes the right call, the next question
is how. The most intuitive answer — stop shipping features, rewrite the
system from a clean slate in the new stack, cut over once it is done — is
also the answer with the worst track record in the industry, and it is
worth understanding precisely why, because the failure mode is structural,
not a matter of a particular team executing poorly.

A big-bang rewrite makes three bets simultaneously, and all three have to
pay off at once for the project to succeed. It bets that the team correctly
understood every piece of behavior the old system had accumulated over
years of bug fixes, edge-case handling, and undocumented business rules —
behavior nobody wrote down as a specification because nobody had to, since
the running system *was* the specification. It bets that a system of
comparable scope can be built faster in the new stack than the old system
decayed, which is rarely true once the new system reaches the complexity
the old one already has. And it bets that the business can tolerate a long
stretch — often measured in quarters, sometimes years — where almost no new
value ships to users, because the entire engineering organization is heads
down on a rewrite that produces nothing releasable until it produces
everything. Any one of those three bets failing sinks the project; the
third one failing is usually what kills it, because a rewrite that is not
yet feature-complete cannot be partially shipped, and a business that has
been promised a cutover date for eighteen months loses patience well before
most rewrites finish. Newman's guidance in *Building Microservices* is
consistent and specific on this point: migrate incrementally, keep the old
and new systems running side by side, and never schedule a single moment
where everything has to work for the first time all at once. The entire
discipline this book teaches — one bounded context extracted at a time,
each one proven equivalent to its old behavior before the old code is
deleted — is Newman's incremental guidance turned into a concrete,
repeatable procedure.

The strangler fig pattern, which Chapter 14 introduces in full, is the
engineering answer to exactly this failure mode, and its value is almost
entirely in what it refuses to do. It refuses to require a complete
rewrite before anything ships: the first extraction in this book, the
review service in Chapter 15, is a small slice of the system, and
it is in production, behind a flag, before any other context has been
touched. It refuses to require the team to fully understand the legacy
system's behavior in advance: each extraction captures the *actual*
behavior of the running monolith as a contract, rather than asking an
engineer to recall or re-derive what that behavior should be from memory or
a stale design document. And it refuses to bet the business on a single
cutover date: because the monolith and the new service run side by side
behind a flag until the new service is proven equivalent, the project can
pause, slow down, or reverse a single extraction without threatening
anything else in flight. None of that removes risk from modernization — it
distributes the risk across many small, independently reversible steps
instead of concentrating all of it into one irreversible moment at the end.
That is what "modernization as disciplined engineering" means concretely:
not caution for its own sake, but risk management applied to the shape of
the migration itself.

{% include excalidraw.html file="rewrite-vs-strangler-risk" alt="A side-by-side risk-shape contrast. Left, the big-bang rewrite: three bets made at once (full behavioral understanding, outrunning decay, business patience for zero shipped value) feeding one irreversible cutover, with a late, all-or-nothing payoff. Right, the strangler fig: small steps starting with the review service, each proven equivalent before the next starts, running side by side with the monolith so any one step can pause, slow, or reverse without threatening the rest, with value shipping continuously." caption="Figure 3.2 — One irreversible bet versus many reversible steps: the same migration, two risk shapes" %}

## Modernization as a measurable activity

The discipline this chapter is arguing for has a specific, checkable form:
behavior stays constant while the properties you actually care about
improve, and both halves of that sentence have to be demonstrated, not
asserted. "Behavior stays constant" is the job of the **behavior-equivalence
suite** this book introduces properly in Chapter 10 — a contract test
collection captured against the running monolith and re-run, unchanged,
against every extracted service, with an **equivalence gate** in the pipeline
that blocks a module's retirement until the gate passes. "The properties you
care about improve" means naming those properties up front — latency,
startup time, memory footprint, deploy independence — and measuring them
before and after, the same way an engineer would validate any other
optimization. A modernization project that cannot show both halves of that
sentence for at least one completed step is not yet disciplined engineering,
whatever its architecture diagrams look like.

This book does not ask you to take that claim on faith, because it already
has a completed example: the review service extraction this book's own r02
iteration ran end to end, with every number below measured on a real build
rather than estimated for the page (`examples/02-review-service/MIGRATION.md`
has the full record). The extraction ran in two phases — Phase A lifts the
Spring code onto Quarkus with minimal changes via the Quarkiverse
Spring-compatibility extensions, so the equivalence gate passes quickly and
the extraction is de-risked before anything is rewritten for elegance; Phase
B then refactors the same service to idiomatic Quarkus, and is where the
measured payoff shows up:

| Build | Startup time | Resident memory | Equivalence suite |
|---|---|---|---|
| Phase A — JVM, Spring-compat | 1.492 s | ~316 MB | 16/16 green |
| Phase B — JVM, idiomatic Quarkus | 1.431–1.437 s | ~304 MB | 16/16 green |
| Phase B — native image | 0.048–0.049 s | ~73 MB | 16/16 green |

Read that table the way an engineer should, not the way a slide deck would.
The JVM-to-JVM improvement from Phase A to Phase B is real but modest —
startup is within run-to-run noise and memory drops only about four percent,
simply from no longer loading three translation extensions at boot. The
payoff that actually justifies the migration shows up moving to native
image: roughly thirty times faster startup and just over four times less
resident memory, which is the difference between a service that can scale
to zero and back in the time a user notices a page load and one that cannot.
None of those numbers would mean anything without the middle column of that
record, which this table does not show but the chapter's own account makes
explicit: the same sixteen-assertion equivalence suite passed, unchanged,
against all three builds, including the native build that only passed after
a closed-world reflection defect was found and fixed — a defect the JVM
build's more forgiving runtime reflection had silently papered over. That is
the whole argument in one artifact: three different runtimes, identical
observable behavior proven by the same unmodified test suite, and a measured
three-property improvement (startup, memory, and — once the flag flips and
the monolith's review module is decommissioned — deploy independence) that
the team can defend in a budget meeting with numbers instead of adjectives.
Every extraction from Chapter 17 onward repeats this exact shape: hold
behavior constant, measure what actually changed, show the work.

## The cost of inaction: coupling compounds

It is worth being equally concrete about the cost of doing nothing, because
"modernize eventually" is itself a decision with a price, and that price is
not flat over time — it compounds. Larry Constantine's observation, quoted
directly in this book's own source material, is the mechanism: "a structure
is stable if cohesion is high, and coupling is low." A system where that
balance has already tipped the other way does not sit still while a team
debates whether to modernize it; every feature added under deadline
pressure, every shortcut taken because the "proper" fix would touch three
other teams' code, adds coupling faster than it adds cohesion, because the
path of least resistance in a tightly coupled system is almost always to
couple the new code to what is already there rather than to carve out a
clean boundary nobody has time to build.

This book does not ask you to take that claim in the abstract either — it
is the entire premise of Part 3. The reference monolith built in Chapter 8
has five smells, each named and catalogued in Chapter 9: a
shared schema with cross-context foreign keys and joins that make inventory
and order data inseparable at the database level; a god `OrderService` that
reaches directly into payment, shipping, and inventory rather than calling
them through a boundary; a single in-process ACID `@Transactional` that
wraps inventory, order, payment, shipment, and notification into one
all-or-nothing commit spanning five bounded contexts; a notification call
wedged synchronously inside that same checkout transaction, so a slow
notification provider can fail an order that had nothing to do with
notifications; and no anti-corruption layer anywhere, so every caller
depends on the monolith's internal shapes directly. None of these smells
was an accident in the fictional team's
original design, and that is exactly the point: they are the ordinary,
defensible shortcuts a real team takes under a real deadline, and each one
is individually cheap the day it is introduced. What Chapter 9 demonstrates
is that they are not cheap cumulatively — the god service and the shared
schema interact to make the order-placement flow nearly impossible to
change safely without touching four other contexts at once, which is
precisely the lead-time and change-failure-rate cost this chapter opened
with, now visible in actual source code rather than described in the
abstract. Chapter 13's treatment of coupling as a dimension you can reason
about — not just a feeling — gives you the vocabulary to see this
compounding before it happens in your own systems, rather than only after a
migration has become urgent enough to be expensive.

The uncomfortable arithmetic is this: every one of those five smells is
cheaper to address today than it will be in a year, because each one that
survives another release cycle acquires new code written against it,
widening its blast radius. A shared schema with three foreign keys across
contexts is a schema you can still split with a weekend's migration script;
the same schema with thirty foreign keys and a dozen reports built directly
against it is a schema that takes a dedicated team a quarter to split
safely, if it can be split at all without a rewrite. "Modernize later" is
therefore not a neutral default — it is a bet that the cost of cutting the
seam stays flat while you wait, and that bet has historically lost. The
discipline this book argues for is not urgency for its own sake; it is
treating the cost curve of accumulating coupling as a real, measurable
quantity, and choosing to act while the cut is still cheap rather than
after it has become the kind of project that only a rewrite can finish.

## What's next

This chapter made the case for modernizing at all, named the conditions
under which that case does not hold, and argued that the engineering
discipline distinguishing a successful modernization from a failed rewrite
is incremental extraction proven equivalent at every step, with measured
properties as the payoff rather than an architecture diagram as the goal.
It did not yet tell you *which* strategy fits a given system, or how to
score one rigorously against the alternatives. **Chapter 4, "Strategies &
Assessment,"** takes that question head-on: it lays out the Lift and Shift,
Modernize and Extend, and Rip and Rewrite menu this book's own monolith is
run through, the strategic-value-versus-change-frequency framing that turns
"should we modernize this" into a defensible, measured answer rather than a
guess, and the specific strategy — a strangler-fig migration executed
within the Modernize and Extend family — that every later chapter commits
to and builds. From there,
Part 3 puts the argument this chapter made into running code: Chapter 8
builds the monolith, Chapter 9 catalogues its five remaining deliberate
smells in the actual source tree, and Chapter 10 captures the
behavior-equivalence suite that makes every later chapter's "measure what
changed, prove what didn't" promise checkable rather than rhetorical.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Its factual claims point at artifacts
this book makes checkable elsewhere: the measured Phase A/B/native numbers
and the 16/16 equivalence-suite results cited above are recorded in full in
`examples/02-review-service/MIGRATION.md`; the five remaining deliberate
smells are catalogued with in-code tags in Chapter 9; and the coupling
vocabulary used to reason about them is developed in full in Chapter 13.*
