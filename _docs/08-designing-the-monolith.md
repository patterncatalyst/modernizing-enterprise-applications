---
title: "Designing the Monolith"
order: 8
part: "The Reference Monolith"
description: "The six bounded contexts as one Spring Boot deployable: domain model, layered modules, single shared schema, REST API, and seed data."
---

## Learning objectives

By the end of this chapter you can:

- Name the six bounded contexts (order, inventory, payment, shipping, notification, review) and the boundary each one models.
- Read the controller-service-repository layering shared by all six contexts and explain why it has no hexagonal ports yet.
- Trace `OrderService#placeOrder` call by call and explain how the pessimistic write lock in `InventoryService#reserve` prevents overselling.
- Identify where cents-as-`long` currency, `@Enumerated(EnumType.STRING)` and a cascading `@OneToMany` with `orphanRemoval` are used, and the bug each one avoids.
- Explain why `ddl-auto: validate` plus Flyway, not Hibernate, owns the schema.
- Recognize which Spring choices (Spring MVC, Spring Data JPA, Spring Security) were made because the Quarkiverse Spring-compatibility bridge can lift them unchanged.
- Read the shared-schema ER diagram and name the cross-context foreign keys that later extractions have to cut.

## Why this matters

Before anything can be modernized it has to exist as something worth modernizing: a believable "before", a system a real team could have shipped and a customer would notice going down. That system is `examples/00-monolith/`, a plain Spring Boot application on JDK 25: one deployable JAR, one PostgreSQL schema, one JVM, a shipping and e-commerce domain split into six bounded contexts.

Every framework API in it was picked because it has a clean bridge to Quarkus. That constraint decides how fast every later extraction can start.

{% include excalidraw.html file="monolith-architecture" alt="A layered Spring Boot monolith: six bounded-context packages (order, inventory, payment, shipping, notification, review) each with a controller, service, and repository layer, all built against one shared PostgreSQL schema populated by Flyway migrations V1 and V2, exposed as one REST API surface documented by springdoc OpenAPI." caption="Figure 8.1: The reference monolith: six bounded contexts, one layered Spring Boot deployable, one shared schema" %}

## The domain: six bounded contexts, one shipping business

The monolith models a small but complete shipping business around "a customer buys a thing and it arrives at their door": **order** (the checkout aggregate and its line items), **inventory** (stock per SKU), **payment** (a charge against an order), **shipping** (dispatching a shipment to an address), **notification** (telling the customer what happened) and **review** (a customer rating a product they bought).

Each context is a Java package under `dev.patterncatalyst.monolith`. A `common` package holds the shared vocabulary of DTOs and enums (`OrderDto`, `OrderStatus`, `StockDto`, `OrderCreate`) that every context may depend on, so the same shapes travel unchanged into later extracted services. Each context stays at roughly three to nine files: an entity, a repository, a service, a controller, maybe a DTO or two.

### Where the code lives

Every class named below lives on the `reference/monolith-before` branch, the monolith as designed with all six contexts live:

```bash
git checkout reference/monolith-before
```

The `examples/00-monolith` tree on `main` is what is left after all six extractions: `MonolithApplication`, the `config` and `security` packages and the Flyway migrations, kept frozen as the "before" referent and serving nothing under `/api`. Each `stage/*` tag (`stage/01-review-extracted` through `stage/06-order-extracted`) holds the tree as it stood after one extraction.

{% include excalidraw.html file="monolith-six-contexts" alt="The monolith as designed: six in-process bounded-context modules (order, inventory, payment, shipping, notification, review) inside one Spring Boot deployable, with no process boundary, no network hop, and no per-context database between any of them." caption="Figure 8.2: Six bounded contexts, one deployable: modules, not services" %}

## Layering: controller, service, repository, six times

Every context has the same classic layered shape. `inventory` is the smallest. `InventoryController` is a thin `@RestController` that delegates to `InventoryService`. The service holds the one piece of domain logic the context has, reserving stock under a lock. `InventoryRepository` is a one-line `JpaRepository<InventoryItem, Long>` interface with two derived-query methods. `order`, `payment`, `shipping`, `notification` and `review` repeat the triad, scaled to their responsibilities: `payment` and `shipping` each have one meaningful service method (`charge`, `dispatch`), and `order` has more because checkout happens there.

This is Spring MVC over Spring Data JPA in its plainest form. `@RestController` classes use `@RequestMapping`, `@GetMapping` and `@PostMapping`. `@Service` classes take their repositories and sibling services by constructor injection. Spring Data implements the `JpaRepository` interfaces at startup from method-name conventions.

Nothing is hexagonal. There are no ports, no interfaces between a domain core and its Spring Data implementation, and in `order`'s case a request type flows straight into a JPA association graph. That absence is one of the smells in the next chapter. A hexagonal boundary is what makes an extraction mechanical, and the monolith lacks one so that introducing ports and adapters has a real gap to fill.

## Entities and DTOs

The module has eight JPA entities: the shared-kernel `Customer` plus `InventoryItem`, `Order`, `OrderItem`, `Payment`, `Shipment`, `Notification` and `Review`. `Customer`, in `common`, is documented as "not one of the six bounded contexts itself". `order`, `review` and `notification` all reference a customer, and in a single schema the cheapest way is a direct `@ManyToOne` join rather than a context-local copy. Its `SMELL[ch.18]` comment names the cost: a decomposed system would have each context own, or replicate through CDC or an anti-corruption layer, only the customer fields it needs.

The shared table outlives the code that owns it. After the Review extraction, `examples/02-review-service` still reads and writes `reviews` directly in this database, in its Phase A form and after Phase B, so the `reviews` foreign keys stay; dropping them would break a service that was just cut over. Shared state can mask a routing defect in the proxy the same way: both the old and the new path see the same rows.

Three representation choices each avoid a specific bug:

| Choice | Where | Bug it avoids |
|---|---|---|
| Price in cents as `long` | `InventoryItem`, `Order.totalCents` | Rounding drift a `double` reintroduces at every arithmetic step |
| `@Enumerated(EnumType.STRING)` | `Order`'s `OrderStatus` | An ordinal column silently changing meaning if the enum is reordered; the database stores `CONFIRMED` |
| `@OneToMany(cascade = ALL, orphanRemoval = true)` | `Order` to `OrderItem` | Saving an `Order` saves its items, and removing an item from the list deletes its row, not just the association |

`Order.addItem` appends the item, sets the owning side of the bidirectional association with `item.assignTo(this)` and increments `totalCents` by price times quantity, all in one place, so no caller can add an item and forget the total. `InventoryItem` bumps its own `updatedAt` in `decrement` and `restock`. `OrderItem` carries the monolith's most-discussed line: a `@ManyToOne` to `inventory.InventoryItem`, one context's table joined directly from another's. That is smell 1 in `SMELLS.md`. `Payment`, `Shipment` and `Notification` each hold a `@ManyToOne` back to `Order`, a status or channel field and a timestamp: three more cross-context joins. `Review` joins `Customer` and `InventoryItem` directly.

The `common` records are shaped to match the reuse-map vocabulary of the sibling example projects, so an extracted context's API contract needs no renaming. `OrderCreate` is a Java `record` with Bean Validation directly on its components: `@NotNull Long customerId`, `@NotEmpty @Valid List<Line> items`, `@NotBlank String paymentMethod`, `@NotBlank String shippingAddress`, and a nested `Line` with `@NotBlank String sku` and `@Positive int quantity`. `OrderController.placeOrder` takes `@Valid @RequestBody OrderCreate command`, so Spring validates every nested line before `OrderService` sees the request. A missing SKU or a zero quantity returns 400 from `GlobalExceptionHandler`'s `MethodArgumentNotValidException` handler. `OrderStatus` has three values (`PENDING`, `CONFIRMED`, `PAYMENT_DECLINED`); there is no partial-fulfillment state machine, because it would obscure the smells.

## The core logic: `OrderService#placeOrder`

`placeOrder` is where five of the six contexts (all but review) meet inside one Spring `@Transactional` boundary. The `SMELL[ch.NN]` comments are the tags `SMELLS.md` catalogues:

```java
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    Customer customer = customerRepository.findById(command.customerId())
            .orElseThrow(() -> new ResourceNotFoundException("No customer with id " + command.customerId()));

    Order order = new Order(customer, command.shippingAddress());

    // SMELL[ch.16]: reaching directly into inventory's entities/repository from
    // the order context, with no anti-corruption layer at the seam.
    for (OrderCreate.Line line : command.items()) {
        InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
        inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
        order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
    }

    order = orderRepository.save(order);

    // SMELL[ch.22]/[ch.26]: payment captured synchronously, in-process, inside
    // the same transaction as the inventory decrement above. A decline here
    // rolls back the inventory reservation too (see PaymentDeclinedException).
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    order.confirm();

    // SMELL[ch.26]: shipping dispatched synchronously from the order's god
    // service rather than being triggered by an event the order context emits.
    shippingService.dispatch(order, order.getShippingAddress());

    // SMELL[ch.17]: notification sent synchronously inside the checkout
    // transaction instead of via an outbox + async consumer.
    notificationService.sendOrderConfirmation(customer, order);

    return toDto(order);
}
```

An unknown customer fails fast with a `404`-mapped `ResourceNotFoundException` before any other work. In the loop, `findBySkuOrThrow` returns the `InventoryItem` entity so the order item can capture the current price, and `reserve` checks and decrements stock. `reserve` takes a pessimistic write lock (`InventoryRepository#findWithLockBySku`, annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)`). Two concurrent checkouts racing for the last unit cannot both read "1 on hand" and both commit: the second blocks until the first commits or rolls back, then re-reads the current quantity. `reserve` throws `InsufficientStockException`, mapped to `409`, as soon as the requested quantity exceeds what is on hand and before any row is written for that item.

`orderRepository.save(order)` persists the order and, through the cascade, its items. Only then does `paymentService.charge` run. Its decline rule is deterministic: any payment method string containing `DECLINE`, case-insensitively, throws `PaymentDeclinedException`, with no real gateway involved. That lets the equivalence collection exercise the decline path without flaky dependencies. The exception unwinds the entire transaction, the order row, its items and the inventory decrement, because one Postgres database gives this method atomic rollback across five tables by default. That is the "works today because one database can do this" smell, which a saga later replaces. Today it is one `@Transactional` annotation with no compensating actions.

On success, `order.confirm()` sets `CONFIRMED`, `shippingService.dispatch` creates a `Shipment` row and `notificationService.sendOrderConfirmation` writes a `Notification` row, all synchronous calls in the same transaction. `toDto` is a static mapper that keeps the JPA entity graph from being serialized directly, which would risk a `LazyInitializationException` outside the transaction or an infinite loop through the `Order` and `OrderItem` back-reference.

{% include excalidraw.html file="place-order-transaction-sequence" alt="OrderService#placeOrder as one Spring @Transactional spanning five bounded contexts in a single call stack: validate the customer, reserve stock, persist the order, charge payment, confirm, dispatch the shipment, and notify the customer; any failure partway through rolls back everything already written." caption="Figure 8.3: placeOrder, call by call: one transaction, five contexts" %}

The four services `OrderService` calls are small. `InventoryService#findBySkuOrThrow` returns the actual `InventoryItem` entity to `order`, not the `StockDto` that inventory's own controller gets; its `SMELL[ch.16]` comment names this the leaky-domain-model smell, because no translation layer exists at that seam yet. `PaymentService.charge` is the decline rule plus a `save`. `ShippingService.dispatch` is a single `repository.save(new Shipment(...))` with no branching, since shipment failure is not a failure mode this monolith demonstrates. `NotificationService.sendOrderConfirmation` formats a string and saves a `Notification` row. Nothing touches email or SMS, because the point is the coupling, a synchronous call inside checkout's transaction.

## Wiring and configuration

`MonolithApplication` is an unremarkable `@SpringBootApplication`. `application.yml` points a Postgres datasource at the compose-stack coordinates (`jdbc:postgresql://localhost:5432/monolith`), sets `spring.jpa.hibernate.ddl-auto: validate` and enables Flyway (`spring.flyway.enabled: true`) on `classpath:db/migration`. With `validate`, Hibernate checks the schema against its mappings at startup but never creates or alters tables. Flyway owns the schema's shape and JPA only describes how objects map onto tables Flyway already created. `spring.jpa.open-in-view: false` rules out the Open Session In View anti-pattern: a lazy-loading exception outside a transaction is a bug to fix at the source.

`GlobalExceptionHandler`, a `@RestControllerAdvice`, is the one error-mapping surface for every controller: `ResourceNotFoundException` to `404`, `InsufficientStockException` to `409`, `PaymentDeclinedException` to `402`, and Bean Validation failures to `400`, all in the same `ApiError` shape so a client never guesses which context produced an error. `OpenApiConfig` registers a springdoc `OpenAPI` bean whose description lists the six contexts and points at `SMELLS.md`.

`SecurityConfig` defines one `SecurityFilterChain` for the application: CSRF disabled, stateless sessions, HTTP Basic, and one rule, `POST /api/reviews` requires authentication and everything else is `permitAll()`. Its `SMELL[ch.15]` comment marks review's one authenticated endpoint governed by the same global chain as every other context. After the Review extraction no controller in the module matches that rule, which is why `SMELLS.md` calls the configuration on `main` "harmless/vacuous" rather than deleting it: it stays as the fossil record of the tangled shared security.

Three simplifications are worth knowing before you copy anything:

- The `DECLINE` rule is not a fraud model. It is a deterministic way to exercise the decline path.
- The single in-memory demo user in `SecurityConfig` is not an identity provider. The Review extraction replaces it with an OIDC-backed configuration standalone on Quarkus.
- `V2__seed_data.sql` relies on `BIGSERIAL` assigning IDs in insertion order against a freshly migrated schema, as its own comment says. That is fine for a demo fixture and dangerous in a production migration where concurrent writers could interleave inserts.

## Persistence: one shared schema, two migrations

Every context's table lives in one PostgreSQL schema built by two Flyway migrations. `V1__init_schema.sql` creates eight tables (`customers`, `inventory_items`, `orders`, `order_items`, `payments`, `shipments`, `notifications`, `reviews`). Its first line is a `SMELL` comment stating the problem: every foreign key crosses a bounded-context boundary freely, because the whole application shares one schema.

- `orders.customer_id` references `customers`.
- `order_items.inventory_item_id` references `inventory_items`.
- `payments`, `shipments` and `notifications` reference `orders`; `notifications` also references `customers`.
- `reviews` references both `customers` and `inventory_items`.

A decomposed system would own one database per context and replace each join with a CDC-fed local copy or a call across an anti-corruption layer.

`V2__seed_data.sql` is the deterministic fixture every later example assumes: two customers (Ada Lovelace, Grace Hopper), three inventory items (a Standard Widget with 100 units, a Deluxe Gadget with 50, and a Pocket Gizmo with only 5, scarce so the insufficient-stock path has a real SKU), one confirmed and shipped order for Ada, and two product reviews. A hardcoded `customer_id = 1` reliably means Ada because the script runs once against a fresh database, which is what both the compose-stack Postgres and the Testcontainers-backed integration tests provide.

{% include excalidraw.html file="monolith-shared-schema-er" alt="The shared-schema entity-relationship diagram: eight tables in one PostgreSQL schema, with customers and inventory_items referenced from multiple context-owned tables and nine foreign keys crossing bounded-context ownership lines freely." caption="Figure 8.4: One shared schema, nine foreign keys crossing context boundaries" %}

## The REST API surface

springdoc OpenAPI generates the contract from the controllers, so no hand-maintained spec can drift. It is served at `/v3/api-docs` with a UI at `/swagger-ui.html`. The six contexts expose:

| Context | Endpoint | Method | Purpose |
|---|---|---|---|
| order | `/api/orders` | `POST` | Place an order (the full checkout flow above) |
| order | `/api/orders/{id}` | `GET` | Fetch one order |
| order | `/api/orders` | `GET` | List all orders |
| inventory | `/api/inventory` | `GET` | List all SKUs and stock levels |
| inventory | `/api/inventory/{sku}` | `GET` | Fetch one SKU's stock level |
| payment | `/api/payments/{id}` | `GET` | Fetch one payment |
| payment | `/api/payments?orderId=` | `GET` | List payments for an order |
| shipping | `/api/shipments/{id}` | `GET` | Fetch one shipment |
| shipping | `/api/shipments?orderId=` | `GET` | List shipments for an order |
| notification | `/api/notifications?customerId=` | `GET` | List notifications for a customer |
| review | `/api/reviews` | `POST` | Create a review (authenticated) |
| review | `/api/reviews/{id}` | `GET` | Fetch one review |
| review | `/api/reviews?sku=` | `GET` | List reviews for a SKU |

All of it is plain Spring MVC, with no GraphQL, RPC framework or custom content negotiation. That ordinariness is what makes capturing the surface as a Newman equivalence collection mechanical.

## Why mainstream Spring

Spring MVC for REST, Spring Data JPA for persistence, Spring Security for the one authenticated route and Bean Validation for request shapes each have a direct counterpart in the Quarkiverse Spring-compatibility bridge (`quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`, `quarkus-spring-security`) that each extraction leans on for its first, fast pass onto Quarkus. That is a design constraint the monolith was built under. Had `OrderRepository` been written against a bespoke persistence abstraction instead of a plain `JpaRepository<Order, Long>`, Phase A of every extraction would need custom translation code before the equivalence check could run once. With the plain interface, the same interface against the same entity works almost unchanged once the compatibility extension replaces the Spring Data JPA starter on the classpath:

{% include codetabs.html langs="Spring Boot (examples/00-monolith, reference/monolith-before)|Quarkus (Phase A lift, quarkus-spring-data-jpa)" %}

```java
// pom.xml dependency: org.springframework.boot:spring-boot-starter-data-jpa
package dev.patterncatalyst.monolith.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

```java
// pom.xml dependency: io.quarkiverse.spring:quarkus-spring-data-jpa
// Same interface, same entity, same method signatures. The Quarkiverse
// extension provides a Spring Data JPA-compatible implementation at build
// time so this file does not need to change for the Phase A lift to pass
// the equivalence gate unchanged.
package dev.patterncatalyst.monolith.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

The tabs are identical by design. The two-phase strategy proves an extraction behaves correctly against the equivalence collection before anyone spends effort making the code idiomatic, and it can do that quickly only because nothing in the monolith's own code has to change. Exotic, hard-to-bridge Spring usage would turn every Phase A from a lift into a rewrite and collapse the two phases into one slow one.

## A healthy "before", with seams already visible

An obviously broken tangle would be an unrealistic "before". This one passes `mvn verify` with 45 test executions, has a complete browsable OpenAPI surface, correctly rolls back a declined payment and an out-of-stock line, and returns consistent errors across every context. A small team would ship and run it.

The six smells in `SMELLS.md` are not bugs a code review would block: shared schema and cross-context joins, the god `OrderService`, one ACID transaction spanning five contexts, a synchronous notification inside that transaction, the missing anti-corruption layer between `order` and `inventory`, and review's tangled-but-independent security. They are structural debt that accumulates invisibly and becomes expensive when a business needs to scale one context, deploy it independently or hand it to another team. Strategic and tactical domain-driven design and event storming find seams like these in a system that comes without a `SMELLS.md`.

> **Decision**
>
> Keep the framework surface to what a compatibility bridge can lift unchanged (plain Spring MVC, Spring Data JPA, Bean Validation), and let Flyway, not Hibernate, own the schema. Anything exotic taxes every later extraction.

> **🤖 Explore with your agent**
>
> "On the `reference/monolith-before` branch, read `examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/order/OrderService.java`. For each cross-context call in `placeOrder`, name the context it reaches, the `SMELL[ch.NN]` tag on it, and what that call becomes if its context is extracted, keeping the framework surface to what the Quarkiverse Spring-compatibility extensions can lift unchanged."
>
> Check: `git grep -n 'SMELL\[ch\.' reference/monolith-before -- examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/order/OrderService.java` lists the tags the reply cites (`SMELL[ch.16]`, `SMELL[ch.17]`, `SMELL[ch.22]`, `SMELL[ch.26]`).

## Build, run, observe

```bash
git checkout reference/monolith-before
cd examples/00-monolith
mvn verify          # three-tier suite: unit + slice + Testcontainers integration
mvn spring-boot:run # starts the app on :8080 against application.yml's datasource
```

`mvn verify` runs the unit, slice and Testcontainers-backed integration tests: 45 test executions on `reference/monolith-before`, 38 at `stage/01-review-extracted` after Review's tests left with its extraction. On `main` the module boots but serves nothing under `/api`. With the application running and the compose-stack Postgres up and seeded, `curl http://localhost:8080/api/inventory` returns the three seeded SKUs. `curl -X POST http://localhost:8080/api/orders` with a JSON body shaped like `OrderCreate` runs the full checkout against the database.

The OpenAPI document at `http://localhost:8080/v3/api-docs` is produced from the controller annotations. If a path or response shape in the table above drifts from the running code, that spec and the Swagger UI at `/swagger-ui.html` disagree with it.

## For deeper coverage

- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): why a believable "before" system, not a strawman, is the right starting point for any extraction; see the [Reading List](/bibliography/).
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): the bounded-context vocabulary the six packages anticipate.
- [Quarkus: Spring Data JPA compatibility guide](https://quarkus.io/guides/spring-data-jpa): the mechanics behind the Phase A lift in the codetabs above.
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the shared-schema tradeoffs the ER diagram makes concrete.
