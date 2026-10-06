---
title: "Content-Based Routing & the Anti-Corruption Layer"
order: 16
part: "The Strangler Fig in Practice"
description: "The Camel content-based router, message translator, and content enricher as the translation layer at the seam; the decorating-collaborator pattern."
---

Chapter 15 extracted Review, the walking skeleton, by routing one path prefix
through the Camel strangler proxy that already sits in front of the
monolith — a flag, a content-based choice, and a backend that moved out from
under the caller without the caller ever noticing. Chapter 11 diagnosed a
second, harder problem sitting one seam over, and this chapter's job is to
fix it. `order.OrderService` does not call `inventory.InventoryService` over
a path the proxy can route at all — it calls it as an ordinary Java method,
inside the same JVM, and gets back a raw JPA entity that inventory never
intended any context outside itself to hold. That is Smell 5 from Chapter 9,
and Chapter 11 named the relationship precisely: a conformist dependency
where a negotiated customer/supplier contract should exist, with the
contract itself — `StockDto` — already built and sitting unused three lines
above the leak. Fixing it needs two Camel ideas working together, not one.
**Content-based routing** is the pattern the strangler proxy already uses to
decide which backend answers a request; this chapter generalizes it past
"which URL path prefix" into "which seam a single piece of data should
travel across." The **anti-corruption layer (ACL)** is the translation
discipline Chapter 11 named but did not build; this chapter builds it as two
further Camel patterns working in concert — a **content enricher** that
performs the actual cross-seam fetch, and a **message translator** that
converts whatever comes back into the one contract type the calling side is
allowed to depend on. Together, the three patterns are the seam-hardening
that turns Chapter 19's inventory extraction from a rewrite into a
configuration change.

The code is in `examples/00-monolith/` (Smell 5's leak, quoted again below
exactly where Chapters 9 and 11 left it) and `examples/01-strangler-proxy/`
(the content-based router this chapter reads before extending its shape).
Nothing new is scaffolded for this chapter. The ACL route built below is a
sketch of the pattern Chapter 19 wires once inventory has its own
Quarkus gRPC service to enrich from; until then, `OrderService#placeOrder`
still calls `InventoryService` directly, because today they are two Spring
beans in one process and no seam physically separates them yet. What this
chapter adds is the shape that call takes the moment a seam does.

## Content-based routing, generalized past a URL path

A **content-based router** inspects something about an incoming message and
sends it down one of several possible paths based on what it finds — Hohpe
and Woolf's original formulation, and the same idea Camel's own catalog
describes as "routes messages to different steps based on a series of
conditions, similar to if-elseif-else," filed under the aliases `router` and
`dispatch`. Camel's Java DSL realizes it with `choice()` / `when()` /
`otherwise()`, and the strangler proxy already runs one in production, on the
Review seam:

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

Read this with Chapter 11's context-mapping vocabulary in hand and it is
doing something more specific than "proxy traffic": it is deciding, per
request, which side of a still-forming context boundary actually owns the
answer right now. The predicate itself is two conditions `and`-ed together —
a path match (*is this request even in Review's territory?*) and a flag read
(*has Review's territory actually moved yet?*) — and the branch taken sets an
exchange property that a second `.choice()` block, lower in the same route,
uses to pick a concrete backend URL. Nothing about this mechanism is specific
to HTTP paths. The "content" a content-based router inspects can be a URI, a
header, a flag, a combination of all three, or — as this chapter needs next —
a SKU string that has nothing to do with any URL at all. What makes a router
"content-based" is that the decision is made by looking *at the message*,
rather than being hardcoded to always go one place.

## Where content-based routing stops helping

Here is the limit worth being precise about, because Smell 5 lives exactly
at it. Suppose you had, today, a perfect content-based router sitting in
front of every stock lookup, correctly deciding "the monolith still owns SKU
data" or "inventory's own service owns SKU data now" on a per-request basis.
That router would solve exactly one problem: *which door to knock on*. It
says nothing whatsoever about *what you're handed once the door opens*. If
the backend behind that door hands back its own internal persistence entity —
complete with its lazy-loading behavior, its column names doing double duty
as field names, its own migrations now silently becoming the caller's
problem — a perfectly-routed request still corrupts the caller's model on
arrival. Content-based routing picks the path a message travels; it has no
opinion on whether the thing traveling down that path is safe for the
receiving side to hold onto. That second, narrower problem is exactly Smell
5, quoted again here because it is worth looking at a second time now that
you can see precisely which concern it is *not* about:

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
    inventoryService.reserve(line.sku(), line.quantity()); // throws InsufficientStockException, no writes yet
    order.addItem(new OrderItem(inventoryItem, line.quantity(), inventoryItem.getPriceCents()));
}
```

There is no routing failure here at all — `order` and `inventory` are
correctly reaching each other, in the same process, every single time. The
failure is in what crosses once they do: `InventoryItem`, an `@Entity`
managed by inventory's own `EntityManager`, mapped directly onto inventory's
own table. A content-based router could sit in front of this call all day
and never notice anything wrong, because routing and corruption are
orthogonal concerns. You need a different mechanism for the second one, and
Evans named it thirty years ago.

## The anti-corruption layer: a contract, not a request

Eric Evans' **anti-corruption layer** is the translation discipline a
downstream bounded context builds specifically to protect its own model from
an upstream context's internal representation, so that an internal change on
one side never silently becomes a breaking change on the other. It is not,
itself, a single mechanism — it is a responsibility, and Camel gives that
responsibility to two concrete, well-named patterns working together.

The **content enricher** is the half that performs the actual cross-seam
fetch. Camel's own catalog describes `enrich` as a step that "enriches the
message with additional data obtained by sending to another endpoint using
request-reply," with the reply "merged into the original message using an
aggregation strategy" — and its aliases, `hydrate` and `augment`, are exactly
the right words for what Smell 5's fix needs: the exchange arrives knowing
only a SKU, and leaves knowing the stock fact that SKU corresponds to. Left
at its default, `enrich()` simply replaces the original message body with
whatever the resource endpoint returned — which, applied naively here, would
just relocate the leak rather than fix it. The fix is supplying a custom
`AggregationStrategy` that does the second half of the job.

The **message translator** is that second half: whatever shape the resource
endpoint actually hands back gets converted, inside the aggregation step,
into the one contract type the calling side is allowed to depend on. Camel
does not reserve one dedicated DSL keyword exclusively for this role the way
it does for `enrich` — the translator is realized by whichever code actually
performs the conversion: an expression-based `transform()` step for simple
cases (the catalog describes it as a step that "sets the message
body using an expression," distinguishing it from `setBody` only by also
setting the `OUT` body on request-reply exchanges), or, for anything with
real field-by-field remapping to do, a small `Processor` or bean. The idiom
this chapter uses puts the translator *inside* the enricher's aggregation
strategy, which keeps "fetch" and "convert" as one atomic step at the seam
rather than two separately-ordered ones a later edit could accidentally
reorder.

## The ACL route, sketched

Here is the shape both patterns take together, written the way it would live
in `examples/01-strangler-proxy/` once Chapter 19 gives it a real second
backend to enrich from. It is not wired into that project
today — no caller invokes `direct:stockFor` yet, because `OrderService`
still reaches `InventoryService` directly, in-process, as quoted above.

```java
package dev.patterncatalyst.strangler;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SKETCH — the anti-corruption layer pattern ch.19's inventory extraction
 * wires for real, once inventory.InventoryService is reachable only over a
 * network seam (gRPC) rather than as a sibling Spring bean in the same JVM.
 * Not invoked by anything in this project today; shown here as the shape
 * order's stock lookup takes the moment a real seam separates the two sides.
 */
@ApplicationScoped
public class InventoryAclRoute extends RouteBuilder {

    /** Mirrors strangler.review.enabled's shape exactly (ch.14/15) — the
     *  same flag-driven cutover mechanism, one seam later. */
    @ConfigProperty(name = "strangler.inventory.enabled", defaultValue = "false")
    boolean inventoryEnabled;

    @ConfigProperty(name = "strangler.monolith.base-url")
    String monolithBaseUrl;

    /** Only resolves to a real backend once ch.19 stands one up. */
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
            // extracted Quarkus/gRPC service from ch.19 onward -- with zero
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
            // From ch.19 (when branch): the resource exchange instead
            // carries an inventory.v1.StockReply -- the gRPC contract that
            // chapter defines -- with its own field vocabulary
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

## How the route works

`direct:stockFor` is the only thing a caller ever needs to know about this
route — a seam address, not a backend address, which is the whole point.
Whatever eventually calls it (a Camel `ProducerTemplate` from inside the
strangler-proxy project today; a Quarkus REST client or a gRPC stub in
`order`'s own extracted service after Chapter 26) sets one header, `sku`, and
asks for a body back. It never names `localhost:8080` or inventory's gRPC
channel directly, which is exactly the indirection a seam is supposed to
buy.

The first `.choice()` block is the content-based router, and it is worth
noticing it does *less* work than `StranglerProxyRoute`'s version, not more.
The review router's predicate combines a path match and a flag read because
it has to decide, per inbound HTTP request, whether this request is even in
Review's territory at all. This router only ever has one kind of request —
"what does SKU `X` cost and how many are on hand" — so the predicate
collapses to the flag alone: `inventoryEnabled`. `setProperty` then stashes a
*complete resource URI string* — not just a label like `TARGET_REVIEW` — on
the exchange, because the thing the next step needs is a concrete address to
enrich from, built by interpolating `${header.sku}` into whichever base URL
the flag selected.

The second step is one call, `.enrich(...)`, but it is doing two jobs at
once, kept in one place rather than split into a fetch step and
a separate translate step. `enrich`'s first argument — an `Expression`, not a
hardcoded string — is what makes this a *dynamic* enrichment: Camel resolves
`${exchangeProperty.stockLookupTarget}` at runtime and performs a real
request-reply call against whatever URI that resolves to, which is precisely
what lets the same route serve both the monolith-backed lookup today and
inventory's own service after Chapter 19 without a second route or a second
deploy. The second argument, `StockDtoTranslatingStrategy`, is where the
translator lives: Camel hands it two exchanges — `original`, the one that
entered this route carrying only a SKU, and `resource`, the one holding
whatever the enrichment call returned — and whatever the strategy returns
becomes the exchange that continues past `.enrich()`. Returning `original`
with its body replaced, rather than returning `resource` untouched, is what
keeps this a *merge* rather than a *replacement*: any header or property the
original exchange was already carrying survives, and only the body changes.

Inside the strategy, `StockDtoTranslator.translate(reply)` is named but not
shown in full here — it is exactly the kind of small, overloaded
conversion method a reader should expect to write for themselves once the
shapes on each side are known, and showing a fabricated gRPC-generated class
in full here would claim more certainty about Chapter 19's actual `.proto`
than this chapter has any business claiming. What matters is the shape of
the decision it makes, documented in the comment above it: against
today's monolith REST endpoint, the translation is nearly a no-op, because
`InventoryController#getBySku` already builds `StockDto` for its own
consumers — the same `StockDto` Chapter 11 pointed at as "already there, just
never handed to `OrderService`." Against inventory's eventual gRPC service,
the translation does real work, remapping a different wire vocabulary
(`stock_keeping_unit`, `unit_price_cents`, `on_hand_qty`) into the field
names `order` already understands (`sku`, `priceCents`, `quantityOnHand`).
That asymmetry is not a flaw in the sketch — it is the accurate shape of what
an ACL actually buys you: protection against a *future* change, paid for
with a small amount of code that looks almost unnecessary on the day you
write it, because the side it's protecting against hasn't diverged yet.

Two fragile bits are worth naming rather than leaving a reader to
discover them. First, `strangler.inventory.enabled` and
`strangler.inventory.base-url` are not read from any `application.properties`
file in this project today — there is no running instance of this route yet,
and naming the properties here is only establishing the convention Chapter
19 will actually configure. Second, the `otherwise` branch's URL
(`monolithBaseUrl + "/api/inventory/${header.sku}"`) assumes the monolith
exposes a per-SKU `GET` endpoint — it does, per Chapter 8's endpoint table —
but nothing in this sketch has actually issued that HTTP call and confirmed
the response shape matches what `StockDtoTranslator` expects; that
confirmation is exactly the kind of claim this book's verification footer
below refuses to mark checked until a real run proves it.

## The decorating collaborator, named

This route is also this book's first worked instance of a fourth pattern
worth naming on sight, because the next six chapters lean on it repeatedly:
the **decorating collaborator**. The idea, used throughout monolith-to-
microservices migration literature for exactly this situation, is to wrap a
call to a system you cannot or should not modify with a new collaborator
that adds behavior at the edges — logging, caching, translation, routing —
without changing a single line inside the thing being wrapped. `InventoryAclRoute`
is precisely that: it decorates the existing stock-lookup call path with
translation (and, from Chapter 19 onward, with routing to a new backend)
entirely from the Camel layer, sitting alongside the monolith rather than
inside it. Nothing in `InventoryService` or `OrderService` has to change for
this decoration to exist — which is the entire reason it can be built now,
before inventory physically moves, as pure seam-hardening rather than as a
change that has to be coordinated with a rewrite of the code it protects.
Chapter 19 reuses this exact shape — a Camel route decorating a legacy call
path with translation and routing — to retire the monolith's inventory calls
for good, which is why the pattern-coverage ledger for this book credits
decorating collaborator to both this chapter (proxy-side, the pattern
introduced) and Chapter 19 (inventory retired, the pattern's payoff).

## Before and after: what crosses the seam

The clearest way to see what this buys is to put the two states of the world
side by side — not as two versions of running code (`OrderItem`'s own shift
from a live entity reference to a value snapshot is Chapter 18's cut, not
this chapter's), but as two answers to one question: what type does the
calling side actually hold after a stock lookup?

{% include codetabs.html langs="Today — the leaked entity (order/OrderService.java)|Through the ACL — the translated contract (illustrative)" %}

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
// Illustrative — the shape ch.19 moves this lookup to, once inventory sits
// behind a real seam. The caller never imports InventoryItem at all; it
// asks the ACL route for a SKU and gets back only the contract.
for (OrderCreate.Line line : command.items()) {
    StockDto stock = producerTemplate.requestBodyAndHeader(
            "direct:stockFor", null, "sku", line.sku(), StockDto.class);
    reserve(line.sku(), line.quantity());  // still a seam call of its own by ch.19
    order.addItem(new OrderItem(stock, line.quantity(), stock.priceCents()));
}
```

The difference is not cosmetic. In the "today" column, `OrderService` has a
compile-time dependency on `inventory.InventoryItem` — a class annotated
`@Entity`, owned by inventory's `EntityManager`, whose shape inventory is
free to assume nobody outside its own package depends on, except that
something outside its own package now quietly does. In the "through the ACL"
column, `OrderService` depends on nothing inventory-shaped at all beyond the
`StockDto` record — a plain, versioned contract that can be shared across a
process boundary precisely because it carries no persistence framework
annotations, no lazy-loading behavior, and no column-name coupling. Inventory
could rename every column in `inventory_items`, swap Hibernate for a hand-
rolled JDBC mapper, or move the whole table to a different database engine,
and nothing in the right-hand column would need to change, because nothing
in the right-hand column ever saw the thing that changed.

## What this buys Chapter 19

Seam-hardening is the right word for what this chapter does, and it is worth
being precise about why the word matters more than "adding a translation
layer" would suggest. Chapter 19 has three jobs when it actually extracts
inventory: stand up the new Quarkus gRPC service, prove it behaves like the
monolith's inventory module did, and make sure nothing that depends on stock
data breaks during the cutover. Without this chapter's work, all three jobs
would have to happen at once, because the calling side would still be
holding a raw `InventoryItem` reference that only compiles against a
same-JVM Spring bean — extracting inventory would force rewriting every
caller in the same breath as standing up the new service, with no way to
prove the rewrite was safe independently of the extraction itself. With this
chapter's ACL and router in place, Chapter 19 only has one real job left: make
the `when` branch's URL resolve to something real, flip
`strangler.inventory.enabled`, and let the equivalence gate confirm nothing
observable changed — precisely the cutover shape Chapter 15 already proved
out on Review. The translation work is done ahead of the extraction it
protects, which is exactly what "hardening a seam" should mean: not waiting
for the cut to discover what needs protecting, but building the protection
first, while it costs almost nothing, so the cut itself becomes the boring
part.

The same discipline this chapter builds for a synchronous request-reply
lookup is not a one-seam trick. Chapter 17's transactional outbox needs the
identical habit applied to an asynchronous payload instead of a synchronous
reply: the row `NotificationService` writes into its outbox must carry a
translated event contract, never a serialized `Order` or `Customer` entity,
for exactly the reason this chapter just walked through for `StockDto` — an
entity serialized into a durable queue is a corruption that outlives the
request that caused it, which is a strictly worse version of the same
mistake. The message translator this chapter introduces as "the thing inside
an enricher's aggregation strategy" is the same pattern Chapter 17 reaches
for as "the thing that builds an outbox row," just pointed at a different
kind of message.

## Cross-check

Two things here are independently checkable without running anything.
First, `grep -rn 'SMELL\[ch\.16\]' examples/00-monolith/src` returns five
matching lines across three files; the two code sites quoted above are among
them, unmodified from Chapter 9 and Chapter 11 — this chapter has not
altered a single line of the monolith to make its argument, which matters
because the whole point of the ACL sketched here is that it fixes the seam
*without* touching the code on either side of it.
Second, the EIP semantics this chapter leans on — `choice`'s predicate
evaluation order, `enrich`'s request-reply-plus-merge behavior and its
default of replacing rather than merging the body unless an
`AggregationStrategy` is supplied, and `transform`'s narrower scope next to
a hand-written translator — were checked against Camel 4.22.1's own EIP
catalog through the Camel MCP server during authoring, the same tool-
grounded source this book's ADLC Map phase uses, rather than relied on from
memory. That check confirms the *vocabulary* this chapter uses is accurate
to the Camel version this project already runs (the same `camel-bom` version
`examples/01-strangler-proxy/pom.xml` pulls in); it does not and cannot
confirm that `InventoryAclRoute` compiles or behaves as sketched, because — as
stated throughout this chapter — it has not been built as a runnable
artifact yet.

## What you learned

- Content-based routing and the anti-corruption layer solve two different
  problems that are easy to conflate because both sit at the same physical
  seam: a router decides *which backend answers*; an ACL decides *what is
  safe to carry back once it does*. The strangler proxy's existing
  `/api/reviews` router proves the first; Smell 5's raw-entity leak is a pure
  instance of the second, with a perfectly correct (single-backend) routing
  decision sitting right next to it.
- Camel realizes an ACL as two patterns working together: a **content
  enricher** (`enrich()`, with a custom `AggregationStrategy`) that performs
  the cross-seam request-reply fetch, and a **message translator** — whether
  a `transform()` expression or, as sketched here, logic living inside the
  aggregation strategy itself — that converts whatever comes back into one
  stable contract type, regardless of which backend answered.
- The **decorating collaborator** pattern is what makes building this ACL
  *before* the physical extraction possible at all: it wraps the existing
  call path with new behavior from the Camel layer, touching zero lines of
  `InventoryService` or `OrderService`, which is why this chapter's sketch
  changes nothing about the monolith even as it prepares the seam Chapter 19
  will cut.
- This is seam-hardening, not decoration for its own sake: once the ACL and
  router exist, extracting inventory becomes a configuration flip and an
  equivalence-gate run — the same cutover shape Chapter 15 already proved on
  Review — instead of a rewrite that has to happen in the same breath as the
  extraction itself.

Chapter 17, "Extraction 2 — Notification Service," puts the same
message-translator discipline to work on an asynchronous payload instead of
a synchronous reply, building the transactional outbox that replaces
Smell 4's in-transaction notification call — and it needs exactly the habit
this chapter just established: never let a persistence entity travel across
a seam, synchronous or not, without a translator standing between it and
whatever's on the other side.

---

*Verification status: <span class="status status--unverified">unverified</span>.
The two `SMELL[ch.16]` excerpts quoted above are real and quoted verbatim
from `examples/00-monolith/` as it exists in the repository today, and the
content-based router excerpt from `StranglerProxyRoute.java` is real,
already running code exercised by this project's own Code CI. The EIP
semantics attributed to `choice`, `enrich`, and `transform` were checked
against Camel 4.22.1's own catalog via the Camel MCP server during authoring.
`InventoryAclRoute` and `StockDtoTranslatingStrategy`, however, are a sketch,
not a runnable artifact: they are not compiled, not wired into
`examples/01-strangler-proxy/`, and not exercised by any test in this
project. What remains unverified is exactly what Chapter 19 exists to
confirm: that this shape compiles against a real inventory backend, that the
dynamic `enrich()` expression resolves correctly under both flag states, and
that the equivalence gate stays green through the cutover it is meant to
make safe.*
