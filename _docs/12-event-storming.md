---
title: "Event Storming the Monolith"
order: 12
part: "Finding the Seams"
description: "Discovering seams from behavior, not schema; the decomposition backlog this produces, run as an ADLC reconnaissance step."
---

Chapter 11 gave you the vocabulary — subdomain, bounded context, aggregate,
ubiquitous language — and the promise that goes with it: if you can name the
boundaries correctly, extraction becomes mechanical instead of archaeological.
What it didn't give you is a *method* for finding those boundaries in a system
that never wrote them down. This chapter is that method. Event storming is a
facilitation technique for discovering bounded contexts and aggregates by
narrating what a system actually *does* — the facts it produces, in the order
it produces them — rather than by reading its schema, its class diagram, or
its package structure. Applied to `examples/00-monolith/`, it turns one
paragraph of prose ("a customer places an order, we check stock, charge the
card, ship the box, and tell them") into a timeline of domain events whose gaps
are exactly the seams the next twenty chapters cut along.

The code is in `examples/00-monolith/`; nothing new has been scaffolded for
this chapter. Every event name below either already exists as a reserved
constant in `common/Topics.java` or is the natural companion this chapter adds
to that set, and every call this chapter storms is quoted verbatim from
`order/OrderService.java#placeOrder` — the same method Chapter 9 tagged with
three of its five remaining smells.

{% include excalidraw.html file="event-storm-checkout" alt="Two stacked readings of the checkout flow: the top band shows today's OrderService#placeOrder as one synchronous call chain inside a single @Transactional across inventory, payment, shipping, and notification; the bottom band shows the same business process re-read with the event-storming grammar — an actor and command producing an Order aggregate, a timeline of domain events (OrderPlaced, StockReserved, PaymentCaptured or PaymentDeclined, ShipmentDispatched, OrderConfirmed, NotificationSent) separated by policies and reactions, an external payment gateway, and an Order Status read model fed by the events." caption="Figure 12.1 — The checkout flow, read two ways: one call chain today, a timeline of domain events once it's event-stormed" %}

## Why behavior beats schema for finding seams

Every monolith accumulates a second, unofficial map of itself: the one
implied by its tables, its foreign keys, and its service dependency graph.
That map is seductive because it's concrete — you can query it, diagram it,
even generate it automatically — and it is also, reliably, the wrong map to
use when you're deciding where a system should be cut. `V1__init_schema.sql`
tells you that `order_items` has a foreign key into `inventory_items`. It does
not tell you *why* that relationship exists, whether it reflects something
the business actually cares about or just the cheapest way one engineer found
to express "these rows are related" in 2019, or whether it is one of this
book's five remaining deliberate smells rather than a real invariant. Schema
is implementation residue. It encodes decisions, but it doesn't encode the
decisions' reasons, and reasons are exactly what you need to tell a
critical boundary from an accidental one.

Event storming, a facilitation technique introduced by Alberto Brandolini,
inverts the question. Instead of "what tables exist and how are they joined,"
it asks "what *happened*, in what order, and who or what made it happen."
A session starts with nothing but a long roll of paper (or, in an ADLC
context, a shared document) and a pile of orange sticky notes, and the only
rule for the first pass is: write down a domain event — a fact, named in the
past tense, that someone in the business would recognize and care about —
every time one occurs in the story you're narrating. `OrderPlaced`. `StockReserved`.
`PaymentDeclined`. Not `createOrder()`, not `UPDATE orders SET status = ...` —
the business fact those operations are trying to record. That single
discipline — past tense, business-recognizable, one note per fact — is what
makes the technique fast: a room full of domain experts and engineers can
timeline an entire checkout flow in twenty minutes, because naming what
happened requires no familiarity with how the code currently implements it.
You do not need to have read `OrderService.java` to know that a customer's
order gets placed, their card gets charged, and their package gets shipped.
You need to have *used* the system, or sold it, or supported it.

That is the property this chapter leans on hardest: event storming surfaces
seams from the *outside in*. A bounded-context boundary, properly found,
is a place where the vocabulary changes — where "reserve" stops meaning
"decrement a quantity column" and starts meaning "authorize a charge," or
where "confirm" stops being about an order's status and starts being about a
message reaching a customer's inbox. Those vocabulary shifts are visible in
the timeline of events almost immediately, because each shift is usually
where one orange sticky hands off to a different kind of actor, a different
external system, or a different part of the business that would staff a
different team if this were drawn as an org chart instead of a sequence
diagram. The schema, by contrast, actively hides those shifts: a shared
Postgres instance makes every context's tables look like equally valid
neighbors, FK joins and all, which is precisely Smell 1 from Chapter 9. Event
storming doesn't care what database a fact is persisted to. It only cares
whether the fact happened and what happened next.

This is also why event storming is the right reconnaissance tool for a book
built around an agentic development lifecycle rather than a liability unique
to in-person workshops. The ADLC's Map phase — introduced in Chapter 5,
exercised end to end in Chapter 7 — is exactly the kind of investigative pass
a storm produces: before an agent generates a single line of extraction code,
something has to establish what the system actually does, in what order, with
what failure modes, independent of how the current implementation happens to
be wired. A human-facilitated workshop with sticky notes on a wall and an
agent narrating a known code path onto a structured timeline are the same
technique at two different scales — the artifact either way is a sequence of
named facts, and that artifact is what Plan consumes next. Nothing about this
chapter asks you to adopt new tooling to get that artifact; the timeline this
chapter builds is written in prose and a diagram, the same way the rest of
this book records decisions.

## The grammar of a session

A real event-storming wall accumulates more than orange stickies, and the
order in which colors get added is deliberate — each new color answers a
question the previous pass left open.

**Domain events (orange)** come first, and only first. The initial pass is
*chaotic exploration*: write down every fact you can think of, out of order,
duplicated, contradicted — the goal is coverage, not correctness. Once the
events for a process exist, the group lines them up into a timeline, which
is itself the first real modeling act: disagreements about ordering are where
hidden assumptions surface, often the same assumptions a single ACID
transaction has been quietly enforcing without anyone noticing.

**Commands (blue)** come next, one per event: the imperative that triggered
the fact. `PlaceOrder` triggers `OrderPlaced`. `CapturePayment` triggers
`PaymentCaptured` or `PaymentDeclined` — note that one command can produce
either of two events, a branch that is invisible in the orange-only pass and
immediately obvious once you ask "what triggered this?" for every sticky on
the wall.

**Actors** — conventionally a small yellow figure, not a box — sit to the
left of each command: who or what issued it. A human customer issues
`PlaceOrder`. A scheduled job, a webhook from a payment gateway, or a policy
reacting to an earlier event can issue a command just as validly as a person
clicking a button, and naming the actor is what tells you later which seam
needs an authenticated human-facing API and which needs a service account on
an internal topic.

**Aggregates (yellow, large)** come from grouping: for each command/event
pair, which cluster of data had to exist, enforce an invariant, and decide
whether the command was even valid? `Order` is the aggregate that decides
whether `PlaceOrder` is well-formed (does this customer exist, are the line
items non-empty); `InventoryItem` is the aggregate that decides whether
`ReserveStock` can succeed (is there enough quantity on hand). An aggregate
boundary found this way is a *behavioral* claim — "this cluster of data is
where this invariant is checked" — which lines up with, but is independently
verifiable from, the tactical-DDD aggregate boundaries Chapter 11 named.

**Policies and reactions (lilac)** are the "whenever/then" rules that chain
events to the next command automatically, with no human actor in the loop:
*whenever* `OrderPlaced`, *then* issue `ReserveStock`; *whenever*
`StockReserved`, *then* issue `CapturePayment`. A policy sticky is the exact
shape a message consumer takes once the system is distributed — it is, not
coincidentally, also the exact shape of one step in a choreographed saga,
which is why this chapter's wall and Chapter 23's saga are the same diagram
read at two points in the book.

**Read models (green)** are the views built by projecting events forward for
some later question to be answered cheaply — "what's the status of order
#4412" needs an `Order Status` read model that several of this flow's events
feed, not a live join across five tables each time someone asks. **External
systems (pink)** are the boxes outside the team's control that a policy has
to call through: a card network, a carrier's dispatch API. Naming them
explicitly is what stops a modeling session from quietly assuming every
collaborator is as reliable and as fast as an in-process method call — which
is exactly the assumption `OrderService#placeOrder` makes today, for all
four of its collaborators, with no exception.

A session runs at three altitudes, and conflating them is the most common way
a storm produces a useless wall. **Big-picture** storming surveys an entire
business — weeks of activity across every process a company runs — to find
candidate bounded contexts and spot where two groups use the same word to
mean different things; it stays at the orange-only, no-detail level
because its job is breadth, not depth. **Process-level** storming
picks one process — checkout, in this chapter — and works it end to end with
the full grammar: events, commands, actors, policies, external systems, read
models. This is the altitude that produces a decomposition backlog, and
process-level storming is this chapter's focus. **Design-level** storming goes
one layer deeper still, inside a single aggregate, to work out the exact
commands, validation rules, and events needed to implement it — this is where
a storm hands off directly to the tactical DDD vocabulary Chapter 11 already
gave you, and where the aggregate's own API gets drafted before a line of
extraction code exists.

## Storming checkout: the process-level pass

Run the process-level pass on checkout and the actor/command opening is the
easiest sticky on the wall: a **Customer** issues **Place Order**, carrying a
customer id, a shipping address, and a list of line items — this is the
payload `OrderCreate` already carries in `order/OrderService.java#placeOrder`.
The **Order** aggregate receives that command and, if it's well-formed, the
first domain event goes up: **OrderPlaced**. This is not a sticky this book
has to invent a name for — it is already sitting, unused, as a public
constant:

```java
// common/Topics.java
public static final String ORDER_PLACED = "order.placed";
```

That line is the clearest evidence in this
codebase that the event-storming questions this chapter is asking were
already being asked by whoever wrote `Topics.java`, well before this chapter
existed — the javadoc on that class says so directly: the names are reserved
"for the future event-driven extractions," kept aligned with the sibling
target services' Kafka topics, with zero messaging infrastructure wired up
yet. An event-storming wall and a reserved-topic-names file are the same
artifact at two different levels of formality.

The next policy on the wall is *whenever OrderPlaced, then reserve stock* —
and here storming proves its value by catching something a straight code read
can miss. In `placeOrder`, the inventory check-and-reserve loop runs *before*
the order is even persisted:

```java
// order/OrderService.java#placeOrder
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
order = orderRepository.save(order);
```

Read literally, the technical execution order is "reserve, then place." Read
as a business process, the conceptual order is "place, then reserve" — a
customer's intent to buy is the triggering fact, and stock reservation is
something that *happens because of* that intent, not something that has to
precede it in wall-clock time. `InsufficientStockException`'s own javadoc
half-admits this tension: it's raised "BEFORE any writes happen, so no
rollback is needed for this path," which is true and also a tell that the
current code is using synchronous validation as a stand-in for what should be
an asynchronous reaction to a fact that already happened. This is exactly the
kind of gap a storm is built to surface and a code read alone tends to paper
over, because code reads naturally follow call-stack order, and call-stack
order is an implementation detail, not a business one. The event the wall
needs next is **StockReserved** — a fact this system doesn't currently name
anywhere, because today "stock got reserved" is indistinguishable from "a
method returned without throwing."

**StockReserved** feeds the next policy: *whenever StockReserved, then
capture payment*, issued to an **external system** — the card network sitting
outside this codebase's control, modeled on the wall as a pink box specifically
so the question "what if this is slow, or down, or ambiguous" gets asked out
loud instead of assumed away. The command produces one of two events, and the
branch is where `PaymentDeclinedException`'s own javadoc names the mechanism
this book is retiring:

```java
// common/exception/PaymentDeclinedException.java (javadoc)
// Because OrderService#placeOrder wraps inventory decrement + order
// persistence + payment + shipment + notification in ONE @Transactional
// (SMELL[ch.22]), raising this mid-method rolls back everything already
// written in this request — including the inventory decrement.
```

On the wall, **PaymentCaptured** and **PaymentDeclined** are two separate
orange stickies coming off one blue command, and the question the facilitator
asks at exactly this point — "when payment is declined, what undoes the
stock reservation?" — is the question this monolith currently answers with
"nothing has to; Postgres's rollback does it by default." That answer is
Smell 3 stated out loud, by the wall itself, before Chapter 22 ever names ACID
versus ACD. A storm doesn't just find seams; it finds the exact question
whose current answer — "the database handles it" — stops being true the
instant either side of the seam gets its own database, which is the premise
every remaining extraction in this book depends on.

Successfully captured payment triggers *dispatch shipment*, which
`ShippingService.dispatch` performs — its own doc comment already
flags the call as synchronous and in-transaction — producing
**ShipmentDispatched**, the third name already reserved in `Topics.java`
(`SHIPMENT_DISPATCHED = "shipment.dispatched"`). The final policy, *whenever
ShipmentDispatched, then notify customer*, is where this chapter's wall
converges exactly with Chapter 9's fourth smell. `NotificationService`'s own
javadoc states the cost: a confirmation send happening synchronously,
inside checkout's own transaction, means checkout latency is coupled to
however long "sending" takes, and a thrown exception there rolls the entire
order back over a concern the customer's confirmed order doesn't actually
depend on. On the wall, this policy produces two events worth keeping
separate even though the current code conflates them into one method call:
**OrderConfirmed** — the business fact that checkout succeeded, independent
of whether the customer has been told yet — and **NotificationSent**, the
narrower fact that a message left the system. (Today's code actually calls
`order.confirm()` mid-flow, right after `paymentService.charge(...)` and
before `shippingService.dispatch(...)` — the wall's placement of
`OrderConfirmed` at the end of the timeline is a deliberate business-order
reading, checkout-succeeded-as-of-dispatch-and-notify, not a transcription
of the call stack.) Splitting them matters because
Chapter 17's extraction needs exactly this seam: checkout can commit
`OrderConfirmed` and move on, while `NotificationSent` becomes a fact an
entirely separate, asynchronous consumer produces on its own schedule. An
**Order Status** read model, the wall's one green sticky for this process,
is fed by `OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched`, and
`OrderConfirmed` — the projection a customer-facing "where's my order" screen
actually needs, built by folding events forward instead of joining five
contexts' live tables on every page load.

## From the wall to the backlog

A finished process-level wall is not decoration; read left to right, it *is*
the decomposition backlog, and the mapping from event boundary to extraction
chapter is close to direct. Every place on the timeline where one policy's
reaction currently crosses into another context's service as a plain Java
method call is a synchronous call today and a published event once that
boundary is cut — which is exactly the shape `common/Topics.java` was
already anticipating with zero infrastructure attached. The wall built in
this chapter maps onto the decomposition roadmap (`build-plan.md` Section E)
as follows: the `OrderPlaced` → `StockReserved` boundary is inventory's seam,
cut with CDC backfill and a decomposed database in Chapter 19; the
`StockReserved` → `PaymentCaptured`/`PaymentDeclined` boundary is payment's
seam, cut as a choreographed saga in Chapter 23, where the lilac policy
sticky *whenever StockReserved, then capture payment* becomes, almost
unchanged, a Kafka consumer reacting to `payment.captured`'s sibling topic;
the `PaymentCaptured` → `ShipmentDispatched` boundary is shipping's seam, cut
in Chapter 24 as an orchestrated saga using the Camel Saga EIP instead of
peer-to-peer events — the same wall, driven by one coordinating definition
rather than each service discovering the next event for itself; and the
`ShipmentDispatched` → `OrderConfirmed`/`NotificationSent` boundary is
notification's seam, cut first, in Chapter 17, as a transactional outbox
feeding an asynchronous consumer. Chapter 16's anti-corruption layer — the
missing translation step this chapter's wall silently assumes exists every
time it draws an arrow between two different-colored stickies — is what has
to exist at each of these boundaries before the event can safely carry
contract-shaped data instead of a raw JPA entity, which is Smell 5 from
Chapter 9 stated as a precondition rather than a complaint.

Notice what the wall does *not* tell you: it tells you *where* a boundary
exists, not *which order is safest to cut them in*. That ordering question —
given five seams, which one has the least coupling pulling against it, which
one's volatility and change-frequency make it most urgent, which one's
distance from the others bounds the blast radius of getting it wrong — is a
different kind of analysis, and it's the subject of the next chapter.
Chapter 13 takes this exact wall and applies coupling theory to it, which is
why event storming and coupling analysis are taught back to back in this
part: storming answers "where," coupling answers "in what sequence," and the
decomposition roadmap you've already seen cited throughout this book is the
product of running both passes, not just one. Every cut the roadmap makes
from Chapter 15 onward is also gated the same way: an equivalence gate runs
the behavior-equivalence suite against both the monolith and the newly
extracted service before that service's old code is decommissioned, which is
the same discipline Chapter 7 demonstrated on Review and Chapter 9 named as
this project's existence proof.

The god `OrderService` itself deserves one more look through the wall's lens,
because it explains something Chapter 9 stated as a roadmap fact without
fully justifying it: why order is extracted last. On the wall, `OrderService`
isn't one box — it's the aggregate that issues *every* policy's triggering
command in this flow: it is the thing that calls reserve, calls capture,
calls dispatch, calls notify, all directly, all synchronously, all inside one
transaction. A service occupying that many command-issuing positions on one
timeline cannot be extracted until each of those positions has already been
rewired to fire an event instead of a direct call — which is precisely what
Chapters 17, 19, 23, and 24 each do, one boundary at a time, before Chapter
26 ever touches `OrderService` itself. The wall doesn't just locate the god
service's smell; it enumerates, in order, the exact edges that have to be
cut before the hardest extraction in this book becomes possible at all.

## What you learned

- Event storming finds bounded-context seams by narrating what a system
  *does* — a timeline of past-tense domain events, each with a triggering
  command and actor, grouped into aggregates, chained by policies/reactions,
  projected into read models, and bounded by external systems — instead of
  inspecting its schema, which encodes implementation accidents rather than
  business meaning.
- A session runs at three altitudes — big-picture (breadth, across a whole
  business), process-level (depth, one process end to end, the backlog-producing
  altitude), and design-level (depth inside one aggregate, handing off to
  tactical DDD) — and conflating them produces a wall that's either too shallow
  to act on or too narrow to show the seams.
- Storming checkout surfaced six events this chapter needed —
  `OrderPlaced`, `StockReserved`, `PaymentCaptured`/`PaymentDeclined`,
  `ShipmentDispatched`, `OrderConfirmed`, `NotificationSent` — three of which
  (`OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched`) were already
  reserved, unused, in `common/Topics.java`, confirming the wall and the
  codebase were already asking the same questions.
- Each event boundary maps directly onto a later chapter's extraction seam —
  inventory (ch.19), payment's choreographed saga (ch.23), shipping's
  orchestrated saga (ch.24), notification's outbox (ch.17) — but the wall
  only answers *where* to cut; *in what order* is Chapter 13's question.

Chapter 13 takes this wall and asks the question it deliberately leaves open:
given five seams, which one is safest to cut first, measured by how strongly
coupled it is to everything around it — the modular-monolith and
decomposed-database-monolith alternatives included, for the seams that turn
out not to be worth cutting at all.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every code excerpt in this chapter is quoted verbatim from
`examples/00-monolith/` as it exists in the repository today, and the three
cross-referenced `Topics.java` constants (`ORDER_PLACED`, `PAYMENT_CAPTURED`,
`SHIPMENT_DISPATCHED`) are directly greppable. What remains unverified is
forward-looking: that the event-to-chapter mapping drawn here — inventory to
ch.19, payment to ch.23, shipping to ch.24, notification to ch.17 — holds up
unchanged once those chapters' extractions and equivalence-gate runs actually
land, and that Chapter 13's coupling analysis, applied to this same wall,
confirms rather than revises the seam locations this chapter identified.*
