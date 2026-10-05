---
title: "The Deliberate Smells"
order: 9
part: "The Reference Monolith"
description: "The coupling, god-service, shared-table, and transaction-scope smells planted in the reference monolith, each tagged to the pattern that will later cure it."
---

The previous chapter assembled `examples/00-monolith/` into one deployable,
one database, one JVM, six bounded contexts living as six packages under a
single Spring Boot application. That assembly was not an accident of
convenience. Every coupling decision in that codebase was chosen, not
overlooked, and six of those decisions are deliberately bad — planted the way a
structural engineer plants a known weak point in a demonstration beam, so
that when the beam is later cut at exactly that point, the cut makes sense to
everyone watching. One of the six — Review's entanglement in a shared
security filter chain despite having no runtime dependency on anything else —
has already been cured; r02 extracted it, and Chapter 7 walked that
extraction end to end as the proof that the ADLC's loop actually holds up
under real work. This chapter catalogues the five that remain, names exactly
where each one lives in the real source tree, explains why each is a
modernization liability rather than a cosmetic one, and tells you which later
chapter cuts along it. It is also a bridge: Part 4, "Finding the Seams,"
opens immediately after this chapter and spends three chapters teaching the
method — domain-driven design, event storming, coupling theory — for finding
cuts like these in a system that didn't come with a `SMELLS.md` file telling
you where they are.

The code is in `examples/00-monolith/`; nothing new has been scaffolded for
this chapter. Every excerpt below is quoted verbatim from files already in
that module, and every one carries an in-code `SMELL[ch.NN]` tag you can find
yourself:

```sh
grep -rn 'SMELL\[ch\.' examples/00-monolith/src
```

That single command is worth running before you read any further, because it
makes a claim this chapter depends on falsifiable rather than rhetorical: the
smells are not described in prose and left for you to trust — they are
comments sitting next to the exact line of code that embodies them, and the
grep output is the authoritative index. `examples/00-monolith/SMELLS.md`
mirrors that index in table form, mapping all six smells (including the
cured one) to their curing chapters. This chapter is the narrative version of
that table, for the five still standing.

## A seam is a line the system already wants to be cut along

Before walking the five, it's worth being precise about the word this
chapter keeps using: **seam**. A seam is not just "a place where the code
could be split" — you can split code almost anywhere by inserting an
interface. A seam is a place where splitting the code *follows a boundary
that already exists conceptually*, even though the implementation currently
straddles it. Bounded contexts are conceptual seams: order, inventory,
payment, shipping, and notification are different areas of responsibility
with different reasons to change, different owners in a real organization,
and different data lifecycles, even though right now they share one schema,
one transaction manager, and one JVM. A deliberate smell, in this monolith,
is the specific *mechanism* by which a conceptual seam gets welded shut in
the implementation — a foreign key where there should be a replicated field,
a direct method call where there should be a published event, a shared
transaction where there should be five independently committable ones.

Each smell
below is not "a bug to fix." Fixing a bug makes code that was wrong become
right, in place. Cutting a seam makes code that was *one thing* become *two
things*, each independently deployable, independently scalable, and
independently owned — and that is a fundamentally larger, riskier, more
valuable move than a bug fix, which is exactly why the book spends from here
through Chapter 26 doing it five more times, each time at higher difficulty.
Read each smell below with the question "what boundary does this weld shut,
and what would have to exist on each side of it to unweld it" — that is the
question Part 4 teaches you to ask systematically, and the question every
cut from Chapter 15 onward actually answers.

## Smell 1 — shared schema and cross-context foreign-key joins

**The pattern.** In a system decomposed along bounded contexts, each context
should own its data exclusively — no other context reads or writes it except
through that context's published contract. The opposite pattern, common in
monoliths that grew organically, is a **shared schema**: one database, one
set of tables, and ordinary relational foreign keys connecting tables that
belong to conceptually different contexts, because a foreign key is the
cheapest way to express "these two rows are related" when everything already
lives in the same database anyway.

**Where it lives.** `examples/00-monolith/src/main/resources/db/migration/V1__init_schema.sql`
is the root of this smell — one Flyway migration, one Postgres schema, every
table in it. From there it surfaces as ordinary JPA `@ManyToOne` associations
wherever a context's entity needs to reference another context's row.
`order.OrderItem` is a clean example:

```java
// order/OrderItem.java
/**
 * SMELL[ch.18]: OrderItem holds a direct JPA @ManyToOne FK/join
 * onto inventory.InventoryItem — an order-context table referencing an
 * inventory-context table in the one shared schema. Once inventory owns its own
 * database (ch.19), this join is replaced by a denormalized copy of the fields the
 * order context actually needs (sku, name, price-at-time-of-order), kept current
 * via CDC.
 */
@ManyToOne(optional = false)
@JoinColumn(name = "inventory_item_id", nullable = false)
private InventoryItem inventoryItem;
```

`order.Order` does the same thing against `common.Customer`
(`@JoinColumn(name = "customer_id")`), `payment.Payment` and
`shipping.Shipment` each hold a direct FK back into `orders`, and
`notification.Notification` joins to both `customers` and `orders`. The
`SMELLS.md` table lists the full set; the shape repeats everywhere a checkout
flow needs to relate one context's data to another's, because that was the
cheapest thing to write at the time each table was added.

**Why it's a liability.** A foreign key is a promise enforced by the
database: this row cannot exist, or cannot be deleted, without that row also
existing. That promise is exactly what makes independent deployability
impossible. If `inventory` moves to its own database, Postgres can no longer
enforce `order_items.inventory_item_id -> inventory_items.id`, so either the
constraint has to be dropped — and with it, every guarantee the application
was silently leaning on — or the two contexts have to stay on the same
database forever, which means they were never really independent contexts to
begin with, just two packages with a shared table underneath pretending to
be contexts. Worse, the coupling is invisible from either context's own code:
`OrderService` doesn't declare "I depend on inventory's schema," the database
does, which means the dependency shows up only when someone tries to change
it — a migration that drops a column inventory no longer needs breaks
order's queries three deploys later, in a different team's incident.

**How to recognize it in your own system.** Grep your schema migrations for
foreign keys that cross a conceptual ownership line — not "does this table
reference another table," every normalized schema does that, but "does the
team or capability that owns the referencing table differ from the team or
capability that owns the referenced table." If the answer is yes and the
join is enforced at the database level rather than through an API call, you
have this smell. A second tell: can you truthfully say which service "owns"
a given table, or does the answer involve the word "well, several
things write to it"? Ownership that several contexts can plausibly claim is
ownership nobody actually has.

**Who cuts it.** Chapter 18, "Shared Data to Owned Data," is where each
context stops reading another context's tables directly and starts holding
only the fields it actually needs, kept current by replication rather than
by a join. Chapter 19 is the mechanism for getting there without a
stop-the-world cutover: change-data-capture (CDC) backfill from the shared
schema into each newly-owned database, exercised concretely as the inventory
extraction.

## Smell 2 — the god `OrderService`

**The pattern.** A "god object" (or god service, in a service-oriented
codebase) is a single component that has taken on responsibility for
orchestrating work that conceptually belongs to many different areas of the
system. It is easy to create by accretion: the first feature that needs to
touch two contexts gets a method on whichever service is closest at hand,
the next feature adds a third dependency, and nobody ever stops to ask
whether the component accumulating all these dependencies is still doing one
job.

**Where it lives.** `order/OrderService.java`, carrying the tag directly on
the class:

```java
/**
 * SMELL[ch.26]: this is the "god service" — it is the single orchestration point
 * for checkout and it reaches directly into inventory, payment, shipping, and
 * notification's services/repositories/entities instead of those contexts being
 * independently deployable collaborators reached over a stable contract. Order is
 * deliberately the HARDEST and LAST extraction in the roadmap (ch.26) precisely
 * because every other context's extraction has to first remove one of this
 * service's direct dependencies.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;
```

Count the constructor's dependencies: a repository for its own aggregate, a
repository for a shared-kernel entity it doesn't own, and three *other
contexts'* services injected directly. `placeOrder` then calls all three in
sequence — `inventoryService.reserve(...)`, `paymentService.charge(...)`,
`shippingService.dispatch(...)`, `notificationService.sendOrderConfirmation(...)`
— as ordinary Java method calls on live Spring beans, in the same process,
on the same call stack.

**Why it's a liability.** Every one of those four dependencies is a reach
across a bounded-context boundary using the cheapest possible mechanism — a
direct reference held by the caller — rather than a contract the callee
publishes and the caller consumes at arm's length. That has two
compounding consequences. First, `OrderService` cannot be deployed, tested,
scaled, or reasoned about independently of the four contexts it reaches
into: changing `PaymentService`'s method signature is, today, an
`OrderService` compile error, which is a strong forcing function toward
coordinated releases across what should be independently-owned services.
Second — and this is the sharper pain for the modernization roadmap
specifically — `OrderService` cannot be *extracted* until every one of those
four dependencies has already been turned into something that isn't a
direct in-process call. That is why order is the hardest and
last extraction in the roadmap (Chapter 26): it is not extracted because it
is interesting last, it is extracted last because nothing else can be
extracted *around* it first — each of the five earlier extractions (review,
notification, inventory, payment, shipping) has to remove one of this god
service's direct edges before order can be cut free of the rest without
dragging four other contexts along with it.

**How to recognize it in your own system.** Pick the service with the most
constructor-injected dependencies in your codebase — in a Spring application
this is a literal, mechanical search, not a judgment call: count
constructor parameters, or injected fields, per `@Service`. A service
sitting meaningfully above the median, especially one whose dependencies
span module or package boundaries that correspond to different business
capabilities, is a god-service candidate. A second, behavioral tell: can you
describe the service's single responsibility in one sentence without using
the word "and"? `OrderService`'s one-sentence description is "places
an order by checking stock, persisting the order, charging payment,
dispatching shipment, *and* sending a notification" — five verbs, five
contexts, one class.

**Who cuts it.** Chapter 26 is the extraction itself, but it is also the
chapter that only becomes possible because Chapters 16, 17, 19, 23, and 24
have each already removed one of `OrderService`'s direct edges — to
inventory, to notification, to payment, to shipping — leaving a much
smaller surface by the time order's own turn comes. The god service is also
the single strongest argument, independent of any one extraction, for why
this monolith needs sagas at all (Smell 3, next) — a service can only
orchestrate five contexts' worth of work in one synchronous call chain if
something is willing to hold open a correspondingly large failure domain,
which is exactly what Smell 3 is.

## Smell 3 — one ACID transaction spanning five bounded contexts

**The pattern.** A relational database transaction gives you atomicity: all
of its writes commit together, or none of them do. That is an enormously
convenient property, and it is tempting to lean on it for correctness
properties that have nothing to do with the database — "if payment fails,
roll the inventory reservation back too" is true today only as a side
effect of everything being one transaction against one database. The smell
is depending on that side effect as if it were a deliberate distributed
transaction mechanism, when it is actually just how far one `@Transactional`
annotation happens to reach.

**Where it lives.** `order/OrderService.java#placeOrder`, the same method
responsible for Smell 2, wrapped in a single Spring `@Transactional`:

```java
/**
 * SMELL[ch.22]: one in-process ACID @Transactional spans FIVE bounded
 * contexts — order, inventory, payment, shipping, notification. It "works"
 * today because Postgres gives us atomic rollback for free across all of them.
 * The moment any one of these becomes its own service with its own database,
 * this rollback-everything behavior disappears and has to be rebuilt
 * explicitly as a saga with compensating actions (ch.23 choreographed,
 * ch.24 orchestrated) — that is the ACID -> ACD story told in ch.22.
 */
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    ...
    inventoryService.reserve(line.sku(), line.quantity());   // throws InsufficientStockException, no writes yet
    order = orderRepository.save(order);
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod()); // a decline rolls back everything above
    shippingService.dispatch(order, order.getShippingAddress());
    notificationService.sendOrderConfirmation(customer, order);
    return toDto(order);
}
```

`PaymentDeclinedException`'s own javadoc names the mechanism explicitly:
"payment + shipment + notification in ONE `@Transactional`" rolls back
"inventory decrement + order persistence" too, the instant the payment
gateway declines a card — five contexts' worth of work, undone atomically,
because Postgres's transaction log doesn't know or care that the rows it's
rolling back belong to five different conceptual owners.

**Why it's a liability.** The `checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction`
test in `SixContextsSmokeTest` proves this behavior works today, and that is
precisely the trap: it works *because* one database is the actual mechanism,
not because anyone designed a correctness protocol for it. The instant any
one of inventory, payment, shipping, or notification gets its own database —
which is the explicit goal of five of the next six extractions — Postgres
can no longer roll back writes it doesn't hold the lock for, and "a payment
decline undoes the inventory reservation" stops being true unless something
new is built to make it true again. That something is a saga: an explicit
sequence of local transactions, each independently committed, each paired
with a compensating action that undoes its effect if a later step fails.
Sagas are strictly harder to write and reason about than one `@Transactional`
block, because the system now has to tolerate an *intermediate* state —
stock reserved, no payment yet — that the current code never has to think
about, since the database simply never lets that intermediate state become
visible outside the transaction. This is the ACID-to-ACD story Chapter 22
tells by name: atomicity, consistency, and durability survive the move to a
distributed system; isolation does not, and the gap left behind is exactly
where saga choreography and orchestration go.

**How to recognize it in your own system.** Look for `@Transactional`
boundaries (or their equivalent in any framework with declarative
transaction management) whose method body calls into more than one
conceptual bounded context — not more than one repository, which is normal,
but more than one *context's* repository or service. A second, sharper tell:
ask "if this transaction's third database write failed right now, what
would the correct behavior be, and does the code currently rely on the
database to produce that behavior automatically?" If the answer is yes, the
correctness of a cross-context rollback is resting entirely on co-location,
and that rug gets pulled the moment any piece of this moves to its own
datastore.

**Who cuts it.** Chapter 22 names the ACID-to-ACD shift conceptually.
Chapters 23 and 24 build the two concrete mechanisms — a choreographed saga
(payment reacts to an `order.placed` event and emits `payment.captured` or a
compensating failure event; no orchestrator, each service reacts to the last
one's event) and an orchestrated saga (shipping's extraction uses a Camel
Saga EIP to drive the sequence from one coordinating definition instead of
peer-to-peer events) — giving the book both styles as working code
rather than describing sagas only in the abstract.

## Smell 4 — synchronous notification inside the checkout transaction

**The pattern.** Not every piece of work a transaction touches needs to be
part of that transaction's atomicity guarantee. Sending a confirmation email
or push notification is a clear example: the customer's order is valid and
confirmed whether or not the notification send succeeds, arrives promptly,
or arrives at all — notification delivery is an *orthogonal concern*, not a
correctness dependency of checkout. The smell is coupling an orthogonal
concern's latency and failure mode to the transaction anyway, because
calling it synchronously, in-process, was the path of least resistance when
the line of code was written.

**Where it lives.** The last line of `placeOrder`, and the method it calls:

```java
// order/OrderService.java
// SMELL[ch.17]: notification sent synchronously inside the checkout
// transaction instead of via an outbox + async consumer.
notificationService.sendOrderConfirmation(customer, order);
```

```java
// notification/NotificationService.java
/**
 * SMELL[ch.17]: Sends the order-confirmation notification SYNCHRONOUSLY, as a
 * direct in-process call from order.OrderService#placeOrder, inside the
 * checkout's own @Transactional. Two problems this plants on purpose:
 * (1) checkout latency is now coupled to however long "sending" a notification
 * takes, even though the customer doesn't need to wait for it; (2) if sending
 * ever threw, it would roll back the whole order along with it. ch.17
 * (notification extraction) replaces this with a transactional outbox + an
 * async event-driven consumer, decoupling both latency and failure domains.
 */
public Notification sendOrderConfirmation(Customer customer, Order order) {
    String message = "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
    return repository.save(new Notification(customer, order, "EMAIL", message));
}
```

`common/Topics.java` is the quiet half of this smell's evidence: it already
declares `order.placed`, `payment.captured`, and `shipment.dispatched` as
reserved topic names the monolith does not yet publish to, specifically so
the event-driven extractions can adopt vocabulary that lines up 1:1 with the
sibling target architectures without the monolith carrying speculative
messaging infrastructure it doesn't use yet.

**Why it's a liability.** This is a narrower, sharper version of Smell 3's
problem, worth separating out because its fix is a named pattern of its own
rather than a saga. Today, if `sendOrderConfirmation` were slow — a flaky
downstream email provider, a notification service under load — every
checkout request would be slow, because the caller is blocked inside the
same transaction waiting for a call it did not need to wait for. And if it
threw, the entire order would roll back over a notification failure, which
is a strictly worse outcome for the customer than "order confirmed,
notification will retry" — the customer's payment was already captured, but
the whole transaction, payment included, unwinds because an unrelated
concern three lines later failed. Coupling an orthogonal concern's failure
mode to a critical path's transaction is a liability independent of
distribution: it's bad even while everything is still in one process, and it
becomes actively untenable the moment notification becomes its own service
reached over a network, where "slow" and "down" are now everyday occurrences
rather than edge cases.

**How to recognize it in your own system.** Ask, for any call inside a
transactional method, "if this specific call failed or hung for ten
seconds, would a reasonable person want the whole transaction to fail or
hang with it?" If the answer is no — the work is a side effect the
rest of the flow doesn't depend on for correctness — but the code currently
makes it a blocking, in-transaction call anyway, that is this smell. The
usual candidates are exactly what you'd guess: notifications, audit
logging, analytics events, cache invalidation, anything whose job is to
*react* to a fact that already happened rather than to help establish that
fact.

**Who cuts it.** Chapter 17 replaces the direct call with a **transactional
outbox**: the checkout transaction writes an outbox row in the same
commit it already makes (so there's no new atomicity problem to solve), and
a separate, asynchronous consumer reads that outbox and performs the actual
notification send, decoupling both the latency and the failure domain from
checkout. Chapter 16, immediately before it, builds the content-based
routing and anti-corruption layer that chapter needs at the seam — which is
also exactly Smell 5.

## Smell 5 — no anti-corruption layer; a raw JPA entity leaks across a seam

**The pattern.** When one bounded context needs data from another, the
textbook answer is for the producing context to publish a stable contract —
a DTO, an event schema, an API response shape — that it controls and can
evolve independently of its own internal representation. The anti-pattern is
handing the *internal* representation straight across the boundary: the
caller now holds a reference to the producer's actual persistence entity,
with the producer's lazy-loading behavior, the producer's column names
masquerading as field names, and the producer's migrations now silently
becoming the caller's problem too.

**Where it lives.** `inventory/InventoryService.java#findBySkuOrThrow`,
called directly by `order/OrderService.java#placeOrder`:

```java
// inventory/InventoryService.java
/**
 * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
 * (order.OrderService) outside this context — there is no anti-corruption
 * layer at this seam. ch.16 introduces a Camel content enricher / message
 * translator here instead.
 */
public InventoryItem findBySkuOrThrow(String sku) {
    return repository.findBySku(sku)
            .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + sku));
}
```

```java
// order/OrderService.java#placeOrder
// SMELL[ch.16]: reaching directly into inventory's entities/repository from
// the order context, with no anti-corruption layer at the seam.
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity());
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

Notice the type flowing across the boundary: `InventoryItem` is annotated
`@Entity`, managed by inventory's own JPA `EntityManager`, and mapped
directly to inventory's `inventory_items` table. `OrderService` doesn't
receive a `StockDto` — the contract type `InventoryService` already builds
for its own REST controller a few lines above `findBySkuOrThrow` — it
receives the live entity, and goes on to store a reference to it inside
`OrderItem` (Smell 1's FK join, reappearing here as the object-graph version
of the same problem).

**Why it's a liability.** This is the smell that makes the other four worse
than they would be alone: it's the absence of exactly the layer that would
otherwise contain the damage. An anti-corruption layer (ACL) — a Camel
message translator, a content enricher, a hand-written mapping method, any
mechanism that sits at the seam and converts one side's model into the
other side's contract — is what lets inventory change its internal entity
shape (rename a column, split a table, move to a different persistence
technology entirely) without `OrderService` ever noticing, because
`OrderService` was never handed the internal shape in the first place. Without
that layer, every internal change to `InventoryItem` is a potential breaking
change to `OrderService`, silently, with no compiler error pointing at the
dependency because Java happily lets a `@Service` from one package return
an `@Entity` from another. It is also, concretely, the reason Smell 1's FK
join and Smell 2's direct service call are even possible to write without
anyone noticing something's wrong: a raw entity reference is the medium both
of those other smells travel through.

**How to recognize it in your own system.** Trace what type crosses a
module or service boundary at each call site. If it's annotated as a
persistence entity (`@Entity`, an ORM-managed class, an ActiveRecord model)
rather than a plain data-transfer type the producing side built specifically
to be read by others, that's this smell, independent of whether the call
itself is synchronous or asynchronous — the same problem shows up just as
easily in an event payload that serializes an entity directly instead of a
versioned event schema.

**Who cuts it.** Chapter 16, "Content-Based Routing & the ACL," builds a
Camel message translator / content enricher at this exact seam — inventory
starts publishing (and `OrderService` starts consuming) `StockDto`, the
contract type that already existed unused a few lines above the leak, with
a translation step in between that can absorb future changes to either
side's internal shape. This chapter comes immediately before
notification's extraction (Chapter 17) because the outbox pattern Chapter
17 needs depends on having a stable event contract to put in the outbox —
which is exactly what an ACL is for.

## The proof this method works: Review, already cured

It would be reasonable to read five smells and five future chapters and
wonder whether the whole exercise is theoretical — whether "tag it, name
the cure, extract it later" actually survives contact with a real codebase.
It already has, once. The sixth smell in `SMELLS.md` — Review tangled into
the monolith's one global `SecurityFilterChain` despite having no runtime
dependency on any other context — is marked **CURED**, not planned. Chapter
7 walked that extraction's entire ADLC loop in detail: the Map phase
confirming Review really was independent before committing to cut it first,
the Camel strangler proxy routing `/api/reviews` by URI, the two-phase lift
to Quarkus, and the behavior-equivalence suite (the Newman collection
captured against the monolith, re-run unchanged against the extracted
service) catching two real bugs along the way — a native-image reflection
gap the JVM build never exposed, and a routing predicate that silently
sent every request to the monolith regardless of the feature flag, caught
only because two backends sharing the same `reviews` table made a passing
suite insufficient evidence on its own. `security/SecurityConfig.java`
still carries its `SMELL[ch.15]` tag today, harmlessly vacuous, because
Review's controller, service, repository, and entity are gone from this
module entirely, and `SixContextsSmokeTest#reviewIsNoLongerServedByTheMonolith`
asserts the monolith now returns 404 where Review used to answer. That one
cured smell is the existence proof for the five still standing in this
chapter: the pattern — identify a conceptual seam, tag the mechanism
welding it shut, name the chapter that will cut it, then actually cut it
with an equivalence gate watching — is not a chapter-outline convenience.
It already produced a real extraction, in this project's own git history,
before this chapter was written.

## What you learned

- Five deliberate smells remain in `examples/00-monolith/`, each tagged
  in-code with `SMELL[ch.NN]` and catalogued in `SMELLS.md`: shared-schema
  cross-context FK joins, the god `OrderService`, one ACID transaction
  spanning five contexts, a synchronous notification call inside checkout,
  and a raw JPA entity leaking across the order/inventory seam with no
  anti-corruption layer.
- Each smell is a seam — a line where a conceptual bounded-context boundary
  already exists but the implementation currently welds it shut with the
  cheapest available mechanism (a foreign key, a direct method call, a
  shared transaction) — not an arbitrary defect to patch in place.
- Each smell names its own cure and chapter: ch.18/19 for shared data,
  ch.26 for the god service, ch.22/23/24 for the transaction span, ch.17
  for synchronous notification, ch.16 for the missing ACL — and several
  smells depend on earlier ones being cured first, which is why the
  extraction order in `build-plan.md` Section E is deliberate, not
  arbitrary.
- The sixth, already-cured smell (Review) is this method's existence proof:
  tag the seam, name the cure, cut it with an equivalence gate watching,
  and it works — this project already did it once, in Chapter 7.

Chapter 10 finishes Part 3 by building the test suite that proves all six
smells actually behave the way this chapter claims — the JUnit layers, the
Testcontainers integration tests, and the Newman behavior-equivalence suite
that every one of the next six extractions will be gated on. Part 4,
"Finding the Seams," then takes the five smells catalogued here and teaches
the general method behind them: strategic and tactical domain-driven
design, event storming, and coupling theory, so that by the time you reach
Chapter 15 you are not just trusting this chapter's map of where to cut —
you can draw that map yourself, on a system that never shipped with a
`SMELLS.md` file at all.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every excerpt in this chapter is quoted verbatim from `examples/00-monolith/`
as it exists in the repository today, and the `grep -rn 'SMELL\[ch\.'
examples/00-monolith/src` command above is directly runnable against that
tree — both are mechanically checkable, not narrated. What remains
unverified is forward-looking: that each named cure (ch.16 through ch.26)
actually resolves its smell the way described here, which can only be
confirmed when those chapters' equivalence-gate runs land, and that the
extraction ordering in `build-plan.md` Section E — each smell's cure
depending on an earlier one — holds up in practice rather than needing
revision once the dependency chain is actually built.*
