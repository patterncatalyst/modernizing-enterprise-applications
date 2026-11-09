---
title: "Content-Based Routing and the Anti-Corruption Layer"
order: 16
part: "The Strangler Fig in Practice"
description: "A Camel content-based router picks the backend, and an enricher with a translating aggregation strategy keeps the monolith's entities from crossing the seam."
---

## Learning objectives

By the end of this chapter you can:

- Separate what a content-based router decides (which backend answers) from what an anti-corruption layer decides (what is safe to carry back).
- Route on message content with `choice()`, `when()` and `otherwise()`, including content that has no URL, such as a SKU.
- Combine `enrich()` with a custom `AggregationStrategy` so one step both fetches and translates.
- Recognize the deliberate smell in the monolith where `OrderService` holds a raw JPA entity from `InventoryService`, and see why routing alone doesn't fix it.
- Build the anti-corruption layer before the physical extraction, with a decorating collaborator that changes no line of the code it protects.

## Why this matters

The strangler proxy routes one path prefix to the extracted Review service behind a flag. The next seam is harder to route. `order.OrderService` doesn't reach `inventory.InventoryService` over a path the proxy can see. It calls an ordinary Java method in the same JVM and gets back a raw JPA entity, `InventoryItem`, that inventory never meant any other context to hold. The coupling analysis named this a conformist dependency where a negotiated contract should exist, and the contract, `StockDto`, already exists and sits unused three lines above the leak.

Two patterns fix it together. A **content-based router** decides which backend answers. An **anti-corruption layer (ACL)**, Evans' term for the translation a downstream context builds to protect its own model, decides what the answer may look like. Camel builds the ACL from a **content enricher** that performs the cross-seam fetch and a **message translator** that converts the reply into the one contract type the caller may depend on.

The route below is a sketch for `examples/01-strangler-proxy/`. Nothing calls it yet, because the two services are still Spring beans in one process and no seam separates them. It shows the shape the lookup takes the moment one does.

## Pattern: Content-Based Router

A content-based router inspects a message and sends it down one of several paths. Camel's catalog describes `choice()` as routing "based on a series of conditions, similar to if-elseif-else". The strangler proxy already runs one on the Review seam:

```java
// examples/01-strangler-proxy/src/main/java/dev/patterncatalyst/strangler/StranglerProxyRoute.java
.choice()
    .when(PredicateBuilder.and(
            simple("${header.CamelHttpPath} startsWith '/api/reviews'"),
            exchange -> reviewEnabled))
        .setProperty(TARGET_PROPERTY, constant(TARGET_REVIEW))
    .otherwise()
        .setProperty(TARGET_PROPERTY, constant(TARGET_MONOLITH))
.end()
```

The predicate combines a path match (is this request in Review's territory?) with a flag read (has that territory moved?). The branch sets an exchange property that a second `choice()` lower in the route uses to pick a backend URL. Nothing here is specific to HTTP paths: the content can be a URI, a header, a flag or, as the next route needs, a SKU string.

### Where routing stops helping

A perfect router in front of every stock lookup would answer one question: which door to knock on. It says nothing about what comes back. If the backend returns its internal persistence entity, with lazy loading, column names doubling as field names and its migrations now the caller's problem, a correctly routed request still corrupts the caller's model. That is the leaky-domain-model smell (smell 5 in `examples/00-monolith/SMELLS.md`). The extraction later deleted both classes from the current tree; they are on the `reference/monolith-before` branch (`git checkout reference/monolith-before`):

```java
// inventory/InventoryService.java
/**
 * SMELL[ch.16]: returns the raw JPA entity, not a DTO/contract, to a caller
 * (order.OrderService) outside this context; there is no anti-corruption
 * layer at this seam. The cure is a Camel content enricher / message
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
    inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

Nothing fails to route. `order` and `inventory` reach each other every time. The failure is the `@Entity` managed by inventory's `EntityManager` and mapped onto inventory's table, now held by the order context.

{% include excalidraw.html file="cbr-vs-acl-seam" alt="A cross-shaped diagram centered on InventoryAclRoute's enrich() step. The horizontal axis is content-based routing: .choice() on strangler.inventory.enabled selects the monolith's /api/inventory/{sku} endpoint on :8080 today, or inventory's own gRPC service on :9004 after the extraction. The vertical axis is the anti-corruption layer: the raw reply from whichever backend answered feeds into a StockDtoTranslatingStrategy that always produces StockDto. The two axes cross at one physical seam but answer independent questions." caption="Figure 16.1: One seam, two orthogonal questions: which backend answers, and what is safe to carry back" %}

## Pattern: Anti-Corruption Layer

The two Camel patterns split the work.

| Pattern | Camel step | Job |
|---|---|---|
| Content enricher | `enrich()` | Request-reply fetch from another endpoint, merged into the exchange by an aggregation strategy. Aliases `hydrate`, `augment`. |
| Message translator | `transform()`, a `Processor`, or logic in the aggregation strategy | Convert whatever came back into the contract type. Camel has no dedicated keyword for it. |

By default `enrich()` replaces the message body with the resource's reply, which would only relocate the leak. A custom `AggregationStrategy` does the second half. Putting the translator inside the strategy keeps fetch and convert as one step, so a later edit can't reorder them.

### The ACL route, sketched

```java
package dev.patterncatalyst.strangler;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SKETCH: the anti-corruption layer pattern the inventory extraction
 * wires for real, once inventory.InventoryService is reachable only over a
 * network seam (gRPC) rather than as a sibling Spring bean in the same JVM.
 * Not invoked by anything in this project today; shown here as the shape
 * order's stock lookup takes the moment a real seam separates the two sides.
 */
@ApplicationScoped
public class InventoryAclRoute extends RouteBuilder {

    /** Mirrors strangler.review.enabled's shape exactly: the
     *  same flag-driven cutover mechanism, one seam later. */
    @ConfigProperty(name = "strangler.inventory.enabled", defaultValue = "false")
    boolean inventoryEnabled;

    @ConfigProperty(name = "strangler.monolith.base-url")
    String monolithBaseUrl;

    /** Only resolves to a real backend once the inventory extraction stands one up. */
    @ConfigProperty(name = "strangler.inventory.base-url")
    String inventoryServiceBaseUrl;

    private static final String TARGET_PROPERTY = "stockLookupTarget";

    @Override
    public void configure() {

        from("direct:stockFor")
            .routeId("inventory-acl")
            .log("inventory-acl: looking up stock for ${header.sku}")

            // --- Content-Based Router: same flag-driven choice as the
            // review seam, deciding which backend a stock lookup enriches
            // from -- the monolith's own REST endpoint today, inventory's
            // extracted Quarkus/gRPC service after its extraction -- with zero
            // change required on whatever calls this route either way.
            .choice()
                .when(exchange -> inventoryEnabled)
                    .setProperty(TARGET_PROPERTY,
                            simple(inventoryServiceBaseUrl + "/inventory/${header.sku}"))
                .otherwise()
                    .setProperty(TARGET_PROPERTY,
                            simple(monolithBaseUrl + "/api/inventory/${header.sku}"))
            .end()

            // --- Content Enricher + Message Translator: a request-reply
            // fetch against whichever backend the choice above selected,
            // merged into this exchange by an AggregationStrategy that
            // translates the reply's shape into StockDto before anything
            // downstream ever sees it -- never Camel's default
            // replace-with-the-raw-reply behavior.
            .enrich(simple("${exchangeProperty." + TARGET_PROPERTY + "}"),
                    new StockDtoTranslatingStrategy());
    }

    /**
     * The translator half of the ACL. Converts whatever the resolved
     * backend actually returned into the one contract (StockDto) this route
     * promises every caller -- regardless of which side answered.
     */
    static final class StockDtoTranslatingStrategy implements AggregationStrategy {
        @Override
        public Exchange aggregate(Exchange original, Exchange resource) {
            // Today (otherwise branch): the monolith's own
            // InventoryController#getBySku already builds StockDto for its
            // REST surface -- see common/StockDto.java -- so this side of
            // the translator is nearly a pass-through; the JSON on the wire
            // is already shaped the way order needs it.
            //
            // After the inventory extraction (when branch): the resource exchange instead
            // carries an inventory.v1.StockReply -- the gRPC contract that
            // extraction defines -- with its own field vocabulary
            // (stock_keeping_unit, unit_price_cents, on_hand_qty). THIS is
            // where the translator earns its keep for real: remapping a
            // genuinely different wire vocabulary into the one shape order
            // is allowed to know.
            Object reply = resource.getMessage().getBody();
            StockDto stock = StockDtoTranslator.translate(reply);

            original.getMessage().setBody(stock);
            original.getMessage().setHeader("stockQuantity", stock.quantityOnHand());
            return original;
        }
    }
}
```

`direct:stockFor` is a seam address, not a backend address. A caller sets the `sku` header and asks for a body, and never names `localhost:8080` or a gRPC channel. The router collapses to the flag alone, because this route serves one kind of request. `setProperty` stores a complete resource URI, built from `${header.sku}`, because `enrich()` takes an expression and resolves it at run time. That is what lets one route serve the monolith today and the extracted service later.

The strategy receives `original`, which carries only the SKU, and `resource`, which carries the reply. Returning `original` with its body replaced makes the step a merge: headers and properties already on the exchange survive.

`StockDtoTranslator.translate(reply)` is named but not shown: it is a small overloaded conversion you write once the shapes on each side are known. The translation is asymmetric. Against the monolith's REST endpoint it is nearly a no-op, because `InventoryController#getBySku` already builds `StockDto`. Against inventory's future gRPC service it does real work, mapping `stock_keeping_unit`, `unit_price_cents` and `on_hand_qty` to `sku`, `priceCents` and `quantityOnHand`. That is what an ACL buys: protection against a divergence that hasn't happened yet, for a small amount of code that looks unnecessary on the day it's written.

Two points are unverified. The `strangler.inventory.*` properties aren't read from any `application.properties` yet, and the `otherwise` URL assumes the monolith's per-SKU `GET` endpoint returns the shape `StockDtoTranslator` expects. Neither has been exercised, because the route isn't built as a runnable artifact.

{% include excalidraw.html file="acl-enricher-translator-flow" alt="A left-to-right pipeline through InventoryAclRoute: an exchange arrives on direct:stockFor knowing only a SKU; .choice() on strangler.inventory.enabled resolves a backend URI, either the monolith's REST endpoint or, after the extraction, inventory's own gRPC service; .enrich() performs a request-reply fetch against that URI; StockDtoTranslatingStrategy.aggregate() converts the reply via StockDtoTranslator.translate() and merges StockDto onto the original exchange; the exchange leaves the route knowing the stock fact." caption="Figure 16.2: The ACL pipeline: a SKU goes in, StockDto comes out, regardless of which backend answered" %}

## The decorating collaborator

Sam Newman's decorating collaborator wraps a call to a system you can't or shouldn't modify with a new collaborator that adds behavior at the edges: logging, caching, translation, routing. `InventoryAclRoute` decorates the stock-lookup path with translation, and later with routing to a new backend, from the Camel layer beside the monolith. `InventoryService` and `OrderService` don't change, so the layer can be built before inventory moves, as seam-hardening rather than part of a rewrite.

### What crosses the seam

{% include codetabs.html langs="Today: the leaked entity (order/OrderService.java)|Through the ACL: the translated contract (illustrative)" %}

```java
// Today: order.OrderService#placeOrder calls InventoryService directly, in
// the same JVM, and holds the live JPA entity for the rest of this method.
for (OrderCreate.Line line : command.items()) {
    InventoryItem inventoryItem = inventoryService.findBySkuOrThrow(line.sku());
    inventoryService.reserve(line.sku(), line.quantity());
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

```java
// Illustrative: the shape the inventory extraction moves this lookup to, once inventory sits
// behind a real seam. The caller never imports InventoryItem at all; it
// asks the ACL route for a SKU and gets back only the contract.
for (OrderCreate.Line line : command.items()) {
    StockDto stock = producerTemplate.requestBodyAndHeader(
            "direct:stockFor", null, "sku", line.sku(), StockDto.class);
    reserve(line.sku(), line.quantity());  // still a seam call of its own after the inventory extraction
    order.addItem(new OrderItem(stock, line.quantity(), stock.priceCents()));
}
```

In the first tab, `OrderService` has a compile-time dependency on `inventory.InventoryItem`, an `@Entity` owned by inventory's `EntityManager`. In the second, it depends on the `StockDto` record, which carries no persistence annotations, no lazy loading and no column coupling, so it can cross a process boundary. Inventory could rename every column in `inventory_items`, replace Hibernate with hand-written JDBC or move the table to another engine, and nothing on the right would change.

{% include excalidraw.html file="leaked-entity-vs-stockdto" alt="A two-panel comparison. Left, today: order.OrderService#placeOrder calls inventoryService.findBySkuOrThrow(sku) as an ordinary in-process Java call and holds InventoryItem, a JPA @Entity owned by inventory's own EntityManager, a compile-time import of inventory's persistence class. Right, through the ACL: the same call goes through producerTemplate.requestBodyAndHeader against direct:stockFor and returns StockDto, a plain record with no persistence annotations and no compile-time dependency on InventoryItem at all." caption="Figure 16.3: What crosses the seam: a raw @Entity import versus a plain-record contract" %}

### What it buys the extraction

Extracting inventory has three jobs: stand up the Quarkus gRPC service, prove it behaves like the monolith's module, and keep every stock consumer working through the cutover. Without the ACL, the callers still hold `InventoryItem`, which compiles only against a same-JVM bean, so they'd be rewritten in the same step as the extraction with no independent proof the rewrite was safe. With it, one job is left: point the `when` branch at the real service, flip `strangler.inventory.enabled` and let the equivalence gate confirm nothing observable changed. That is the cutover shape Review already proved.

The same habit applies to asynchronous payloads. A row in a transactional outbox must carry a translated event contract, never a serialized `Order` or `Customer` entity. An entity serialized into a durable queue outlives the request that wrote it, which is a worse version of the same mistake.

> **Decision**
>
> Route when the question is which backend answers; add an anti-corruption layer when the question is what type the caller may hold. Build the layer before the extraction, so the cut itself becomes a flag flip.

> **🤖 Explore with your agent**
>
> "On the reference/monolith-before branch, in `examples/00-monolith`, find every place `OrderService#placeOrder` uses `InventoryItem` from `InventoryService#findBySkuOrThrow`. Write an `InventoryAclRoute` in `examples/01-strangler-proxy` that serves `direct:stockFor` through `choice()` on `strangler.inventory.enabled` and `enrich()` with an `AggregationStrategy` that returns `StockDto`. Don't edit any monolith file."
>
> Check: `grep -rn 'SMELL\[ch\.16\]' examples/00-monolith/src` still returns the same five lines in three files, and `git diff --stat` shows changes only under `examples/01-strangler-proxy`.

## For deeper coverage

- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the content-based router, content enricher and message translator the anti-corruption layer is built from.
- Eric Evans, *Domain-Driven Design* (Addison-Wesley, 2003): the anti-corruption layer concept.
- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the decorating collaborator, used to harden a seam before the extraction behind it exists.
- Cloud Native Design Patterns, [DDD & Hexagonal Architecture](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/19-appendix-f-ddd-hexagonal.md): bounded contexts and the ports and adapters shape the ACL protects.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the anti-corruption layer and API composition patterns behind the seam-hardening; see the [Reading List](/bibliography/).
