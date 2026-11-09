---
title: "The Strangler Fig Pattern"
order: 14
part: "The Strangler Fig in Practice"
description: "Fowler's pattern in four ordered moves, the proxy, redirection and shared-database variants, and the Camel content-based router that makes a cutover a configuration change."
---

## Learning objectives

By the end of this chapter you can:

- Apply the strangler fig as four ordered moves (intercept, route, incrementally replace, retire) and explain why retiring old code before a cutover is proven loses the pattern's safety.
- Distinguish the Proxy, Redirection and Shared-Database variants by the question each answers: how the routing decision is made, and whether data ownership moves with code ownership.
- Build the Camel content-based router in `StranglerProxyRoute.java` and explain `bridgeEndpoint=true` and `throwExceptionOnFailure=false`.
- Keep the cutover flag in configuration so a cutover and its reversal are a restart, not a redeploy.
- Diagnose the `startsWith '/reviews'` predicate bug and explain why a green 49-of-49 equivalence run could not catch it.
- Sequence a decommission: delete only what the extraction owns, defer the data cut, and flip the committed default last.

## Why this matters

A ranked list of contexts says which seam to cut first. It doesn't say how to cut one in a running production system without a weekend outage. The mechanism is the Camel route in `examples/01-strangler-proxy/`, which fronts the monolith, and it was exercised against a real cutover, a real bug and a real decommission. The proxy is a long-running process; its `README.md` covers building it, running it and pointing the equivalence suite through it.

{% include excalidraw.html file="strangler-review-extraction" alt="Two stacked diagrams showing the Camel strangler proxy before and after the Review cutover: before, the proxy forwards /api/reviews traffic to the Spring monolith; after, the flag strangler.review.enabled routes that traffic to the Quarkus review service instead, with the monolith's Review module decommissioned." caption="Figure 14.1: The strangler proxy before and after the Review cutover: one Camel route, one flag, two backends." %}

## Pattern: Strangler Fig

Martin Fowler named the pattern after a botanical process. A fig seed lands in a host tree's canopy and grows a lattice of its own around the trunk while the host keeps living. Only when the fig can stand alone does the host, starved of light, die and rot away, leaving the fig in the host's shape. Four moves, in this order:

1. **Intercept.** Put something in front of the old system that sees every request. Nothing about the host has changed, but the mechanism that will redirect its traffic exists.
2. **Route.** Decide per request which system answers. At first the answer is always the old system, but the decision point now lives outside the old code, so nothing after this step touches the old system again.
3. **Incrementally replace.** Stand up one new piece as its own service, flip the routing decision for that piece only, verify, and repeat.
4. **Retire.** When a piece of the old system gets no traffic, remove it. Readers' instinct treats this as the goal, but done too early it turns an incremental migration into a big-bang rewrite with extra ceremony.

{% include excalidraw.html file="strangler-four-moves" alt="The strangler fig pattern's four moves in order: intercept, route, incrementally replace, retire, shown as a reversible zone spanning the first three moves (both backends still exist, the flag can flip either direction) and a separate irreversible zone at retire. A dashed warning path shows what happens if retire is reached before a stop-the-old-system check passes: the fallback is gone and the migration quietly becomes a big-bang rewrite." caption="Figure 14.2: The four moves, in order: intercept, route, incrementally replace, retire. The window stays reversible through move three; retire is the one move that closes it, and it only belongs last." %}

The fig never kills the host and grows into the gap. The system under modernization keeps serving traffic for the whole migration, so the new system never has to catch up to a frozen target, and nothing requires everything to move at once. The migration is reversible piece by piece until a host limb is actually removed.

## Three variants of the same idea

| Variant | Question it answers | In this system |
|---|---|---|
| Proxy | How is the routing decision made? A facade in front of the old system routes on anything it can see: path, header, cookie, a percentage for canary traffic | A standalone Camel-on-Quarkus application, separate from the monolith and every extracted service. Every client request, the Newman equivalence suite included, goes through it |
| Redirection | The Proxy variant with one narrow rule: route by URI path | The Review cutover. A path rule maps onto REST resource boundaries, so a migration stages one bounded context at a time, and the rule stays mechanical and auditable |
| Shared-Database | Must the new service own a database before it goes live? | Review's `application.properties` sets `quarkus.hibernate-orm.schema-management.strategy=none`: in its Phase A (spring-compat lift) form the service owns no schema and reads and writes the `reviews` table in the monolith's Postgres instance. Per-context data ownership comes later, in the shared-data-to-owned-data work |

The shared database isn't a corner cut. The service boundary and the data boundary are separable, and forcing both in one step buys nothing but a data migration the cutover didn't yet need. The variants aren't exclusive: one proxy can route most contexts by path and one by another signal, and a code cutover can go live while its data cutover happens later. The point is to name which variant answers which question at each seam, so the code decision and the data decision aren't discovered to be two decisions mid-migration.

## The Camel route

`StranglerProxyRoute.java` is a single `RouteBuilder` whose `configure()` intercepts, decides and forwards:

```java
from("platform-http:/api?matchOnUriPrefix=true")
    .routeId("strangler-fig-proxy")
    .log("strangler-proxy: ${header.CamelHttpMethod} ${header.CamelHttpPath}")

    .choice()
        .when(PredicateBuilder.and(
                simple("${header.CamelHttpPath} startsWith '/api/reviews'"),
                exchange -> reviewEnabled))
            .setProperty(TARGET_PROPERTY, constant(TARGET_REVIEW))
        .otherwise()
            .setProperty(TARGET_PROPERTY, constant(TARGET_MONOLITH))
    .end()

    .choice()
        .when(simple("${exchangeProperty." + TARGET_PROPERTY + "} == '" + TARGET_REVIEW + "'"))
            .to(reviewServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
        .otherwise()
            .to(monolithBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
    .end();
```

- **Consumer.** `matchOnUriPrefix=true` lets one consumer take every path under `/api`, so the route isn't rewritten when a resource appears. The cost: `platform-http` puts the full incoming path in `CamelHttpPath`, `/api` prefix included, not a path relative to the consumer.
- **First `choice()`.** This is the content-based router from *Enterprise Integration Patterns*. A request is tagged `stranglerTarget=review` only when the path starts with `/api/reviews` and `strangler.review.enabled` is on. Everything else, including `/api/reviews` with the flag off, is tagged `monolith`. Keeping the decision in an exchange property, separate from the forward, lets you diagnose a routing bug by reading the decision alone.
- **Second `choice()`.** This is the reverse proxy. `bridgeEndpoint=true` reuses the inbound method, path and query string exactly as received instead of building a new URI from the Camel message; without it every header, query parameter and the verb would be copied by hand, and any one missed is an observable difference. With it, nothing differs between talking to the proxy and talking to a backend. `throwExceptionOnFailure=false` stops Camel from turning a backend 4xx or 5xx into an exception; without it a `402` or `404` reaches the client as Camel's generic error response instead of the backend's real status and body.
- **Fixed destinations.** `strangler.monolith.base-url` and `strangler.review.base-url` are operator-configured constants. The route picks between two known URIs and never interpolates a header or path segment into a destination, so nothing in a request can steer the proxy to an arbitrary host.

The flag is a plain `@ConfigProperty`:

```java
@ConfigProperty(name = "strangler.review.enabled", defaultValue = "false")
boolean reviewEnabled;
```

Flipping it touches no route code. Set it in `application.properties`, or override it with `-Dstrangler.review.enabled=true` or `STRANGLER_REVIEW_ENABLED=true`, and restart; the route reads the CDI-managed value on every request. A routing decision baked into source needs a build, a review and a deploy to reverse, three chances for the reversal itself to fail. A decision read from config needs none of them.

## The reversibility window: a bug the equivalence suite missed

The route shown earlier is the fixed version. With the flag at `false`, the Newman equivalence run (sixteen requests, forty-nine assertions, captured once against the running monolith and never edited) ran through the proxy and passed 49 of 49, as expected. Flipping the flag to `true` should have sent Review traffic to the Quarkus service. The run again passed 49 of 49, and that result was wrong.

The original predicate checked `startsWith '/reviews'`, which never matches `/api/reviews`. Every request, flag on or off, fell through to the monolith. The run could not see it because the Review service reads and writes the same `reviews` table in the same database as the monolith's Review module. Two processes serving identical rows return the same bytes, so a black-box contract check cannot tell the new service answering from the old backend answering with identical data. The assertions weren't wrong; they just don't test routing when both backends agree.

What surfaced the bug was simpler than any added assertion: stop the monolith and retry the same request through the proxy. A correct cutover keeps working, because the Review service is a separate process. Instead the request failed exactly like every other `/api/**` path. That tests the routing decision itself. After the predicate was corrected to `startsWith '/api/reviews'`, the same check came back as it should: Review requests succeeded and every other path failed. The repair happened entirely inside the proxy, which is the containment a strangler seam is meant to buy. While both the monolith's Review module and the extracted service existed, the flag could move either way, and both directions were proven against the full suite.

{% include codetabs.html langs="Before the cutover (flag=false)|After the cutover (flag=true, routing fixed)" %}

```bash
# Flag off: every request, including Review, reaches the monolith.
curl -s localhost:8888/api/reviews?sku=SKU-WIDGET-001   # 200, monolith's Review module
curl -s localhost:8888/api/orders                       # 200, monolith (unaffected)
```

```bash
# Flag on, monolith stopped entirely, to prove the routing decision itself:
curl -s localhost:8888/api/reviews?sku=SKU-WIDGET-001   # 200, review-service; monolith is down
curl -s localhost:8888/api/orders                       # 500, still targets the dead monolith, as expected
```

## Decommission: the one irreversible step

Once the routing fix was verified, the monolith's Review module (controller, service, repository, entity, DTOs and the tests written against them) was deleted from `examples/00-monolith/`. Its security configuration stayed: with no route matching `/api/reviews`, the one authenticated rule never fires, and leaving it kept the change inside Review's own files. The `reviews` table and its seed data stayed too, because the extracted service still depends on them and data ownership moves later. Then `strangler.review.enabled=true` became the committed default in `application.properties`.

The result is observable. `GET http://localhost:8080/api/reviews` returns `404 Not Found` and `GET http://localhost:8080/api/orders` returns `200 OK`. Restarting the proxy with `-Dstrangler.review.enabled=false` still works mechanically, but `/api/reviews` now returns `404`, because the backend that value used to select has nothing to serve. The mechanism that provided reversibility still functions; one of its two destinations is gone.

Decommission goes last, after every other check is green. Retiring the old code before the routing fix was verified would have destroyed the fallback that made diagnosing the predicate bug cheap. Retiring it only after the stop-the-monolith check proved the new service was answering is what keeps "incremental" from becoming "big-bang, spread over more calendar time."

> **Decision**
>
> Put a router in front, cut one path prefix at a time behind a configuration flag, prove the routing with the old backend stopped, and retire old code only after that proof.

> **🤖 Explore with your agent**
>
> "Read `examples/01-strangler-proxy/src/main/java/dev/patterncatalyst/strangler/StranglerProxyRoute.java` and `CUTOVER.md`. Review the routing predicates for any path that would match the wrong prefix (for example `/api/reviews` against a longer path), confirm no destination URI is built from request data, and list the checks I should run with a backend stopped before retiring any old code."
>
> Check: the reply should point at the `startsWith` predicates and `CamelHttpPath` including the `/api` prefix, and propose a stop-the-backend request per extracted context.

## For deeper coverage

- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the incremental, strangler-first migration playbook this whole book follows.
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the canonical write-up of the pattern the Camel proxy implements.
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the content-based router the strangler proxy's `choice()`/`when()`/`otherwise()` route is built from.
- Chris Richardson, [microservices.io: Strangler Application](https://microservices.io/patterns/refactoring/strangler-application.html): a second, independent catalog entry for the same pattern, useful for cross-checking Fowler's narrative against a pattern-language framing.
- Cloud Native Design Patterns, [Composition](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/03-composition.md): the companion book's treatment of fronting a system under migration with a composition layer, the same role the proxy plays.
