---
title: "Event Sourcing & CQRS"
order: 21
part: "Data Across the Seam"
description: "CQRS read/write split, load-bearing for the gateway read side; event sourcing covered but kept light and optional; the trade-offs of each."
---

Chapter 17 built a pipeline — an atomic outbox write, a polling relay, a
Kafka topic, an idempotent consumer — and Chapter 20 pushed deeper on that
same mechanism: what it costs to keep a dual-write from ever happening, how a
consumer stays correct under at-least-once redelivery, what ordering and
deduplication actually require once more than one event type flows down the
same topic. Both chapters answered "how do I get a fact out of one service
and onto a log reliably." This chapter asks the question on the other side of
that log: once a fact is out there, reliably, what do you *do* with it — and
when is building a whole second model around "reacting to facts" the right
call rather than a self-inflicted wound? That is the question CQRS and event
sourcing both answer, in two different and often-confused ways, and it is the
question this chapter answers honestly about its own codebase before it
answers it in the abstract: `examples/03-notification-service` is already,
right now, a CQRS read model. It was built that way in Chapter 17 without
either chapter using the term, and seeing why it qualifies — and exactly
where it stops short of full event sourcing — is the fastest way into both
patterns that exists in this book.

The code is in `examples/00-monolith/` and `examples/03-notification-service/`,
already built and already discussed in Chapter 17; this chapter adds no new
example of its own because its job is to name what that code already does,
not to build another extraction. Reread `examples/03-notification-service/`'s
run script if you want to drive it again while reading this chapter — its
`README.md` covers what it does and how.

## CQRS: one model for writing, maybe several for reading

Command Query Responsibility Segregation, the name Greg Young gave the
pattern, says something simpler than its name suggests: stop assuming that
the model you use to *change* data and the model you use to *read* data have
to be the same shape, stored the same way, scaled the same way, or kept
consistent on the same schedule. A conventional CRUD service collapses both
responsibilities into one: one table, one entity, one repository, serving
both an `INSERT` that enforces an invariant and a `SELECT` that renders a
dashboard. That collapse is free when the two responsibilities genuinely want
the same shape — which is most of the time, and is exactly why `OrderService`
in this book's monolith, and `review-service` after its extraction in
Chapter 15, are plain CRUD and are correct to be plain CRUD. CQRS earns its
complexity precisely in the cases where that assumption breaks: when the
write side needs to enforce an invariant across a small, normalized cluster
of fields (can this customer place this order, is there enough stock) while
the read side needs to answer a completely different question, shaped
differently, at a completely different scale (show me every order a customer
has ever placed, with shipping status and notification history denormalized
into one screen, served to ten times as many requests as checkout ever sees).
Force both needs through one model and you get one of two bad outcomes: a
write model polluted with read-optimized denormalization it doesn't need, or
a read path doing expensive joins across tables that were never designed to
answer that question quickly. CQRS's actual mechanism for avoiding both is
unglamorous: keep the authoritative write model exactly as normalized and
invariant-focused as it already should be, and build one or more **read
models** — purpose-shaped, independently stored, independently scaled views —
fed from the write side by the thing this book has already spent two
chapters building: published events.

The deck's own framing of the trade-off, which this book's pattern-coverage
matrix inherits almost unchanged, lists the benefits side first: loose
coupling between the service that writes and the service that reads, the
ability to scale each independently (notification's read traffic has nothing
to do with checkout's write traffic, and should never have to share a
connection pool with it), a schema genuinely optimized for its own query
shape instead of a compromise, and — the easiest benefit to undersell — a
query that finally matches a user's actual intent instead of reverse-engineering
intent from a general-purpose row. The considerations side is just as
direct: more moving parts (now there are two models instead of one, and a
pipeline connecting them), a read model that is, structurally, never
instantaneously up to date with the write side that fed it, and a form of
distributed data management that a single-table CRUD service never has to
think about at all. Nothing about this trade-off disappears because the
read model happens to be fed by Kafka instead of, say, a batch ETL job. CQRS
names an architectural decision about splitting models; event-driven
plumbing is one way — this book's way — of keeping the split models in sync,
not the thing being decided.

## `notification-service` is already a CQRS read model — and already honest about not being more than that

Go back to `NotificationService#recordOrderPlaced`, quoted in full in
Chapter 17, and read it again with "is this CQRS" as the question instead of
"is this idempotent." The answer is yes on every count that matters. The
write side of this system — `order.OrderService#placeOrder`, in the monolith,
the same code Chapter 9 tagged and Chapter 17 modified only enough to add the
outbox write — is one model: an `Order` aggregate, backed by ordinary
relational rows, whose entire job is to decide whether a checkout is valid
and to enforce that decision transactionally. `examples/03-notification-service`
is a second, independently stored, independently scaled model — its own
Postgres schema (`notification`, not the monolith's `public`), its own
Flyway history, its own `Notification` entity with plain `Long` columns
instead of the `@ManyToOne` relations a shared schema would make tempting —
built for exactly one query shape: "what has this customer been told, and
when." It does not enforce `Order`'s invariants. It cannot reject a checkout.
It has no opinion about stock, price, or payment status beyond whatever
`order.placed`'s payload happened to carry. It is read-optimized, data-owned,
and independently deployable, which is the whole CQRS argument made concrete
in one already-running service:

```java
// examples/03-notification-service/.../Notification.java
@Entity
@Table(name = "notifications", schema = "notification")
public class Notification {
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;
    // channel, message, sentAt — shaped for "what did we tell this
    // customer," nothing else. No FK into the monolith's orders table;
    // populated once, from the event payload, not looked up live.
}
```

Now name precisely what this *isn't*, because the distinction is the entire
point of this chapter and it is easy to blur. `notification-service` does
not replay `order.placed` from the beginning of time to compute its current
state every time it needs an answer, and it is not the authoritative record
of whether an order was placed at all — the monolith's `orders` table still
is, and always will be, for as long as this architecture stands. What
`recordOrderPlaced` does is a single, idempotent, check-then-insert reaction
to one event, producing one row that *represents* a fact the write side
already decided. That is a **materialized view**, Kleppmann's term for it in
*Designing Data-Intensive Applications* and the right term for it here: a
cache of a derived answer, kept current by incrementally folding new facts
into it, rebuildable from the source facts if it were ever lost, but not
itself the source of truth for anything. A CQRS read model, in other words —
built the honest, minimal way this book commits to, and not, on its own, an
event-sourced aggregate. The gap between those two things is worth making
precise, because it is exactly the gap this chapter spends its second half
explaining why this book declines to close.

## Event sourcing: when the log stops being a side effect and becomes the record

Event sourcing takes a stronger position than CQRS does, and the two are
easy to conflate because they are so often deployed together. CQRS says
"separate your read model from your write model." Event sourcing says
something about the *write* model specifically: don't store current state as
mutable rows at all — store the append-only sequence of events that produced
that state, and treat *that sequence* as the one and only system of record.
Current state, under event sourcing, is not a thing you persist; it is a
thing you *compute*, on demand or on a cadence, by replaying every event an
aggregate has ever emitted, in order, from the beginning (or from the last
snapshot, which exists purely to bound how far back a replay has to reach).
An event-sourced `Order` would have no `orders` row with an `OrderStatus`
column that gets updated in place. It would have an append-only stream —
`OrderPlaced`, `PaymentCaptured`, `ShipmentDispatched`, `OrderConfirmed`, the
exact timeline Chapter 12's event-storming wall already drew — and
`Order`'s current status would be *the fold of that stream*, something like
the following sketch, offered here purely as illustration, since this
codebase never actually implements it:

```java
// Illustrative only — no such class exists in this codebase, by design.
// An event-sourced Order's "current state" is a fold over its own history,
// not a stored field.
OrderState current = eventStore.loadEvents(orderId).stream()
        .reduce(OrderState.empty(), OrderState::apply, (a, b) -> b);
```

Once you see state expressed that way, Kleppmann's framing of the whole
derived-data landscape in *DDIA* snaps into focus, and it is the framing this
chapter borrows most directly: a log is a totally ordered, append-only
sequence of facts, and *every* materialized view, index, cache, and
read-optimized table in a system is a deterministic function of that log,
applied once and kept incrementally up to date thereafter. CDC — Chapter 19's
mechanism, reading a database's write-ahead log and turning its row changes
into a stream of events after the fact — and the outbox — Chapter 17 and
20's mechanism, writing an explicit event row transactionally alongside a
conventional update — are both ways of manufacturing a log out of a system
that was not designed around having one as its primary structure. Event
sourcing is the other direction entirely: design the system around the log
*first*, and treat the conventional, queryable table as the thing that gets
derived from it, not the other way around. Kleppmann calls this general move
"turning the database inside out" — the database you query becomes a cache of
the log, rather than the log (when one exists at all, as an audit trail or a
CDC byproduct) being an afterthought bolted onto a database that was always
the real source of truth. Event sourcing and CQRS are frequently paired for
a structural reason that follows directly from this: an append-only event
log is a genuinely poor structure to query directly — "all orders over $500
placed by this customer last month" against a raw stream of
`OrderPlaced`/`PaymentCaptured`/... events means scanning and refolding the
entire relevant history on every question — so an event-sourced write side
almost always needs at least one CQRS read model projected off of it just to
be usable at all. The reverse implication does not hold, and this is the
exact point this book's own choice turns on: CQRS does not require event
sourcing. You can build a read model fed by events while the write side
behind those events stays exactly what it has always been — a conventional,
mutable, relationally-enforced aggregate — which is precisely what
`OrderService` and `notification-service` do today, and precisely why
`notification-service` is a CQRS read model and not a corner of an
event-sourced system.

## Why this book builds CQRS-lite and not event sourcing — stated as a decision, not a shortcut

This book's own pattern-coverage ledger states the choice plainly: event
sourcing is demoted to light and optional while CQRS is load-bearing,
because the gateway chapter genuinely needs a read/write split and nothing
in this house migration needs a fully event-sourced aggregate to prove its
point. It is worth spelling out *why* that is the right call rather than a
concession, because event sourcing's cost is easy to underestimate from a
diagram and easy to feel only after you've paid it. An event-sourced
aggregate commits you to **schema evolution of every event type, forever**:
`OrderPlaced` v1 is part of your system's permanent history the moment the
first order is placed, and every future code change that wants to read an
order's history has to be able to deserialize and correctly interpret every
version of every event that has ever been emitted, including the ones
written by code that no longer exists — a discipline usually called
*upcasting*, and one that adds real, ongoing engineering cost with no
equivalent in a mutable-row system, where an `ALTER TABLE` and a migration
script retire the old shape outright. It commits you to **bounding replay
cost** deliberately, via periodic snapshots, because an aggregate with
years of history and no snapshot strategy gets slower to load with every
event it has ever emitted, a failure mode that is invisible on day one and
only shows up once the log is long enough to matter. It commits you to
**tooling and team familiarity that most shops don't have on day one** — ad
hoc SQL against an `orders` table is something every engineer on a team can
do without training; ad hoc questions against an event store generally
cannot be answered without purpose-built read models, which means every new
question a product manager asks might mean standing up a new projection
before it can be answered at all. None of these costs are hypothetical, and
none of them are free just because a framework makes the event store easy to
wire up. They are the reason Chapter 3's discipline — *microservices are not
themselves the goal*, each pattern earns its place against the smell it
cures, not the elegance it offers — applies doubly here: event sourcing is
not a complexity you reach for because a chapter title promises you will
learn it eventually; it is a complexity you reach for because you have a
specific, articulable need (a true audit trail that must reconstruct any
historical state exactly, or a domain where "what happened and in what
order" genuinely is the business question, like a ledger or a trading
system) that a mutable table with change events cannot satisfy as cleanly.
This project's house migration never develops that need. Every read-model
consumer this book builds needs "what is the current state, derived from
recent facts" — `notification-service` needs "has this customer been told,"
and Chapter 26's GraphQL gateway will need "what does this order currently
look like across five services' worth of data" — and none of them need
"replay this aggregate's entire history to reconstruct what it looked like
at any arbitrary point in time," which is the specific question event
sourcing exists to answer well. Building the heavier pattern to answer a
question nobody in this migration is actually asking would be exactly the
unjustified complexity Chapter 3 warns against, dressed up as thoroughness.

What this book builds instead deserves its own name, because "CQRS" alone
undersells how modest the actual mechanism is: **CQRS-lite**, or
event-fed read models. The write side stays exactly what it would be without
any of this — a conventional aggregate, backed by conventional rows, with no
event-sourcing machinery anywhere near it. The only new piece is the outbox
and its downstream consumers, which exist purely to let other services react
to what the write side already decided, asynchronously, without those
services querying the write side's database directly (the shared-schema
mistake Chapter 18 names at length) and without forcing the write side to
know or care who is listening. `notification-service`'s own `README.md`
states the reasoning for owning its schema in almost these words: an
event-driven read model that shared the upstream aggregate's table would
have two independent writers racing on the same rows with no protocol to
reconcile them, so owning the projection's storage is what makes the "lite"
in CQRS-lite safe rather than merely convenient.

## Eventual consistency, rebuild-from-events, and what a reader should actually verify

Every CQRS read model inherits the same honest cost, and Chapter 17 already
taught you the proof technique for it, which generalizes to every read model
this book builds from here on, not just notification's: a read model is, by
construction, never instantaneously current with the write side that feeds
it. The order exists, committed, in the monolith's database the instant
`placeOrder` returns; the outbox relay has up to its poll interval before it
even attempts to publish; the consumer has its own processing latency on top
of that. That window is not a bug to be engineered away — closing it
completely would mean giving the read model synchronous knowledge of every
write, which is precisely the coupling CQRS exists to avoid — it is a
property to be *bounded, stated, and tested*. The bounded-wait poll plus
negative-check discipline Chapter 17's behavior-equivalence suite uses
against `notification-service` — retry on a miss, up to a stated budget,
and separately prove the suite is watching the real pipeline by killing the
consumer and confirming the assertion goes red rather than merely timing
out softly — is not a notification-specific trick. It is the general shape
any test of any CQRS read model has to take, because "did the write
eventually show up in the read model" is a fundamentally different claim
from "did the write show up," and a suite that doesn't know the difference
will either flake under load or, worse, pass for the wrong reason.

The other property worth naming plainly is **rebuild-from-events**, because
it is both a real strength of this architecture and a real limitation that
is easy to overstate. Because `notification-service`'s table is *derived*
data — a fold over `order.placed`, not an independent source of truth — it
can, in principle, be dropped and rebuilt by replaying the topic: reset the
consumer group's offset to the earliest retained record, and
`OrderPlacedConsumer` will idempotently re-derive the same rows it derived
the first time, exactly the same guarantee that makes redelivery safe in the
first place working in your favor instead of against it. That property is
genuinely valuable — it is the thing that makes a read model's own
persistence disposable rather than precious, a very different risk profile
from a table that is itself the only copy of a fact. But it holds only as
far as the log actually retains the history needed, and this is the honest
edge of CQRS-lite as opposed to true event sourcing: Kafka's retention
window is finite and configured, not infinite by design the way a dedicated
event store's log is. Replaying `order.placed` rebuilds `notification-service`
cleanly only for orders placed within whatever retention policy this
system's topics are configured with; past that window, the log itself no
longer has the fact to replay, and the only reason this doesn't strand the
system is that the *write side* — the monolith's `orders` table today, the
order-service's rows after Chapter 26 — remains authoritative and
independently durable regardless of what Kafka still retains. A fully
event-sourced system does not get to lean on that fallback, because for an
event-sourced aggregate the log *is* the only copy of the fact; this book's
read models get to be more relaxed about retention precisely because they
are not the system of record for anything, only a convenience derived from
it.

## Forward to the gateway, and to the consistency chapter that names what this one assumed

Chapter 26 is where this chapter's load-bearing half gets built for real.
The order extraction — the last and hardest in this book, the one Chapter 12's
event-storming wall already marked as unable to move until every command it
issues has somewhere else to land — separates a command side (an `Order`
aggregate enforcing exactly the invariants it enforces today, extracted but
not re-architected around events) from a query side: a GraphQL gateway that
aggregates read models projected from order, inventory, shipping, and
notification into one queryable graph, so that a client asking "what does my
order look like right now" is served from purpose-built, independently
scaled projections instead of forcing the order-service's write path to
reach synchronously into four other services on every request. That gateway
is this chapter's CQRS argument scaled from one read model to several, and
everything this chapter named — the pipeline that feeds a read model, the
schema-ownership discipline that keeps it from racing its source, the
bounded-wait-plus-negative-check proof that it is genuinely current rather
than coincidentally current — carries forward unchanged.

Chapter 22 names, directly and without euphemism, the consistency guarantee
every read model in this chapter has been quietly spending since the moment
the monolith's single `@Transactional` stopped being able to cover more than
one table at a time: dirty reads, lost updates, non-repeatable reads, the
isolation ACID used to give you for free and that an eventually-consistent
read model, by its nature, cannot. This chapter showed you what it looks
like to live with that trade on purpose, in one small, already-running
service; Chapter 22 is where the bill gets itemized.

## What you learned

- **CQRS separates a write model from one or more read models** because
  commands and queries often have genuinely different shapes, scaling needs,
  and consistency requirements — not because splitting a model is
  inherently better than keeping one.
- **`notification-service` is already a CQRS read model**: its own schema,
  built by idempotently folding `order.placed` events into purpose-shaped
  rows, is a materialized view of a fact the write side already decided —
  not a second source of truth, and not an event-sourced aggregate.
- **Event sourcing is a stronger, different claim**: the event log itself is
  the system of record, and current state is computed by replay rather than
  stored directly. It is almost always paired with CQRS (a raw event log is
  a poor thing to query), but CQRS never requires it — this book's write
  side stays conventional throughout.
- **This book deliberately builds CQRS-lite — event-fed read models, not
  full event sourcing** — because nothing in this migration needs permanent
  replay-from-genesis state reconstruction, and because event sourcing's
  real costs (event schema evolution forever, snapshot strategy, replay
  cost, team and tooling unfamiliarity) are exactly the kind of
  unjustified complexity Chapter 3's "microservices are not themselves the
  goal" discipline warns against when adopted without a specific need.
- **A CQRS read model is eventually consistent and, because it is derived
  data, rebuildable from its source events** — but only as far as the
  event log's own retention reaches; past that window, the write side's own
  durable storage, not the log, is what keeps the system honest.

Chapter 22 names exactly what consistency this architecture has given up to
get here. Chapter 26 is where CQRS stops being demonstrated on one small
service and becomes the load-bearing shape of this book's last and hardest
extraction.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every code excerpt and behavior described above is quoted or derived from
code that already exists and already runs in this repository —
`examples/00-monolith/`'s `OrderService#placeOrder` and outbox machinery,
and `examples/03-notification-service/`'s `Notification` entity,
`OrderPlacedConsumer`, and `NotificationService#recordOrderPlaced`, all
discussed and exercised in Chapter 17. This chapter introduces no new code
of its own; its claims are a reframing of already-verified behavior under
the CQRS and event-sourcing vocabulary, not a new runtime claim. The one
illustrative snippet in this chapter — the `eventStore.loadEvents(...)` fold
sketch — is explicitly not implemented anywhere in this codebase and is
offered only to make the write-model difference between CQRS-lite and full
event sourcing concrete; a reader should not expect to find an event store
anywhere in this repository, by design. What remains forward-looking and
unverified is Chapter 26's claim that the GraphQL gateway can in fact
aggregate order, inventory, shipping, and notification read models behind
one queryable graph without forcing synchronous cross-service calls on the
write path — that claim is this chapter's prediction, not yet this book's
demonstrated fact, until Chapter 26's own extraction and equivalence-gate
run land.*
