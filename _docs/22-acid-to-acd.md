---
title: "ACID → ACD: Living Without Isolation"
order: 22
part: "Data Across the Seam"
description: "The consistency just given up — dirty reads, lost updates, non-repeatable reads — where it bites and how to bound it."
---

Chapter 21 gave the gateway's read side a model that tolerates staleness —
CQRS accepts that a read model can lag its write model by a bounded
window, because the alternative (reading and writing the same normalized
tables under one transaction manager) is the thing the gateway was built to
get away from — and it closed by naming this chapter directly as the one
that "itemizes the bill": dirty reads, lost updates, non-repeatable reads,
the isolation ACID used to give you automatically and that an eventually
consistent system, by its nature, cannot. This chapter pays that bill in
full, without euphemism, and asks the harder question underneath Chapter
21's acceptance: what, precisely, is being given up, and why is giving it up
not optional once a system crosses the seam from one process to several? The
monolith's checkout today answers a question every one of its six bounded
contexts can take for granted — "did this commit, all of it, or none of it"
— with a single word: **yes**, guaranteed by one `@Transactional` boundary and
one database's write-ahead log. Every chapter since Chapter 17 has been
quietly removing contexts from that guarantee's reach. This chapter names the
guarantee being removed, proves with the project's own evidence exactly how
much the monolith depends on it today, and states precisely what replaces it
once inventory, payment, and shipping are no longer sitting inside the same
process as the order they belong to. There is no new runnable example here —
this chapter grounds entirely in code and evidence Chapters 9, 10, and 17
already put in the repository — because the idea is conceptual and the proof
should come from a system the reader has already watched behave this way.

## SMELL #3: one transaction, four contexts, one rollback

`examples/00-monolith/SMELLS.md` catalogues the deliberate smell this chapter
is about in one line, and the line is worth reading exactly as written
before anything else: **"One in-process ACID transaction spanning
contexts"** — `OrderService#placeOrder` wraps the inventory decrement, order
persistence, payment capture, and shipment dispatch in a single
`@Transactional`, so any failure anywhere rolls back everything, everywhere.
Notification's synchronous call used to sit inside that same boundary too,
until Chapter 17 moved it out — the outbox write that replaced it is still
part of this transaction, because the outbox is order-owned infrastructure,
not a borrowed call into another context's service, but the call that *used*
to reach into notification's logic and block on it is gone. SMELL #3 is what
is left after that first cure: four bounded contexts — order, inventory,
payment, shipping — still committing or rolling back as one atomic unit,
exactly where this book's build plan always said the ACID→ACD argument would
be made (`build-plan.md` §E: "Choreographed **saga**; compensations;
ACID→ACD realized").

The method's own javadoc states the mechanism and its expiration date in the
same breath:

```java
// order/OrderService.java#placeOrder
/**
 * SMELL[ch.22]: one in-process ACID {@code @Transactional} spans FOUR bounded
 * contexts — order, inventory, payment, shipping (plus this context's own
 * outbox table write...). It "works" today because Postgres gives us atomic
 * rollback for free across all of them. The moment any one of these becomes
 * its own service with its own database, this rollback-everything behavior
 * disappears and has to be rebuilt explicitly as a saga with compensating
 * actions (ch.23 choreographed, ch.24 orchestrated) — that is the
 * ACID -> ACD story told in ch.22.
 */
@Transactional
public OrderDto placeOrder(OrderCreate command) {
```

Walk the method's actual body and the "four contexts, one commit" claim stops
being an abstraction and becomes four concrete calls inside one unbroken
stack frame: `inventoryService.reserve(...)` decrements stock before a single
byte of the order has been written; `orderRepository.save(order)` persists
the order itself; `paymentService.charge(order, order.getTotalCents(),
command.paymentMethod())` captures payment against a total that was only just
computed; `shippingService.dispatch(order, order.getShippingAddress())`
books a shipment against an order that, at this exact point in the method, is
not yet guaranteed to exist from any other transaction's point of view,
because this one hasn't committed. Every one of those four calls either all
happen or none of them do, and nothing in the method's own code expresses
that promise explicitly — it is bought entirely by the method boundary
Spring's `@Transactional` wraps around the whole thing, backed by one
Postgres connection's write-ahead log. That is the strength this chapter
names before explaining why it cannot travel: the
monolith's checkout author never had to write a single line of
failure-recovery code, because the database wrote it automatically, for every
possible combination of partial failure, the instant the `@Transactional`
annotation was added.

## The free rollback, proven — not asserted

A claim this consequential earns more than a code comment's say-so, and this
project does not ask you to take it on faith. `PaymentDeclinedException`'s
own javadoc states the dependency: raising this exception mid-method
"rolls back everything already written in this request — including the
inventory decrement," a sentence that is only true because of where the
exception is thrown relative to the transaction boundary, and the contrast
with its sibling exception makes that precision sharper. `InsufficientStockException`
is raised *before* any writes happen at all — a checkout that exceeds stock
on hand never touches the database, so no rollback is needed for that path
— while `PaymentDeclinedException` is raised *after* the inventory
reservation and the order row have both already been written inside this
transaction, which is exactly the scenario a rollback exists to undo.

The project proves this at two different tiers of evidence, and the gap
between them is the whole argument. At the unit tier,
`OrderServiceTest#placeOrder_paymentDeclined_throwsAfterInventoryReservedButBeforeShippingOrNotification`
mocks every collaborator and can only prove *orchestration order* — that
`inventoryService.reserve(...)` was called before `paymentService.charge(...)`
threw, and that `shippingService.dispatch(...)` was never reached after it
did. Its own in-code comment states the limit of what a mocked test
can show: "inventory WAS reserved (in-memory) before the decline; in the real
flow only the surrounding `@Transactional` rolls that back. This unit test
proves the orchestration order, not the rollback itself." A mock cannot roll
anything back; there is no database underneath it to undo.

The real proof — a real Postgres transaction, actually rolled back, observed
from outside the process — lives in the behavior-equivalence suite, in
`tooling/newman/mea.postman_collection.json`'s "Scenario 3 — Payment-Declined"
folder, and it earns its evidentiary weight by doing something the unit test
structurally cannot: capturing the *database's own state* before the
decline, sending a real HTTP request that hits a real `@Transactional`
method against a real Postgres instance, and then checking that state again
afterward.

```javascript
// mea.postman_collection.json, "Scenario 3 — Payment-Declined"
// 3a. capture stock before the declined checkout
pm.collectionVariables.set('widgetStockBeforeDecline', json.quantityOnHand);

// 3b. checkout with paymentMethod: "CARD-DECLINE" -> expect 402
pm.test('Declined payment returns 402 Payment Required', function () {
    pm.response.to.have.status(402);
});
pm.test('Documented error contract: error=PAYMENT_DECLINED', function () {
    pm.expect(json.error).to.eql('PAYMENT_DECLINED');
});

// 3c. re-read stock after the decline
var before = Number(pm.collectionVariables.get('widgetStockBeforeDecline'));
pm.test('Quantity on hand is unchanged — the reservation rolled back with the ' +
    'declined payment (same ACID transaction, SMELL[ch.22])', function () {
    pm.expect(json.quantityOnHand).to.eql(before);
});
```

Read those three steps as a single experiment rather than three independent
assertions: step 3a takes a snapshot, step 3b forces a mid-transaction
failure through the exact code path the javadoc above describes, and step 3c
re-reads the same row from the same database and finds it unchanged — not
"unchanged according to a mock that was never asked to change it," but
unchanged because Postgres actually undid the `UPDATE` that
`inventoryService.reserve(...)` issued, the instant `charge(...)` threw
further down the same call stack. This is the behavior-equivalence suite's
black-box counterpart to the unit test's white-box proof, and this project's
own testing chapter already draws the contrast directly: the same branch
proven once by calling Java directly with every collaborator mocked, and
once by sending a real request and reading nothing but the HTTP response and
a stock count back. Only the second proof is evidence that a real rollback
happened; the first is evidence that the code would *ask* for one.

## Why the free rollback cannot survive decomposition

Follow the paper trail this project already committed to, and the fate of
this transaction is not a matter of opinion: SMELLS.md's own row for SMELL #3
names its cure as ch.23 (choreographed saga) and ch.24 (orchestrated saga),
not "add transaction isolation across services" or "configure a distributed
transaction coordinator." That is a deliberate omission, and it is worth
spelling out why distributed transactions — the obvious-looking fix to a
reader meeting this problem for the first time — are not on this book's menu
at all.

The textbook mechanism for atomicity across multiple databases is the
**two-phase commit protocol**, and its two phases describe exactly what it
would take to give `placeOrder` the same all-or-nothing guarantee once
inventory, payment, and shipping each own a separate database behind a
separate service. In the **prepare** phase, a coordinator asks every
participant — the order database, the inventory database, the payment
database, the shipping database — to ready its local transaction and vote
whether it *can* commit, without yet committing; every participant that
votes yes must then hold its local locks and its prepared, uncommitted state
until the coordinator tells it what to do next. In the **commit** phase, the
coordinator collects every vote and, only if every single one was "yes,"
tells every participant to commit; if even one voted "no" (or never
answered), it tells every participant to abort instead.

Kleppmann's treatment of this protocol in *Designing Data-Intensive
Applications* names the exact three failure modes that make it unworkable at
the boundary this book is building across. First, the coordinator is a
single point of failure for the entire operation: if it crashes after
collecting votes but before broadcasting the commit decision, every
participant that voted yes is stuck holding its locks indefinitely,
unable to safely commit or abort on its own, because it does not
know what the other participants decided — this is 2PC's "in doubt" state,
and it can only be resolved by the coordinator recovering, which may take
an unbounded amount of time. Second, the protocol is synchronous and
blocking by construction: every participant's local locks are held across
*two* network round-trips to every other participant, for the entire
duration of the slowest one, which is precisely the latency and availability
cost a modernization project is trying to shed, not acquire, at the
service boundary. Third — and this is the one that makes it a non-starter
for independently owned databases specifically — 2PC requires every
participant to speak the same coordination protocol and trust the same
coordinator, which quietly re-centralizes the very thing decomposition was
meant to distribute: operational ownership. A payment service a different
team owns, versions, and deploys on its own schedule cannot be asked to hold
open database locks on an order it has never heard of, waiting on a
coordinator it does not operate, for as long as it takes a shipping service
three hops away to finish preparing its own, unrelated write. The monolith's
free rollback worked because one process held one connection to one
database for the whole operation; a distributed transaction tries to
recreate that illusion across process and ownership boundaries where it was
never physically true to begin with, and the cost of maintaining the
illusion — held locks, a fragile coordinator, and reduced availability for
every participant on every request — is higher than the cost of admitting
the illusion is gone and designing for its absence instead. That is why the
build plan's own strategy document states it as a one-line verdict rather
than a protocol to configure: "why 2PC is off the table" is the opening
clause of Chapter 23's own description, not a footnote inside it.

## ACD: what survives the cut, and what has to be rebuilt

The consistency property this book asks you to adopt in place of full ACID
across the seam is best named **ACD** — **A**tomic (locally, inside each
service's own database), **C**onsistent (eventually, across services, once
every step of a multi-service operation has either completed or been
compensated), and **D**urable (as before: once a service commits its local
write, that write survives a crash) — with the **I**, isolation across the
whole operation, deliberately absent, which is exactly the property this
chapter's own title names. ACD sits in the same family as **BASE**
(**B**asically **A**vailable, **S**oft state, **E**ventually consistent),
the term Kleppmann and the wider distributed-systems literature use for the
same tradeoff described from the availability side rather than the
transaction side; this book prefers ACD because it names the property by
contrast with the acronym the reader already has memorized from the
monolith, rather than introducing a second, unrelated acronym to hold the
same idea.

Here is what actually survives the decomposition, stated precisely rather
than as a slogan. Each extracted service keeps full local ACID — inventory's
own database, payment's own database, and shipping's own database each still
wrap their own write in a real `@Transactional` (or its Quarkus/Panache
equivalent) against their own schema, with the same atomicity, isolation, and
durability guarantees the monolith's single Postgres instance always
provided. Nothing about *this* chapter asks a service to give up transactions
inside its own boundary — a service that cannot atomically commit its own
write is simply broken, decomposition or not. What does not survive is
atomicity *across* services: there is no longer one connection, one
write-ahead log, and one commit point spanning the order write, the
inventory decrement, the payment capture, and the shipment dispatch, because
those four things are now four separate commits, on four separate
connections, to four separate databases, each of which can succeed or fail
independently of the other three.

The property a saga restores in place of the dropped cross-service atomicity
is not "guarantee it never happens" — that guarantee is specifically what 2PC
could not deliver without the costs named above — but "guarantee that when a
later step fails, an explicit, carefully written **compensating action**
undoes the effect of every step that already succeeded." A compensating
action is not a rollback in the database sense; it is a new forward-moving
transaction, written by the service that owns the data being undone, that
produces the opposite business effect of the step it is compensating for.
Where `inventoryService.reserve(...)` decremented stock inside the same
local transaction the monolith controlled completely, an inventory service
extracted behind its own API has to expose something like a `release`
or `cancelReservation` operation that a saga can call explicitly when a
later step — payment, in Chapter 23's choreographed version — fails. Nothing
makes that compensating call happen automatically the way Postgres made the
monolith's rollback happen automatically; a saga author has to write it, name
it, and test its failure-path behavior exactly as carefully as the happy
path, because the one mechanism that used to do this automatically — one database,
one transaction manager — is no longer in the room.

{% include excalidraw.html file="acid-to-acd" alt="The ACID-to-ACD spine: on top, OrderService#placeOrder's single transaction rolling back every extracted context automatically, with no compensating code written for it; on the bottom, each service keeping full local transactions inside its own database while cross-service atomicity and isolation are gone, replaced by an explicit compensating action a saga runs instead of a database rollback." caption="Figure 22.1 — What ACID gives up at the seam, and what a saga rebuilds" %}

## CAP, PACELC, and what "correctness" means now

The reason this tradeoff is not a design mistake to be engineered around, but
a fundamental limit to be designed *with*, is the CAP theorem's own
statement: a distributed data system facing a network partition must choose
between **consistency** (every read sees the most recent write) and
**availability** (every request gets a response), and it cannot have both
during the partition. Four independently deployed databases, reachable only
over a network that can and will experience latency, timeouts, and outright
partitions, are exactly the system CAP describes — the monolith's one
process never had this choice to make, because it never had a network
between its "nodes" to partition in the first place. PACELC sharpens the
same idea for the much more common case where nothing is actually broken:
**E**lse, when the system is running normally with no partition at all, it
still has to choose between **L**atency and **C**onsistency on every
request, because keeping every replica of a payment status byte-for-byte
current requires blocking the caller until every replica agrees, and not
blocking the caller means the caller might read a status that is a few
milliseconds stale. A saga's compensating-action model is a concrete,
practical answer to PACELC's every-day question: it chooses low latency and
local availability on every individual step, and accepts a bounded window
where the system's overall state has not yet converged, rather than making
every step wait for global agreement that may never arrive cheaply, or at
all.

That is the sentence this chapter asks you to internalize before Part 7 puts
it into running code: correctness, at the scale of a distributed checkout,
no longer means "instantaneous and globally agreed" — it means **the system
provably converges to a single consistent state, within a bounded window,
and never settles on a state nobody intended.** An order that briefly shows
as "placed" while payment has not yet been captured is not a bug under ACD
the way it would be a severe one under the monolith's ACID contract; an
order that settles permanently as "placed" after payment was actually
declined, with no compensating action ever running to correct it, is the
real failure ACD has to guard against, and guarding against it is what a
saga's explicit compensation step — not an implicit database rollback — now
has to do explicitly.

## The isolation anomalies the monolith never had to name

Because the monolith's checkout lived entirely inside one
`SERIALIZABLE`-or-stronger transaction boundary by default, it never had to
reckon with the classic isolation anomalies a textbook introduces early and
a working system usually only meets at the seam between two of them. A
**dirty read** — one transaction observing another transaction's
not-yet-committed write — becomes a live possibility the instant payment is
captured in its own service and a different part of the system (a
reporting view, a customer-facing order-status page, the GraphQL gateway's
read model from Chapter 21) can observe "payment captured" before the
shipping step of the same saga has run or failed; the monolith's single
commit point meant no external reader could ever observe a state that later
turned out to be undone, because nothing was visible to anyone until the
whole thing committed or none of it did. A **lost update** becomes possible
the moment two sagas touch the same inventory row through two independent,
service-owned transactions with no single lock manager coordinating them,
instead of the monolith's one process serializing every write through one
connection pool. A **non-repeatable read** — rereading the same row twice
inside one logical operation and getting two different answers — is now the
*ordinary* case for any saga step that reads another service's state before
acting on it, because that state can change between the read and the
dependent write for reasons entirely outside the saga's own control.

None of these three anomalies is a defect introduced by sloppy engineering
at the seam; they are the literal, named cost of trading one shared
transaction manager for several independently committing ones, and this
book's answer to all three is the same one Chapter 17 already demonstrated
in miniature: bound the window, make the staleness visible rather than
silent, and verify convergence explicitly rather than assuming it. The
notification extraction's bounded-wait poll — retry up to ten times at a
fixed interval rather than asserting instantaneous consistency — is this
chapter's dirty-read and non-repeatable-read problem solved one topic early,
for the simplest possible case: one event, one idempotent consumer, no
compensating action required because notification can never meaningfully
fail checkout in the first place. Payment and shipping's sagas, in Part 7,
face the harder version of the same problem — multiple steps, any of which
can fail, each needing its own compensating action — and inherit the same
underlying discipline: a bounded window is acceptable; an unbounded one, or
a silently inconsistent one, is not.

## Three extractions, three different answers

It is worth being precise that "give up atomicity, adopt ACD" is not applied
uniformly to every context this transaction currently spans, because not
every context carries the same cost if its consistency check runs a few
hundred milliseconds late. Notification already left the transaction
entirely in Chapter 17 — a confirmation message arriving a few seconds after
checkout returns is invisible to the customer and has zero compensating
action to write, because there is nothing to undo if a notification is
merely late. That extraction is this chapter's proof of concept, not its
hardest case.

Inventory is the harder case, and Chapter 19 makes a deliberate, named
exception to the general eventual-consistency pattern because of it: the
stock *check* and *reservation* step stays **synchronous**, over gRPC,
specifically because overselling the same physical unit of stock to two
customers is a correctness failure no compensating action can fully repair
— you can refund a customer's card after the fact, but you cannot always
produce a second physical widget that does not exist. Reserving stock stays
a request-response call the order context waits on before it commits to
anything else, even after inventory is its own service with its own
database; what becomes asynchronous is everything *downstream* of a
successful reservation, not the reservation decision itself. That is the
calibration this chapter wants you to carry forward: ACD is not "make
everything eventually consistent everywhere" — it is "make exactly as much
consistent, and exactly as synchronous, as the specific business invariant
requires, and no more."

Payment and shipping are where the full saga pattern is actually built,
because both of them tolerate a bounded window *and*
need a compensating action when something downstream goes wrong — a
captured payment whose shipment later fails to dispatch has to be refunded,
not merely logged as an anomaly. Chapter 23 builds the choreographed version
of that saga for payment: services reacting to each other's events
(`order.placed` triggers payment's attempt, `payment.captured` or
`payment.declined` triggers whatever reacts to it next) with no central
coordinator, which is the natural extension of the event-driven style
Chapter 17 already introduced. Chapter 24 builds the same outcome for
shipping with the opposite control style — a central orchestrator, using the
Camel Saga EIP, explicitly sequencing each step and explicitly invoking the
matching compensating action the moment any step reports failure. Putting
both styles on the same codebase, back to back, is this book's way of
making the choreography-versus-orchestration tradeoff concrete rather than
theoretical: the same underlying ACD guarantee, built two structurally
different ways, so the reader can judge for themselves which shape fits
which kind of saga before being told which one "wins."

## What you learned

- The monolith's checkout gets atomic rollback across four bounded contexts
  **automatically**, paid for entirely by one `@Transactional` boundary and one
  database's write-ahead log — a guarantee this project proved with two
  different tiers of evidence: a mocked unit test showing the *orchestration
  order* a decline forces, and the behavior-equivalence suite's
  "Payment-Declined" scenario showing the *real, observed* rollback by
  re-reading Postgres state before and after a declined checkout over a real
  HTTP request.
- That guarantee cannot travel across a service boundary. Two-phase commit —
  the textbook mechanism for atomicity across independent databases — trades
  it for a single-point-of-failure coordinator, locks held across multiple
  blocking network round-trips, and a recentralization of operational
  ownership that defeats the purpose of decomposing in the first place.
- **ACD** (atomic locally, eventually consistent across services, durable as
  before) replaces full cross-service ACID; **BASE** names the same tradeoff
  from the availability side. What is kept: full local ACID inside each
  service's own database. What is rebuilt, by hand, is cross-service
  atomicity — as a saga, with an explicit **compensating action** for every
  step that needs one, because no database transaction manager spans the
  services anymore to do it automatically.
- CAP and PACELC are not abstractions once a checkout crosses a real network:
  every saga step trades consistency for availability-and-latency on
  purpose, which is why "correctness" under ACD means *converges to a
  consistent state within a bounded window*, not *instantaneous and globally
  agreed* — and why a bounded-wait poll with a stated budget, not an
  instantaneous assertion, is the right shape of check for it.
- Not every context needs the same answer: notification tolerates full
  asynchrony with no compensating action at all (Chapter 17); inventory's
  stock reservation stays synchronous because overselling is a
  correctness failure a compensation cannot fully repair (Chapter 19);
  payment and shipping get the full saga treatment because they tolerate a
  bounded window but need compensation when something fails
  downstream (Chapters 23 and 24).

Chapter 23 picks this argument up and makes it concrete on the Payment
extraction: a choreographed saga, event-driven exactly in the style Chapter
17 introduced, with `order.placed` and `payment.captured`/`payment.declined`
as its vocabulary and a compensating action standing in for the rollback
this chapter just took away. Chapter 24 builds the same guarantee again, for
Shipping, through a central orchestrator instead of peer-to-peer events —
the same ACD outcome, reached by the opposite control style, on the same
codebase.

---
*Verification status: not applicable — this is a conceptual chapter with no
runnable example under `examples/`. Every artifact it cites is real code
and real, dated evidence already in this repository:
`examples/00-monolith/SMELLS.md` (SMELL #3's row and cure mapping),
`order/OrderService.java#placeOrder` and its `@Transactional` javadoc,
`common/exception/PaymentDeclinedException.java` and
`InsufficientStockException.java`, `order/OrderServiceTest.java`'s
payment-declined unit test and its own comment naming the unit tier's limit,
and `tooling/newman/mea.postman_collection.json`'s "Scenario 3 —
Payment-Declined" folder, whose three-step capture/decline/recheck sequence
is paraphrased (condensed, not byte-exact) above and is also narrated in
Chapter 10's testing chapter. What a reader's own run should confirm
independently: executing that Newman folder against a freshly seeded
monolith to watch the stock count hold steady across the decline,
rather than taking the paraphrased assertions on the page as sufficient — the whole point of a
behavior-equivalence suite is that it is runnable, not merely readable.*
