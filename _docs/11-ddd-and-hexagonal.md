---
title: "Strategic & Tactical DDD + Hexagonal"
order: 11
part: "Finding the Seams"
description: "Subdomains, bounded contexts, context mapping, aggregates and domain events, and why ports and adapters make a decided boundary cheap to cut."
---

## Learning objectives

By the end of this chapter you can:

- Classify a subdomain as core, supporting or generic and predict the extraction order from that classification.
- Explain why a shared `common` package and a single Postgres schema break a bounded context's defining property: a private model and a public contract.
- Use context-mapping vocabulary (shared kernel, customer/supplier, conformist, anti-corruption layer) to diagnose the order-to-inventory relationship as an undecided conformist coupling.
- Tell a value-object snapshot from a live entity reference inside an aggregate, using `OrderItem`'s `priceCents` and its `inventoryItem`.
- Explain ports and adapters, and why they make an already-decided boundary cheap to cut without deciding where it goes.
- Read Review's Phase A/B diff and explain why zero outbound edges to sibling contexts, not a textbook port interface, made its extraction cheap.
- Reconstruct the extraction order (review, notification, inventory, payment and shipping, order) from subdomain classification, context mapping and outbound-edge counting alone.

## Why this matters

"Seam" means a line where a conceptual boundary already exists but the implementation welds it shut. Finding a seam in a system with no `SMELLS.md` pointing at it takes vocabulary. Domain-Driven Design (DDD) decides where a system should be cut, and hexagonal architecture makes a decided cut cheap to execute. Every smell in the monolith is a DDD concept with its name filed off.

The monolith code quoted here is `examples/00-monolith/` as designed, on the `reference/monolith-before` branch (`git checkout reference/monolith-before`); on `main` the extractions have deleted its context packages. The contrast is `examples/02-review-service/` on `main`.

## Strategic DDD: naming the lines that already exist

A **subdomain** is classified by competitive value and rate of change:

| Class | Meaning | Build stance |
|---|---|---|
| Core | Where the business differentiates; complex and strategic | Best engineering attention |
| Supporting | Necessary, not a differentiator | Build simply |
| Generic | Solved elsewhere in a well-known shape | Adopt an off-the-shelf answer |

Against the monolith's six contexts: **order** is core, the checkout aggregate customers notice and every other context serves. **Inventory**, **payment** and **shipping** are supporting: table stakes for e-commerce. **Notification** and **review** are generic.

That classification predicts the extraction order. The two generic subdomains, notification and review, change rarely, have few invariants and carry the least organizational risk, so they go first. Order, the core, is the opposite on every axis and goes last, once the extraction toolkit is proven on lower-stakes ground.

Each subdomain becomes one or more **bounded contexts**: the scope in which one model and one **ubiquitous language** stay consistent, and outside which a word may mean something else. A context owns its model privately and exposes only a public contract, so two contexts may hold different ideas of an "item." The monolith works against that. `order`, `inventory`, `payment`, `shipping` and `notification` share one `common` package of DTOs and enums, and one Postgres schema whose tables any context's JPA mappings can join against. That is one context wearing five package names, and the root condition of every smell.

## Context mapping: the relationship nobody decided

**Context mapping** gives the relationship between two contexts a fixed vocabulary instead of whatever the cheapest line of code produced.

- **Shared kernel:** a narrow slice of model two contexts agree to share, with coordinated changes. The monolith has the entire database shared by all six contexts with no governance. Nobody decided this; a foreign key is simply the cheapest way to relate two rows in one schema.
- **Customer/supplier:** the downstream context consumes the upstream's data through a stable, versioned contract (a DTO or event schema) that the downstream side has enough weight to shape. This is what order and inventory should have.
- **Conformist:** the downstream accepts the upstream's internal model wholesale. This is what they have:

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
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity());
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

Conformism is legitimate when the upstream is clean, stable and outside your influence, such as a vendor API you have no leverage over. Here `order` and `inventory` are siblings in one codebase, heading to separate deployables that could negotiate. `InventoryService` already builds a `StockDto` for its own REST controller and doesn't hand it to `OrderService`. Conformism chosen with eyes open is a strategy; conformism that happens because the live entity was sitting right there is a smell. What's missing is an **anti-corruption layer (ACL)**, the translation Evans prescribes for a downstream context that must protect its model from an upstream's internals. It would sit at this seam and hand `OrderService` an inventory-owned contract instead of the live JPA entity. A Camel message translator and content enricher implement it in the content-based routing and ACL work, turning the conformist coupling into the customer/supplier contract it should have been.

The gap is specific: nobody decided what the order-to-inventory relationship should be. No contract was drafted and no map was drawn; the relationship is whatever the cheapest implementation in a shared JVM and schema produced, a direct object reference. Context mapping's value is not the four words but the discipline of making that decision explicitly for every seam.

{% include excalidraw.html file="context-map" alt="A context map of the monolith's six bounded contexts: order's edge into inventory is conformist with no anti-corruption layer, though inventory's StockDto already exists unused; order's edges into payment, shipping and notification are direct synchronous calls in one transaction with no port; review has zero outbound edges; all six sit on one Postgres schema, a shared kernel with no governed edges." caption="Figure 11.1: Context map: six bounded contexts, one relationship nobody decided" %}

## Tactical DDD: what lives inside a seam

An **aggregate** is a cluster of objects treated as one consistency unit: the boundary inside which invariants hold together and a single transaction commits. `Order` and its `OrderItem`s are the clearest aggregate. `Order#addItem` appends an item and updates `totalCents` together inside the root, so no code path yields an order whose total disagrees with its items.

The same `OrderItem` breaks the sibling rule. An aggregate should reference other aggregates by identity or a small copied value, never by a live reference to another root, because that silently extends the consistency boundary into a context you don't own. `OrderItem` holds exactly that reference:

```java
// order/OrderItem.java: the SMELL[ch.18] join
@ManyToOne(optional = false)
@JoinColumn(name = "inventory_item_id", nullable = false)
private InventoryItem inventoryItem;
```

In the checkout flow, `inventoryItem.getPriceCents()` is captured once at order time and stored as `OrderItem`'s own `priceCents`. That is a **value object**: an immutable fact about this line at this moment, with no identity or lifecycle. `InventoryItem` is a legitimate **entity**, with identity and a lifecycle (`decrement`, `restock`). Neither type is wrong alone. The defect is one correct snapshot (`priceCents`) next to a live reference (`inventoryItem`) that should be a second snapshot: the SKU and name `order` needs, copied in, not joined to. The shared-schema smell is, in tactical terms, an aggregate boundary that leaked one field at a time.

**Domain events** let aggregates in different contexts react to each other without holding references. `common/Topics.java` reserves three, `order.placed`, `payment.captured` and `shipment.dispatched`, and nothing publishes them yet. Today `OrderService#placeOrder` calls `paymentService.charge(...)` and `shippingService.dispatch(...)` as synchronous methods inside one transaction. That is the one-transaction smell and the synchronous-notification smell restated. A domain event published once `Order` is confirmed, consumed independently by whichever context reacts, should replace each of those calls; the outbox and saga extractions build that replacement.

A repository belongs to exactly one aggregate and only that context should call it. `InventoryRepository` is reached by `InventoryService` and, through `findBySkuOrThrow`, effectively by `OrderService`. That is the missing ACL again, seen from the data-access side. The conformist coupling, the leaked aggregate boundary and the cross-context repository are one absent seam seen from three angles.

## Pattern: Hexagonal Architecture

Hexagonal architecture (ports and adapters) sets how expensive it is to act on a boundary decision. A domain core holding entities, aggregates and business logic depends on nothing: no web framework, ORM or broker. It declares **ports**: inbound ports for what it can be asked to do, outbound ports for what it needs. **Driving adapters** (REST controller, gRPC service, message consumer) translate a protocol into an inbound-port call. **Driven adapters** (repository, event publisher) implement the outbound ports. Every dependency points inward, so a protocol can be swapped without the core noticing.

The monolith has no such structure: no interfaces separate a domain core from its Spring Data implementation, and a controller's request type flows to a JPA association graph. `OrderService` is constructor-injected with `InventoryService`, `PaymentService`, `ShippingService` and `NotificationService`: four other contexts' concrete classes, not four ports. An interface around it would only declare the same five-context fan-out. Ports make a clean boundary cheap to cut and have no opinion on a boundary never drawn. That is why each direct edge out of `OrderService` has to be removed before Order itself can be extracted.

{% include excalidraw.html file="hexagonal-ports-adapters" alt="A hexagonal-architecture diagram: a domain core at center with no dependency on any web framework, ORM or broker; driving adapters (REST controller, gRPC service, message consumer) each depending inward on an inbound port; driven adapters (database repository, event publisher) each depending inward on an outbound port the core declared." caption="Figure 11.2: Hexagonal architecture: ports and adapters, dependencies pointing inward" %}

## Why Review could leave first

`examples/02-review-service` is Review after both migration phases. Phase A lifted it onto Quarkus nearly unchanged through the Quarkiverse Spring-compatibility extensions. Phase B made it idiomatic: Spring Data JPA became Panache and Spring MVC became Jakarta REST. The equivalence suite caught no behavioral regression in the business logic.

```java
// review/ReviewService.java (Phase B, idiomatic Quarkus). No jakarta.ws.rs
// import anywhere in this file; the business logic doesn't know it is
// reached over HTTP.
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
// review/ReviewResource.java: the driving adapter. A translation from HTTP
// to calls on ReviewService, nothing more.
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
// review/ReviewRepository.java: the driven adapter that changed.
// Phase A: Spring Data's JpaRepository<Review, Long>. Phase B: a Panache repository.
@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {
    public List<Review> findAllByInventoryItemSku(String sku) {
        return list("inventoryItem.sku", sku);
    }
}
```

`ReviewResource` only translates a JAX-RS request into a method call. `ReviewRepository` was swapped from `JpaRepository` to `PanacheRepository`, and `ReviewService` changed only mechanically (`findById` returns a nullable reference instead of an `Optional`, and `persist` replaces `save`). When only a context's own adapters depend on its internals, changing the protocol or persistence technology never touches the business logic.

The caveat: `ReviewRepository` is a concrete class, not an interface the service programs against. This isn't textbook hexagonal. What made Review cheap was that `ReviewService` had **zero outbound edges to any other bounded context**. It reads `CustomerRepository` and `InventoryRepository` against the shared schema, but never calls `OrderService`, `PaymentService`, `ShippingService` or `NotificationService`. With nothing on the other side of the cut to renegotiate, extraction is cheap almost regardless of internal ports. `OrderService` could wear perfect port interfaces and still be the hardest extraction, because its four live edges into contexts it doesn't own are what make it hard.

## The extraction order is a prediction

Combine the subdomain classification, the context-map audit and edge counting, and the extraction order becomes a forecast:

| Context | Position | Why |
|---|---|---|
| Review | First | Generic, zero outbound edges: nothing to renegotiate |
| Notification | Second | One inbound edge (order calls it), none outbound; one synchronous call becomes an outbox-published event |
| Inventory | Third | The order relationship is a conformist coupling disguised as a shared kernel; extraction must repair it with a CDC backfill first |
| Payment, shipping | Fourth | Each is a synchronous call inside `placeOrder`'s single transaction; extraction forces the shift from ACID to ACD and sagas, with domain events coordinating |
| Order | Last | Core, four live outbound edges, the most invariants; it can't be cut until its neighbors have negotiated boundaries to be cut against |

The method generalizes. Classify each area by value and rate of change. Draw a context map and ask, for each integration point, which relationship exists and whether anyone chose it. Check whether each aggregate holds a value snapshot or a live reference to something it doesn't own. Count a service's outbound edges before judging how hard it is to cut.

> **Decision**
>
> Cut a context first when it is generic and has zero outbound edges. Before cutting any seam, name its context-map relationship; if nobody chose it, choose it, and put an ACL where the downstream would otherwise conform. Don't add port interfaces around a context whose boundary isn't decided.

> **🤖 Explore with your agent**
>
> "On the `reference/monolith-before` branch, in examples/00-monolith, count the outbound edges (injected services from other bounded contexts) of OrderService, InventoryService, PaymentService, ShippingService, NotificationService and the review package. Classify the order-to-inventory relationship as shared kernel, customer/supplier or conformist, and propose replacing the `InventoryItem` reference in OrderItem with a copied sku and name, without changing any code until I approve."
>
> Check: the reply reports four outbound edges for OrderService (inventory, payment, shipping, notification services), none for the review package (it reads `InventoryRepository` and `CustomerRepository` but calls no other context's service), and calls order-to-inventory conformist.

## For deeper coverage

- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): subdomains, bounded contexts, context mapping and the anti-corruption layer; see the [Reading List](/bibliography/).
- Sam Newman, *Building Microservices* (2nd ed., O'Reilly, 2021): service boundaries and integration styles, the operational counterpart to DDD's conceptual boundaries.
- Cloud Native Design Patterns, [Appendix F: DDD & Hexagonal Architecture](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/19-appendix-f-ddd-hexagonal.md): the companion book's treatment of ports and adapters.
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the message translator and content enricher used to build the missing anti-corruption layer.
