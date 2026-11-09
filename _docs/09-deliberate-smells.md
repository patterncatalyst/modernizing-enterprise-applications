---
title: "The Deliberate Smells"
order: 9
part: "The Reference Monolith"
description: "The coupling, god-service, shared-table, and transaction-scope smells planted in the reference monolith, each tagged to the pattern that will later cure it."
---

## Learning objectives

By the end of this chapter you can:

- Locate every `SMELL[ch.NN]` tag with `grep -rn 'SMELL\[ch\.' examples/00-monolith/src` and map each one to the extraction that cures it.
- Explain why a seam is a conceptual boundary the implementation currently welds shut, not an arbitrary place where code could be split.
- Diagnose shared-schema cross-context foreign-key coupling and why it stays invisible until another team's migration breaks it.
- Diagnose the god `OrderService` by counting constructor dependencies and applying the "one sentence without 'and'" heuristic.
- Explain why one `@Transactional` boundary spanning five contexts is correct only because of co-location, and what must replace it once the contexts get separate databases.
- Diagnose the synchronous-notification and missing-anti-corruption-layer smells and name the pattern (transactional outbox, message translator) that cures each.
- Use Review's already-cured sixth smell as evidence that "tag it, name the cure, cut it with an equivalence check watching" works on a real codebase.

## Why this matters

Six coupling decisions in the monolith are planted flaws, like a known weak point in a demonstration beam. One of the six, Review's entanglement in a shared security filter chain despite having no runtime dependency on anything else, has been cured by the Review extraction. Each of the other five has an extraction that cuts along it.

Every excerpt below is quoted from `examples/00-monolith/` as designed, on the `reference/monolith-before` branch, and carries an in-code `SMELL[ch.NN]` tag. On `main` the extractions deleted the context packages. The grep output is the authoritative index, and `examples/00-monolith/SMELLS.md` is the same index in table form, covering all six smells and their cures.

```sh
git checkout reference/monolith-before
grep -rn 'SMELL\[ch\.' examples/00-monolith/src
```

## A seam is a line the system already wants to be cut along

You can split code almost anywhere by inserting an interface. A **seam** is a place where splitting follows a boundary that already exists conceptually while the implementation straddles it. Order, inventory, payment, shipping and notification have different reasons to change, different owners and different data lifecycles, yet share one schema, one transaction manager and one JVM. Each planted smell is the mechanism that welds a seam shut: a foreign key where there should be a replicated field, a direct method call where there should be a published event, one shared transaction where there should be five independently committable ones.

{% include excalidraw.html file="smell-map" alt="All six planted smells overlaid on the monolith's six-context structure: the shared schema, the god OrderService, the one-transaction checkout, the synchronous notification call, the missing anti-corruption layer between order and inventory, and review's already-cured tangled security, each annotated with the extraction that cures it." caption="Figure 9.1: The six planted smells, mapped onto the monolith's structure" %}

A smell here is not a bug to fix. Fixing a bug makes wrong code right in place; cutting a seam turns one thing into two, each independently deployable, scalable and owned. Ask of each smell what boundary it welds shut and what has to exist on each side to unweld it.

## Smell 1: shared schema and cross-context foreign-key joins

**The pattern.** Each bounded context should own its data exclusively, and nothing else reads or writes it except through the context's published contract. The opposite, common in monoliths that grew organically, is a **shared schema**: one database whose ordinary foreign keys connect tables belonging to different contexts, because a foreign key is the cheapest way to say two rows are related when everything lives in one database anyway.

**Where it lives.** `V1__init_schema.sql` under `db/migration` is the root: one Flyway migration, one Postgres schema, every table. It surfaces as JPA `@ManyToOne` associations wherever one context references another's row, as in `order.OrderItem`:

```java
// order/OrderItem.java
/**
 * SMELL[ch.18]: OrderItem holds a direct JPA @ManyToOne FK/join
 * onto inventory.InventoryItem. An order-context table references an
 * inventory-context table in the one shared schema. Once inventory owns its own
 * database, this join is replaced by a denormalized copy of the fields the
 * order context actually needs (sku, name, price-at-time-of-order), kept current
 * via CDC.
 */
@ManyToOne(optional = false)
@JoinColumn(name = "inventory_item_id", nullable = false)
private InventoryItem inventoryItem;
```

`order.Order` joins `common.Customer`, `payment.Payment` and `shipping.Shipment` hold FKs into `orders`, and `notification.Notification` joins both `customers` and `orders`.

**Why it's a liability.** A foreign key is a promise the database enforces: this row cannot exist, or be deleted, without that row. That promise is what blocks independent deployability. If `inventory` moves to its own database, Postgres can no longer enforce `order_items.inventory_item_id -> inventory_items.id`. Either the constraint is dropped, with every guarantee the application silently leaned on, or the two contexts stay on one database forever and were never independent. The coupling is also invisible from either context's code: `OrderService` does not declare a dependency on inventory's schema, the database does. A migration that drops a column inventory no longer needs breaks order's queries three deploys later, in a different team's incident.

**How to recognize it.** Grep your migrations for foreign keys that cross an ownership line: the team that owns the referencing table differs from the team that owns the referenced one, and the database enforces the join instead of an API call. If "who owns this table?" gets the answer "several things write to it", nobody owns it.

**Who cuts it.** The shared-data-to-owned-data work stops each context reading another's tables and has it hold only the fields it needs, kept current by replication instead of a join. Change-data-capture (CDC) backfill from the shared schema into each newly owned database gets there without a stop-the-world cutover, exercised on the inventory extraction.

## Smell 2: the god `OrderService`

**The pattern.** A god service is one component that orchestrates work belonging to many areas. It forms by accretion: each feature that touches two contexts adds a method and a dependency to whichever service is closest.

**Where it lives.** `order/OrderService.java`, tagged on the class:

```java
/**
 * SMELL[ch.26]: this is the "god service". It is the single orchestration point
 * for checkout and it reaches directly into inventory, payment, shipping, and
 * notification's services/repositories/entities instead of those contexts being
 * independently deployable collaborators reached over a stable contract. Order is
 * deliberately the HARDEST and LAST extraction in the roadmap precisely
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

Count the constructor: a repository for its own aggregate, a repository for a shared-kernel entity it does not own, and four other contexts' services injected directly. `placeOrder` calls `inventoryService.reserve(...)`, `paymentService.charge(...)`, `shippingService.dispatch(...)` and `notificationService.sendOrderConfirmation(...)` as ordinary method calls on live Spring beans, in one process, on one call stack.

**Why it's a liability.** Each of those four dependencies reaches across a context boundary by the cheapest mechanism, a direct reference, instead of a contract the callee publishes. `OrderService` cannot be deployed or scaled independently of the four contexts, and changing `PaymentService`'s signature is today an `OrderService` compile error, which forces coordinated releases. And `OrderService` cannot be extracted until every one of those edges has stopped being an in-process call. That is why order is the hardest and last extraction: the five earlier ones (review, notification, inventory, payment, shipping) each remove one of the god service's direct edges, so order can be cut free without dragging four other contexts along.

**How to recognize it.** Count constructor parameters or injected fields per `@Service`. One well above the median, with dependencies spanning different business capabilities, is a candidate. The behavioral tell: describe its responsibility in one sentence without "and". `OrderService` places an order by checking stock, persisting the order, charging payment, dispatching shipment, *and* sending a notification: five verbs, five contexts, one class.

**Who cuts it.** The order extraction itself, last, once the earlier extractions have removed its edges to inventory, notification, payment and shipping. The god service is also the strongest argument for sagas (Smell 3): one synchronous call chain can orchestrate five contexts only if something holds open an equally large failure domain.

## Smell 3: one ACID transaction spanning five bounded contexts

**The pattern.** A transaction commits all its writes or none. "If payment fails, roll the inventory reservation back too" holds today only as a side effect of one transaction against one database. The smell is depending on that side effect as if it were a designed distributed-transaction mechanism, when it is only how far one `@Transactional` annotation reaches.

**Where it lives.** `order/OrderService.java#placeOrder`, the method behind Smell 2:

```java
/**
 * SMELL[ch.22]: one in-process ACID @Transactional spans FIVE bounded
 * contexts: order, inventory, payment, shipping, notification. It "works"
 * today because Postgres gives us atomic rollback for free across all of them.
 * The moment any one of these becomes its own service with its own database,
 * this rollback-everything behavior disappears and has to be rebuilt
 * explicitly as a saga with compensating actions (choreographed or orchestrated). That is the ACID -> ACD story.
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

`PaymentDeclinedException`'s Javadoc names the mechanism: "payment + shipment + notification in ONE `@Transactional`" rolls back "inventory decrement + order persistence" too when the gateway declines a card.

**Why it's a liability.** Checkout works across all five contexts in one transaction, and that is the trap: it works because one database is the mechanism, not because anyone designed a correctness protocol. Once inventory, payment, shipping or notification gets its own database, Postgres can no longer roll back writes it does not hold, and "a payment decline undoes the inventory reservation" stops being true unless something rebuilds it. That something is a saga: a sequence of local transactions, each committed independently, each paired with a compensating action that undoes its effect if a later step fails. Sagas are harder because the system must tolerate intermediate states (stock reserved, no payment yet) the database currently never exposes. This is the ACID-to-ACD shift: atomicity, consistency and durability survive distribution, isolation does not, and the gap is where saga choreography and orchestration go.

**How to recognize it.** Look for `@Transactional` boundaries whose body calls into more than one bounded context's repository or service. Then ask: if the third write failed right now, what is the correct behavior, and does the code rely on the database to produce it? If yes, cross-context rollback rests on co-location.

**Who cuts it.** Two concrete mechanisms. A choreographed saga: payment reacts to an `order.placed` event and emits `payment.captured` or a compensating failure event, with no orchestrator. An orchestrated saga: the shipping extraction uses the Camel Saga EIP to drive the sequence from one coordinating definition instead of peer-to-peer events.

## Smell 4: synchronous notification inside the checkout transaction

**The pattern.** Not every piece of work a transaction touches belongs to its atomicity guarantee. A confirmation email is an example: the order is valid whether or not the send succeeds, arrives promptly or arrives at all. The smell is coupling that orthogonal concern's latency and failure mode to the transaction because a synchronous in-process call was the path of least resistance.

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
 * ever threw, it would roll back the whole order along with it. The
 * notification extraction replaces this with a transactional outbox + an
 * async event-driven consumer, decoupling both latency and failure domains.
 */
public Notification sendOrderConfirmation(Customer customer, Order order) {
    String message = "Order #%d confirmed, total $%.2f".formatted(order.getId(), order.getTotalCents() / 100.0);
    return repository.save(new Notification(customer, order, "EMAIL", message));
}
```

`common/Topics.java` declares `order.placed`, `payment.captured` and `shipment.dispatched` as reserved topic names the monolith does not yet publish to, so the event-driven extractions can adopt them without the monolith carrying messaging infrastructure it does not use.

**Why it's a liability.** This is a narrower version of Smell 3's problem with its own named fix. If `sendOrderConfirmation` were slow, say a flaky email provider, every checkout would be slow, because the caller is blocked in the transaction on a call it did not need to wait for. If it threw, the whole order would roll back over a notification failure, after the customer's payment was already captured. That is worse than "order confirmed, notification will retry", even in one process, and untenable once notification sits behind a network where slow and down are routine.

**How to recognize it.** For each call inside a transactional method, ask whether you would want the whole transaction to fail or hang for ten seconds because that call did. If not, it reacts to a fact already established. Typical candidates: notifications, audit logging, analytics events, cache invalidation.

**Who cuts it.** The notification extraction replaces the direct call with a **transactional outbox**. The checkout transaction writes an outbox row in the commit it already makes, so there is no new atomicity problem, and a separate asynchronous consumer reads the outbox and sends the notification. It needs a stable event contract and a translation step at the seam, which is Smell 5.

## Smell 5: no anti-corruption layer; a raw JPA entity leaks across a seam

**The pattern.** When one context needs another's data, the producer should publish a stable contract (a DTO, an event schema, an API response shape) that it controls and evolves independently of its internal representation. The anti-pattern hands the caller the producer's persistence entity, with its lazy loading, its column names posing as field names and its migrations, which silently become the caller's problem.

**Where it lives.** `inventory/InventoryService.java#findBySkuOrThrow`, called by `order/OrderService.java#placeOrder`:

```java
// inventory/InventoryService.java
/**
 * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
 * (order.OrderService) outside this context. There is no anti-corruption
 * layer at this seam. The ACL work introduces a Camel content enricher / message
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

`InventoryItem` is an `@Entity` managed by inventory's own `EntityManager` and mapped to `inventory_items`. `OrderService` does not receive a `StockDto`, the contract type `InventoryService` already builds for its own REST controller. It receives the live entity and stores a reference to it inside `OrderItem`, the object-graph version of Smell 1's FK join.

**Why it's a liability.** This smell makes the other four worse by removing the layer that would contain the damage. An anti-corruption layer (ACL) is a Camel message translator, a content enricher, a hand-written mapping method, or anything at the seam that converts one side's model into the other side's contract. It lets inventory rename a column, split a table or change persistence technology without `OrderService` noticing, because `OrderService` was never handed the internal shape. Without it, every internal change to `InventoryItem` is a potential silent breaking change to `OrderService`, with no compiler error, because Java lets a `@Service` in one package return an `@Entity` from another. The raw entity reference is also the medium Smell 1's FK join and Smell 2's direct call travel through, which is why both get written without anyone noticing.

**How to recognize it.** Trace the type that crosses a boundary at each call site. If it is a persistence entity instead of a data-transfer type the producer built for others to read, that is this smell, synchronous or not. An event payload that serializes an entity instead of a versioned schema has the same problem.

**Who cuts it.** Content-based routing plus an ACL: a Camel message translator or content enricher at this seam, with inventory publishing and `OrderService` consuming `StockDto`, the contract type that already existed unused a few lines above the leak. It comes before the notification extraction because the outbox needs a stable event contract.

## Evidence the method works: Review, already cured

The sixth smell in `SMELLS.md`, Review tangled into the monolith's one global `SecurityFilterChain` despite no runtime dependency on any other context, is marked **CURED**, not planned. The Review extraction's Map phase confirmed that Review was independent before committing to cut it first, routed `/api/reviews` by URI through the Camel strangler proxy, lifted it to Quarkus in two phases, and re-ran the Newman equivalence collection captured against the monolith unchanged against the extracted service. That run caught two bugs: a native-image reflection gap the JVM build never exposed, and a routing predicate that silently sent every request to the monolith regardless of the feature flag. The second was found only because two backends sharing the `reviews` table made a passing collection insufficient evidence.

`security/SecurityConfig.java` on `main` still carries its `SMELL[ch.15]` tag, harmlessly vacuous, because Review's controller, service, repository and entity are gone from the module. The monolith now returns 404 where Review used to answer. Identify the seam, tag the mechanism, name the cure, cut with an equivalence check watching: that sequence has already produced one extraction.

> **Decision**
>
> Cut only along a seam, and order the cuts so each earlier extraction removes one of the god service's direct edges; the god service goes last.

> **🤖 Explore with your agent**
>
> "On the `reference/monolith-before` branch, run `grep -rn 'SMELL\[ch\.' examples/00-monolith/src` and read `examples/00-monolith/SMELLS.md`. For each of the six smells name the file it lives in, the seam it welds shut and the extraction that cuts it. Then count the constructor dependencies of `order/OrderService.java`, say which belong to other contexts, and order the extractions so the order service goes last."
>
> Check: the reply cites `V1__init_schema.sql`, `security/SecurityConfig.java` and `inventory/InventoryService.java`, counts six constructor dependencies of which four (inventory, payment, shipping, notification services) belong to other contexts, and puts the order extraction last.

## Build, run, observe

```bash
git checkout reference/monolith-before
grep -rn 'SMELL\[ch\.' examples/00-monolith/src   # every tag, all six contexts live
git checkout main
grep -rn 'SMELL\[ch\.' examples/00-monolith/src   # what survives the extractions
cat examples/00-monolith/SMELLS.md                  # all six smells and their cures
```

On `main` the grep finds only `SecurityConfig.java`'s `SMELL[ch.15]` and the comments in the applied Flyway migrations (`V1__init_schema.sql`, `V2__seed_data.sql`, `V3__outbox.sql`), which are never edited. Every other tag left with the file that carried it. Checking out the `stage/*` tags in order shows the tags disappearing one extraction at a time.

## For deeper coverage

- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): the anti-corruption-layer and bounded-context vocabulary the five smells are named with; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the message translator and content enricher that cure Smell 5.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga and transactional-outbox patterns that replace Smell 3's single ACID transaction and Smell 4's synchronous call.
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the incremental cut-and-prove method behind "tag it, name the cure, cut it later".
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): why shared-schema coupling turns dangerous the instant data ownership splits.
