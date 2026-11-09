---
title: "Coupling, the Modular Monolith & When Not to Split"
order: 13
part: "Finding the Seams"
description: "Constantine's coupling ladder, afferent and efferent coupling as an extraction ranking, Khononov's balancing model, and the modular monolith as a legitimate destination."
---

## Learning objectives

By the end of this chapter you can:

- Place each remaining smell in the monolith on the Constantine coupling ladder (content, common, control, stamp, data).
- Compute afferent (Ca) and efferent (Ce) coupling for a context from its dependency graph and rank contexts for extraction.
- Explain Khononov's strength, distance and volatility model and why splitting strong, volatile coupling across a network produces a distributed monolith.
- Describe the three disciplines of a modular monolith: module isolation, no cross-module entity references, module-owned schemas.
- Name the precondition for cutting a seam: an anti-corruption layer, an outbox, a CDC pipeline or a saga is already in place.

## Why this matters

Bounded contexts and event storming tell you where the system could be cut. They don't say which seam to cut first, or whether to cut at all. Coupling answers both, because you can count it. The five smells planted in the monolith (a shared schema, a god service, a synchronous call, a leaky entity, a transaction that spans contexts, numbered 1 to 5 in `examples/00-monolith/SMELLS.md`) are all coupling problems, and scoring them on one scale turns an extraction order into something you can defend instead of an argument won by whoever spoke last.

Nothing here changes code. The ranking is computed from the monolith's real dependency graph, and it is why Review goes first and Order goes last. The extractions have since removed the context packages from the current `examples/00-monolith` tree; the monolith as designed, with every class named below, is on the `reference/monolith-before` branch (`git checkout reference/monolith-before`).

## Coupling and cohesion

Larry Constantine's formulation: **a structure is stable if cohesion is high and coupling is low.** Cohesion is how tightly the elements inside one module belong together. Coupling is how much one module's internals depend on another's. The two trade against each other: pulling everything a module needs for its one job inside it raises cohesion and lowers coupling at once, because it no longer reaches outside itself. `OrderService` shows both failing: "places an order by checking stock, persisting the order, charging payment, dispatching shipment and sending a notification" takes four "and"s to describe, and a class with no single purpose has no natural boundary stopping it from reaching into everyone else's business.

Coupling is the dimension to build a strategy on because it is countable. You can enumerate the edges in a dependency graph with a static analysis tool or a disciplined `grep`. Cohesion stays a per-module judgment call. Conway's observation is the organizational mirror ("organizations which design systems are constrained to produce designs which are copies of the communication structures of these organizations"): a coupling edge in the code is usually a coordination requirement between two teams. A foreign key between `order_items` and `inventory_items` means whoever owns inventory's schema must coordinate with whoever owns order's queries, whether or not an org chart says so.

## The Constantine coupling ladder

Constantine and Yourdon ranked coupling from worst to best. Every rung has a live example in the monolith.

| Rung | Meaning | Where it is in the monolith |
|---|---|---|
| Content | One module relies on another's internal data, bypassing its interface | `OrderService#placeOrder` receives a live, JPA-managed `InventoryItem` from `InventoryService#findBySkuOrThrow` and reads `getPriceCents()` and `getSku()`. Java's access modifiers keep it short of the letter of the definition, not the spirit |
| Common | Modules share a widely visible data store | One Postgres schema holds `order`, `inventory`, `payment`, `shipping`, `notification` and the `customers` shared kernel, with no enforced ownership |
| Control | A parameter's value dictates the receiver's control flow | `OrderCreate.paymentMethod()` travels from the REST controller into `PaymentService#charge`, where it selects the approve or decline path |
| Stamp | A whole composite crosses a boundary when the receiver needs part, so any change to its shape can break the receiver | `OrderItem`'s constructor takes a full `InventoryItem` for three fields: sku, name and price at order time. Passing the whole `StockDto` after the entity leak is fixed would still be stamp coupling |
| Data | Only simple, purpose-built parameters cross | `OrderService.listAll()` returns `List<OrderDto>` built by `toDto`, already done right, a few lines from the content-coupling violation in the same class: `toDto` was written for the REST controller, `findBySkuOrThrow` for a Java caller's convenience |

Message coupling and API contract coupling have no example in the monolith. They are what the extracted services look like, and you need the whole ladder to measure how far they've moved.

{% include excalidraw.html file="coupling-ladder" alt="The Constantine coupling ladder drawn as seven stacked rungs, worst to best, each paired with its monolith example. Content coupling: OrderService#placeOrder reading a live, JPA-managed InventoryItem's priceCents and sku straight from findBySkuOrThrow. Common coupling: one shared Postgres schema across order, inventory, payment, shipping, notification, and customers. Control coupling: OrderCreate.paymentMethod() threaded into PaymentService#charge, selecting an approve-or-decline code path. Stamp coupling: OrderItem's constructor taking the full InventoryItem entity for the three fields it needs. Data coupling: OrderService.listAll() returning List<OrderDto> via toDto, already done right. Message coupling and API/data-structure coupling have no monolith example: they are the target architecture the extracted services build." caption="Figure 13.1: The Constantine coupling ladder, worst to best, each rung pinned to its monolith example" %}

## The two worst offenders

**Shared-database coupling** (`SMELL[ch.18]`, smell 1) is common and content coupling at once. `order_items` joins `inventory_items`; `payments`, `shipments` and `notifications` each hold a foreign key back into `orders`; `order` and `notification` both join `customers`. `order/OrderItem.java`, `payment/Payment.java`, `shipping/Shipment.java`, `notification/Notification.java` and `common/Customer.java` carry the `SMELL[ch.18]` tag on exactly these relationships. A foreign key isn't a negotiated contract. The database enforces a promise ("this row can't exist without that row") that neither context's code declares and neither can relax alone. It is invisible at the point of use: nothing in `OrderService` says it depends on inventory's schema, so the coupling announces itself only when a migration three deploys later, owned by another team, drops a column and breaks a query `order` never knew it leaned on.

On Khononov's balancing model this is the maximal case: intrusive strength, on data that changes constantly, at zero distance today. It stays survivable only because distance is zero. The moment a context gets its own database, distance jumps while strength stays put, and the distributed monolith arrives unless strength drops first.

**God-service orchestration coupling** (`SMELL[ch.26]`, smell 2) is control coupling multiplied by four. `OrderService` calls inventory, payment, shipping and notification as a synchronous in-process sequence, and a caller needs all four callees' exception behavior (`InsufficientStockException`, `PaymentDeclinedException`) to use any one of them correctly. Its efferent coupling is four and its afferent coupling is near zero, so its instability `I = Ce / (Ca + Ce)` is close to 1: it depends on everything and nothing depends on it. Shared-database coupling is dangerous because it is invisible; god-service coupling is dangerous because it is a single point of maximum fan-out: each of the four callees has to stay behavior-compatible with `OrderService`'s expectations or checkout breaks. That is why Order is extracted last: each other extraction removes one of its four edges, and only when little is left can it be cut.

## Afferent and efferent coupling as a ranking

The taxonomy says what kind of coupling each smell is. A number says which context to cut first. **Afferent coupling (Ca)** is how many components depend on a component. **Efferent coupling (Ce)** is how many components it depends on. Both are countable from an import graph today, without an architect's opinion.

The rule: **extract low-Ca, low-Ce contexts first and save high-Ca, high-Ce contexts for last.** Ca is the number of dependents you must migrate alongside you, Ce the number of dependencies you must sever or bridge before you leave. Together they are the blast radius. A context nobody calls and that calls nothing can be lifted out in an afternoon. One that everyone calls and that calls everyone can't move until both directions of traffic are rerouted.

| Order | Context | Ca / Ce | What the cut has to sever |
|---|---|---|---|
| 1 | Review | Ca ≈ 0, Ce ≈ 0 | Nothing. It has no runtime dependency on order, inventory, payment, shipping or notification, so the first extraction exercises the mechanism (strangler proxy, equivalence gate, flag-gated cutover) with no coupling risk competing for attention |
| 2 | Notification | low / low | One inbound edge from `OrderService`, the synchronous in-transaction call. An outbox has to replace it first, set up by the anti-corruption layer and content-based routing |
| 3 | Inventory | moderate / ≈ 0 | `OrderService` reads it at checkout and `OrderItem` holds a foreign key into it, where the shared-schema and leaked-entity smells both land. Needs CDC backfill before the database can split |
| 4, 5 | Payment, shipping | moderate / moderate | One inbound edge each from `OrderService`, both inside the same spanning transaction. Splitting either removes the free rollback they get from sharing a database, so the cut needs a saga. Shipping gains a dependency on payment once payment is extracted |
| 6 | Order | highest / highest | `Payment`, `Shipment` and `Notification` all carry an `order_id` join, and `OrderService`'s constructor injects four other services |

Putting Order first would mean solving every other context's severance problem at once with no smaller extraction's lessons to carry forward, the "biggest cut first" failure a blast-radius ranking exists to prevent. Rank by Ca and Ce, cut the lowest first, and let each extraction lower the score of what remains until the hardest context is the smallest remaining problem.

{% include excalidraw.html file="context-dependency-graph-cace" alt="A directed dependency graph of the six contexts. Order sits at the center with four outbound edges (OrderService's calls into inventory, payment, shipping, and notification, its efferent coupling of four) and three inbound foreign-key edges from payment, shipment, and notification back to order, its afferent coupling. Inventory also carries a data-level edge from order via OrderItem's foreign key into InventoryItem. Review has no edges in or out. A dashed edge from shipping to payment marks the dependency shipping only gains once payment is extracted. Below, the same six contexts sorted into the resolved extraction order: review, notification, inventory, payment, shipping, then order, lowest Ca/Ce first." caption="Figure 13.2: The six contexts' dependency graph, scored by Ca/Ce, resolving into the extraction order" %}

## The modular monolith

Khononov's model says distance multiplies cost: splitting strong, volatile coupling across a network adds distance without removing strength. Most of coupling's damage (invisible cross-context writes, god objects with unbounded fan-out, leaked internal types) comes from module boundaries, not process boundaries, and module boundaries can be enforced inside one deployable for a fraction of the operational cost.

A **modular monolith** is one build, one JVM and one release train with the three disciplines a distributed system gets automatically, enforced by tooling instead:

- **Package or build-module isolation.** The compiler, the build's module graph or an ArchUnit rule refuses to compile code that imports another context's internal package. The leaked `InventoryItem` becomes a build failure the moment `order` imports it instead of `inventory.StockDto`, which is earlier and cheaper than an incident.
- **No cross-module entity references.** `@ManyToOne` and `@JoinColumn` from `OrderItem` to `InventoryItem`, from `Payment`, `Shipment` and `Notification` to `Order`, and from everything to `Customer`, are disallowed across a module boundary. A module persists only its own entities and refers to others by plain identifier (`Long customerId`, not `Customer customer`) plus a published in-process method to resolve it. It is the discipline of an anti-corruption layer applied before the seam crosses a network.
- **Module-owned schemas.** Each module gets its own schema in the same Postgres instance (`order.orders`, `inventory.inventory_items`), so a foreign key physically can't cross modules, while a second database, connection pool and credential set wait until a decomposition need justifies them. This is the midpoint between one undifferentiated schema and one database per service.

Doing all three first separates two questions a premature microservices migration conflates. "Do these boundaries track real bounded contexts?" is cheap to answer and fix inside one deployable, where moving a class between packages is a refactor. "Is coupling low enough, and is this context's operational profile different enough, to justify a second deployable, database and on-call rotation?" should be asked only after the first has a confident answer.

The reference monolith doesn't stop at this stage; its extraction goes straight from the shared schema to services with their own databases, to demonstrate the whole journey with measured before and after. For your own system the modular monolith is frequently the correct destination. If the problem is lead time and change-failure rate inside one team's release train, and not independent scaling or independent team ownership, module boundaries, banned entity references and split schemas can retire most of the smells' damage with no second deployable, broker or mesh. Distribute only for a named, measured need: independent scaling, independent deployment cadence or independent team ownership.

## The distributed-monolith trap

A team sees coupling, concludes "microservices fix coupling," and distributes a system whose coupling it never reduced. The result has every operational cost of distribution (latency, partial failure, deploys that still have to be coordinated, tracing standing in for a stack trace) and none of the monolith's simplicity, because calls that were in-process methods are now network calls making the same synchronous demands. In Khononov's terms distance went up, strength didn't go down, and volatility on the core domain was already high: all three dimensions high at once, which is the model's definition of maximum pain.

Splitting this monolith into six services without first addressing the shared schema and the god service would produce exactly that: six deployables reaching across the network into tables and services they don't own. So each extraction cuts a context only once the coupling in its way has an answer (an ACL, an outbox, a CDC pipeline, a saga), and the equivalence gate proves that answer held instead of discovering in production that it didn't.

## From three lenses to an extraction plan

Domain-driven design gives the conceptual boundaries: bounded contexts, aggregates, and the rule that protocols stay out of the domain core so extraction is mechanical rather than a rewrite. Event storming finds those boundaries from behavior when nobody has drawn them, by watching where domain events cluster and where one team's vocabulary stops making sense to the next. Coupling gives the order. The output is one artifact: a list of bounded contexts, each annotated with the behavioral evidence that the boundary is real and each scored for blast radius. Review is first because its score is the floor, Order last because its score is the ceiling, and each remaining extraction executes one row of the ranked list.

> **Decision**
>
> Rank candidate seams by Ca and Ce and cut the lowest first. Cut a seam only when the coupling in its way (shared schema, synchronous call, spanning transaction) already has an answer. If the problem is lead time inside one team, stop at a modular monolith.

> **🤖 Explore with your agent**
>
> "Check out the reference/monolith-before branch. In `examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/`, build the import graph between the order, inventory, payment, shipping, notification and review packages, compute Ca, Ce and instability for each, and rank the contexts for extraction. List every `@ManyToOne` or `@JoinColumn` that crosses a package boundary and name the Constantine rung of each. Propose an ArchUnit rule that would fail the build on the `InventoryItem` leak, without changing any behavior."
>
> Check: the ranking should put review first and order last, and the ArchUnit rule should fail when `order` imports `inventory.InventoryItem`.

## For deeper coverage

- Sam Newman, *Building Microservices* (2nd ed., O'Reilly, 2021): service boundaries and the distributed-monolith failure mode named precisely; see the [Reading List](/bibliography/).
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the data-ownership tradeoffs behind module-owned schemas, the lightest-weight version of "own your data."
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): bounded contexts as the conceptual boundaries that a coupling metric scores.
- Cloud Native Design Patterns, [Anti-Patterns](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/13-anti-patterns.md): the companion book's catalogue of the distributed-monolith failure mode in the same terms.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the saga and API-composition patterns that have to exist before a ranked extraction can safely distribute.
