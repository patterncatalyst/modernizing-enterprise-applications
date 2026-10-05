---
title: "Strategies & Assessment"
order: 4
part: "Why Modernize"
description: "Lift & Shift, Modernize & Extend, Rip & Rewrite, the strategic-value x change-frequency 2x2, the ease-of-migration rubric, and the evolution arc reframed for 2026."
---

Chapter 3 made the case that modernization needs a reason before it needs a
plan — that "microservices" is never the goal, only sometimes the answer.
This chapter assumes you have a reason and asks the next two questions in
order: *which* strategy fits the system you actually have, and once you have
picked one, *in what sequence* do you cut it apart without breaking it.
Those are different questions with different failure modes. Pick the wrong
strategy and you spend a year modernizing the wrong thing the wrong way. Pick
the right strategy but the wrong sequence and you spend the first three
months of the right project proving nothing except that the hardest part of
the system is, in fact, hard — a lesson that was true before you started and
is expensive to relearn under deadline pressure. This chapter answers both,
and commits this book to a specific, named strategy before Part 3 builds the
monolith that strategy will be run against.

## The strategy menu: what each option actually buys

Every modernization effort, regardless of the vocabulary a given vendor or
consultancy attaches to it, is choosing a point on the same spectrum: how
much of the existing system's *shape* survives the effort, versus how much of
its *behavior* must survive it. At one end, you touch almost nothing about
the system's internal shape and simply move where it runs. At the other, you
discard the shape entirely and rebuild behavior from a blank editor. The
industry has accumulated a family of names for stops along that spectrum —
sometimes called the "Rs" — and it is worth naming them plainly once, because
the rest of this chapter, and this book's strategy choice, only makes sense
against the full menu:

- **Retain** — change nothing; the system stays exactly where and how it is.
  Not a modernization strategy so much as the explicit decision not to
  modernize *this* system *now*, usually because its strategic value is low
  and its change frequency is lower still.
- **Retire** — decommission the system because the capability it provides is
  no longer needed, or is better provided by something already running
  elsewhere in the portfolio.
- **Repurchase** — replace the system with a commercial or SaaS product
  rather than modernizing the custom code at all; appropriate when the
  capability is not a competitive differentiator and a vendor already solves
  it well.
- **Rehost** ("lift and shift") — move the running system onto new
  infrastructure — a container platform, a different data center, a cloud
  provider — with its internal architecture essentially untouched. The deck
  this book draws from calls this **Lift and Shift**: automate the
  infrastructure and middleware, containerize the existing workload, deploy
  it on a modern platform, and leave the application's code, data model, and
  external integrations exactly as they were. A close cousin lives at this
  same point on the spectrum under a different mechanism: **Container-Native
  Virtualization**, which runs an existing virtual-machine workload inside a
  container platform without re-architecting it at all — the least complex
  option on the whole menu, and often the fastest route to a measurable cost
  benefit for a system that does not justify deeper investment.
- **Replatform** — a slightly deeper rehost: adopt a handful of
  platform-native capabilities (managed data stores, platform-native
  logging, a managed message broker) during the move, without changing the
  application's architecture or domain boundaries.
- **Refactor / Re-architect** — change the system's internal structure —
  its module boundaries, its data ownership, its deployment topology — while
  preserving its external behavior throughout the change. This is where the
  deck's **Modernize and Extend** strategy lives: the legacy system stays
  intact while a new architectural layer is added alongside it, with new
  integration points connecting old and new, cut over incrementally rather
  than all at once.
- **Rebuild / Replace** — discard most or all of the existing codebase and
  write new software against the same (or a deliberately changed) set of
  requirements. The deck's **Rip and Rewrite**: legacy interfaces and data
  are fully replaced, a handful of data elements or features are re-wrapped
  for continuity, and the rest is retired outright. This is the strategy of
  choice for systems whose rate of required change has outgrown what
  incremental modernization can plausibly keep up with — a mainframe
  platform, a large monolith that has accreted past the point of safe
  incremental change, or commercial-off-the-shelf software nobody can
  refactor because nobody owns its source.

Each option trades the same two currencies against each other: **how much
risk you take on at once**, and **how long you wait before the system is
better**. Retain and Repurchase take almost no technical risk because they
avoid touching the system's code at all — but Retain buys you nothing, and
Repurchase trades a code problem for a vendor-dependency problem. Rehost and
replatform are cheap and fast, and that is exactly their appeal when a
system's strategic value is modest and its change frequency is low: you get
a real, measurable win — lower operating cost, a supported platform, a
shrinking data-center footprint — in weeks, without betting the project on
understanding a codebase nobody currently understands. What rehosting does
*not* buy you is anything about the system's actual architecture: the shared
schema is still shared, the single deployable is still single, and every
structural reason the system was expensive to change before the move is
expensive to change after it, just on newer hardware. Rebuild buys you the
cleanest possible result on paper — no legacy constraints at all — at the
highest risk on the menu: a long, uninterrupted stretch where the new system
does not yet do what the old one does, no way to verify partial progress
against production behavior until the whole thing is finished, and the
well-documented "second-system effect" where the rewrite quietly grows scope
to fix everything wrong with the original, not just modernize it.

Refactor and re-architect sit in the middle, and the deck frames the choice
among all of these explicitly as a 2×2: plot a candidate system's
**strategic value** against its **change frequency**, and the quadrant it
lands in suggests which strategy fits. High strategic value and high change
frequency — a system the business is actively investing in and iterating on
— is exactly where incremental re-architecture earns its cost, because every
future feature lands faster once the architecture stops fighting the team
that maintains it. Low strategic value and low change frequency is Retain or
rehost territory — there is no return on the deeper investment. The deck
pairs that 2×2 with an **ease-of-migration rubric** scored across code,
configuration, data, secrets, network topology, installation, licensing, and
system type, rated easy, moderate, or difficult — a second axis orthogonal to
strategic value, because a system can be strategically critical *and*
difficult to touch at the same time, and that combination is precisely where
sequencing, not strategy choice, becomes the hard problem. This book's
monolith sits in exactly that quadrant on purpose: high strategic value (it
is the order-taking and fulfillment path for a shipping business), and, on
the ease-of-migration rubric, difficult on data (one shared schema with
cross-context foreign keys) and moderate everywhere else — a believable,
unglamorous candidate for re-architecture rather than a straw man built to
make the case too easy.

## The evolution arc, reframed for 2026

The strategy menu above answers "how much of the system changes." A second
question, worth answering once before this book commits to a toolchain, is
*where the industry's architecture has already gone*: Modernize and Extend
lands on top of a well-worn arc, not a blank slate. The deck this book
draws its house style from traces that arc in four stops: **Monolith** →
**SOA/ESB** → **Microservices 1.0** → **Cloud-Native (Microservices
2.0)**. SOA's promise was integration through a central bus, and its
failure mode, named plainly in the deck's own framing, was centralization —
"the main issue with SOA and ESBs is centralization, from both
architectural and organizational points of view" — the same coupling
problem Chapter 3 diagnosed inside a single monolith, just moved up to the
integration layer. Microservices 1.0 answered that by decentralizing
deployment, but every capability a monolith or an ESB used to provide for
free — discovery, routing, resilience, configuration, tracing — now had to
be rebuilt as application code, and the industry's answer for several years
was a specific, named stack: Netflix Eureka for discovery, Hystrix for
circuit breaking, Ribbon for load balancing, Zuul for edge routing, Zipkin
for tracing, and Spring Cloud Config for externalized configuration. Every
one of those libraries is dated in 2026, several formally in maintenance
mode, not because the problems they solved went away, but because the
problems *moved*.

That move is the reframe this book is built around. Cloud-Native does not
discard the Microservices-1.0 chassis; it relocates each piece of it from
an in-process library onto the platform underneath the service. Kubernetes'
own Service and Endpoints objects do what Eureka did: a pod is discoverable
by existing, not by calling a registry client. A service mesh such as Istio
does what Ribbon and Zuul did together, as a sidecar no application code
has to link against. OpenTelemetry, emitting the same kind of spans Zipkin
once collected, replaces a vendor-specific tracing client with a
vendor-neutral one the platform exports. And SmallRye Fault Tolerance and
MicroProfile Config — the specifications Quarkus implements natively — give
this book's own services Hystrix's circuit breaker and Spring Cloud
Config's externalized configuration as an annotation and an
`application.properties` entry, not a separate client and server to run and
patch. The pattern persisted; only its
implementation moved, from something every service bundled to something the
platform now provides for free. That persistence-of-pattern,
relocation-of-implementation is exactly why this book targets Quarkus on
Kubernetes rather than inventing its own chassis from scratch: the chassis
already exists, and building on it is what lets Chapter 15 onward spend its
effort on domain boundaries and behavioral equivalence instead of
re-deriving service discovery, circuit breaking, and distributed tracing
from first principles the way a team modernizing in 2015 had no choice but
to do.

## Why this book commits to incremental strangler-fig re-architecting

Given that menu, this book picks one point on it and defends the choice
rather than surveying the rest forever: **incremental re-architecture,
executed through the Strangler Fig pattern, gated at every step by a
behavior-equivalence suite.** That is a specific commitment, not a synonym
for "Modernize and Extend" — it names the mechanism (strangler-style
incremental replacement), the direction (toward independently deployable
services with owned data), and the safety property (every cut is proven
behaviorally equivalent before the old code is retired) all in one sentence,
and it is worth being explicit about why each of those three pieces beats
the alternatives for the system this book builds.

Against **rehosting**, the case is straightforward: this book's monolith is
not expensive to run — it would happily sit on a single modest container
indefinitely. Its actual cost is structural. A god `OrderService` that
reaches directly into four other contexts' internals, a shared schema with
cross-context foreign keys, and one checkout transaction that spans five
bounded contexts are not infrastructure problems a platform move fixes; they
are architecture problems that get carried forward, unchanged, onto whatever
new infrastructure receives them. Rehosting this system would be a real,
fast win on operating cost and a complete non-answer to the actual
modernization goal.

Against **rebuilding**, the case is about risk shape, and this is where
Sam Newman's case for incremental migration over a big-bang rewrite (*Building
Microservices*, 2nd ed.) is worth citing directly rather than paraphrasing
loosely: a big-bang rewrite defers every check of whether the new system
actually behaves like the old one until the single moment the whole thing
cuts over, which is precisely the moment a defect is most expensive to find
and least reversible to fix. An incremental, seam-by-seam migration inverts
that risk shape — each seam is a small, independently verifiable bet, wrong
guesses are cheap to discover and cheap to reverse, and the business keeps
getting value from the system throughout the migration instead of waiting
for a finish line that keeps receding. That argument does not depend on
Quarkus, or Camel, or anything specific to this book's toolchain; it is a
property of incremental migration as a *strategy*, and it is the single
biggest reason this book never seriously considers Rip and Rewrite for a
system whose current behavior — flawed as its architecture is — is the exact
thing the business depends on today.

What makes "incremental" more than a slogan here is the second half of the
commitment: **the behavior-equivalence suite and the equivalence gate**
introduced in Part 0 and built in full in Part 3. Incremental migration is
only actually lower-risk than a rewrite if you can *prove*, at each step,
that the piece you just extracted behaves the way the piece it replaced did
— otherwise "incremental" just means "the same risk, spread out and
harder to notice." That is the role the monolith's own contract-test
collection plays once it becomes the equivalence suite: it is captured once
against the running "before" system, it is never edited to make a later
service pass, and it is re-run unchanged against every extracted service as
the gate that must go green before the corresponding monolith module is
retired. Ozkaya's treatment of decomposition strategies (*Design
Microservices Architecture with Patterns & Principles*) frames the same
discipline from the decomposition side: decomposing by business capability
or subdomain only pays off if each extracted piece can be independently
validated against the behavior it is replacing, not just independently
deployed. The equivalence gate is this book's concrete answer to that
requirement — a re-runnable, automatable check standing between "the code
compiles and the happy path works" and "this service is trusted," which
Chapter 10 builds and every extraction chapter from Chapter 15 onward
depends on.

## The Strangler Fig family, at the altitude a strategy decision needs

Naming the mechanism — Strangler Fig — is enough for this chapter's purposes;
the full mechanics, including this project's own Camel-based strangler
proxy and the anti-corruption layer at each seam, belong to **Chapter 14,
"The Strangler Fig Pattern,"** in Part 5, where there is running code to
ground them in. What an assessment-and-strategy chapter needs is the shape
of the pattern family at the level a decision actually requires.

Martin Fowler's original pattern, named for a fig species that grows around
a host tree and eventually replaces it entirely while the tree keeps
standing throughout, describes three steps applied repeatedly: identify a
piece of functionality to move, implement that functionality in a new
service, and reroute calls so the new service — not the old code — now
answers them. The pattern's appeal is precisely that each of those three
steps is small and independently reversible, which is what makes
"incremental" more than a marketing word for "slower rewrite." Three
variants of that base pattern matter for a strategy-level decision, because
they answer different questions about *how* the rerouting happens and *what*
state of data separation is acceptable along the way:

- **The Proxy variant** inserts a dedicated proxy layer in front of the
  monolith before any functionality moves. Every call still reaches the
  monolith at first, passing straight through the proxy; migrating a piece
  of functionality then means standing it up as a new service and flipping
  the proxy's routing for just that piece, with the monolith never aware
  the caller moved. The proxy is the single place every later cutover
  decision gets made, which is exactly why this book's strangler proxy
  (Chapter 14) is the first piece of new infrastructure every extraction
  touches, before any domain code does.
- **The Redirection variant** is the Proxy variant with a specific routing
  rule: the proxy redirects by URI path (or, for non-HTTP traffic, by an
  equivalent addressable resource identifier), which maps cleanly onto REST
  resource boundaries and lets a migration be staged story by story — one
  endpoint, one resource, one bounded context at a time — rather than
  requiring a single atomic cutover of an entire subsystem. This is the
  variant this book actually uses for every one of its six extractions: each
  one reroutes one resource's traffic once its replacement service has
  passed the equivalence gate.
- **The Shared-Database variant** is a plain acknowledgment that data
  separation and code separation do not have to happen in the same step.
  Under this variant, the newly extracted service and the still-shrinking
  monolith continue reading and writing the same underlying database while
  the *code* decomposition proceeds, with data ownership untangled
  afterward. This is not a destination this book recommends staying at —
  Part 6 spends an entire arc (Chapters 18 through 22) on exactly the cost
  of shared data and the path off it — but as a *transitional* state inside
  a single extraction, it is sometimes the only defensible option, and this
  book's own review extraction uses it deliberately: the review service is
  served independently behind the strangler proxy while its underlying table
  stays in the monolith's shared schema a while longer, because the service
  boundary and the data boundary are genuinely separable decisions, and
  forcing them to happen in the same step buys nothing.

Knowing the family exists, and which variant answers which question, is
what a strategy-level decision needs. Deciding *where* to point the proxy
first is a different problem — the one the rest of this chapter solves.

## Assessing the monolith: how do you choose an order

A strategy answers "how do we cut." It does not answer "what do we cut
first, and in what order after that." That second question is an
assessment problem, and getting it wrong does not show up as a failed
migration — it shows up as a migration that technically succeeds while
teaching the team the wrong lessons in the wrong order, because the hardest
seam was tackled before anyone had practiced on an easy one. Four criteria,
applied together rather than separately, turn "where should we start" from
a guess into a defensible answer.

**Coupling and cohesion.** Larry Constantine's observation that "a structure
is stable if cohesion is high, and coupling is low" is not abstract theory
here — it is a direct, measurable question to ask of each candidate bounded
context: how many other contexts does this one call synchronously, and how
many other contexts call into it? A context with zero synchronous
collaborators in either direction can be extracted and verified in
isolation; a context wired into four others' service and repository layers
cannot be extracted without first untangling, or at minimum proxying, every
one of those four relationships. Coupling is the single strongest predictor
of how much *other* work an extraction will drag behind it.

**Change frequency.** The same signal the strategic-value/change-frequency
2×2 used at the strategy level applies again at the per-context level: a
context the business rarely touches is a low-stakes place to validate a new
mechanism, because nobody is actively shipping features against it that a
migration could collide with. A context under constant feature development
is exactly where the payoff of modernizing is highest, but also where a
botched extraction does the most damage to delivery velocity in the near
term — which argues for tackling it once the mechanism has already been
proven elsewhere, not first.

**Risk and blast radius.** Independent of coupling, ask what breaks, and for
whom, if this extraction goes wrong: does it sit on the revenue path, does a
failure here cascade into contexts that have *not* yet been touched, and can
the cutover be reversed cheaply if the equivalence gate catches a regression
after rollout has already begun. A context that fails loudly and in
isolation is a far safer first bet than one that fails silently and spreads.

**Data entanglement.** Shared schema, cross-context foreign keys, and
JPA joins spanning bounded-context boundaries are a distinct axis from
code coupling, because two contexts can be perfectly decoupled in their
service layer while still being welded together at the database — and a
context's data can be entangled even when nothing calls it synchronously at
all. A context with its own schema, or one that can plausibly get one
without a data migration, is cheaper to extract than one that shares tables
with everything else in the system.

**Team boundaries.** Conway's Law cuts both ways here: a context one team
can own end to end, from its domain logic to its data to its on-call
rotation, is a context that can be extracted without a cross-team
coordination cost layered on top of the technical cost. A context that
currently requires three teams to agree before anything changes is going to
cost that same coordination overhead during extraction, regardless of how
clean its code turns out to be.

None of these four criteria is sufficient alone. A context can be
low-coupling and still high-risk if it sits on the revenue path; a context
can be rarely-changed and still deeply data-entangled. The assessment that
actually produces a defensible extraction sequence scores every candidate
bounded context against all four, together, and orders them from lowest
combined score — lowest coupling, lowest risk, least data entanglement,
clearest team ownership — to highest. Ozkaya's framing of
decomposition strategies makes the same point from a different direction:
decomposing by business capability only works as a *sequencing* decision,
not just a boundary-drawing one, when the capabilities are evaluated against
exactly these kinds of structural signals rather than against which one
feels most important to the business. Strategic importance decides *whether*
to modernize a system at all, back in the earlier sections of this chapter;
it is deliberately the wrong signal for deciding *which seam to cut
first* inside a system you have already committed to modernizing, because
the most important context is almost never the least risky one to practice
on.

One more framing, from API Architecture, is worth folding into this
assessment rather than treating as a separate concern: Gough's case for
**API-led seams** (*Mastering API Architecture*) — treating the contract at
a boundary as the thing you design and stabilize first, before you decide
how the two sides of that contract are implemented — is effectively a fifth
lens on the same question, and it correlates strongly with the other four.
A context that already exposes a clean, REST-shaped API surface, with no
synchronous collaborators reaching past that surface into its internals, is
simultaneously low-coupling, low-risk, and already seam-shaped before any
extraction work begins — the contract barely needs inventing, only
enforcing. A context whose "API" is really just whatever method signature
happens to be convenient for an internal caller to invoke directly — no
contract, no translation layer, nothing standing between one context's
internals and another's — carries high coupling and high data entanglement
almost by definition, because nothing has ever forced its boundary to be
explicit. Assessing a monolith for extraction order is, in large part,
assessing *how API-shaped each candidate seam already is*, before you have
written a single line of the service that will eventually sit behind that
API.

## This book's own answer: review first, and why

The preceding criteria are not abstract in this book — they are exactly the
ones applied, in Part 1 of the project's own build plan, to this book's
six bounded contexts, and the resulting sequence is the one the rest of
this book executes: **review, then notification, then inventory, then
payment, then shipping, then order together with its GraphQL gateway.**
Walking that sequence against the criteria above is itself worked evidence
of the assessment method, not just a plan to take on faith.

**Review goes first**, and the reasoning is a direct application of
coupling and risk, not an arbitrary starting point. Review is a REST-only
leaf: nothing in order, inventory, payment, shipping, or notification calls
into review synchronously, and review calls nothing in any of those five
contexts either. Its only entanglement with the rest of the monolith was
never architectural in the first place — it was incidental, a side effect of
sharing the monolith's single global security configuration with every
other endpoint, despite having no runtime dependency on anything the
security configuration was actually protecting. That is about as close to
zero coupling as a bounded context inside a real system gets, and it means
review can be extracted, stood up as its own service, routed to through the
redirection variant of the strangler proxy, and verified against the
equivalence suite with **nothing else in the system able to confound the
result**. If the extraction fails, the cause is the extraction mechanism
itself — the proxy, the anti-corruption layer, the equivalence gate, the
cutover flag — not some tangled dependency on a context not yet touched.
That property is exactly what a first extraction needs to be worth: the
full mechanism gets proven once, end to end, against the lowest-risk
possible subject, before it is asked to carry any real domain complexity.
It is, deliberately, an anticlimactic first win — nobody is impressed that
reviews moved to their own service — and that is the point: the second
extraction is where the mechanism starts paying for itself, because by then
it already works.

**Notification comes second** because it is the next-lowest-coupling
context, but it is not risk-free in quite the same way review was: nothing
calls notification's *output* synchronously, but notification previously
sat *inside* the checkout transaction, called synchronously from order. That
difference is exactly why notification's extraction forces the next new
mechanism rather than repeating review's: a transactional outbox in the
monolith publishes the event reliably, and notification becomes the
system's first event-driven, Kafka-consuming service rather than another
REST leaf. Rising difficulty, not rising domain importance, is the ordering
principle.

**Inventory comes third** because order needs it synchronously at checkout
time, which review and notification did not require of anything, and because
inventory's data lives in the same shared schema the rest of the monolith
uses — the data-entanglement axis, not the coupling axis, is what makes
inventory harder than notification. Extracting it forces change-data-capture
to backfill a newly owned database before cutting writes over, which is the
first time this book's sequence has to solve a data-ownership problem rather
than only a code-boundary problem.

**Payment and shipping come fourth and fifth** because extracting either one
removes a piece of the monolith's single ACID checkout transaction, which is
the point at which the system can no longer get distributed-transaction
safety for free — payment forces a choreographed saga, shipping the same
problem solved by an orchestrated one, so the two extractions teach both
coordination styles back to back on one codebase rather than in the
abstract.

**Order, together with its GraphQL gateway, comes last** because it is, by
a wide margin, the highest-coupling, highest-risk context in the whole
system: the god `OrderService` that reaches directly into inventory's,
payment's, shipping's, and notification's services and repositories is the
single largest coupling and data-entanglement liability this monolith has,
and every one of the five extractions before it exists in part to remove one
of those direct dependencies before order itself has to move. By the time
order's turn comes, the strangler proxy, the anti-corruption layer pattern,
the equivalence gate, and the whole ADLC loop that produces each extraction
have all been exercised five times already — which is precisely why the
hardest, highest-value seam is saved for when the mechanism extracting it is
the most proven, not the newest.

## What's next

This chapter picked a strategy — incremental, strangler-fig re-architecture,
gated on behavioral equivalence — and the method for ordering the cuts
inside it, and showed both applied to this book's own system as worked
evidence rather than assertion. Two things still have to happen before any
of that method can run for real: the lifecycle that will actually *execute*
each step, and the system the method will be run against. **Part 2, "The AI
Development Lifecycle (ADLC),"** names that lifecycle in full, starting with
**Chapter 5, "From SDLC to ADLC."** **Part 3, "The Reference Monolith,"**
then builds the six-context system this chapter has been describing in the
abstract, including the deliberate smells each extraction above is designed
to cure, and the test suite that becomes the behavior-equivalence suite
every later chapter depends on. Once that system exists, **Part 4, "Finding
the Seams,"** returns to the assessment questions raised here — coupling,
cohesion, domain boundaries — with the full apparatus of domain-driven
design, event storming, and coupling theory, and shows the mechanics behind
the ordering this chapter only argued for in outline.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Its claims about this project's own
extraction order are checkable against `_plans/build-plan.md` (Section E,
the decomposition roadmap, and tension T1 in Section A.2) and
`examples/00-monolith/SMELLS.md` (smells #2 and #6, which name the coupling
this chapter's "order last, review first" argument depends on). The strategy
and strangler-fig mechanics named here are built out, with running code, in
Part 3 and Part 5.*
