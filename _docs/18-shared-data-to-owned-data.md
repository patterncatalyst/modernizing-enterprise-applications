---
title: "From Shared Data to Owned Data"
order: 18
part: "Data Across the Seam"
description: "The shared-data pattern and its limits; the cost of the shared schema; planning the split that the Inventory extraction will carry out."
---

Chapter 13 scored the monolith's five remaining smells on the Constantine
taxonomy and on afferent/efferent coupling, and it named shared-database
coupling (`SMELL[ch.18]`) as the single worst offender in the system — common
coupling and content coupling at once, invisible at the point of use because
the database enforces the dependency instead of the code declaring it. That
chapter earned the claim with a taxonomy and a ranking. It did not yet spend
the time the claim deserves on what the shared schema actually costs to run,
or on what a team gives up the day it finally splits one database into many.
This chapter does that work. There is no new code here and no new runnable
example — `examples/00-monolith/` is unchanged since Chapter 10, and
`examples/02-review-service/` and `examples/03-notification-service/` are
unchanged since Chapters 15 and 17. What this chapter adds is the argument
that turns Chapter 13's ranking into a plan: why this particular coupling is
the hardest one in the book to break, what database-per-service actually buys
and actually costs, the concrete moves that get a foreign key out of a shared
schema and into two owned ones, and the two services already sitting in this
repository that embody the two different stages of that journey — one still
deferring it, one that never had to make the mistake in the first place.
Chapter 19 runs the technique for real, against Inventory, with CDC doing the
heavy lifting this chapter only describes.

## Why shared-database coupling is the hardest coupling to break

Every other smell Chapter 9 catalogued lives in application code, which means
every other smell has an owner who can see it, reason about it, and change it
inside one pull request. The god `OrderService`'s four outbound dependencies
are sitting in one constructor, visible to anyone who opens the file. The
missing anti-corruption layer is a `findBySkuOrThrow` call returning a raw
entity instead of a DTO — one method, one fix, reviewable in a diff. The
shared schema is different in kind, not just degree, because its coupling
does not live in a class anyone owns. It lives in `V1__init_schema.sql`, a
migration that runs once, and in nine `REFERENCES` clauses (eight of them
crossing a bounded-context seam) that Postgres itself enforces for as long
as the tables exist:

```sql
-- V1__init_schema.sql
CREATE TABLE orders (
    ...
    customer_id      BIGINT       NOT NULL REFERENCES customers (id),
    ...
);

CREATE TABLE order_items (
    ...
    order_id           BIGINT  NOT NULL REFERENCES orders (id),
    inventory_item_id  BIGINT  NOT NULL REFERENCES inventory_items (id),
    ...
);

CREATE TABLE payments (
    ...
    order_id     BIGINT      NOT NULL REFERENCES orders (id),
    ...
);
```

`payments`, `shipments`, and `notifications` each hold a foreign key straight
into `orders`; `order_items` holds one into `inventory_items`; `reviews`
holds two, into `customers` and `inventory_items`. `Customer` itself is the
clearest case of all — its own class-level javadoc calls it a "shared-kernel
entity. Not one of the monolith's bounded contexts itself," referenced
directly by `order` via JPA and, at the database level only, by
`notifications` and `reviews` as well. Six tables, five bounded contexts, one
schema, and not one line of Java anywhere that says "I depend on inventory's
table shape" or "I depend on customer's column list." The database says it,
silently, on every row.

That silence is the entire reason this coupling outranks the others. A
foreign key is not a negotiated contract between two teams who both read and
approved it — it is a constraint one migration author wrote once, which every
future writer to either table must now honor forever, whether or not they
know it exists. Nothing fails at the point where the coupling is introduced.
Something fails later, somewhere else, usually owned by a different person:
inventory drops a column it believes nothing uses, and a query order wrote
two years ago, against a join nobody remembers authorizing, breaks in
production. Every other smell this book catalogues announces itself in a
code review. This one waits for a migration.

Kleppmann's framing in *Designing Data-Intensive Applications* gives this
exact failure mode its proper name: a foreign key is a form of referential
integrity the database enforces for you, and the price of that convenience is
that every writer to the referenced table is now implicitly coupled to every
reader that depends on the reference holding. Inside one database, that price
is close to free — Postgres checks the constraint on every write and refuses
to let it break, so the coupling and its enforcement are the same mechanism.
The moment a system is split across two databases, the enforcement stays
behind in the old schema while the coupling itself does not disappear — it
just becomes invisible to the one mechanism that used to catch it. That is
Chapter 13's distributed-monolith trap restated in data terms: distance goes
up, the dependency the foreign key was quietly managing does not go away, and
now nothing is checking it at write time at all.

## The database-per-service principle: what you buy, what you give up

The destination this chapter is aiming at — one owned database per bounded
context, no table any other context can query directly — is usually sold on
its benefits alone: independent deployability, because a schema migration no
longer requires coordinating with every other context that happens to query
the same tables; independent scaling, because `inventory_items` under a flash
sale's read load does not have to compete with `orders`' write load on the
same connection pool; and independent technology choice, because a context
whose access pattern is fundamentally a key-value lookup is no longer forced
onto the same relational engine a context doing multi-row transactional
writes needs. Those benefits are real, and the decomposition roadmap in
`build-plan.md` Section E is built to win them one context at a time.

What that same sales pitch tends to leave out is the full size of what gets
given up to get there, and naming it plainly is this chapter's job before
Chapter 19 goes and pays the cost for real.

**Cross-table joins become API calls or replicated read models.**
`OrderItem`'s `@ManyToOne` into `InventoryItem` today is a single SQL join,
executed inside the same transaction, consistent by construction because
there is only one database to be consistent with. The instant inventory owns
its own database, that join has exactly two replacements, and both are
qualitatively worse in the same way: a network call, which can fail,
time out, or return a stale answer the instant after it returns; or a
locally-held copy of inventory's data, which is only ever as fresh as the
last event that updated it. There is no third option that recovers the
original join's properties — a single, atomic, always-current read across two
contexts' data — because that property was never really free. It was a cost
the shared schema had already paid, upfront, by coupling every writer to
every reader. Splitting the database does not introduce a new cost; it stops
hiding an old one.

**Referential integrity across services is gone, by design, not by
oversight.** Nothing will stop a payment service, once it owns its own
database, from holding a `order_id` that points at an order which the order
service has since deleted, archived, or never actually created. Inside one
schema, Postgres's `REFERENCES orders (id)` clause makes that state
unreachable — the write simply fails. Across two schemas, that guarantee is
gone, and it is gone permanently, not until someone remembers to rebuild it.
What takes its place is a weaker, eventually-consistent promise: the owning
context publishes the facts that matter (an order existed, with this id, at
this moment), and every other context that cares either consumes that fact
once and keeps its own copy, or calls back to ask, accepting that the answer
might be stale by the time it arrives. Chapter 22's ACID→ACD argument is this
exact trade, generalized from referential integrity to transactions as a
whole: the database no longer gives you certain states cannot exist, and the
system has to be designed, deliberately, to tolerate the states it no longer
prevents.

**What is bought in exchange is autonomy** — the ability for one team to
change their schema, deploy their service, and scale their storage without a
cross-context migration meeting, a shared maintenance window, or a shared
on-call rotation standing in the way. That autonomy is the entire argument
for paying the first two costs, and it is only a good trade when the
business actually needs it: independent scaling because one context's load
profile is genuinely different from its neighbors', independent deployment
cadence because one team ships faster than the others, or independent
ownership because two different teams, not one, are responsible for the code.
Chapter 13 already made this point about services in general — "microservices
are not the goal" — and it applies with even more force to data, because a
split database is far harder to undo than a split deployable. Merging two
codebases back into one repository is an afternoon's work. Merging two
diverged schemas, each of which has accumulated its own migrations, its own
assumptions, and its own data drift, is not.

## From foreign key to snapshot: the denormalization move, concretely

The mechanical heart of every data decomposition in this book is one move,
repeated per relationship: replace a live join into another context's table
with either a locally-owned, point-in-time copy of exactly the fields this
context actually needs, or an explicit call across a published contract when
a point-in-time copy would be wrong. `OrderItem`'s own javadoc already names
the destination for the first kind of relationship, because it was written
with Chapter 19 in mind from the day the smell was planted:

```java
/**
 * SMELL[ch.18]: {@code OrderItem} holds a direct JPA {@code @ManyToOne} FK/join
 * onto {@code inventory.InventoryItem} — an order-context table referencing an
 * inventory-context table in the one shared schema. Once inventory owns its own
 * database (ch.19), this join is replaced by a denormalized copy of the fields the
 * order context actually needs (sku, name, price-at-time-of-order), kept current
 * via CDC.
 */
```

It is worth sitting with exactly what changes and, just as importantly, what
does not. `OrderItem` already stores `unitPriceCents` as its own column,
copied at order-placement time rather than read live off
`inventoryItem.getPriceCents()` on every access — that part of the design was
already correct, and it was correct before any service boundary existed,
because a customer's receipt has to show what they paid, not what the SKU
costs today. What is *not* yet denormalized is the SKU and the product name:
both are reached today by following the live `@ManyToOne` into the current
`inventory_items` row, which means an order placed last year silently
inherits whatever name and SKU that row holds *today* — if inventory renames
"Deluxe Gadget" to "Deluxe Gadget Pro" next quarter, every historical order's
display quietly relabels itself, with no record that anything changed. That
is a correctness bug hiding inside a convenience, and it was always a bug,
independent of any decomposition — the shared schema just made it invisible
by making "read the current row" the path of least resistance. The
illustrative shape the real Chapter 19 migration will build looks like this —
not yet in this repository, a sketch of the target, not a claim about
existing code:

```java
// Illustrative — the target shape ch.19 builds, not yet in examples/.
// order_items gains its own columns; the @ManyToOne into InventoryItem
// is removed once inventory owns its own database.
@Entity
@Table(name = "order_items")
public class OrderItem {
    // ...
    @Column(name = "inventory_item_sku", nullable = false)
    private String sku;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents; // already owned today — unchanged
}
```

```sql
-- Illustrative — order_items after the FK is removed
ALTER TABLE order_items DROP CONSTRAINT order_items_inventory_item_id_fkey;
ALTER TABLE order_items ADD COLUMN inventory_item_sku VARCHAR(64) NOT NULL;
ALTER TABLE order_items ADD COLUMN product_name_snapshot VARCHAR(255) NOT NULL;
```

Three fields replace one live join: `sku` and the product name become
point-in-time snapshots, written once at order-placement time exactly the way
`unitPriceCents` already is, and the live foreign key — along with the
guarantee that `inventory_items.id` must exist for the row to be valid —
disappears entirely. Nothing left in `order`'s schema can tell you what
inventory's *current* stock level, price, or name is; order no longer needs
to know, because it already captured the facts it actually cares about at the
one moment they mattered. For the facts order genuinely does need live — can
this SKU still be reserved, right now, at checkout time — the decomposition
roadmap's answer is a synchronous call across the seam (`quarkus-grpc` for
inventory, per `build-plan.md` Section E), not a stale local copy, because a
stock check is exactly the case where a snapshot would be actively wrong.
Both moves point at the same lesson from two directions: once a relationship
crosses an owned-data boundary, you have to decide, explicitly, whether the
consuming context needs the truth *as of this instant* (call across the seam)
or the truth *as of the moment that mattered to it* (keep a snapshot, fed by
an event or a backfill). A live FK quietly picked the first answer for every
relationship in the schema, whether or not it was the right one for that
relationship — which is exactly how the SKU-rename bug above got into the
code without anyone deciding it should be there.

## Data duplication as a deliberate choice, not a lapse

Every engineer trained on relational modeling learns to treat duplicated data
as a defect to be normalized away — the same fact stored in two places is the
textbook setup for the two places disagreeing. That instinct is correct
inside one database, where normalization is nearly free and a single
transaction can keep every copy of a fact consistent at the moment it
changes. It becomes actively wrong advice the moment two contexts own
separate databases, because the alternative to duplication is not
consistency — it's a live cross-service call on every read, paying a network
hop and a new failure mode for every join a single schema used to answer in
microseconds. Kleppmann's treatment of derived data in *Designing
Data-Intensive Applications* names the reframe directly: a cache, an index, a
materialized view, and a denormalized copy of another service's data are all
the same idea wearing different names — each one is a redundant copy of some
upstream fact, kept in a shape optimized for how *this* reader needs it,
whose correctness depends entirely on how reliably it is kept in sync with
its source of truth rather than on whether a copy exists at all. Once that
reframe lands, the question a data-decomposition plan actually has to answer
stops being "should this fact be duplicated?" — it always will be, the moment
two services both need it — and becomes "what keeps this copy accurate, and
how stale is it allowed to get before that staleness is a bug instead of a
tolerated property of the design?" Chapter 19's CDC backfill is this book's
answer for inventory's data: a mechanism that keeps a derived copy current by
tailing the source of truth's own write-ahead log, which is a stronger
freshness guarantee than an application remembering to publish an event on
every write, and the specific reason CDC earns a whole chapter rather than a
paragraph.

## Read models fed by events: the shape Chapter 21 completes

The outbox Chapter 17 built for notification is this same idea at its
smallest possible scale, and it is worth naming the connection explicitly
rather than leaving a reader to spot it alone. `OutboxEvent` is a durable,
ordered record of one fact — an order was placed — published at-least-once to
every consumer that cares, and `examples/03-notification-service`'s
`Notification` table is a read model built entirely from consuming that one
event stream, never from a query against order's own tables. That is CQRS's
read side in miniature: a materialized, purpose-built copy of just the fields
one consumer needs, kept current by consuming events rather than by querying
the source of truth live. Chapter 20 hardens the publishing half of this
pattern — ordering, deduplication, the dual-write trap an outbox closes.
Chapter 21 generalizes the consuming half into a full read/write split, where
the GraphQL gateway's read side is itself one more event-fed projection, no
different in kind from notification's table, just assembled from more
streams. Every piece of that eventual architecture is this chapter's same
single move — a live join replaced by an owned, event-fed copy — applied
again and again until the whole system is built from it.

## The lived contrast: one schema, one deferral, one clean start

This book does not have to argue the shared-schema cost in the abstract,
because two already-extracted services sit in this repository making the
opposite choice under two different sets of constraints, and the difference
between them is the clearest evidence available for everything above.

`examples/02-review-service` is still, as of this writing, in its Phase A
form (DRQ-029), and its own `README.md` states the deferral in plain terms:
it persists against "the SAME podman-stack Postgres the monolith uses...
reading/writing the existing `reviews` table plus minimal read-only
projections of the shared `customers`/`inventory_items` tables," and "owns
**no** schema of its own yet — true database decomposition is deferred to the
data-across-the-seam chapters (ch.18/19), not this step." `Customer.java`'s
own javadoc explains exactly why that deferral is safe rather than
embarrassing: dropping the `reviews` table from the shared schema today
"would break the very service r02/S10 just cut over to." The service boundary
and the data boundary are separable decisions, and Review's extraction
deliberately separated them — a scoping choice, made on purpose, revisited
here rather than quietly left unexplained.

`examples/03-notification-service` made the opposite choice from the day its
first migration ran, and its own `V1__create_notifications_table.sql`
explains why in terms this chapter has now built the vocabulary to
appreciate fully:

```sql
-- Why own schema, not shared: ch.15's Review extraction (Phase A) could stay
-- in the monolith's shared schema because Review only ever *reads* a
-- synchronously-consistent REST contract. Notification is event-driven
-- (ch.17 S5 consumes `order.placed` off Kafka asynchronously) — an
-- event-driven read model that shared the upstream aggregate's table would
-- have two writers racing on the same rows (the monolith's now-decommissioned
-- synchronous path, and this service's consumer) with no way to reconcile
-- them. Owning the table from the start avoids that split-brain entirely.
```

That is the two-writer problem a shared schema creates the instant a second,
independent write path exists — and notification could not defer owning its
data the way review did, because review only ever had one writer at a time
(first the monolith, then, after cutover, the review service), while
notification's asynchronous consumer and the monolith's write path would have
had to coexist, racing on the same rows, for the length of the cutover
window. Owning the schema from day one was not a nicety for notification; it
was the only design that avoided a split-brain by construction rather than
by hoping nobody raced it in practice. `Customer.java`'s own javadoc records
the aftermath precisely: the monolith's `notifications` table "is deliberately
kept in this shared schema, now write-only history that nothing in this
module reads or writes anymore," orphaned on purpose, with no runtime
dependency left on it at all — a cleaner ending than review's, because
notification never had a live reader left behind to protect.

Two services, two genuinely different starting constraints, two different
answers — and the contrast is the whole argument this chapter has been
building in prose, made concrete: own your data from day one when a second
writer is coming, and it is safe to defer only when you can state, plainly,
why deferring doesn't yet create one.

## Planning the split: what Chapter 19 actually has to do

Ranking a context's coupling, as Chapter 13 did, and naming the technique, as
this chapter has, still leaves one question unanswered: how does a schema
that has been live since `V1__init_schema.sql` actually get split without a
maintenance window the business cannot afford? Chapter 19 answers that
question for inventory specifically, but it is worth previewing the shape of
the answer here, because it reframes what "the split" even means. The naive
version — stop the monolith, export inventory's rows, stand up a new
database, import them, restart everything pointed at the new location — is a
big-bang cutover with exactly the failure profile this book's strangler-fig
discipline exists to avoid. The CDC-based version Chapter 19 actually runs
instead treats the split as a *backfill*: a connector tails the shared
schema's write-ahead log, replicating every change to `inventory_items` into
a brand-new, independently-owned database in near-real-time, while the
monolith keeps writing to the old table exactly as it does today. Only once
that replica has caught up and proven itself current does traffic actually
cut over — reads first, behind a flag, the same reversibility discipline
every extraction in this book has used since Review — and only after the cut
holds does the old table's write path get retired. The schema split happens
gradually, underneath a system that keeps running the whole time, which is
the same lesson the strangler fig taught about code, now applied to data.

One more question this chapter should not duck: what happens to the
behavior-equivalence suite once there are two databases instead of one? Every
extraction through Chapter 17 kept the suite's job simple, because
"equivalent" meant "the same answer comes back," checkable by one backend or
another, with both backends still reading from — or, worse, coincidentally
agreeing despite — the same underlying rows, which is precisely the false-
equivalence trap Chapter 7 and Chapter 17 both had to defeat. A decomposed
database raises the stakes on that same trap: once inventory's data genuinely
lives in two places, a green run has to prove the *new* database is the one
actually answering, not that it happens to agree with a shared table it
never touched. Chapter 19 extends the negative-check discipline Chapter 17
introduced — kill the dependency, force red, restore it, confirm green —
to the database split itself, and that extension, not a new testing
philosophy, is what makes the equivalence gate trustworthy against a
decomposed data tier rather than merely optimistic about it.

## What you learned

- Shared-database coupling is the hardest coupling in this book to break
  because it is enforced by the database, not declared in application code —
  a foreign key is a dependency nothing in `OrderService` or `InventoryService`
  states explicitly, which is exactly why it survives unnoticed until a
  migration on one side breaks a query on the other.
- Database-per-service trades a database's free referential integrity and
  free cross-table joins for independent deployability, scaling, and team
  ownership — cross-table joins become network calls or event-fed read
  models, and referential integrity across services stops existing by
  design, replaced by eventual consistency the system has to be built to
  tolerate.
- The core technique is a denormalization move repeated per relationship:
  replace a live FK with either a point-in-time snapshot of the fields this
  context actually needs (order's SKU/name/price, following the same
  discipline `unitPriceCents` already uses) or an explicit call across a
  published contract when the data genuinely has to be current, never a
  silent default to whichever behavior the shared schema happened to make
  easiest.
- Data duplication across owned schemas is a deliberate choice, not a lapse
  in normalization discipline — every cache, read model, and denormalized
  copy is the same idea, and the real design question is what keeps each
  copy's staleness bounded and acceptable, which is what CDC, outboxes, and
  CQRS read models each answer in their own way.
- `examples/02-review-service` (shared schema, deferred on purpose, safe
  because it has one writer at a time) and `examples/03-notification-service`
  (owned schema from day one, necessary because a second writer was coming)
  are the same decision made correctly under two different constraints — the
  clearest evidence in this repository for when deferring data ownership is
  defensible and when it isn't.

Chapter 19 picks this plan up and runs it for real against Inventory: a CDC
connector tailing the monolith's write-ahead log, a backfill into a database
inventory actually owns, a synchronous gRPC seam for the facts order needs
live, and an equivalence gate taught to prove the new database is the one
really answering. Chapter 20 then hardens the outbox pattern this chapter
leaned on for notification's example into a general treatment of ordering,
deduplication, and the dual-write trap, and Chapter 21 generalizes the
event-fed read model this chapter previewed into CQRS's full read/write
split — the same single move, replace a live join with an owned copy, run
once per remaining relationship in the schema until nothing is left.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`; `examples/00-monolith/`,
`examples/02-review-service/`, and `examples/03-notification-service/` are
all unchanged by this chapter. Every schema definition, entity, and javadoc
quoted above is drawn directly from those modules' current source and is
independently checkable with `grep -rn 'SMELL\[ch\.18\]' examples/00-monolith/src`
and by reading `examples/02-review-service/README.md`,
`examples/00-monolith/.../common/Customer.java`, and
`examples/03-notification-service/.../db/migration/V1__create_notifications_table.sql`
directly. The two illustrative code snippets in the denormalization-move
section are explicitly marked as sketches of Chapter 19's target shape, not
claims about existing code, and should not be treated as verified until
Chapter 19 actually builds and runs them. What remains unverified going
forward is the plan itself: that CDC backfill, the gRPC seam, and the
extended negative-check discipline this chapter previews actually hold up
against Inventory's real data and real traffic, which only Chapter 19's own
equivalence-gate run can confirm.*
