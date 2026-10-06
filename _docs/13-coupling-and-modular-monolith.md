---
title: "Coupling, the Modular Monolith & When Not to Split"
order: 13
part: "Finding the Seams"
description: "Khononov's three dimensions of coupling; the modular monolith and decomposed-DB monolith as legitimate destinations; the distributed-monolith trap."
---

Chapter 11 gave you bounded contexts and aggregates as the *conceptual*
vocabulary for a seam, and Chapter 12 gave you event storming as a *process*
for discovering where those seams sit in a system that never shipped with a
map. Both chapters answer the question "where could this system be cut?" by
pointing at behavior — the verbs on the sticky notes, the domain language
that changes meaning as you cross a boundary. Neither chapter answers a
second, equally important question: given several legitimate candidate
seams, which one do you cut *first*, and — the question this book's own
`build-plan.md` had to answer before a single extraction shipped — is
cutting any of them, right now, actually the right call? This chapter
supplies the metric that answers both: **coupling**, measured precisely
enough to rank candidates rather than merely describe them, and precisely
enough to reveal when the correct answer is not to cut at all. The five
deliberate smells Chapter 9 catalogued in `examples/00-monolith/` are, every
one of them, a coupling problem wearing a different costume — a shared
schema, a god service, a synchronous call, a leaky entity, a transaction
that spans contexts it shouldn't. This chapter gives you the vocabulary to
see that they are the same problem, scored on the same scale, and the scale
is what turned Section E of `build-plan.md` into a defensible sequence
instead of an argument settled by whoever spoke last in the planning
meeting.

There is no new code in this chapter — `examples/00-monolith/` is unchanged
from Chapter 10, and the extraction order it motivates is realized starting
in Chapter 15. What this chapter adds is the measurement that makes the plan
*checkable*: given the real dependency graph of a real codebase, you can
compute which context has the smallest blast radius, and that computation,
not intuition, is why review went first and order went last.

## Coupling and cohesion as the structural property, not a vibe

Larry Constantine's original formulation, from the structured-design
literature this book's whole vocabulary descends from, is a single sentence
worth keeping in exactly its plain form: **a structure is stable if
cohesion is high and coupling is low.** Cohesion measures how tightly the
*elements inside one module* belong together — do they share a single,
nameable purpose, or did they end up in the same file because that was
convenient the day someone wrote them. Coupling measures the opposite
direction: how much one module's internals leak into, or depend on, another
module's internals. The two are not independent axes you can optimize in
isolation — in practice they trade against each other, because the usual
way to raise cohesion inside a module is to pull in everything that module
needs to do its one job, which simultaneously *reduces* that module's
coupling to its neighbors, since it no longer needs to reach outside itself
to get the job done. `OrderService` is the demonstration case run
backwards: its cohesion is low — "places an order by checking stock,
persisting the order, charging payment, dispatching shipment, and sending a
notification" needs five verbs and four "and"s to describe accurately, which
Chapter 9 already pointed out as the plain-English tell for a god service —
and its coupling is correspondingly high, because a class with no single
purpose has no natural boundary keeping it from reaching into everyone
else's business.

What makes coupling the right word to build an entire decomposition
strategy around, rather than just one more code-smell synonym, is that it
is *measurable* in a way cohesion mostly isn't. You can count the edges in a
dependency graph. You cannot, in the same mechanical way, count how
"cohesive" a class feels — cohesion stays a judgment call applied to one
module at a time, while coupling is a relationship between two or more
modules that a static analysis tool, or a disciplined `grep`, can actually
enumerate. That asymmetry is why this chapter spends most of its time on
coupling: it is the dimension you can turn into a ranking, and a ranking is
what an extraction *order* requires. Conway's observation sits underneath
all of this as the organizational mirror of the same idea — "organizations
which design systems are constrained to produce designs which are copies of
the communication structures of these organizations" — which is why a
coupling edge in the code is so often also, quietly, a coordination
requirement between two teams. A foreign key between `order_items` and
`inventory_items` is not just a database constraint; it is a standing
requirement that whoever owns inventory's schema coordinate with whoever
owns order's queries before touching it, whether or not an org chart ever
wrote that requirement down.

## The Constantine coupling taxonomy, applied to the monolith

Constantine and Yourdon's original structured-design work ranked module
coupling on a ladder from worst to best, and the ladder is worth walking in
full because every rung has a live example sitting in
`examples/00-monolith/` today — this is not a taxonomy imported from a
textbook and left abstract, it is a taxonomy that scores the actual code
the previous four chapters built.

**Content coupling** is the worst rung: one module reaches inside another
and directly modifies or relies on its internal data, bypassing whatever
interface that module exposes. The monolith's closest approach to content
coupling is `OrderService#placeOrder` receiving a live, JPA-managed
`InventoryItem` entity straight from `InventoryService#findBySkuOrThrow` and
reading its internal fields (`inventoryItem.getPriceCents()`,
`inventoryItem.getSku()`) rather than a contract type built for external
consumption — Chapter 9's Smell 5. It falls just short of the textbook
definition of content coupling only because Java's access modifiers still
gate direct field access; everything else about the relationship —
depending on the producer's exact internal representation, with no
translation layer absorbing change — is content coupling's spirit if not
its letter, which is exactly why an anti-corruption layer (Chapter 16) is
the cure rather than a milder fix.

**Common coupling** is two or more modules sharing the same global or
widely-visible data store, where any of them can change state the others
depend on without the others knowing it happened. The monolith's single
Postgres schema is common coupling at the data-tier scale: `order`,
`inventory`, `payment`, `shipping`, `notification`, and the `customers`
shared kernel all read and write tables in one schema that nothing
enforces ownership boundaries on, so a migration that `inventory` runs for
its own reasons can silently break a query `order` wrote against the same
table. This is Smell 1, and it is the smell this chapter treats as the
single worst offender in the monolith — the section after this one explains
why.

**Control coupling** is one module passing another a flag or parameter
whose *value* dictates the receiving module's internal control flow — the
caller has to know something about the callee's internal branching logic to
use it correctly. The monolith's checkout flow brushes against this in a
narrower form: `OrderCreate.paymentMethod()` is threaded from the REST
controller all the way into `PaymentService#charge`, where its value
selects a code path (approve vs. decline) that the order-placement flow's
own success or failure then depends on — the caller doesn't just pass data,
it passes a value whose meaning only makes sense in terms of payment's
internal decision logic.

**Stamp coupling** is passing a whole composite data structure across a
boundary when the receiver only needs part of it — the receiver becomes
implicitly dependent on the structure's full shape, including fields it
never touches, so any change to that shape is a potential breaking change
even to callers using a fraction of it. `OrderItem`'s constructor taking a
full `InventoryItem` entity, when all it actually needs is three of that
entity's fields (sku, name, price-at-time-of-order — the exact fields
Chapter 9's Smell 1 annotation names as what a denormalized copy should
carry), is stamp coupling layered on top of Smell 5's leaked-entity problem:
even once Chapter 16 fixes the "raw entity" half of that smell, passing the
whole `StockDto` instead of the three fields order actually needs would
still be stamp coupling, just a milder version of it.

**Data coupling** is the best rung reachable while still passing anything
at all: modules communicate only through simple, well-defined parameters —
primitives or small, purpose-built value types with no incidental fields
riding along. `OrderService.listAll()` returning `List<OrderDto>` built by
`toDto`, rather than returning the `Order` JPA entities directly, is the
monolith's own data coupling, already done right, sitting a few lines away
from Smell 5's violation of exactly the same principle at a different seam.
The contrast is instructive on its own: the codebase already demonstrates
both ends of the same ladder, in the same class, because `toDto` was
written for the REST controller's benefit while `findBySkuOrThrow` was
written for a Java caller's convenience, and nobody applied the same
discipline to both call sites.

Two of Constantine's rungs — **message coupling** (interaction only through
message passing, no shared state at all) and well-defined API/contract
coupling — don't have a live example in the monolith for the obvious
reason: they're what the *target* architecture looks like once Chapters 14
through 26 are done, which is exactly the point of walking the ladder now.
You cannot evaluate how far the extracted services have moved without
knowing the full span of the ladder you started on.

## The two worst offenders, scored

Two of the five Chapter 9 smells dominate every other consideration in this
book's own extraction plan, and it's worth being explicit about *why* those
two specifically, using the taxonomy just built rather than gut feeling.

**Shared-database coupling** (`SMELL[ch.18]`) is content-and-common coupling
at the same time, which is what makes it the worst single offender in the
system. It is common coupling because every context reads and writes
tables in one shared Postgres schema with no enforced ownership boundary —
`order_items` joins `inventory_items`, `payments` and `shipments` and
`notifications` each hold a direct foreign key back into `orders`, and
`order`/`notification` both join `customers` (`order/OrderItem.java`,
`payment/Payment.java`, `shipping/Shipment.java`,
`notification/Notification.java`, and `common/Customer.java` all carry the
`SMELL[ch.18]` tag on exactly this relationship). And it edges toward
content coupling because a foreign key is not a negotiated contract — it is
the database enforcing a promise ("this row cannot exist without that row")
that neither context's own code declared and neither context's own code can
unilaterally relax. The reason this smell outranks the others in sheer
structural damage is that it is invisible at the point of use: nothing in
`OrderService`'s source code says "I depend on inventory's schema," the
database says it, silently, which means the coupling only announces itself
when someone tries to break it — a migration three deploys later, owned by
a different team, that drops a column inventory no longer needs and breaks
a query `order` never knew it was leaning on. Measured against Khononov's
balancing-coupling model (Appendix E), this is the textbook maximal-strength
case: intrusive coupling, at zero distance today, on data that changes
constantly — every one of the three dimensions reads high, and the smell
only stays survivable because distance is currently zero. The instant any
one context gets its own database, distance jumps to its maximum while
strength stays exactly where it was, which is the distributed-monolith
trap arriving on schedule unless something changes strength *before*
distance changes.

**God-service orchestration coupling** (`SMELL[ch.26]`) is a different
failure mode on the same scale: it is primarily *control* coupling stacked
across four separate relationships at once, because `OrderService` doesn't
just call `inventoryService`, `paymentService`, `shippingService`, and
`notificationService` — it calls them as a synchronous, in-process sequence
where the entire checkout operation's control flow is dictated by whether
each call throws, and a caller needing to know all four callees' internal
exception behavior (`InsufficientStockException`,
`PaymentDeclinedException`) to use any one of them correctly is control
coupling by definition, multiplied by four. It also layers in instability
in the Robert Martin sense that Appendix E's afferent/efferent table names
directly: a class with four outbound dependencies on other contexts'
services has an efferent coupling (`Ce`) of four and, being a leaf of the
checkout flow rather than something other contexts call into, an afferent
coupling (`Ca`) near zero — an instability score `I = Ce/(Ca+Ce)` close to
1, meaning `OrderService` depends on everything and nothing depends back on
it, which is exactly backwards from where you want your most
business-critical orchestration logic to sit on the stability spectrum.
Where shared-database coupling is dangerous because it's invisible, god-
service coupling is dangerous because it's a single point with maximum
fan-out: every one of the four services it reaches into has to stay
behavior-compatible with `OrderService`'s expectations forever, or checkout
breaks, which is why Chapter 9 already named it as the reason order has to
be extracted *last* — not as a narrative convenience, but because each of
the other five extractions literally cannot complete until it has removed
one of `OrderService`'s four edges, shrinking its efferent coupling by one
each time until there's finally little enough left to cut.

## Afferent and efferent coupling: the metric that produces a ranking

The taxonomy above tells you *what kind* of coupling each smell embodies.
It does not, by itself, tell you *which context to cut first* — for that
you need a number, not a category, and the number this book's planning used
is the same one Appendix E introduces formally: **afferent coupling (Ca)**,
how many other components depend on a given component, and **efferent
coupling (Ce)**, how many other components a given component depends on.
Both are mechanically countable from an import graph or a dependency
analysis, which is what makes them useful for sequencing rather than merely
descriptive — you can compute them today, on the actual codebase, without
waiting for an architect's opinion.

The rule that falls out of these two numbers is: **extract low-Ca,
low-Ce contexts first, and save high-Ca, high-Ce contexts for last** —
because the number of dependents you have to migrate alongside you (Ca) and
the number of dependencies you have to sever or bridge before you can leave
(Ce) are both, directly, the size of the extraction's blast radius. A
context nobody calls into and that calls into nothing else can be lifted
out whole, in an afternoon, with nothing left behind that notices. A
context everyone calls into, that itself calls into everyone, cannot move
until both directions of that traffic have been rerouted — which is exactly
describing the god service from the opposite direction.

Walk the six contexts with this lens and the extraction order in
`build-plan.md` Section E stops looking like a narrative choice and starts
looking like a sorted list:

- **Review** has `Ca ≈ 0` (nothing in the monolith calls into review) and
  `Ce ≈ 0` (review has no runtime dependency on order, inventory, payment,
  shipping, or notification — Chapter 9's sixth, already-cured smell names
  this explicitly: "Review had no runtime dependency on
  order/inventory/payment/shipping/notification"). That is the *lowest*
  possible coupling score a context in this system can have, and it is
  exactly why it was extracted first, in Chapter 15, and why that extraction
  is deliberately anticlimactic rather than impressive: a context with
  nothing calling in and nothing calling out has no blast radius to manage,
  so the extraction exercises the *mechanism* — the strangler proxy, the
  equivalence gate, the flag-gated cutover — without any coupling risk
  competing for attention.
- **Notification** has low `Ca` (only `OrderService` calls into it) and low
  `Ce` (it reaches into nothing else), but its one inbound edge is the
  synchronous, in-transaction call that is Smell 4 — so its extraction
  (Chapter 17) is slightly harder than review's not because of fan-out, but
  because that single edge currently sits inside `OrderService`'s ACID
  transaction and has to be replaced by an outbox before the edge can be
  safely cut, which Chapter 16's anti-corruption layer and content-based
  routing set up first.
- **Inventory** has moderate `Ca` (called by `OrderService`, read at
  checkout time, and joined by `OrderItem`'s foreign key — Smell 1's data
  coupling *and* Smell 5's leaked-entity coupling both land on this one
  context) and essentially zero `Ce` of its own. Its extraction (Chapter 19)
  is harder than notification's because severing the *data*-level coupling
  requires a mechanism notification never needed — CDC backfill — before
  the owning database can split.
- **Payment** and **shipping** each have moderate `Ca` (one inbound edge
  from `OrderService`; today nothing calls payment from shipping, but
  shipping will gain that outbound dependency once payment is extracted),
  and both are read and written inside the same spanning transaction,
  Smell 3, so splitting either one forces the
  question Chapter 22 answers of what replaces the free rollback they
  currently get from sharing a database with the rest of checkout. Their
  extractions (Chapters 23 and 24) are harder again because severing this
  edge means building a saga, not just an outbox or a CDC pipeline — the
  compensating-action machinery neither review, notification, nor inventory
  needed.
- **Order** has the highest `Ca` of any context (it is the thing every other
  context's entities point back to via foreign key — `Payment`, `Shipment`,
  and `Notification` all carry a direct `order_id` join) and by far the
  highest `Ce` (`OrderService`'s constructor alone injects four other
  contexts' services). That is the single worst coupling score on both axes
  simultaneously, which is exactly why it is extracted last, in Chapter 26,
  and exactly why Chapter 9 already told you the other five extractions
  each have to remove one of this context's outbound edges before order's
  own extraction becomes tractable at all. An extraction order that put
  order first would have had to solve every other context's severance
  problem *at the same time*, with no smaller extraction's lessons to carry
  forward — which is precisely the "biggest cut first" failure mode a
  blast-radius ranking exists to prevent.

That is the whole method: rank contexts by `Ca` and `Ce`,
cut the lowest-scoring context first, and let each completed extraction
reduce the score of whatever remains until the hardest context is also the
smallest remaining problem. It is the same discipline Part 1 argued for in
the abstract — risk distributed across many small, reversible steps instead
of concentrated into one large one — made concrete as an actual number you
can compute from the import graph sitting in front of you.

## The modular monolith: most of the benefit, none of the distribution tax

Everything so far has described coupling as a problem to reduce by
splitting a deployable into more deployables. That is not, on its own,
license to split everything — and Khononov's balancing-coupling model
(Appendix E) already supplied the reason why not, as a single
formula: **distance is a cost
multiplier, and splitting strong, volatile coupling across a network adds
distance without removing strength**, which produces a *distributed*
monolith — the same tangle as before, now paying for network calls,
partial failures, and multi-team deploy coordination on top of coupling
that was never actually addressed. The uncomfortable fact underneath that
formula is that most of coupling's actual damage — invisible cross-context
writes, god objects with unbounded fan-out, leaked internal types — is a
property of **module boundaries**, not of **process boundaries**, and
module boundaries can be enforced inside a single deployable just as
rigorously as across a network, for a fraction of the operational cost.

This is what makes the **modular monolith** a legitimate destination in its
own right, not merely a waypoint on the way to microservices. A modular
monolith is one deployable — one build, one JVM, one release train, exactly
like the monolith this book built in Chapter 8 — with the three
disciplines a distributed system gets automatically, enforced by tooling
and convention instead:

- **Package or build-module isolation**, where the compiler (or the build
  tool's module graph — Java's module system, or simply a convention
  enforced by a linter like ArchUnit) refuses to compile code in one
  context's package that imports a class from another context's internal
  package. This turns Smell 5's leaked `InventoryItem` entity from a
  runtime discovery into a *build failure*: the moment `order` tries to
  import `inventory.InventoryItem` instead of `inventory.StockDto`, the
  build stops, which is strictly earlier and cheaper feedback than
  discovering the coupling during an incident three deploys later.
- **No cross-module entity references**, meaning the JPA `@ManyToOne`/`@JoinColumn`
  pattern this chapter has spent its whole walkthrough pointing at —
  `OrderItem` → `InventoryItem`, `Payment`/`Shipment`/`Notification` →
  `Order`, everything → `Customer` — is simply disallowed across a module
  boundary, even though every module still lives in the same schema and the
  same database connection pool. Each module is only permitted to persist
  its own entities and to reference other modules' data by a plain
  identifier (a `Long customerId`, not a `Customer customer`) plus a
  published, in-process contract method to resolve it — the same
  discipline Chapter 16's anti-corruption layer teaches for a *distributed*
  seam, enforced here for a boundary that hasn't crossed a network yet.
- **Module-owned schemas**, a concept worth naming at this depth level and
  deferring the mechanics of (ch.18/19 build the actual migration): each
  module gets its own schema namespace inside the same Postgres instance —
  `order.orders`, `inventory.inventory_items` — so a foreign key *physically
  cannot* cross module boundaries even by accident, while the operational
  cost of a second database, a second connection pool, and a second set of
  credentials to rotate is deferred until a real decomposition need
  justifies paying it. This is "own your data" practiced at the lightest
  weight the idea supports — the full shared-data-to-owned-data story,
  including replication and CDC, is Chapter 18's and 19's subject, not
  this one's; this chapter's job is only to name decomposed-database
  monoliths as the midpoint between "one undifferentiated schema" and "one
  database per service."

The pedagogical payoff of doing all three inside a monolith first, before
distributing anything, is that it isolates two problems that a premature
microservices migration conflates. "Are these module boundaries actually
correct — do they track real bounded contexts, or did we guess wrong about
where the seams are?" is a question you can answer and *cheaply fix* inside
a single deployable, where moving a class between packages is a refactor,
not a cross-team, cross-database migration. "Is this coupling now low
enough, and this context's operational profile different enough from its
neighbors', to justify the cost of a second deployable, a second database,
a second on-call rotation, and a network between them?" is an entirely
separate question, and it should only be asked — per this book's own
Chapter 3 argument that microservices are not themselves the goal — once
the first question already has a confident answer. A modular monolith lets
you get that confident answer, and most of coupling's actual damage fixed,
without placing a single bet on distribution you might later have to
reverse at much higher cost than renaming a package.

None of this contradicts this book's own strangler-fig sequence.
This book's own reference monolith is not taken to the modular-
monolith stage as an intermediate step before Chapter 15 — its extraction
sequence goes straight from "one undifferentiated shared schema" to "owned
service with its own database," because the book's pedagogical goal is
demonstrating the full strangler-to-microservices journey end to end on a
system with a measured before/after. A reader applying this chapter to
their *own* system, however, should read the modular-monolith option as a
live, frequently-correct destination: if your organization's actual
problem is lead time and change-failure rate inside a single team's
release train, and not independent scaling or independent team ownership
across multiple teams, enforcing module boundaries, banning cross-module
entity references, and splitting schemas by module can retire most of
Chapter 9's five smells' *damage* without ever standing up a second
deployable, a message broker, or a service mesh. Distribute only where the
business case — a named, measured need for independent scaling, independent
deployment cadence, or independent team ownership — actually justifies the
operational tax a network boundary imposes. That is Chapter 3's argument
("microservices are not the goal") made concrete at the exact decision
point where a team chooses how far to carry a refactor.

## The distributed-monolith trap, named precisely

It is worth closing the loop on the failure mode this chapter has gestured
at twice already, because it is the single most common outcome of getting
this chapter's lesson backwards: a team sees coupling, concludes
"microservices fix coupling," and distributes a system whose coupling they
never actually reduced. The result has every operational cost of a
distributed system — network latency, partial failure, independent
deploys that still have to be coordinated because nothing was actually
decoupled, distributed tracing standing in for what used to be a stack
trace — and none of a monolith's redeeming simplicity, because the calls
that used to be in-process method invocations are now network calls making
exactly the same synchronous, tightly-coupled demands of each other that
they always did. Khononov's formula names the mechanism precisely:
distance went up, strength did not go down, and volatility on the core
domain was already high — all three dimensions landed high at once, which
is the model's own definition of maximum coupling pain. A hypothetical
version of this monolith that split into six services *without* first
addressing Smell 1 (the shared schema) and Smell 2 (the god service) is the
distributed monolith this chapter warns against by name: six deployables,
each reaching across the network into tables and services it does not own,
paying every operational cost of distribution while keeping every coupling
problem Chapter 9 catalogued — which is exactly why this book's actual
extraction sequence does not do that. Each chapter from 15 onward only cuts
a context once the specific coupling standing in its way has an answer —
an ACL, an outbox, a CDC pipeline, a saga — and the equivalence gate exists
precisely to prove that answer actually held, rather than discovering in
production that it didn't.

## Part 4, consolidated: the extraction plan

Part 4 has now supplied three lenses, in this order, and here is how they
combine into the one plan the rest of the
book runs. Chapter 11's domain-driven design gave you *where the
conceptual boundaries are* — the bounded contexts, the aggregates, the
rule that protocols stay out of the domain core so a later extraction is
mechanical rather than a rewrite. Chapter 12's event storming gave you *how
to find those boundaries from behavior* when no one has drawn them for you
— watching where domain events cluster, where one team's vocabulary stops
making sense to the next, producing the decomposition backlog as a
reconnaissance artifact rather than an architect's guess. This chapter has
given you *the order to cut them in* — coupling, scored precisely enough by
the Constantine taxonomy and the Ca/Ce metric to rank six already-identified
candidate boundaries from cheapest to most expensive to extract, and
precise enough to also say when the right answer for a given
boundary is a modular monolith rather than a network call.

Put the three together and Part 4's output is a single artifact, not three
separate ones: a list of bounded contexts (Chapter 11), each annotated with
the behavioral evidence that the boundary is real (Chapter 12), each scored
for coupling and ranked by blast radius (this chapter), which is exactly
what `build-plan.md` Section E already is — review first because its score
is the floor, order last because its score is the ceiling, with
notification, inventory, payment, and shipping sorted in between by exactly
how much coupling each one's extraction has to sever. Every chapter from
here forward executes one row of that ranked list. **Part 5, "The Strangler
Fig in Practice,"** opens immediately after this chapter with the mechanism
that makes a ranked list executable one row at a time without ever
requiring a big-bang cutover — Fowler's strangler fig pattern, the proxy
that lets old and new code serve traffic side by side, and the first
extraction, review, run end to end exactly where this chapter's coupling
score said it belonged: first, because there was nothing in its way.

## What you learned

- Coupling and cohesion are Constantine's structural pair — "a structure is
  stable if cohesion is high and coupling is low" — and coupling is the
  dimension this book builds a sequencing strategy on because it is
  measurable, while cohesion stays a per-module judgment call.
- The Constantine taxonomy (content → common → control → stamp → data,
  worst to best) scores every one of Chapter 9's five remaining smells on
  the same scale: Smell 5 reads as content coupling, Smell 1 as common
  coupling, the checkout flow's payment-method threading as control
  coupling, the raw-entity-to-`OrderItem` handoff as stamp coupling on top
  of its leaked-entity problem, and `toDto`'s DTO-returning methods as the
  data coupling the rest of the codebase should match.
- Shared-database coupling (Smell 1) and god-service orchestration coupling
  (Smell 2) are the two worst offenders because they combine multiple rungs
  of the ladder at once — common-plus-content for the schema, control
  coupling multiplied across four outbound edges for the service — and
  Khononov's strength/distance/volatility model explains precisely why
  distributing either one without first weakening it produces a
  distributed monolith rather than a fix.
- Afferent coupling (Ca, who depends on you) and efferent coupling (Ce,
  what you depend on) turn "which seam first?" into a number: review's
  near-zero Ca and Ce put it first in Chapter 15; order's maximal Ca and Ce
  — every other context's foreign key points at it, and its own service
  injects four others — put it last in Chapter 26, with the four middle
  extractions sorted by how much coupling each one's cut has to sever.
- The modular monolith — package/build-module isolation, no cross-module
  entity references, module-owned schemas — captures most of coupling's
  fix inside a single deployable, at a fraction of a network boundary's
  operational cost, and distributing further is justified only by a named
  business case for independent scaling, deployment cadence, or team
  ownership, per Chapter 3's "microservices are not the goal."

Part 5, "The Strangler Fig in Practice," begins immediately after this
chapter with the mechanism — Fowler's strangler fig, the proxy, and the
feature-flagged cutover — that turns this ranked extraction plan into six
real, individually reversible migrations, starting with the context this
chapter's own scoring already named as the obvious first cut.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`; `examples/00-monolith/` is unchanged
from Chapter 10. Every coupling score and code excerpt above is drawn
directly from that module's current source and from `SMELLS.md`'s mapping
of each smell to its curing chapter, both independently checkable with
`grep -rn 'SMELL\[ch\.' examples/00-monolith/src`. What remains unverified
is forward-looking: that the Ca/Ce ranking this chapter derives for the six
contexts actually predicts each extraction's real difficulty once Chapters
15 through 26 run it, which can only be confirmed as each chapter's
equivalence-gate result lands.*
