---
title: "Designing the Monolith"
order: 8
part: "The Reference Monolith"
description: "The six bounded contexts as one Spring Boot deployable: domain model, layered modules, single shared schema, REST API, and seed data."
---

Chapter 7 proved the loop by running it once, end to end, on a real
extraction. This chapter builds the system that loop will spend the rest of
this book strangling. Before anything can be modernized, it has to exist as
something worth modernizing — not a toy with three classes and a `TODO`, but a
believable "before": a system a real team could have shipped, that does real
work, that a customer would actually notice if it went down. That system is
`examples/00-monolith/`, a plain Spring Boot 3.5 application on JDK 25, one
deployable JAR, one PostgreSQL schema, one JVM, built around a shipping and
e-commerce domain split into six bounded contexts. This chapter walks its
design — the domain, the layering, the schema, the API surface — and makes
explicit a choice that will matter for the rest of this book: every framework
API this monolith uses was picked because it has a clean bridge to Quarkus,
not in spite of it.

The code is in `examples/00-monolith/`. There is no separate run script for
this chapter — it is an ordinary Maven project. `mvn spring-boot:run` starts
it against the local Postgres coordinates in `src/main/resources/application.yml`;
`mvn verify` runs the full three-tier JUnit suite (unit, slice, and
Testcontainers-backed integration tests) this chapter and the next two build
on, and already passes green — 38 test executions today (45 at the commit
history Chapter 7 quoted, `e6d62bc`, before Review's tests were removed in
r02).

{% include excalidraw.html file="monolith-architecture" alt="A layered Spring Boot monolith: six bounded-context packages (order, inventory, payment, shipping, notification, review) each with a controller, service, and repository layer, all built against one shared PostgreSQL schema populated by Flyway migrations V1 and V2, exposed as one REST API surface documented by springdoc OpenAPI." caption="Figure 8.1 — The reference monolith: six bounded contexts, one layered Spring Boot deployable, one shared schema" %}

## The domain: six bounded contexts, one shipping business

The monolith models a small but complete e-commerce shipping business, split
into six bounded contexts that map onto the obvious nouns of "a customer buys
a thing and it arrives at their door": **order** (the checkout aggregate and
its line items), **inventory** (stock levels per SKU), **payment** (capturing
a charge against an order), **shipping** (dispatching a shipment to an
address), **notification** (telling the customer what happened), and
**review** (a customer rating a product they bought). Each context lives as
its own Java package under `dev.patterncatalyst.monolith` —
`order`, `inventory`, `payment`, `shipping`, `notification` — with a shared
vocabulary of DTOs and enums (`OrderDto`, `OrderStatus`, `StockDto`,
`OrderCreate`) living in a `common` package that every context is allowed to
depend on, so the same shapes travel unchanged into later extracted services.
This scale discipline is deliberate: each context stays in roughly the
three-to-nine-file range — an entity, a repository, a service, a controller,
maybe a DTO or two — because a reference monolith that sprawls into hundreds
of files per context stops being readable as a teaching artifact and starts
being its own research project.

Read `MonolithApplication.java`'s class-level Javadoc and you will notice
something this chapter owes you a clear, accurate account of: it says the monolith
"originally" held six contexts as sibling packages, and that `review` "has
been extracted and decommissioned from this module" as of this project's own
r02 walking skeleton — the very extraction Chapter 7 narrated commit by
commit. That is not a mistake in the example; it is this book keeping its own
promise from Chapter 5 that "the book is built the way it teaches." The
chapters you are reading were produced by the same ADLC loop they describe,
and that loop had already run the Review extraction — proxy, Phase A lift,
Phase B refactor, cutover, decommission — by the time this chapter was
written. So what you will find in `examples/00-monolith/src/main/java` today
is five context packages (`order`, `inventory`, `payment`, `shipping`,
`notification`); review's controller, entity, and service were deleted in
that extraction, and `review` now lives on as `examples/02-review-service`,
reached through the Camel strangler proxy described later in this book.

That is a deliberate narrative choice, not a continuity error, and it is
worth stating plainly rather than quietly stepping around: this chapter
describes the monolith **as designed** — all six contexts, Review included —
because that is the "before" picture every later chapter needs as its
starting point, and because Review's design (a REST-only context sharing one
security filter chain with five contexts it has no runtime dependency on) is
exactly the smell Chapter 9 catalogues and Chapter 15 cuts. Where this
chapter cites real, present-tense code — entities, services, controllers,
migrations — that code is the five contexts still resident in the module
today. Where it describes review's original shape, it draws on `SMELLS.md`'s
historical record and the javadoc comments left behind specifically so that
record would survive the deletion. Nothing here is invented; it is simply
describing a system at the moment just before this book's own first cut,
using a repository that — truthfully — has already made that cut once.

## The layering: controller, service, repository, three times five

Every one of the five contexts still in the module follows the same classic
layered shape, and once you have read one you have effectively read all five.
Take `inventory` as the clearest example, because it is the smallest:
`InventoryController` is a thin `@RestController` that does nothing but
delegate to `InventoryService`; `InventoryService` holds the one piece of
domain logic this context has (reserving stock under a lock, discussed
below); and `InventoryRepository` is a one-line `JpaRepository<InventoryItem,
Long>` interface with two derived-query methods. `order`, `payment`,
`shipping`, and `notification` repeat the same controller-service-repository
triad, scaled up only as far as each context's actual responsibility
requires — `payment` and `shipping` each have exactly one meaningful method
on their service (`charge`, `dispatch`); `order` has more because it is where
checkout itself happens, which the next section covers in full.

This is Spring MVC over Spring Data JPA, the plainest possible version of
each: `@RestController` classes mapped with `@RequestMapping`/`@GetMapping`/
`@PostMapping`, `@Service` classes constructor-injected with the repositories
and sibling services they need, and `JpaRepository` interfaces that Spring
Data implements at startup from method-name conventions and a handful of
explicit annotations. Nothing here is hexagonal — there are no ports, no
interfaces separating a domain core from its Spring Data implementation, and
a controller's request type flows straight through to a JPA entity's
association graph in `order`'s case. That absence is itself one of the
smells Chapter 9 names: a hexagonal boundary is exactly what makes an
extraction mechanical later, and this monolith deliberately does not have
one yet, so that Chapter 11's introduction of ports and adapters has a real
gap to fill rather than a strawman.

## How the code works

### The entities: one shared kernel, five contexts' worth of state

The module has seven resident JPA entities: one shared-kernel class plus six
context-owned ones — `InventoryItem`, `Order`, `OrderItem`, `Payment`,
`Shipment`, `Notification`. Review's entity was the eighth, removed in r02
when Review's code left the monolith. `Customer`, in the
`common` package, is explicitly documented as "not one of the monolith's
bounded contexts itself" — it exists because `order` and `notification` both
need to reference a customer, and in a single shared schema the cheapest way
to do that is a direct `@ManyToOne` join rather than a context-local copy.
Its Javadoc is unusually candid about why it still carries a foreign key from
a table (`reviews`) whose owning Java code has already left the building:
`examples/02-review-service` is still in its Phase A form (DRQ-029) and reads
and writes that table directly against this same database, so dropping it
here would break a service this book's own walking skeleton just cut over to.
That one comment is worth re-reading, because it is a live example of
Chapter 7's "shared state masking a routing defect" lesson playing out in the
schema itself, not just the proxy.

`InventoryItem` is the simplest owned entity: a SKU, a name, a price in
cents (never a floating-point currency type — a recurring convention in this
codebase, because cents-as-`long` sidesteps rounding bugs that a `double`
would reintroduce at every arithmetic step), a quantity on hand, and an
`updatedAt` timestamp bumped by its own `decrement`/`restock` methods rather
than left to callers to manage. `Order` is the richest entity in the module:
it holds a `@ManyToOne` to `Customer`, an `OrderStatus` enum persisted as a
string (`@Enumerated(EnumType.STRING)`, so the database stores `CONFIRMED`
rather than an ordinal integer that would silently shift meaning if the enum
were ever reordered), a running `totalCents` total, and a `@OneToMany` list
of `OrderItem`s with `cascade = CascadeType.ALL, orphanRemoval = true` — which
means saving an `Order` saves its items for free, and removing an item from
the in-memory list actually deletes its row, not just the association.
`Order`'s own `addItem` method is where the running total gets maintained:
it appends the item, calls back into it to set the owning side of the
bidirectional association (`item.assignTo(this)`), and increments
`totalCents` by that line's price times quantity — all in one place, so no
caller can add an item and forget to update the total, because the total
isn't a caller's responsibility at all. `OrderItem` itself carries the
monolith's most-discussed single line: a `@ManyToOne` to
`inventory.InventoryItem`, which is one bounded context's table joined
directly from another's, inside one shared schema. That is smell #1 in
`SMELLS.md`, and Chapter 9 spends real time on it; this chapter's job is only
to show you the actual annotation that makes it true, not to cure it.

`Payment`, `Shipment`, and `Notification` (not shown in full here, but
structurally identical in shape to `Order`'s simpler siblings) each hold a
`@ManyToOne` back to `Order`, a status or channel field, and a `createdAt` or
`sentAt` timestamp — three more cross-context joins into the same shared
schema, following the same pattern.

### The DTOs: a shared vocabulary that survives extraction

The `common` package's records are not incidental — they are named and
shaped on purpose to match the "reuse-map" vocabulary this project's sibling
example projects already use, specifically so that when a context is
extracted later in this book, its API contract doesn't have to be renamed or
reshaped to match. `OrderCreate` is the checkout request: a Java `record`
with Bean Validation annotations directly on its components —
`@NotNull Long customerId`, `@NotEmpty @Valid List<Line> items`,
`@NotBlank String paymentMethod`, `@NotBlank String shippingAddress` — and a
nested `Line` record with its own `@NotBlank String sku` and
`@Positive int quantity`. Because `OrderController.placeOrder` declares its
parameter as `@Valid @RequestBody OrderCreate command`, Spring validates the
entire nested structure — including every line in the list, because the
outer `items` field is itself annotated `@Valid` — before `OrderService` ever
sees it; a missing SKU or a zero quantity never reaches the service layer at
all; it comes back as a 400 from `GlobalExceptionHandler`'s
`MethodArgumentNotValidException` handler. `OrderStatus` is a three-value
enum (`PENDING`, `CONFIRMED`, `PAYMENT_DECLINED`) deliberately kept this
small because the monolith's checkout flow is itself deliberately simple —
there is no partial-fulfillment or multi-shipment state machine here, because
that complexity would obscure the smells this monolith exists to demonstrate
rather than illuminate them.

### The core logic: `OrderService#placeOrder`, call by call

If this monolith has one method worth reading slowly, it is
`OrderService#placeOrder`, because it is where all five of the remaining
contexts meet inside a single Spring `@Transactional` boundary. Walking it in
the order it actually executes:

```java
@Transactional
public OrderDto placeOrder(OrderCreate command) {
    Customer customer = customerRepository.findById(command.customerId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "No customer with id " + command.customerId()));

    Order order = new Order(customer, command.shippingAddress());

    for (OrderCreate.Line line : command.items()) {
        InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
        inventoryService.reserve(line.sku(), line.quantity());
        order.addItem(new OrderItem(inventoryItem, line.quantity(),
                inventoryItem.getPriceCents()));
    }

    order = orderRepository.save(order);

    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    order.confirm();

    shippingService.dispatch(order, order.getShippingAddress());
    notificationService.sendOrderConfirmation(customer, order);

    return toDto(order);
}
```

The first call resolves the customer or fails fast with a `404`-mapped
`ResourceNotFoundException` — there is no point building an order for a
customer that does not exist, so this check happens before any other work.
The `for` loop over `command.items()` does two things per line, deliberately
kept separate: `findBySkuOrThrow` fetches the actual `InventoryItem` entity
(not a DTO — more on that in a moment) so the order item can capture its
current price, and `reserve` performs the actual stock check and decrement
inside the same loop iteration. Splitting "look up" from "reserve" instead of
having one method do both is a small design choice with a real consequence:
`reserve` takes a pessimistic write lock (`InventoryRepository`'s
`findWithLockBySku`, annotated `@Lock(LockModeType.PESSIMISTIC_WRITE)`) so
that two concurrent checkouts racing for the last unit of a SKU cannot both
read "1 on hand," both decide they can proceed, and both commit — the second
transaction blocks on the lock until the first commits or rolls back, and
then re-reads the now-current quantity. `reserve` throws
`InsufficientStockException` — mapped by `GlobalExceptionHandler` to a `409`
— the moment the requested quantity exceeds what's on hand, *before* any row
is written for that item, which is why the exception's Javadoc specifically
notes "throws ..., no writes yet."

`orderRepository.save(order)` persists the order and, thanks to the
cascading `@OneToMany`, its items in the same call. Only after that succeeds
does `paymentService.charge` run — and this is the line that makes the
`@Transactional` boundary load-bearing rather than decorative. `charge`'s
demo decline rule is intentionally simple: any payment method string
containing `DECLINE` (case-insensitively) throws
`PaymentDeclinedException`, deterministically, with no real payment gateway
involved — a design choice made specifically so the behavior-equivalence
suite Chapter 10 builds can exercise the decline path without flaky external
dependencies. When that exception is thrown, Spring's default rollback
behavior unwinds the *entire* transaction: the order row, its items, and the
inventory decrement from the loop above all revert, because Postgres is
giving this method atomic rollback across five tables for free. That is
exactly the "it works today because one database can do this" smell Chapter
9 names and Chapter 22/23/24 eventually replace with an explicit saga — this
chapter's job is only to make sure you have seen, concretely, what "it works
today" actually means at the code level: one `@Transactional` annotation, no
saga machinery, no compensating actions, because none are needed yet.

If payment succeeds, `order.confirm()` flips the status to `CONFIRMED`,
`shippingService.dispatch` creates a `Shipment` row synchronously, and
`notificationService.sendOrderConfirmation` writes a `Notification` row —
still inside the same transaction, still a plain synchronous method call.
`toDto` at the end is a static mapper that walks the order's items and builds
the `OrderDto` the controller actually returns; it exists specifically so the
JPA entity graph — with its lazy associations and bidirectional references —
never has to be serialized directly to JSON, which would risk either a
`LazyInitializationException` outside the transaction or an accidental
infinite loop through the `Order`↔`OrderItem` back-reference.

### `InventoryService`, `PaymentService`, `ShippingService`, `NotificationService`

Each of the four services `OrderService` calls into is small enough to read
in full, and each is worth a sentence on the one thing it does that a reader
retyping this code should understand the reason for. `InventoryService`'s
`findBySkuOrThrow` returns the *actual* `InventoryItem` JPA entity to its
caller in `order`, not a `StockDto` — the same method `listAll`/`getBySku`
use to build DTOs for inventory's own controller is not reused here, and
that is not an oversight; it is the leaky-domain-model smell the
in-code comment names directly, because there is no translation layer at
this particular seam yet. `PaymentService.charge` is the decline rule just
discussed, plus a straightforward `save`; `ShippingService.dispatch` is a
single `repository.save(new Shipment(...))` call with no branching at all —
dispatch always succeeds in this model, because shipment failure is not one
of the failure modes this reference monolith is built to demonstrate.
`NotificationService.sendOrderConfirmation` formats a plain string message
and saves a `Notification` row; nothing here touches email, SMS, or any real
channel, because the point of this context is the *coupling* — a synchronous
call inside checkout's transaction — not the delivery mechanism.

### The wiring: `MonolithApplication`, `application.yml`, cross-cutting config

`MonolithApplication` is an unremarkable `@SpringBootApplication` with a
`main` method that calls `SpringApplication.run` — the only thing worth
noting is its class-level Javadoc, already discussed above, documenting the
module's own history accurately instead of erasing the fact that Review was ever there.
`application.yml` configures a Postgres datasource pointed at the project's
local podman-stack coordinates (`jdbc:postgresql://localhost:5432/monolith`),
sets `spring.jpa.hibernate.ddl-auto: validate` — meaning Hibernate checks the
schema matches its entity mappings at startup but never generates or alters
tables itself — and turns on Flyway (`spring.flyway.enabled: true`) pointed
at `classpath:db/migration`, which is where the schema actually comes from.
That `ddl-auto: validate` choice is deliberate and worth internalizing now,
because it recurs throughout this book: Flyway, not Hibernate, owns the
schema's shape, and JPA's job is only to describe how Java objects map onto
tables Flyway already created. `spring.jpa.open-in-view: false` closes the
Open Session In View anti-pattern — a lazy-loading exception outside a
service method's transaction boundary is a bug to be fixed deliberately, not
papered over by keeping a Hibernate session open through the whole web
request.

Two small `@Configuration` classes round out the wiring.
`GlobalExceptionHandler`, a `@RestControllerAdvice`, is the one error-mapping
surface shared by every controller in the module: `ResourceNotFoundException`
to `404`, `InsufficientStockException` to `409`, `PaymentDeclinedException`
to `402`, and Bean Validation failures to `400`, each producing the same
`ApiError` shape so a client never has to guess which context produced an
error. `OpenApiConfig` registers a springdoc `OpenAPI` bean with a title and
version; its description field is itself an artifact of this book's own
accuracy discipline, because it states in plain text that the monolith "still
owns" five contexts and that review was "extracted and decommissioned in
r02/S10" — the generated OpenAPI spec a reader hits at `/v3/api-docs` tells
the same true story this chapter does. `SecurityConfig` configures exactly
one `SecurityFilterChain` for the whole application: CSRF disabled, stateless
sessions, HTTP Basic auth, and a single authorization rule
(`POST /api/reviews` requires authentication; everything else is
`permitAll()`). That rule no longer matches any route in this module — there
is no `review` controller left to match it — which is precisely why
`SMELLS.md` calls this configuration "harmless/vacuous" today rather than
deleting it outright: it is left in place as the fossil record of the smell
Chapter 15 actually cured, for a reader who wants to see what "tangled into
shared security" looked like in code before it was fixed.

### The fragile bits, named plainly

A few choices in this module are simplifications made on purpose for a
teaching artifact, and they are worth naming rather than leaving a reader to
discover them by surprise. The payment decline rule — any method string
containing `DECLINE`, case-insensitively — is not a real fraud or
risk-scoring model; it exists purely so the equivalence suite Chapter 10
builds has a deterministic way to exercise the decline path. The single
in-memory demo user in `SecurityConfig` is not a real identity provider;
Chapter 15's extraction replaces it with an OIDC-backed configuration
standalone on Quarkus, precisely because review's authentication was never a
feature the monolith as a whole needed. And the seed data in
`V2__seed_data.sql` depends on Postgres's `BIGSERIAL` assigning IDs in
strict insertion order against a freshly migrated schema — the script's own
comment says so — which is a convenience for a demo fixture, not a pattern
to copy into a production migration where concurrent writers could interleave
inserts unpredictably.

## Persistence: one shared schema, two Flyway migrations

Every context's table lives in one PostgreSQL schema, created by exactly two
Flyway migrations that run in order at application startup.
`V1__init_schema.sql` creates seven tables — `customers`, `inventory_items`,
`orders`, `order_items`, `payments`, `shipments`, `notifications`, and
`reviews` — and its very first line is a `SMELL[ch.18]` comment stating the
smell outright: every foreign key in this file crosses a bounded-context
boundary freely, because there is exactly one schema for the whole
application to share. `orders.customer_id` references `customers`;
`order_items.inventory_item_id` references `inventory_items`; `payments`,
`shipments`, and `notifications` all reference `orders`; `reviews` references
both `customers` and `inventory_items`. A decomposed system, the comment
notes, would own one database per context and replace every one of those
joins with either a CDC-fed local copy or a call across an anti-corruption
layer — exactly the subject of Chapter 18 and Chapter 19, two parts of this
book away from here.

`V2__seed_data.sql` is the deterministic demo fixture every later chapter's
examples assume exists: two customers (Ada Lovelace, Grace Hopper), three
inventory items (a Standard Widget with 100 units on hand, a Deluxe Gadget
with 50, and a Pocket Gizmo with only 5 — deliberately scarce, so the
insufficient-stock path has a real SKU to exercise), one fully confirmed and
shipped order for Ada, and two product reviews. The file's own header
comment is explicit about the one constraint that makes it work at all: it
relies on `BIGSERIAL` assigning IDs 1, 2, 3... in insertion order against a
schema that was just created fresh, so a hardcoded `customer_id = 1` in a
later `INSERT` reliably means Ada. That is a fine assumption for a Flyway
migration that only ever runs once per fresh database — which is exactly
what both the local podman-stack Postgres and the Testcontainers-backed
integration tests give it — and it would be a dangerous assumption anywhere
IDs might already be in use, which is worth flagging precisely because the
pattern is common enough in demo fixtures that it is easy to copy
uncritically into a context where it no longer holds.

## The REST API surface

springdoc OpenAPI generates the full contract from the controllers
themselves — no hand-maintained spec to drift out of sync — served at
`/v3/api-docs` with a browsable UI at `/swagger-ui.html`. The surface across
the five contexts still resident in the module:

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

Every one of these is plain Spring MVC — no GraphQL, no RPC framework, no
custom content negotiation — because the API surface itself is not where
this book spends its teaching budget; it is the substrate the
behavior-equivalence suite in Chapter 10 captures as a Newman collection, and
a surface this ordinary is exactly what makes that capture mechanical rather
than an exercise in working around framework idiosyncrasies.

## Why mainstream Spring, on purpose

Every one of the frameworks wired into this module — Spring MVC for REST,
Spring Data JPA for persistence, Spring Security for the one authenticated
route, Bean Validation for request shapes — was chosen for a reason this
chapter can now state plainly, because you have just seen the code that
proves it: each one has a direct counterpart in the Quarkiverse
Spring-compatibility bridge (`quarkus-spring-web`, `quarkus-spring-di`,
`quarkus-spring-data-jpa`, `quarkus-spring-security`) that Part 5's
extractions lean on for their first, fast pass onto Quarkus (DRQ-029). This
is not an incidental convenience; it is a design constraint this monolith
was built under from the start. If `OrderRepository` had been written
against some bespoke in-house persistence abstraction instead of a plain
`JpaRepository<Order, Long>` interface, Phase A of every future extraction
would need custom translation code before the equivalence gate could even
run once. Because it is instead the plainest possible Spring Data interface,
the Phase A lift is close to mechanical — the same interface, against the
same entity, works almost unchanged once the compatibility extension is on
the classpath instead of the real Spring Data JPA starter:

{% include codetabs.html langs="Spring Boot (today, examples/00-monolith)|Quarkus (Phase A lift, quarkus-spring-data-jpa)" %}

```java
// pom.xml dependency: org.springframework.boot:spring-boot-starter-data-jpa
package dev.patterncatalyst.monolith.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

```java
// pom.xml dependency: io.quarkiverse.spring:quarkus-spring-data-jpa
// Same interface, same entity, same method signatures — the Quarkiverse
// extension provides a Spring Data JPA-compatible implementation at build
// time so this file does not need to change for the Phase A lift to pass
// the equivalence gate unchanged.
package dev.patterncatalyst.monolith.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

The two tabs are identical on purpose. That sameness is the entire point of
DRQ-029's two-phase strategy: Phase A proves an extraction behaves correctly
against the equivalence suite *before* anyone spends effort making the code
idiomatic, and it can prove that quickly precisely because nothing in the
monolith's own code has to change to get there. A monolith built on exotic,
hard-to-bridge Spring usage would force every extraction's Phase A to start
with a rewrite instead of a lift — which would collapse the two-phase
strategy back into one slow phase, exactly the thing Chapter 5 argued the
ADLC's speed is supposed to avoid.

## A healthy "before," with seams already visible

It would be easy to write a "before" monolith that is obviously broken —
tangled spaghetti nobody would ship — and call that realistic. This one is
not that. `mvn verify` passes 38 tests green; the OpenAPI surface is
complete and browsable; the checkout flow correctly rolls back a declined
payment and an out-of-stock line; the error responses are consistent across
every context. This is software a small team would genuinely ship, review,
and run in production — which is exactly why it is the right "before." The
six smells `SMELLS.md` catalogues (shared schema and cross-context joins,
the god `OrderService`, one ACID transaction spanning five contexts, a
synchronous notification inside that transaction, the missing
anti-corruption layer between `order` and `inventory`, and review's
tangled-but-independent security) are not bugs a code reviewer would catch
and block on. They are the kind of structural debt that accumulates
invisibly in a system that works fine at today's scale and today's team
size, and only becomes expensive once a business tries to scale a single
context, deploy it independently, or hand it to a different team. Chapter 9
names and locates each one precisely, tagged to the exact pattern that cures
it later in this book. Part 4, starting two chapters from here, teaches the
method — strategic and tactical domain-driven design, event storming — for
finding a seam like this systematically, in a system where nobody left you a
`SMELLS.md` file to read.

## Build, run, observe

```bash
cd examples/00-monolith
mvn verify          # three-tier suite: unit + slice + Testcontainers integration
mvn spring-boot:run # starts the app on :8080 against application.yml's datasource
```

With the application running and the local podman-stack Postgres up and
seeded, `curl http://localhost:8080/api/inventory` returns the three seeded
SKUs; `curl -X POST http://localhost:8080/api/orders` with a JSON body
shaped like `OrderCreate` runs the full checkout flow this chapter just
walked, end to end, against a real database.

## Cross-check

The generated OpenAPI document at `http://localhost:8080/v3/api-docs` is an
independent description of the API surface, produced by springdoc from the
controller annotations rather than hand-written — if a method, path, or
response shape in this chapter's table above ever drifts from the running
code, that spec (and the Swagger UI rendering it at `/swagger-ui.html`) will
disagree with this chapter immediately, which is exactly the property that
makes it a useful cross-check rather than a second copy of the same claim.

## What you learned

- The monolith's six bounded contexts (order, inventory, payment, shipping,
  notification, review) share one classic layered shape — Spring MVC
  controller, `@Service`, Spring Data JPA repository — repeated five times in
  the code you can read today, with review's original sixth copy preserved
  in `SMELLS.md`'s record of what Chapter 15 later extracts.
- `OrderService#placeOrder` is the one method that ties all five remaining
  contexts together inside a single `@Transactional` boundary — the exact
  mechanism Chapter 9 names as a smell and Chapters 22-24 eventually replace
  with an explicit saga.
- One shared PostgreSQL schema, two Flyway migrations, and a seed script that
  quietly depends on fresh-database insertion order are the persistence
  layer's whole story — and its cross-context foreign keys are precisely
  what Chapter 18/19 later untangle.
- Every framework choice here (Spring MVC, Spring Data JPA, Spring Security)
  was made because it bridges cleanly to Quarkus via the Quarkiverse
  Spring-compatibility extensions (DRQ-029) — a design constraint on the
  "before" picture, not an afterthought discovered during extraction.

Chapter 9, "The Deliberate Smells," takes the six `SMELL[ch.NN]` comments
scattered through this code and turns them into a deliberate catalogue —
naming each one precisely and pointing, for the first time in this book, at
the exact chapter that will cure it.

---

*Verification status: <span class="status status--unverified">unverified</span>.
The code, schema, and API surface described above are real and already
exist in `examples/00-monolith/` as of this project's own r02 walking
skeleton, and `mvn verify`'s green run is itself recorded in this project's
commit history (Chapter 7, commit `e6d62bc` — 45 test executions at that
point; 38 today, per Chapter 10, after Review's tests were removed in r02).
What this chapter
adds beyond that existing record — the REST endpoint table, the Flyway
migration walkthrough, and the Phase A codetabs comparison above — has not
been independently re-run against a live instance during authoring and
should be confirmed by actually starting the application and hitting each
listed endpoint before this chapter is treated as a verified reference.*
