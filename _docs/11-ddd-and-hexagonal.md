---
title: "Strategic & Tactical DDD + Hexagonal"
order: 11
part: "Finding the Seams"
description: "Subdomains, bounded contexts, context mapping, aggregates, and domain events — keeping protocols out of the domain core so extraction is mechanical."
---

Chapter 9 catalogued five smells still standing in `examples/00-monolith/` and
named, for each one, the chapter that will cut it. Chapter 10 built the
behavior-equivalence suite that will prove each cut preserved behavior. Both
chapters used the word "seam" freely — a line where a conceptual boundary
already exists but the implementation currently welds it shut — without ever
explaining where that word's vocabulary comes from or how to find a seam in a
system that didn't ship with a `SMELLS.md` file pointing at one. This chapter
supplies that vocabulary. Domain-Driven Design (DDD) is not a styling choice
for how you name your classes; it is a discipline for deciding where a system
should be cut, and hexagonal architecture is the structural discipline for
making a cut, once decided, cheap to execute. Together they are this book's
seam-finding toolkit, and every smell Chapter 9 named turns out to be a DDD
concept with its name filed off.

The code is in `examples/00-monolith/` and, for contrast, in
`examples/02-review-service/` — nothing new has been scaffolded for this
chapter. Every excerpt below is quoted verbatim from files already in those
two modules, the same discipline Chapter 9 held itself to.

## Strategic DDD: naming the lines that already exist

Strategic DDD starts from a blunt question: not "how should we write this
code," but "what are the different things this business does, and which of
them deserve their own model." Evans calls the answer a **subdomain**, and
classifies each one by how much competitive value it carries and how fast it
changes. A **core** subdomain is where the business actually differentiates
itself — complex, strategically important, worth your best engineering
attention. A **supporting** subdomain is necessary but not a differentiator —
build it simply, because nobody is choosing this business over a competitor
because of how well it does this one thing. A **generic** subdomain is a
solved problem elsewhere — notifications, ratings, authentication — that most
systems need in roughly the same shape, which is exactly why it's usually
fine to adopt an off-the-shelf answer rather than invent one.

Run that classification against the six contexts the monolith already has, and
the answer falls out almost without effort. **Order** is core: it's the
checkout aggregate, the one piece of the system whose correctness and
responsiveness customers notice directly, and the one every other context
exists to serve. **Inventory**, **payment**, and **shipping** are supporting:
every e-commerce system needs stock levels, a way to capture a charge, and a
way to get a package moving, but doing those three things is table stakes,
not a competitive edge — build them correctly and move on. **Notification**
and **review** are generic: sending a confirmation message and collecting a
star rating are both solved problems with well-known shapes, which is
precisely why Chapter 4's strategy discussion would file them under "buy or
adopt a known pattern" rather than "invest heavily here."

Notice what that classification predicts, before this book ever gets to the
extraction roadmap: the two generic subdomains — notification and review —
are also the first two services extracted (Chapters 15 and 17), and the core
subdomain — order — is extracted last (Chapter 26). That is not a
coincidence this chapter is pointing out after the fact; it's strategic DDD's
own logic. A generic subdomain changes rarely, has few internal invariants
worth agonizing over, and has the least organizational risk if its extraction
goes imperfectly. A core subdomain is exactly the opposite on every axis,
which is why you save it for last, once the ADLC loop and the extraction
toolkit have both been proven on lower-stakes ground.

Each subdomain, in turn, becomes one or more **bounded contexts** — the scope
within which one model and one **ubiquitous language** stay internally
consistent, and outside of which the same word is allowed to mean something
else entirely. This is the half of strategic DDD the monolith's design
actively works against, and it's worth being precise about how. A bounded
context's defining property is that it owns its model privately and exposes
only a deliberate, public contract — which means two different contexts are
*allowed*, even expected, to have two different ideas of what an "item" is.
In the monolith today, `order`, `inventory`, `payment`, `shipping`, and
`notification` share one `common` package of DTOs and enums, and — more to
the point — one Postgres schema in which every table is a first-class citizen
any context's JPA mappings can join against. That is not five bounded
contexts; it is one context wearing five package names. A single shared
vocabulary stretched across six conceptually distinct areas of responsibility
is itself a symptom worth naming, not a convenience to take for granted —
and it is the root condition every smell in Chapter 9 grows out of.

## Context mapping: naming the relationship nobody decided

Once you have candidate bounded contexts, the next strategic-DDD question is
how they should relate to each other, and DDD's **context mapping** gives
that question a fixed vocabulary instead of leaving it to whatever the
cheapest line of code happened to do. Four of those patterns matter most for
this book's purposes, and the order↔inventory seam the god `OrderService`
reaches across is a worked example of nearly all of them at once.

A **shared kernel** is a deliberate, narrow slice of model two bounded
contexts agree to share explicitly — both teams know the kernel exists, both
have to coordinate before changing it, and both accept the coupling as a
conscious trade-off for a small, stable piece of shared vocabulary. That is
not what the monolith has. One Postgres schema, readable and joinable by
every context's JPA mappings, is a shared kernel with no edges — not a
narrow, governed slice of shared model, but the entire database treated as if
it belonged equally to all six contexts at once. Nobody decided this was a
shared kernel; it is simply what you get by default when nobody decides
anything, because a foreign key is the cheapest way to relate two rows that
happen to live in the same schema.

The relationship the order↔inventory seam *should* have is **customer/supplier**:
order is the downstream consumer of inventory's stock data, inventory is the
upstream supplier, and a well-formed customer/supplier relationship means the
two sides negotiate a stable, versioned contract — a DTO or event schema the
supplier commits to and the consumer builds against — with the downstream
side carrying enough weight in that negotiation to actually shape what the
contract contains, because it's the downstream side's business need driving
the integration in the first place.

Instead, what actually exists is **conformist**: `order.OrderService` accepts
`inventory`'s internal representation wholesale, with no negotiation at all.

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
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity());
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

Conformist is a legitimate choice *sometimes* — it's the right call when the
upstream is clean, stable, and outside your influence, the way a
small team integrating with a large vendor's API has no real leverage to
negotiate a bespoke contract and is better off just accepting the vendor's
shape. That is not this situation. `order` and `inventory` are siblings in
the same codebase, owned by (eventually) the same organization, heading
toward being two independently deployable services that absolutely could
negotiate a contract — `inventory.InventoryService` even builds one already,
a `StockDto`, for its own REST controller a few lines above
`findBySkuOrThrow`, and simply doesn't hand it to `OrderService`. Conformism
chosen deliberately, with eyes open, is a strategy. Conformism that happens
because nobody built the alternative and the live entity reference was
sitting right there is what Chapter 9 calls a smell, and what this chapter
can now name precisely: a missing **anti-corruption layer (ACL)** — the
translation Evans prescribes specifically for a downstream context that
needs to protect its own model from an upstream context's internal
representation. An ACL would sit at exactly this seam and do exactly what
the unused `StockDto` is already shaped to do: hand `OrderService` a stable,
owned-by-inventory contract instead of inventory's live JPA entity. That
translation layer — built in Chapter 16 as a Camel message translator and
content enricher — is this chapter's conceptual gap turned into working code,
two chapters from here.

It's worth being exact about what was actually missing, because it is a
specific, nameable thing and not a vague complaint about code quality:
nobody ever decided what the order↔inventory relationship *should be*. No
`DRQ`-style decision was made, no contract was drafted, no map was drawn —
the relationship that exists today is simply whatever the cheapest
implementation produced, which is a direct object reference, because that is
always the cheapest thing a shared JVM and a shared schema can produce.
Context mapping's real value isn't the four vocabulary words above; it's the
discipline of forcing that decision to be made explicitly, for every seam, so
the relationship between two contexts is something somebody chose rather
than something nobody noticed.

## Tactical DDD: modeling what lives inside a seam

Strategic DDD decides where the lines go. **Tactical DDD** decides what the
model looks like on each side of a line, and three of its building blocks —
aggregates, value objects versus entities, and domain events — are each,
again, a name for something Chapter 9 already found broken.

An **aggregate** is a cluster of objects treated as one consistency unit: the
boundary inside which invariants must hold together, and the unit a single
transaction commits as a whole. `Order` and its `OrderItem`s are the
monolith's clearest aggregate, and in one respect its design already shows
good tactical instincts: `Order#addItem` doesn't let a caller append an item
and separately, maybe, remember to update the running total — appending an
item and updating `totalCents` happen together, inside the aggregate root, so
there is no code path that can produce an `Order` whose total doesn't match
its items. That's exactly the discipline an aggregate root exists to enforce.

But the same `OrderItem` that gets this right also gets a sibling rule wrong.
DDD's standard guidance is that an aggregate should reference *other*
aggregates by identity or by a small, copied value — never by
holding a live reference to another aggregate's root object, because doing so
silently extends your consistency boundary into a context you don't own and
can't control. `OrderItem` holds exactly that live reference:

```java
// order/OrderItem.java — the SMELL[ch.18] join quoted in Chapter 9
@ManyToOne(optional = false)
@JoinColumn(name = "inventory_item_id", nullable = false)
private InventoryItem inventoryItem;
```

The field two lines below it in the real checkout flow —
`inventoryItem.getPriceCents()`, captured once at order time and stored as
`OrderItem`'s own `priceCents` — is tactical DDD done correctly: a **value
object** snapshot, a plain immutable fact about this specific line item at
this specific moment, with no identity and no lifecycle of its own.
`InventoryItem` itself, by contrast, is a legitimate **entity** — it has
identity, it has a lifecycle (`decrement`/`restock`), and inventory's own
code is right to model it as one. The problem isn't that either type is
modeled wrong in isolation. The problem is that `OrderItem` holds one correct
value-object snapshot (`priceCents`) sitting directly next to one incorrect
live entity reference (`inventoryItem`) that should have been a second value
snapshot — the SKU and name `order` actually needs, copied in, not joined
to. Smell 1 from Chapter 9 is, in tactical-DDD terms, an aggregate boundary
that leaked, one field at a time, because nobody drew the line between "data
this aggregate owns a copy of" and "a reference to someone else's aggregate"
consistently across the whole class.

**Domain events** are tactical DDD's mechanism for aggregates in different
contexts to react to each other's state changes without holding references
to each other at all. `common/Topics.java` already names three —
`order.placed`, `payment.captured`, `shipment.dispatched` — as reserved
constants nothing in the monolith publishes to yet, specifically so the
event-driven extractions later in this book can adopt this vocabulary
unchanged. Today, `OrderService#placeOrder` fakes the effect those events
should have by calling `paymentService.charge(...)` and
`shippingService.dispatch(...)` directly, as ordinary synchronous method
calls, inside the same transaction — which is Smell 3's one-ACID-transaction
problem and Smell 4's synchronous-notification problem, both restated: a
domain event is precisely what should exist in place of each of those direct
calls, published once `Order` has confirmed, consumed independently by
whichever context reacts to it. Chapters 17, 23, and 24 build that
replacement.

And **repositories** carry one rule tactical DDD is explicit about that the
monolith also breaks in the same place: a repository belongs to exactly one
aggregate, and only that aggregate's own context should call it directly.
`InventoryRepository` is reached not just by `InventoryService` but, via
`InventoryService.findBySkuOrThrow`, effectively by `OrderService` as well —
a repository access crossing a context boundary is the same violation as the
missing ACL, viewed from the data-access side rather than the domain-model
side. These are not three separate problems with three separate causes; they
are one absent seam, visible from three different tactical-DDD angles at
once.

## Hexagonal architecture: ports that make a seam cheap to cut

Strategic and tactical DDD tell you *where* a boundary should be and *what*
should live on each side of it. **Hexagonal architecture** (ports and
adapters) is the structural discipline that determines how expensive it is
to actually act on that decision once you've made it. A domain core sits in
the middle, holding entities, aggregates, and business logic, and depends on
nothing — no web framework, no ORM, no message broker. It declares **ports**:
inbound ports describing what it can be asked to do, outbound ports
describing what it needs from the world. **Driving adapters** — a REST
controller, a gRPC service, a message consumer — translate an incoming
protocol into a call on an inbound port. **Driven adapters** — a database
repository, an event publisher — implement the outbound ports the core
declared. Every dependency arrow points inward, toward the core; the core
never imports a protocol, and a protocol can be swapped without the core
noticing.

Chapter 8 already told you that the monolith does not have this
structure: "there are no ports, no interfaces separating a domain core from
its Spring Data implementation, and a controller's request type flows
straight through to a JPA entity's association graph." That absence is not
incidental — it's deliberate, so that this chapter's introduction of
ports-and-adapters discipline has a real gap to fill rather than a strawman
nobody would actually build. `OrderService` is the sharpest example: it is
constructor-injected not just with its own repository but with
`InventoryService`, `PaymentService`, `ShippingService`, and
`NotificationService` directly — four other contexts' concrete
implementations, not four ports. Drawing a Java interface around
`OrderService` today wouldn't buy you an extraction boundary; the interface
would just have to declare the same five-context fan-out the class already
has, because hexagonal architecture makes a *clean* boundary cheap to cut —
it has no opinion on a boundary that was never drawn in the first place. That
is exactly why Chapters 16, 17, 19, 23, and 24 each have to remove one of
`OrderService`'s direct edges before Chapter 26 can extract order at all:
ports-and-adapters discipline is a force multiplier on a boundary decision
already made, not a substitute for making one.

## Why Review could leave first: ports, measured in a real diff

This chapter doesn't have to argue hexagonal's payoff only in the abstract,
because this book already produced the contrast. `examples/02-review-service`
is the Review context after its full two-phase migration (Chapter 7, Chapter
15): Phase A lifted it onto Quarkus nearly unchanged via the Quarkiverse
Spring-compatibility extensions, and Phase B refactored it to idiomatic
Quarkus — Spring Data JPA swapped for Panache, Spring MVC swapped for
Jakarta REST — without the equivalence suite ever catching a behavioral
regression in the business logic itself. Look at what changed and what
didn't.

```java
// review/ReviewService.java (Phase B, idiomatic Quarkus) — no jakarta.ws.rs
// import anywhere in this file; the business logic has no idea it is reached
// over HTTP at all.
@ApplicationScoped
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final CustomerRepository customerRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional
    public ReviewDto createReview(ReviewCreate command) {
        Customer customer = Optional.ofNullable(customerRepository.findById(command.customerId()))
                .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));
        InventoryItem item = inventoryRepository.findBySku(command.sku())
                .orElseThrow(() -> new ResourceNotFoundException("No inventory item with sku " + command.sku()));
        Review review = new Review(customer, item, command.rating(), command.comment());
        reviewRepository.persist(review);
        return toDto(review);
    }
    // ...
}
```

```java
// review/ReviewResource.java — the driving adapter: a three-method
// translation from HTTP to calls on ReviewService, nothing more.
@Path("/api/reviews")
public class ReviewResource {
    private final ReviewService service;
    @POST @RolesAllowed("CUSTOMER")
    public Response createReview(@Valid ReviewCreate command) {
        ReviewDto dto = service.createReview(command);
        return Response.created(URI.create("/api/reviews/" + dto.id())).entity(dto).build();
    }
    // getById, listBySku follow the same one-line-delegation shape
}
```

```java
// review/ReviewRepository.java — the driven adapter that changed. Phase A:
// Spring Data's JpaRepository<Review, Long>. Phase B: a Panache repository.
@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {
    public List<Review> findAllByInventoryItemSku(String sku) {
        return list("inventoryItem.sku", sku);
    }
}
```

Two things are worth being precise about, in both directions. First, the
payoff: `ReviewResource` is a driving adapter in every sense that matters —
it imports nothing `ReviewService` needs to know about, and it does nothing
but translate a JAX-RS request into a method call. `ReviewRepository` is a
driven adapter that was swapped — Spring Data's `JpaRepository` for a
Panache `PanacheRepository` — and the business logic in `ReviewService`
needed almost no change to accommodate it; the only adjustment was mechanical
(`findById` returning a nullable reference instead of an `Optional`, and
`persist` instead of `save`), not a rewrite of any invariant or decision
`ReviewService` makes. That is the entire argument for ports and adapters,
proved on a real diff instead of asserted in the abstract: when the only
things depending on a context's internals are that context's own adapters,
changing an adapter is cheap, and changing the protocol or the persistence
technology never touches the business logic at all.

Second, the caveat: `ReviewRepository` is a concrete class, not a
declared interface `ReviewService` programs against — this is not textbook
hexagonal with an explicit `Port` interface the domain owns and an adapter
implementing it elsewhere. What made Review cheap to extract wasn't a
perfectly drawn hexagon; it was something more modest and, for this book's
purposes, more important: `ReviewService` had **zero outbound edges to any
other bounded context**. It reaches its own `CustomerRepository` and
`InventoryRepository` for read-only lookups against the same shared schema
`order` also uses, but it never calls `OrderService`, `PaymentService`,
`ShippingService`, or `NotificationService` — Review has no synchronous
dependency on anything else in the monolith, a property Chapter 9 named as
the sixth, already-cured smell. A context with no outbound edges to its
siblings is cheap to extract almost regardless of how disciplined its
internal ports are, because there is nothing on the other side of the cut
that has to be renegotiated. `OrderService`, by contrast, could have the most
textbook-perfect port interfaces imaginable around its own aggregate and
would still be the hardest extraction in this book, because the thing making
it hard was never the shape of its internal wiring — it's the four live
edges reaching into contexts it doesn't own.

## The extraction order is a prediction, not a preference

Put strategic DDD's subdomain classification, context mapping's relationship
audit, and hexagonal architecture's edge-counting together, and the
extraction order in `build-plan.md` Section E stops looking like a
pedagogical convenience and starts looking like something closer to a
forecast. Review goes first because it is a generic subdomain with zero
outbound edges — nothing to renegotiate, nothing to make asynchronous,
nothing to backfill. Notification goes second because it has exactly one
inbound edge (order calls it) and zero outbound edges of its own; turning
that one edge from a synchronous call into an outbox-published event
(Chapter 17) is the only renegotiation required, and it only has to happen
once. Inventory is harder, because its context-mapping relationship with
order isn't just synchronous — it's actually broken, a conformist
relationship masquerading as a shared kernel through one schema — so its
extraction (Chapter 19) has to repair the relationship itself via CDC
backfill before a clean boundary can even exist to extract across. Payment
and shipping (Chapters 23 and 24) are each reached as a synchronous call
inside `placeOrder`'s single transaction, and extracting either one forces
the ACID-to-ACD shift Chapter 22 names and the saga machinery Chapters 23
and 24 build — domain events, for the first time in this book, doing the
coordinating work tactical DDD always said they should. And order — the core
subdomain, the god aggregate, the context with four live outbound edges and
the most invariants worth protecting — goes last (Chapter 26), not as a
narrative flourish but because strategic DDD's own logic makes it true:
the context with the most relationships to everyone else cannot be cleanly
cut until its neighbors already have standalone, negotiated boundaries of
their own to be cut *against*.

That is the method this chapter hands you, and it generalizes past this
one monolith. Classify each area of the system by value and rate of change.
Draw a context map and ask, for every integration point, which of the
possible relationships actually exists today and whether anyone chose it on
purpose. Check what each aggregate holds — a value snapshot of what it needs,
or a live reference to something it doesn't own. Count a service's outbound
edges before you judge how hard it will be to cut free. None of those steps
require inside knowledge of this book's specific domain; they are a
repeatable audit you can run against any system, including one that never
shipped a `SMELLS.md` file telling you where to look.

## What you learned

- Strategic DDD's subdomain classification (core/supporting/generic) predicts
  this book's own extraction order before the roadmap ever states it: order
  is core and extracted last; inventory, payment, and shipping are
  supporting; notification and review are generic and extracted first.
- A bounded context's defining property — a private model, a public contract
  — is exactly what the monolith's shared `common` package and single
  Postgres schema erase; context mapping gives the resulting, undecided
  relationship a name (shared kernel with no edges, conformism instead of a
  negotiated customer/supplier contract) instead of leaving it undiagnosed.
- Smell 5's missing anti-corruption layer is precisely a context-mapping
  decision nobody made: `order` should be inventory's customer, negotiating
  a stable contract; instead it conforms wholesale to inventory's live JPA
  entity, because that was the cheapest thing available, not because anyone
  chose it.
- Tactical DDD's aggregate, value-object, and repository rules each flag the
  same leak from a different angle: `OrderItem` holding a live
  `InventoryItem` reference instead of a value snapshot, domain events
  (`order.placed`, `payment.captured`) reserved but unpublished, and
  `InventoryRepository` reached across a context boundary it doesn't belong
  to.
- Hexagonal architecture doesn't create a clean boundary; it makes one that
  already exists cheap to cut. Review's extraction proves the payoff on a
  real diff — `ReviewService` never imported a protocol, and its repository
  was swapped from Spring Data to Panache with no change to business logic —
  precisely because Review had zero outbound edges to any other context, the
  one property that mattered more than any port interface's literal shape.

Chapter 12, "Event Storming the Monolith," takes a complementary approach to
everything this chapter just did by classification: instead of reading code
and sorting it into subdomains, it discovers seams from *behavior* — walking
the business process as a sequence of facts and decisions — and produces the
decomposition backlog the rest of Part 4 and Part 5 draw from. Chapter 16
then builds the concrete fix this chapter only named: a Camel message
translator and content enricher sitting at exactly the order↔inventory seam,
turning the conformist relationship diagnosed here into the customer/supplier
contract it should have been from the start.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every code excerpt in this chapter is quoted verbatim from
`examples/00-monolith/` and `examples/02-review-service/` as both exist in
the repository today, and the DDD/hexagonal classifications applied to them
(subdomain tiers, context-mapping relationships, aggregate-boundary
analysis) are this chapter's own reasoning about that real code, not a
separate claim requiring its own test run. What remains unverified is
forward-looking: that Chapter 16's anti-corruption layer actually resolves
the order↔inventory relationship the way this chapter predicts, and that the
extraction-order forecast in the closing section holds up once Chapters 17,
19, 23, 24, and 26 are actually built — both confirmable only as those
chapters land and their equivalence-gate runs go green.*
