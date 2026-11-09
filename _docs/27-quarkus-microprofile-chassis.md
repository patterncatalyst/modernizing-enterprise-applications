---
title: "The Quarkus / MicroProfile Chassis"
order: 27
part: "Communication & Contracts"
description: "An audit of Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client and Security: what the services wire in versus what Quarkus only makes available, plus native image and the dev-mode loop."
---

## Learning objectives

By the end of this chapter you can:

- Audit which MicroProfile capabilities a service wires in versus which ones a dependency in `pom.xml` merely makes available.
- Replace Spring's `@Value` with SmallRye Config's `@ConfigProperty` and `%dev`/`%prod`/`%test` profiles without changing the key or default.
- Say why the services use manual `withDeadlineAfter` deadlines and REST client timeouts instead of Fault Tolerance annotations, and what `@CircuitBreaker` would require.
- Read the MicroProfile Health surface (`/q/health/live`, `/q/health/ready`, `/q/health/started`) and how the Reactive Messaging broker check rolls into readiness.
- Fix the GraalVM closed-world reflection gap with `@RegisterForReflection` for a DTO reachable only through a generic `Response`.
- Distinguish HTTP Basic with `@RolesAllowed` from JWT/OIDC, and securing one route from securing a system.
- Read a chassis scorecard that separates used, partial and deferred.

## Why this matters

A framework capability is a capability a service has only when someone wires it in. A dependency in a `pom.xml` is a capability available. An annotation on a method, a property in `application.properties` and a check that proves the wiring is a capability used. This audit covers seven capabilities (Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client, Security) against the code in `examples/`: `StranglerProxyRoute` in `examples/01-strangler-proxy/`, the gRPC deadlines in `examples/07-order-service/` and `examples/08-graphql-gateway/`, the `pom.xml` files, `ReviewResource` in `examples/02-review-service/`, the monolith's still-present `SecurityConfig`, `OrderServiceClient` in `examples/06-shipping-service/`, the four REST clients in the gateway and the `ApiError` record lifted into six services. The result: two capabilities fully used, one for a single endpoint, and the rest on the shelf by stated choice.

## One chassis, eight builds

Every Quarkus service (review, notification, inventory, payment, shipping, order, the aggregation-only graphql-gateway and the strangler proxy) builds against the same `io.quarkus.platform:quarkus-bom`, pinned by an identical `quarkus.platform.version` property in all eight `pom.xml` files, plus `quarkus-arc`, the CDI container each declares explicitly. A smaller set of extensions repeats across most of them:

| Extension | Where | Absent from |
|---|---|---|
| `quarkus-rest-jackson` | The JAX-RS/Jackson stack under `ReviewResource` and its siblings | |
| `quarkus-hibernate-orm-panache` | Every service that owns a table | |
| `quarkus-flyway` | Every service that owns its schema | `review-service`, which reads the monolith's shared schema and must never run a migration |
| `quarkus-messaging-kafka` | Services on the async side of an outbox or saga: notification, inventory, payment, shipping, order | review (no event traffic) and the gateway (publishes nothing) |

## Configuration: `@ConfigProperty` and profiles

**Status: used.** SmallRye Config is bundled into every Quarkus service and carries every tunable value. The clearest side-by-side is the inventory gRPC timeout. The monolith's `RemoteInventoryClient`, deleted at decommission and kept at `stage/03-inventory-extracted`, took it by constructor-injected `@Value`; the order service takes it by `@ConfigProperty`:

{% include codetabs.html langs="Monolith: Spring @Value, constructor injection (pre-decommission)|order-service: Quarkus @ConfigProperty, field injection (current)" %}

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

Same key, same default, same five seconds; the order service's `application.properties` pins `inventory.grpc.timeout-ms=5000`. Spring's `@Value` takes a SpEL-flavored placeholder on a constructor parameter. `@ConfigProperty` takes a plain key and an explicit `defaultValue` on a field, resolved before CDI calls a constructor. The field version keeps the key and default side by side. It is the same capability in a different idiom, not one the migration had to learn from nothing.

`StranglerProxyRoute` is the widest use: six fields, one per extracted context, each bound to its own `strangler.<context>.base-url` key.

```java
// examples/01-strangler-proxy/.../StranglerProxyRoute.java
@ConfigProperty(name = "strangler.review.base-url")
String reviewServiceBaseUrl;

@ConfigProperty(name = "strangler.notification.base-url")
String notificationServiceBaseUrl;
// ... inventory, payment, shipping, order, same pattern
```

Each is a fixed, operator-set routing target the router dispatches to by URI prefix. The `strangler.*.enabled` switches are retired, but the six base URLs are the environment-shaped values `@ConfigProperty` exists for: `http://localhost:8081` in a local run, another host in any other topology, with no code change.

Profile-aware properties give one answer in dev and another in production without two files. `examples/07-order-service/` and `examples/06-shipping-service/` prefix their datasource block with `%dev.` and `%prod.`:

```properties
# examples/07-order-service/.../application.properties
%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service
```

Both leave the `%test` datasource unset, so Quarkus Dev Services starts an isolated, ephemeral Testcontainers Postgres for `@QuarkusTest` runs instead of pointing tests at the long-lived compose database that `%dev` and `%prod` share. The gateway and shipping also use `${VAR:default}` values, `${ORDER_SERVICE_URL:http://localhost:8087}` in the gateway and `${ORDER_SERVICE_BASE_URL:http://localhost:8087}` in shipping, so a container-orchestrated deployment overrides every downstream address without touching a committed file.

## Fault Tolerance: the annotations the services don't use

**Status: deferred.** Grep `examples/` for `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Fallback` or `@Bulkhead` and the result is empty. No `pom.xml` declares `smallrye-fault-tolerance`. The extension is one dependency line away.

In its place is a manual `withDeadlineAfter` at every synchronous, mutating gRPC call site. `RemoteInventoryClient` in `examples/07-order-service/` carries it at `reserve`, `release` and `getStock`, all keyed off `inventory.grpc.timeout-ms`. `GatewayApi` in `examples/08-graphql-gateway/` carries it on its `stock` resolver with a hard-coded three seconds, because that is the one call site that hasn't earned a tunable property. On the REST side the same bound is plain config: shipping's `order-service` client sets `connect-timeout=2000` and `read-timeout=3000`, and the gateway's four REST clients each set `connect-timeout=5000` and `read-timeout=5000`.

A `@CircuitBreaker` adds a state machine that stops hammering a known-down dependency, and a `@Bulkhead` caps in-flight calls to isolate one slow collaborator. Neither is built, because no measured failure rate in the project's traffic justifies the complexity. When one does, it is an annotation on `RemoteInventoryClient#reserve`, not a redesign. Nothing has been added to any dependency tree since.

## Health and readiness

**Status: partial.** `quarkus-smallrye-health` is in six `pom.xml` files (notification, inventory, payment, shipping, order, graphql-gateway) and absent from `review-service` and the strangler proxy. Each service that declares it gets `/q/health`, `/q/health/live` and `/q/health/ready`, assembled at build time with no hand-written `@Readiness` or `@Liveness` bean. For the five services on the async side of a Kafka topic, SmallRye Reactive Messaging's own health integration feeds the same `/q/health/ready`, so a broken Kafka connection becomes part of readiness with no service-specific code.

The capability has no consumer yet. Nothing in the proxy's routing or the compose and CI definitions points a platform liveness or readiness probe at these endpoints; that wiring belongs to the minikube deployment work. Review-service, the one service still sharing the monolith's schema, is also the one with no readiness signal.

## Metrics

**Status: deferred.** No `pom.xml` declares `quarkus-micrometer` or a Micrometer registry, no service uses `@Timed` or `@Counted`, and nothing exposes `/q/metrics`. The metrics surface arrives with the platform work that consumes it. Adding it earlier, with no dashboard or alert waiting, is the same capability-from-a-catalog trap as an unjustified circuit breaker.

## OpenAPI: the contract surface the migration dropped

**Status: mostly deferred on the Quarkus side.** The monolith had a real OpenAPI surface and still carries it: `springdoc-openapi-starter-webmvc-ui` in `examples/00-monolith/pom.xml` and an `OpenApiConfig` bean titled "Reference Monolith API". With every controller extracted, its javadoc records that the generated spec documents zero paths, "harmless and vacuous". It stays for the same reason `SecurityConfig` stayed after the Review cutover: removal isn't needed for the frozen module to build and serve as the golden-baseline referent.

No extracted service picked the capability back up. No `pom.xml` from `examples/02-review-service/` through `examples/07-order-service/` declares `quarkus-smallrye-openapi`, a one-dependency addition. A client that used to introspect the monolith's REST contract through a generated spec has nothing equivalent for any of the six REST services.

The idea survives in the aggregation layer, with a different technology. `examples/08-graphql-gateway/` runs on `quarkus-smallrye-graphql`, and a GraphQL schema is a machine-readable, introspectable description of every query a client can issue: `order`, and the `payments`, `shipments`, `reviews` and `stock` fields hung off `OrderView`. It describes the gateway's aggregated view, not each service's REST contract, but it is the one surface in the system a tool can ask "what can I call here".

## REST Client

**Status: used.** The MicroProfile REST Client has no Spring original to contrast: the monolith spoke gRPC to inventory and in-process to everything else, so no `RestTemplate` or `WebClient` call was replaced. It appears at the two seams where a Quarkus service reads another Quarkus service's REST surface.

Shipping's saga enrichment step is the narrow case, one interface and one dependency:

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

The URL and timeouts live in `application.properties`: `quarkus.rest-client."order-service".url`, defaulted from `order.service.base-url`, with a 2-second connect timeout and a 3-second read timeout.

The gateway is the wide case: `OrderRestClient`, `PaymentRestClient`, `ShipmentRestClient` and `ReviewRestClient`, each `@RegisterRestClient` with its own `configKey` (`order-service`, `payment-service`, `shipping-service`, `review-service`) and injected into `GatewayApi` as an `@RestClient` field. Each has a config-driven URL and a `connect-timeout`/`read-timeout` pair defaulted with the same `${VAR:default}` pattern as the gateway's gRPC and Kafka config. A typed interface bound by a config key, not a hard-coded URL, is the idiomatic MicroProfile shape for this fan-out.

## Security: HTTP Basic today, JWT deferred

**Status: partial.** No `pom.xml` declares `quarkus-smallrye-jwt`. One service uses HTTP Basic through `quarkus-elytron-security-properties-file`, to reproduce the single authenticated endpoint the monolith had with the identical 401/201 contract. The monolith's `SecurityConfig` protects `POST /api/reviews` behind one in-memory demo user:

{% include codetabs.html langs="Monolith: Spring Security, one global filter chain|review-service: Quarkus HTTP Basic, @RolesAllowed" %}

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

`@RolesAllowed` is a plain Jakarta Security annotation, so that part did not change shape. What replaces the `SecurityFilterChain` bean and `InMemoryUserDetailsManager` is five lines of configuration:

```properties
# examples/02-review-service/.../application.properties
quarkus.http.auth.basic=true
quarkus.security.users.embedded.enabled=true
quarkus.security.users.embedded.plain-text=true
quarkus.security.users.embedded.users.demo-customer=demo-pass
quarkus.security.users.embedded.roles.demo-customer=CUSTOMER
```

A declarative embedded realm reproduces the `demo-customer`/`CUSTOMER` identity the monolith hard-coded in a `@Bean`. The monolith's one shared, global filter chain covered every context's endpoints whether they needed authentication or not; this config belongs to the one service that does. The Newman collection in `tooling/newman/mea.postman_collection.json` checks the pair through the proxy against whichever backend is live: an unauthenticated write is rejected with 401 and an authenticated one returns 201.

The limits are real. Basic over a demo in-memory realm authenticates one route in one service. It carries no claims, no downstream service can verify it independently, and the other six services' endpoints are unauthenticated. JWT or an OIDC provider issuing tokens every service validates locally with `quarkus-smallrye-jwt`, without a shared session or an identity-store call per request, is the capability the menu names and it is deferred.

## Build-time optimization and the native image

**Status: fully used.** All eight Quarkus `pom.xml` files carry a `native` Maven profile, activated by `-Dnative`, that sets `quarkus.native.enabled` to `true` and `quarkus.package.jar.enabled` to `false`:

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

Six services' `ApiError` records carry `@RegisterForReflection`: review, notification, inventory, payment, shipping and order. GraalVM's native image builds a closed world, resolving reflective access at build time. Quarkus's Jackson reflection scan never saw `ApiError` as a type because every exception mapper returns the generic `jakarta.ws.rs.core.Response`, not `Response<ApiError>`. On the JVM the gap is invisible: Jackson falls back to runtime reflection and every 400/404 error-body test passes. Under native, an unregistered DTO reachable only through a generic `Response` turns an intended 400 into a 500. One annotation on the one missing class is the idiomatic fix.

The review-service extraction measured the payoff:

| Build | Startup time | Resident memory | Equivalence suite |
|---|---|---|---|
| Phase A: JVM, Spring-compat | 1.492 s | ~316 MB | 16/16 green |
| Phase B: native image | 0.048-0.049 s | ~73 MB | 16/16 green |

That is roughly thirty-one times faster startup and just over four times less resident memory with the identical 16-of-16 result.

{% include excalidraw.html file="migration-metrics-review" alt="A two-band comparison figure for the review-service extraction's measured metrics. Top band, startup time: Phase A JVM Spring-compat at 1.492 seconds, Phase B JVM idiomatic Quarkus at 1.431 to 1.437 seconds, and Phase B native image at 0.048 to 0.049 seconds, with the native bar roughly 31 times shorter than Phase A's. Bottom band, resident memory: Phase A at about 316 MB, Phase B JVM at about 304 MB, and Phase B native at about 73 MB, with the native bar roughly 4 times shorter than Phase A's. Every row carries the same 16 of 16 green equivalence-suite result." caption="Figure 27.1: Startup time and memory across Phase A, Phase B JVM, and Phase B native, same 16/16 equivalence suite throughout" %}

## The dev-mode inner loop

**Status: used, with one exception.** `quarkus:dev` live reload is the everyday loop for every service. The exception is `quarkus.datasource.devservices.enabled=false` in every service's `application.properties`. Review-service's file gives the reason: the services share one long-lived compose Postgres instead of each dev loop starting an ephemeral Testcontainers database, because review's Phase A was "the same shared database, unchanged". Where isolation matters, the `%test` profile pairs with `smallrye-reactive-messaging-in-memory`, which lets a `@QuarkusTest` exercise a real `@Incoming`/`@Outgoing` pipeline, such as the redelivery check on `OrderPlacedConsumer`, without a Kafka broker. Dev Services is switched off at the datasource layer and replaced by a narrower substitute at the messaging layer.

## The chassis scorecard

| Status | Capability | Evidence |
|---|---|---|
| Used | Config | `@ConfigProperty` in every service; `%dev`/`%prod`/`%test` profiles in order and shipping; six base URLs in `StranglerProxyRoute`; `${VAR:default}` in the gateway and shipping |
| Used | REST Client | `OrderServiceClient` in shipping; four typed clients in the gateway, all config-driven |
| Used | Native image | `native` profile in all eight poms; `@RegisterForReflection` on six `ApiError` records; about 31x startup and 4x memory gain with 16/16 green |
| Used | Dev loop | `quarkus:dev`; Dev Services off at the datasource, `%test` plus in-memory messaging instead |
| Partial | Health | `quarkus-smallrye-health` on six of eight services, broker readiness included, no platform consumer |
| Partial | Security | HTTP Basic with `@RolesAllowed` on one route in review-service; JWT/OIDC not attempted |
| Deferred | Fault Tolerance | No annotations, no dependency; `withDeadlineAfter` and REST timeouts stand in |
| Deferred | Metrics | No Micrometer, no `/q/metrics` |
| Deferred | OpenAPI | Monolith's springdoc surface now vacuous; no `quarkus-smallrye-openapi`; the GraphQL schema is the nearest replacement and answers a narrower question |

{% include excalidraw.html file="chassis-scorecard" alt="A two-column scorecard of the MicroProfile/Quarkus chassis this migration actually wired in. Left column, used or partially used: Config via @ConfigProperty and profile-aware properties across every service; REST Client via typed, config-driven clients in shipping and the gateway; build-time optimization and the native image, with the measured ~31x startup and ~4x memory payoff; the quarkus:dev inner loop with Dev Services switched off at the datasource layer by stated choice; Health on six of eight services with no platform consumer yet; and Security as HTTP Basic plus @RolesAllowed on exactly one route. Right column, deferred with a stated reason: Fault Tolerance (no annotations, no dependency, manual deadlines standing in); Metrics (no Micrometer, deferred to the platform work); OpenAPI on the Quarkus side (dropped from the monolith's springdoc surface, partially replaced by the gateway's GraphQL schema); and JWT/OIDC (no token-based identity anywhere in the system)." caption="Figure 27.2: The chassis scorecard: what this migration wired in, and what it left on the shelf" %}

> **Decision**
>
> Count a capability as used only when an annotation, a property and a check prove it. Add a deferred one (Fault Tolerance, Metrics, OpenAPI, JWT) when traffic, a consumer or a contract need asks for it, and record the gap with its reason.

> **🤖 Explore with your agent**
>
> "Audit `examples/` against this chassis scorecard: for each of Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client and Security, grep the `pom.xml` files and `src/` trees of the services and tell me which capabilities are used, partial or only declared. Then add `quarkus-smallrye-openapi` to `examples/02-review-service` only, and show the generated spec for `POST /api/reviews`."
>
> Check: `grep -rlE '@Retry|@Timeout|@CircuitBreaker|@Fallback|@Bulkhead' examples/` prints nothing before your change, and `curl localhost:8081/q/openapi` returns the review-service spec after it.

## Build, run, observe

- Audit by grep: `grep -rlE '@Retry|@Timeout|@CircuitBreaker|@Fallback|@Bulkhead' examples/` is empty, and `grep -l quarkus-smallrye-health examples/*/pom.xml` lists notification, inventory, payment, shipping, order, the gateway and the schema-registry demo, never review-service or the proxy.
- Start a service with `./mvnw quarkus:dev` and read `/q/health/ready` on its port (notification :8083, inventory :8084, payment :8085, order :8087, shipping :8088).
- Run the Newman collection through the proxy: the anonymous `POST /api/reviews` returns 401 and the Basic-authenticated one returns 201.
- Build a native executable with `./mvnw package -Dnative` and compare startup and resident memory with the JVM build.
- Remove `@RegisterForReflection` from `ApiError` and run the native build: an intended 400 becomes a 500.

## For deeper coverage

- [Quarkus: Configuration guide](https://quarkus.io/guides/config): the `@ConfigProperty` and profile mechanism.
- [MicroProfile](https://microprofile.io/): the spec family (Config, Fault Tolerance, Health, Metrics, OpenAPI, REST Client, JWT) audited here.
- Michael Nygard, *Release It!* (2nd ed., Pragmatic Bookshelf, 2018): the stability patterns (timeouts, circuit breakers, bulkheads) named as available and not yet adopted; see the [Reading List](/bibliography/).
- [Quarkus: Native reference guide](https://quarkus.io/guides/native-reference): the closed-world reflection model behind the `@RegisterForReflection` fix.
- Cloud Native Design Patterns, [Security](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/12-security.md): token-based identity, the JWT/OIDC capability left deferred here.
