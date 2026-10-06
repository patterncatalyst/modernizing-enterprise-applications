---
title: "The Quarkus / MicroProfile Chassis"
order: 27
part: "Communication & Contracts"
description: "Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client, JWT; build-time optimization, native image, and the dev-mode inner loop."
---

This chapter introduces no new extraction and no new runnable example. It
names the chassis every service from Chapter 15 on was built on top of, and
it audits — capability by capability, against this frontmatter's own menu —
which parts of that chassis a service actually wired in versus which parts
Quarkus merely makes available. Every claim below is grounded in code
already in this repository: `examples/01-strangler-proxy/`'s
`StranglerProxyRoute`, `examples/07-order-service/`'s and
`examples/08-graphql-gateway/`'s `RemoteInventoryClient`/`GatewayApi` gRPC
deadlines, six services' `pom.xml`s, `examples/02-review-service/`'s
`ReviewResource` and its `application.properties`, the monolith's
(still-present) `SecurityConfig`, `examples/06-shipping-service/`'s
`OrderServiceClient`, four REST client interfaces under
`examples/08-graphql-gateway/`, and the `ApiError` record lifted into six
services. Chapter 25 closed its own resilience scorecard with a forward
reference aimed squarely at this one: "a chassis capability a framework
offers is only a chassis capability a *service* has if someone wires it
in." This chapter is that sentence, applied to the whole frontmatter instead
of one annotation.

That is the discipline this chapter asks you to hold onto from here to the
scorecard at the end: a dependency sitting in a `pom.xml` is a capability
*available*. An annotation actually placed on a method, a property actually
set in `application.properties`, a test that actually proves the wiring
works — those are a capability *used*. The frontmatter above lists seven
things MicroProfile and Quarkus offer. This project uses two of them fully,
leans on a third for exactly one write endpoint, and leaves the rest on the
shelf, by choice and for a stated reason. Naming which is which, and why, is
the whole chapter.

## One chassis, six services

Every Quarkus service extracted since Chapter 15 — review, notification,
inventory, payment, shipping, order, plus the aggregation-only
graphql-gateway — is built against the same `io.quarkus.platform:quarkus-bom`
(version `3.40.1`, pinned via the identical `quarkus.platform.version`
property in all eight `pom.xml` files, counting
`examples/01-strangler-proxy/` alongside the seven application services).
That shared BOM, plus `quarkus-arc` (the CDI container every one of these
services declares explicitly) is the one piece of chassis every single
service has in common, no exceptions. Layered on top of that floor, a
smaller set of extensions repeats across most but not all of them:
`quarkus-rest-jackson` (the JAX-RS/Jackson stack `ReviewResource` and its
five siblings run on), `quarkus-hibernate-orm-panache` (every service that
owns a database table), `quarkus-flyway` (every service that owns its own
schema — notably absent from `review-service`, which still reads the
monolith's shared schema and therefore must never run a migration of its
own), and `quarkus-messaging-kafka` (every service on the async side of an
outbox or a saga — notification, inventory, payment, shipping, order; absent
from review, which has no event traffic, and from the gateway, which
aggregates over REST and gRPC and publishes nothing).

That's the floor. Everything from here down is the frontmatter's menu of
MicroProfile capabilities sitting *above* that floor — Config, Fault
Tolerance, Health, Metrics, OpenAPI, REST Client, Security — and a direct
answer, capability by capability, to "did anyone actually turn this on."

## Configuration: `@ConfigProperty` and profile-aware properties

**Status: USED.** This is the capability with the least daylight between
"the frontmatter promises it" and "the services actually do it." SmallRye
Config — MicroProfile Config's implementation, bundled into every Quarkus
service by default — is how every tunable value in this system reaches the
code that needs it, and it replaced a narrower, less consistent set of
Spring mechanisms doing the same job in the monolith.

The clearest side-by-side is the inventory gRPC timeout, because this
project has both versions of it on record. The monolith's own
`RemoteInventoryClient` — gone from the working tree today, decommissioned
along with the rest of the monolith's inventory module in order-plan S10,
but preserved in this project's git history (commit `45618ca`) and already
quoted once, in Chapter 25, as the resilience chapter's worked example —
took its timeout as a constructor-injected `@Value`:

{% include codetabs.html langs="Monolith — Spring @Value, constructor injection (pre-decommission)|order-service — Quarkus @ConfigProperty, field injection (current)" %}

```java
// examples/00-monolith/.../inventory/RemoteInventoryClient.java (pre-decommission)
public RemoteInventoryClient(
        @Value("${inventory.grpc.host:localhost}") String host,
        @Value("${inventory.grpc.port:9004}") int port,
        @Value("${inventory.grpc.timeout-ms:5000}") long timeoutMs) {
    this.host = host;
    this.port = port;
    this.timeoutMs = timeoutMs;
}
```

```java
// examples/07-order-service/.../order/RemoteInventoryClient.java
@ConfigProperty(name = "inventory.grpc.timeout-ms", defaultValue = "5000")
long inventoryGrpcTimeoutMs;
```

Same key, same default, same five-second value — the order service's own
`application.properties` pins `inventory.grpc.timeout-ms=5000` explicitly —
carried forward unchanged across the migration. What changed is the
injection style: Spring's `@Value` takes a SpEL-flavored placeholder string
on a constructor parameter; MicroProfile's `@ConfigProperty` takes a plain
key and an explicit `defaultValue` on a field, resolved by SmallRye Config
before CDI ever calls a constructor. The field version reads as slightly
more declarative — the key and its default sit next to each other, not
threaded through a string template — but the two are doing the identical
job, which is the point: this is not a capability the migration had to
learn from nothing, it's a capability Spring already had, expressed in a
different idiom.

`StranglerProxyRoute` is the widest single use of `@ConfigProperty` in the
system: six fields, one per extracted context, each bound to its own
`strangler.{review,notification,inventory,payment,shipping,order}.base-url`
key —

```java
// examples/01-strangler-proxy/.../StranglerProxyRoute.java
@ConfigProperty(name = "strangler.review.base-url")
String reviewServiceBaseUrl;

@ConfigProperty(name = "strangler.notification.base-url")
String notificationServiceBaseUrl;
// ... inventory, payment, shipping, order, same pattern
```

— each one a fixed, operator-set routing target the edge router's
`configure()` method dispatches to by URI prefix. No flag selects between
them anymore (order-plan S10 retired the six `strangler.*.enabled` cutover
switches once the monolith had nothing left to route to), but the six
`base-url` properties themselves are exactly the kind of environment-shaped
value `@ConfigProperty` exists for: the same route code reaches
`http://localhost:8081` in a developer's local run and a different host
entirely in any other topology, with zero code change.

Profile-aware configuration is the second half of this capability, and it
shows up wherever a service needs one answer in dev and a different one in
production without two separate property files. `examples/07-order-service/`
and `examples/06-shipping-service/` both prefix their datasource block with
`%dev.` and `%prod.`:

```properties
# examples/07-order-service/.../application.properties
%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service
```

and both leave the unprefixed (and therefore `%test`-reachable) key unset by
the same stated choice the comment in each file explains — a second
configuration decision hiding inside the first: leaving the `%test` profile
without an explicit datasource lets Quarkus Dev
Services stand up an isolated, ephemeral Testcontainers Postgres for
`@QuarkusTest` runs, rather than pointing tests at the same long-lived
podman-stack database `%dev` and `%prod` share. The gateway and shipping
both also use environment-variable defaults inside their property values —
`${ORDER_SERVICE_URL:http://localhost:8087}` in the gateway,
`${ORDER_SERVICE_BASE_URL:http://localhost:8087}` in shipping, the same
`${VAR:default}` SmallRye Config syntax used throughout both files' Kafka
bootstrap and REST client URLs — so a container-orchestrated deployment can
override every downstream address without touching a committed file at all.

## Fault Tolerance: the annotations this book does not use

**Status: DEFERRED.** This is the chapter's centerpiece, because it is the
sharpest gap between what the frontmatter names and what any service does.
Grep this project's `examples/` tree for `@Retry`, `@Timeout`,
`@CircuitBreaker`, `@Fallback`, or `@Bulkhead` and the result is empty —
zero hits, in any service, anywhere. No `pom.xml` in this repository
declares `smallrye-fault-tolerance`. MicroProfile Fault Tolerance is a real
Quarkus extension this project could add in one dependency line, and it is
not here.

What stands in its place is the same mechanism Chapter 25 already walked at
length: a manual `withDeadlineAfter` call at every synchronous, mutating
gRPC call site this system has. `examples/07-order-service/`'s
`RemoteInventoryClient` carries it at three call sites — `reserve`,
`release`, and `getStock` — all three keyed off the same
`inventory.grpc.timeout-ms` field quoted above:

```java
// examples/07-order-service/.../order/RemoteInventoryClient.java
public ReserveResult reserve(String sku, int quantity) {
    ReserveReply reply = inventoryStub.withDeadlineAfter(inventoryGrpcTimeoutMs, TimeUnit.MILLISECONDS)
            .reserve(ReserveRequest.newBuilder()
                    .setStockKeepingUnit(sku)
                    .setRequestedQty(quantity)
                    .build());
    return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
}
```

`examples/08-graphql-gateway/`'s `GatewayApi` carries the identical pattern
on its own gRPC call to inventory, hard-coded rather than config-driven
because the gateway's `stock` resolver is the one call site in this system
that hasn't yet earned its own tunable property:

```java
// examples/08-graphql-gateway/.../GatewayApi.java
public StockView stock(@Source OrderItemView item) {
    StockReply reply = inventoryClient.withDeadlineAfter(3, TimeUnit.SECONDS)
            .getStock(GetStockRequest.newBuilder()
                    .setStockKeepingUnit(item.sku())
                    .build());
    return new StockView(reply.getStockKeepingUnit(), reply.getOnHandQty(), reply.getAvailable());
}
```

Outside gRPC, the REST client side of this same discipline shows up as
plain connect/read timeouts in `application.properties` rather than an
annotation — shipping's `order-service` client sets
`connect-timeout=2000`/`read-timeout=3000`, and the gateway's four REST
clients each set `connect-timeout=5000`/`read-timeout=5000`. Same idea,
same boundedness, expressed as config rather than as `@Timeout`.

What a `@CircuitBreaker` or `@Bulkhead` would add on top of that — a state
machine that stops hammering a known-down dependency, a cap on concurrent
in-flight calls that isolates one slow collaborator's resource consumption
from the rest of a service — is exactly what Chapter 25 already named and
declined to build, for the same reason this chapter inherits rather than
re-argues: no measured failure rate in this project's own traffic has yet
justified the added complexity. This chapter has nothing to add to that
argument except where it lands once SmallRye Fault Tolerance is sitting in
every service's dependency tree rather than the monolith's: the day this
project's traffic justifies a circuit breaker, it is a one-annotation
addition on `RemoteInventoryClient#reserve`, not a redesign. Read Chapter
25's own
scorecard for the full taxonomy of what's deferred and why — this chapter
is not repeating that walkthrough, only confirming that nothing has
changed since: no fault-tolerance dependency has been added anywhere in
this tree.

## Health and readiness: `quarkus-smallrye-health` on six of eight

**Status: PARTIAL.** `quarkus-smallrye-health` sits in six `pom.xml` files —
notification, inventory, payment, shipping, order, and the graphql-gateway —
and is absent from two: `review-service` and `examples/01-strangler-proxy/`
(the monolith, not being a Quarkus service at all, was never a candidate).
Every service that declares the dependency gets the standard MicroProfile
Health surface at no extra cost: `/q/health`, `/q/health/live`,
`/q/health/ready`, assembled at build time with no custom `@Readiness` or
`@Liveness` bean written by hand anywhere in this codebase. For the five
services on the async side of a Kafka topic, that surface carries more than
it appears to at first glance — SmallRye Reactive Messaging ships its own
health-check integration that
wires straight into the same `/q/health/ready` endpoint, so a broken Kafka
connection becomes part of readiness automatically, without a line of
service-specific health code.

What this capability does not yet have is a consumer. Nothing in
`examples/01-strangler-proxy/`'s routing, and nothing in this repository's
compose or CI definitions, currently points a platform's liveness or
readiness probe at any of these six endpoints to gate traffic or restarts.
The six endpoints exist, respond correctly, and are not wired into anything
that acts on their answer — that wiring is Part 9's subject (the minikube
chapters), not this one's. Review-service's absence from the list is its
own small data point: the one service still sharing the monolith's
Postgres schema is also the one service with no readiness signal of its own.

## Metrics: named by the frontmatter, not yet wired

**Status: DEFERRED.** This one is short because there is nothing to
describe. No `pom.xml` in this project declares `quarkus-micrometer` or any
Micrometer registry extension; no service anywhere uses `@Timed` or
`@Counted`; no `/q/metrics` endpoint is exposed by anything this project
runs. The frontmatter names Metrics as part of the chassis because Quarkus
offers it, not because any service here has turned it on. Chapter 30 is
where this gets built — a Prometheus-scrapeable metrics surface is exactly
the kind of platform-consumption concern Part 9 exists to add, and adding
it now, with no dashboard or alerting rule waiting to consume it, would be
the same speculative-infrastructure trap Chapter 25 already named for
circuit breakers: a capability added because a catalog says a service
should have it, not because this project's own operational needs have
asked for it yet.

## OpenAPI: the contract surface the migration dropped

**Status: mostly DEFERRED on the Quarkus side.** The monolith had a real
OpenAPI surface, and it's still sitting in the frozen codebase today:
`springdoc-openapi-starter-webmvc-ui` in `examples/00-monolith/pom.xml`, and
a `OpenApiConfig` bean that registered it —

```java
// examples/00-monolith/.../config/OpenApiConfig.java
@Bean
public OpenAPI monolithOpenApi() {
    return new OpenAPI()
            .info(new Info()
                    .title("Reference Monolith API")
                    .version("0.1.0-r02")
                    .description(/* ... */));
}
```

That bean's own javadoc is candid about its current state: with every REST
controller extracted and the module decommissioned, the generated spec now
documents zero paths — "harmless and vacuous," left in place rather than
deleted for the same reason `SecurityConfig` was left in place after
Review's own cutover: removing it isn't required for the frozen module to
build or behave correctly as the suite's golden-baseline referent.

None of the six extracted Quarkus services picked this capability back up.
No `pom.xml` under `examples/02-review-service/` through
`examples/07-order-service/` declares `quarkus-smallrye-openapi` — a
one-dependency addition that would give any of them the identical
build-time-generated Swagger surface the monolith had, and none of them has
it. That is a real surface the migration dropped, not a wash: a client
that used to introspect the monolith's REST contract through a generated
spec has nothing equivalent to introspect on any of the six REST services
today.

The one place this project *did* carry the "introspectable contract" idea
forward is the aggregation layer, and it's a different technology doing a
related job rather than a direct replacement: `examples/08-graphql-gateway/`
runs on `quarkus-smallrye-graphql`, and a GraphQL schema is, by
construction, a machine-readable, introspectable description of every query
a client can issue against it — `order`, and the `payments`/`shipments`/
`reviews`/`stock` fields `GatewayApi`'s `@Source` resolvers hang off an
`OrderView`. It answers a narrower question than the monolith's OpenAPI spec
did (it describes the gateway's own aggregated view, not each of the six
services' REST contracts individually), but it is the one contract surface
in this system today a tool can still ask "what can I call here" and get a
real answer back.

## REST Client: typed HTTP clients in shipping and the gateway

**Status: USED.** Unlike Config, Health, and OpenAPI, the MicroProfile REST
Client has no Spring original to contrast against — the monolith talked to
inventory over gRPC and to every other context in-process, so there is no
`RestTemplate` or `WebClient` call this capability replaced. It's net-new,
and it shows up at exactly the two seams where a Quarkus service needs to
read from another Quarkus service's REST surface rather than its own
database or an event stream.

`examples/06-shipping-service/`'s saga enrich step is the narrower case —
one interface, one downstream dependency:

```java
// examples/06-shipping-service/.../OrderServiceClient.java
@RegisterRestClient(configKey = "order-service")
public interface OrderServiceClient {
    @GET
    @Path("/api/orders/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    OrderSummary getOrder(@PathParam("id") Long id);
}
```

bound to a URL and a pair of timeouts that live entirely in
`application.properties` — `quarkus.rest-client."order-service".url`,
defaulted from the service's own `order.service.base-url` property, with a
2-second connect timeout and a 3-second read timeout.

`examples/08-graphql-gateway/` is the wider case: four separate interfaces —
`OrderRestClient`, `PaymentRestClient`, `ShipmentRestClient`,
`ReviewRestClient` — each `@RegisterRestClient`-annotated with its own
`configKey` (`order-service`, `payment-service`, `shipping-service`,
`review-service`), each injected into `GatewayApi` with the matching
`@RestClient` field:

```java
// examples/08-graphql-gateway/.../GatewayApi.java
@RestClient
OrderRestClient orderRestClient;

@RestClient
PaymentRestClient paymentRestClient;

@RestClient
ShipmentRestClient shipmentRestClient;

@RestClient
ReviewRestClient reviewRestClient;
```

Every one of those four backs onto a config-driven URL and a
`connect-timeout`/`read-timeout` pair in the gateway's own
`application.properties` (`quarkus.rest-client.order-service.url`, and the
same shape repeated for the other three), each defaulted via the same
`${VAR:default}` pattern the gateway's gRPC and Kafka config already use.
This is the gateway's whole job, in fact — it owns no data of its own, and
every field it resolves is a call to one of these four typed clients or the
one gRPC stub next to them. A typed REST client interface, bound by a
config key rather than a hard-coded URL, is the idiomatic MicroProfile shape
for exactly this kind of fan-out, and it's the one frontmatter capability in
this chapter that needed no cross-framework translation to land — there was
nothing on the Spring side to translate from.

## Security: HTTP Basic today, JWT deferred

**Status: PARTIAL.** The frontmatter names JWT, and no service in this
project uses it — no `pom.xml` declares `quarkus-smallrye-jwt`. What one
service does use, and uses correctly, is HTTP Basic authentication via
`quarkus-elytron-security-properties-file`, and it exists for exactly one
reason: reproducing a single authenticated endpoint the monolith already
had, with the identical 401/201 contract.

The monolith's original `SecurityConfig` — still present in the frozen
codebase today, in `examples/00-monolith/`, left in place for the same
scope-discipline reason `OpenApiConfig` was — protected exactly one route,
`POST /api/reviews`, behind one in-memory demo user:

{% include codetabs.html langs="Monolith — Spring Security, one global filter chain|review-service — Quarkus HTTP Basic, @RolesAllowed" %}

```java
// examples/00-monolith/.../security/SecurityConfig.java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.POST, "/api/reviews").authenticated()
                    .anyRequest().permitAll())
            .httpBasic(Customizer.withDefaults());
    return http.build();
}

@Bean
public UserDetailsService userDetailsService(PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
            User.withUsername("demo-customer")
                    .password(encoder.encode("demo-pass"))
                    .roles("CUSTOMER")
                    .build());
}
```

```java
// examples/02-review-service/.../ReviewResource.java
@POST
@RolesAllowed("CUSTOMER")
public Response createReview(@Valid ReviewCreate command) {
    ReviewDto dto = service.createReview(command);
    return Response.created(URI.create("/api/reviews/" + dto.id())).entity(dto).build();
}
```

The annotation on the method is almost incidental — `@RolesAllowed` is a
plain Jakarta Security annotation, not a Spring one, so this part of the
code didn't change shape at all moving frameworks. What replaced Spring
Security's `SecurityFilterChain` bean and its `InMemoryUserDetailsManager`
is five lines in `application.properties`:

```properties
# examples/02-review-service/.../application.properties
quarkus.http.auth.basic=true
quarkus.security.users.embedded.enabled=true
quarkus.security.users.embedded.plain-text=true
quarkus.security.users.embedded.users.demo-customer=demo-pass
quarkus.security.users.embedded.roles.demo-customer=CUSTOMER
```

a declarative, config-only embedded realm reproducing the identical
`demo-customer`/`CUSTOMER` identity the monolith hard-coded in a Java
`@Bean`. The comment in that same file states the payoff directly: this
replaces the monolith's one shared, global filter chain — which every
other bounded context's endpoints sat inside, whether they needed
authentication or not — with review-service's own standalone config, owned
by the one service that actually needs it.

This isn't an asserted contract; the behavior-equivalence suite proves it.
Newman items **4c** ("Unauthenticated write is rejected → 401") and **4d**
("Authenticated write succeeds → 201 Created"), in
`tooling/newman/mea.postman_collection.json`, exercise exactly this pair —
the same anonymous `POST /api/reviews` that must fail, and the same
Basic-authenticated one that must succeed, run through the proxy against
whichever backend is live.

What this buys is real, and so is what it doesn't buy: HTTP Basic over a
demo in-memory realm authenticates one route in
one service. It is not a token-based identity a downstream service can
verify independently, it carries no claims, and it does nothing for any of
the other six services' endpoints, every one of which is unauthenticated
today. JWT — or an OIDC provider issuing tokens every service could
validate locally via `quarkus-smallrye-jwt`, without a shared session or a
call back to an identity store on every request — is the capability the
frontmatter actually names, and it is squarely deferred: no service in this
project issues or validates one.

## Build-time optimization and the native image

**Status: FULLY USED.** This is the other capability, alongside Config,
with no daylight between the frontmatter's promise and the code's reality.
Every one of the eight Quarkus `pom.xml` files in this repository — the
seven application services plus the strangler proxy — carries a `native`
Maven profile, activated by the `-Dnative` property, that flips
`quarkus.native.enabled` to `true` and `quarkus.package.jar.enabled` to
`false`:

```xml
<!-- examples/02-review-service/pom.xml, repeated (same shape) in all eight -->
<profile>
    <id>native</id>
    <activation>
        <property><name>native</name></property>
    </activation>
    <properties>
        <quarkus.package.jar.enabled>false</quarkus.package.jar.enabled>
        <skipITs>false</skipITs>
        <quarkus.native.enabled>true</quarkus.native.enabled>
    </properties>
</profile>
```

Six services' `ApiError` record carries `@RegisterForReflection` —
`review-service`, `notification-service`, `inventory-service`,
`payment-service`, `shipping-service`, and `order-service` — and that
annotation exists because Chapter 7 already walked the gate that caught it:
GraalVM's native image builds a closed world, resolving every reflective
access at build time rather than at runtime, and Quarkus's Jackson
reflection scan never saw `ApiError` as a type because every exception
mapper in this codebase returns the generic `jakarta.ws.rs.core.Response`
rather than `Response<ApiError>`. Under the JVM build that gap is invisible
— Jackson falls back to ordinary runtime reflection, and every 400/404
error-body test passes. Under native, an unregistered DTO reachable only
through a generic `Response` turns an intended 400 into a 500, and the fix
— one annotation, on the one class actually missing from the reflection
scan — is `@RegisterForReflection`, the idiomatic answer for exactly this
reflection shape (DRQ-029).

The payoff this buys is measured, not asserted, and Chapter 3's own
numbers for the review-service extraction are the record:

| Build | Startup time | Resident memory | Equivalence suite |
|---|---|---|---|
| Phase A — JVM, Spring-compat | 1.492 s | ~316 MB | 16/16 green |
| Phase B — native image | 0.048–0.049 s | ~73 MB | 16/16 green |

Roughly thirty-one times faster startup, just over four times less resident
memory, the identical 16-of-16 green equivalence result in both rows — this
is the one capability in this chapter where "the frontmatter promised it
and the code delivers it" needed no asterisk.

{% include excalidraw.html file="migration-metrics-review" alt="A two-band comparison figure for the review-service extraction's measured metrics. Top band, startup time: Phase A JVM Spring-compat at 1.492 seconds, Phase B JVM idiomatic Quarkus at 1.431 to 1.437 seconds, and Phase B native image at 0.048 to 0.049 seconds, with the native bar roughly 31 times shorter than Phase A's. Bottom band, resident memory: Phase A at about 316 MB, Phase B JVM at about 304 MB, and Phase B native at about 73 MB, with the native bar roughly 4 times shorter than Phase A's. Every row carries the same 16 of 16 green equivalence-suite result." caption="Figure 27.1 — Startup time and memory across Phase A, Phase B JVM, and Phase B native, same 16/16 equivalence suite throughout (reproduced from Chapter 3)" %}

## The dev-mode inner loop

**Status: USED, with a deliberate exception.** `quarkus:dev`'s live-reload
loop — edit a Java file, hit the endpoint again, see the change without a
restart — is the everyday development experience behind every one of this
project's Quarkus services, and nothing about that needs re-explaining here;
it's the same inner loop Quarkus always advertises, unmodified.

One place this project turns a piece of that convenience off, by stated
choice: `quarkus.datasource.devservices.enabled=false` is
set in every service's `application.properties`, and review-service's own
file states the reason directly: these services, for now, share a single
long-lived podman-stack Postgres instance rather than each dev loop spinning
up its own ephemeral Testcontainers database, because Phase A's whole point
for review-service was "the same shared database, unchanged," not an
isolated per-run database Dev Services would otherwise provide
automatically. The substitute for isolation, where isolation still matters,
is the `%test` profile paired with `smallrye-reactive-messaging-in-memory` —
the in-memory connector that lets a `@QuarkusTest` exercise a real
`@Incoming`/`@Outgoing` pipeline, like `OrderPlacedConsumerTest`'s
redelivery check, without a Kafka broker on the other end at all. Dev
Services is not simply missing; it's switched off at the datasource layer
and replaced by a narrower, targeted substitute at the messaging layer,
which is a different thing than an oversight.

## The chassis scorecard

Pulled together, here is exactly what this migration wired into the
MicroProfile chassis Quarkus offers, and what it left on the shelf:

**Used, and proven by real code:** Config (`@ConfigProperty` across every
service, `%dev`/`%prod`/`%test` profiles in order-service and
shipping-service, six base-url properties in `StranglerProxyRoute`,
environment-variable defaults in the gateway and shipping); REST Client
(`OrderServiceClient` in shipping, four typed clients in the gateway, every
one config-driven); build-time optimization and the native image (a
`native` profile in all eight poms, `@RegisterForReflection` on six
services' `ApiError`, a measured ~31× startup and ~4× memory improvement
with an unchanged 16/16 green suite); and the `quarkus:dev` inner loop
(with Dev Services switched off at the datasource layer by stated choice,
replaced by `%test` + the in-memory messaging connector).

**Partial, wired for one narrow case:** Health
(`quarkus-smallrye-health` on six of eight services, including automatic
broker-connectivity readiness, with no platform yet consuming any of it);
and Security (HTTP Basic plus `@RolesAllowed` protecting exactly one route
in review-service, proven by Newman 4c/4d, with every other endpoint in
this system unauthenticated and JWT/OIDC not yet attempted anywhere).

**Deferred, with a stated reason:** Fault Tolerance (zero
`@Retry`/`@Timeout`/`@CircuitBreaker`/`@Fallback`/`@Bulkhead` anywhere, no
`smallrye-fault-tolerance` dependency — manual `withDeadlineAfter` calls and
REST client timeouts stand in, same gap Chapter 25 already scoped);
Metrics (no Micrometer dependency, no `@Timed`/`@Counted`, no
`/q/metrics`, deferred to Chapter 30); and OpenAPI on the Quarkus side (the
monolith's springdoc surface was real and is now frozen/vacuous; none of
the six extracted services added `quarkus-smallrye-openapi`; the gateway's
introspectable GraphQL schema is the nearest replacement, and it answers a
narrower question than the surface that was dropped).

{% include excalidraw.html file="chassis-scorecard" alt="A two-column scorecard of the MicroProfile/Quarkus chassis this migration actually wired in. Left column, used or partially used: Config via @ConfigProperty and profile-aware properties across every service; REST Client via typed, config-driven clients in shipping and the gateway; build-time optimization and the native image, with the measured ~31x startup and ~4x memory payoff; the quarkus:dev inner loop with Dev Services switched off at the datasource layer by stated choice; Health on six of eight services with no platform consumer yet; and Security as HTTP Basic plus @RolesAllowed on exactly one route. Right column, deferred with a stated reason: Fault Tolerance (no annotations, no dependency, manual deadlines standing in); Metrics (no Micrometer, deferred to ch.30); OpenAPI on the Quarkus side (dropped from the monolith's springdoc surface, partially replaced by the gateway's GraphQL schema); and JWT/OIDC (no token-based identity anywhere in the system)." caption="Figure 27.2 — The chassis scorecard: what this migration wired in, and what it left on the shelf" %}

## What you learned

- **A chassis capability a framework offers is only a chassis capability a
  service has if someone wires it in** — Quarkus and MicroProfile make all
  seven capabilities in this chapter's frontmatter available to every
  service in this repository, and this project uses two of them fully
  (Config, build-time/native), one for a single endpoint (Security), one
  partially with no consumer yet (Health), and leaves three on the shelf, by
  stated choice (Fault Tolerance, Metrics, OpenAPI on the Quarkus side).
- **Config is the capability with the smallest gap between promise and
  practice** — the same `inventory.grpc.timeout-ms` key and the same
  five-second default survive the move from Spring's `@Value` to
  MicroProfile's `@ConfigProperty` unchanged, and `StranglerProxyRoute`'s
  six base-url fields show the same mechanism scaled to every extracted
  context at once.
- **The native-image payoff is the one number in this chapter that needed
  no hedge** — a ~31× faster startup and ~4× smaller footprint, against an
  identical 16/16 green equivalence suite, bought by a `native` Maven
  profile in all eight poms and a single `@RegisterForReflection` fix for a
  real closed-world reflection gap, not a hypothetical one.
- **Deferred is not the same as missing** — Fault Tolerance and Metrics
  have zero footprint in this codebase today, and that absence is named
  with a reason (no measured failure rate, no consumer waiting on a
  dashboard) rather than quietly carried forward unexamined, the same
  scope-discipline posture Chapter 25 already modeled for circuit breakers
  and Chapter 3 named for microservices themselves.
- **"Security" and "JWT" are not synonyms in this system** — review-service
  reproduces the monolith's one authenticated route with HTTP Basic and an
  embedded realm, proven by Newman's 401/201 pair, while every other
  endpoint in this system remains open and token-based identity has not
  been attempted anywhere.

Chapter 28 turns from the chassis a service runs on to the question of what
a service promises the services calling it — contracts, versioning, and the
service registry a system this size eventually needs once "whichever base
URL is in `application.properties` today" stops being a sufficient answer
for who's allowed to call whom, and with what guarantee the shape won't
change underneath them.

---

*Verification status: not applicable — conceptual chapter, no new runnable
example. Cited: `examples/01-strangler-proxy/` (`StranglerProxyRoute.java`'s
six `@ConfigProperty` base-url fields and its `native` Maven profile),
`examples/02-review-service/` (`ReviewResource.java`'s `@RolesAllowed`,
`application.properties`'s HTTP Basic/embedded-realm block and
`devservices.enabled=false`, `ApiError.java`'s `@RegisterForReflection`,
`pom.xml`'s absence of `quarkus-smallrye-health`), `examples/00-monolith/`
(`security/SecurityConfig.java`, `config/OpenApiConfig.java`, and, from git
history at commit `45618ca`, the pre-decommission
`inventory/RemoteInventoryClient.java`'s `@Value`-injected fields, already
quoted once in Chapter 25), `examples/06-shipping-service/`
(`OrderServiceClient.java`, `application.properties`'s REST client timeouts),
`examples/07-order-service/` (`order/RemoteInventoryClient.java`'s
`@ConfigProperty`-bound deadline and its three `withDeadlineAfter` call
sites, `application.properties`'s `%dev`/`%prod` profile blocks),
`examples/08-graphql-gateway/` (`GatewayApi.java`'s four `@RestClient`
fields and its own `withDeadlineAfter`, `OrderRestClient.java`/
`PaymentRestClient.java`/`ShipmentRestClient.java`/`ReviewRestClient.java`,
`application.properties`'s environment-variable-defaulted client URLs),
`tooling/newman/mea.postman_collection.json` (items 4c/4d), and
`_docs/03-modernization-as-engineering.md` (the Phase A/Phase B native
numbers table) and `_docs/25-failure-modes-and-resilience.md` (the
resilience scorecard this chapter's Fault Tolerance section cross-references
rather than repeats). Re-confirm: a fresh grep of `examples/` for
`@Retry|@Timeout|@CircuitBreaker|@Fallback|@Bulkhead`, `micrometer`,
`smallrye-openapi`, and `smallrye-jwt` across every `pom.xml` and `.java`
file still returns zero hits; the native startup/memory numbers and the
`inventory.grpc.timeout-ms` default still match the committed
`application.properties` and Chapter 3's table, since both are the kind of
measured, re-checkable values this book has already asked you to verify
independently more than once.*
