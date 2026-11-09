---
title: "Extraction 1: Review Service"
order: 15
part: "The Strangler Fig in Practice"
description: "Extracting Review, a REST-only context with HTTP Basic auth and no synchronous dependency: equivalence gate, Camel proxy, two-phase Quarkus migration, flag-gated cutover and decommission."
---

## Learning objectives

By the end of this chapter you can:

- Explain why Review is the first bounded context to extract and how that ordering de-risks every extraction after it.
- Capture a behavior-equivalence suite before any code moves and use it as the check that makes "done" a checkable claim.
- Put a Camel content-based router in front of the monolith so a cutover is a configuration change.
- Run the two-phase migration: lift onto Quarkus with the Spring-compatibility extensions, then rewrite to idiomatic Quarkus (Panache, `@ServerExceptionMapper`), measuring both.
- Recognize the GraalVM closed-world reflection defect and why only a suite that exercises the native artifact catches it.
- Cut over behind a flag and decommission the old backend as the one irreversible step.

## Why this matters

Pulling one bounded context out of a running Spring Boot monolith without breaking a client is a sequence of mechanical steps, each gated by a check. Everything here comes from code that ran: `examples/02-review-service/` (the extracted service, in both its Phase A and Phase B forms), `examples/01-strangler-proxy/` (the Camel seam that fronts the monolith) and `tooling/newman/` (the Newman collection that gated every step). Each example directory has a `README.md` covering how to build and run it, and `demos/demo-cutover.sh` walks the cutover against a running monolith, proxy and Review service.

{% include excalidraw.html file="strangler-review-extraction" alt="Two stacked states of the strangler proxy topology. Before cutover: client and the Newman behavior-equivalence suite both hit the Camel proxy on :8888, which forwards every request, including /api/reviews, to the Spring monolith on :8080; the Quarkus Review service on :8081 exists but is not yet reachable. After cutover: the proxy still forwards most traffic to the five-context monolith, but /api/reviews now routes to the Quarkus Review service; both backends share the same PostgreSQL instance and reviews table." caption="Figure 15.1: The strangler proxy topology before and after the Review cutover" %}

## Why Review leaves first

Review has an afferent coupling of about zero (nothing in the monolith calls it) and an efferent coupling of about zero. It reads customer and inventory rows to validate a review, but through its own repository queries against rows it doesn't own, never through a call into `CustomerService` or `InventoryService`. Its only coupling to the rest of the monolith was accidental: one shared `SecurityFilterChain` governing an endpoint nothing else touched. Accidental coupling is cheap to cut; structural coupling is not.

A context with nothing calling in and nothing calling out has no blast radius, so the extraction exercises the whole machinery (equivalence suite, strangler proxy, two-phase migration, flag-gated cutover, decommission) without a second context's behavior confounding whether a step worked. It isn't risk-free: a defect only a native build could expose and a routing bug that 49 green assertions missed both turned up here. Difficulty was deferred, not avoided. The Notification extraction is where the first dose arrives, a synchronous call inside the monolith's one ACID transaction.

## Capture the contract first

Before the Maven module, the proxy or any Quarkus configuration existed, the Review contract had to exist as a re-runnable artifact. An extraction without an independently captured definition of "behaves the same" is plausible, not verifiable. The monolith's externally observable HTTP contract, captured once against the running monolith, is the behavior-equivalence suite, and it is never edited to make a later service pass. `tooling/newman/mea.postman_collection.json` holds it; its "Review Context Contract" folder has five scenarios:

```
GET /api/reviews?sku=... -> 200, non-empty array, items shaped
    { id, customerId, sku, rating (1-5), comment, createdAt }
GET /api/reviews/{id}    -> 200, same shape
POST /api/reviews, no credentials             -> 401
POST /api/reviews, HTTP Basic demo-customer   -> 201 Created, echoes fields, Location header
POST /api/reviews, authenticated, bad rating  -> 400 { "error": "VALIDATION_FAILED", ... }
```

The five scenarios expand to sixteen assertions on fields and status codes. Each is written against {% raw %}`{{baseUrl}}`{% endraw %}, a Postman environment variable and never a hardcoded host, so the identical collection targets `localhost:8080` (the monolith) today and `localhost:8081` (the Quarkus service) tomorrow with no edits; `tooling/newman/review-service.postman_environment.json` is the second target. The collection inspects only status codes, body shapes and content types and never reaches into Postgres, which is why it runs unmodified against a service whose schema, framework and programming model differ from the original's.

## The seam: the Camel strangler proxy

The next piece must exist before any extracted code: a seam the extraction can land behind unnoticed. `StranglerProxyRoute.java` is a single Camel route on `platform-http` that listens on `:8888` and forwards everything except `/api/reviews` to the monolith on `:8080`. Clients and the equivalence suite talk to `:8888` only. It is a Camel content-based router combined with a configuration-driven flag:

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

The first `choice()` decides which backend: the path must start with `/api/reviews` and the injected `@ConfigProperty boolean reviewEnabled` must be true. The second dispatches to the chosen base URL. `bridgeEndpoint=true` makes it a reverse proxy by reusing the inbound method, path, query string and body. `throwExceptionOnFailure=false` returns the backend's real 4xx or 5xx instead of Camel's error envelope, which would break every suite assertion that checks a specific 4xx.

The route selects only between `strangler.monolith.base-url` and `strangler.review.base-url`, both read from configuration, never a URI built from headers or path segments. A dynamic-URI route that trusted request data would be an open redirect; this one can't be steered anywhere the operator didn't configure. The flag, `strangler.review.enabled`, defaults to `false`, so the first deployment sent every request, `/api/reviews` included, to the monolith. That let the proxy be proven transparent, a full equivalence run through `:8888` matching a run against the monolith directly, before any Review code existed.

## Phase A: lift with the Spring-compatibility extensions

Phase A is the least interesting migration possible: take the monolith's `review` package (Spring MVC controller, Spring Data repository, `@Service` class) and run it on Quarkus with as few edits as the Quarkiverse extensions allow. `quarkus-spring-web`, `quarkus-spring-di` and `quarkus-spring-data-jpa` recognize Spring's annotations at build time and wire them into Quarkus. The quarkus-agent MCP server's `migrate-spring-to-quarkus` skill drove the pass from a version-matched annotation map instead of an agent's memory of how the frameworks differ. The map confirmed that `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping` and `ResponseEntity<T>` work unchanged.

Three edits were still needed, each at a documented edge of the compatibility story:

- **Security.** Spring Security's `HttpSecurity`/`SecurityFilterChain` DSL has no compatibility equivalent; the extension reaches only method-level `@Secured` and `@PreAuthorize`. Review's authenticated endpoint uses Jakarta `@RolesAllowed("CUSTOMER")`. That also cures the smell of Review sharing the monolith's one global filter chain: the same line fixed both.
- **Transactions.** Spring's `@Transactional` has no shim. Quarkus always uses `jakarta.transaction.Transactional`, so the import changed and nothing else.
- **Scope.** `@Service` alone maps to a CDI `@Singleton` under `quarkus-spring-di`. `@Singleton` is a pseudo-scope that can't be client-proxied, so `@InjectMock` couldn't replace it. Adding `@ApplicationScoped` beside `@Service` fixed that with no method body touched.

Phase A was trusted because the Review contract captured against the monolith ran unmodified against the lifted service and every assertion passed, not because it compiled.

## Phase B: idiomatic Quarkus

Phase B removes the shim and rewrites the same behavior in Quarkus idioms: Jakarta REST instead of Spring MVC, Panache repositories instead of Spring Data interfaces, `@ServerExceptionMapper` instead of `@RestControllerAdvice`. `examples/02-review-service/MIGRATION.md` records it component by component. The JPA entities (`Review`, `Customer`, `InventoryItem`) needed zero edits, because Panache's repository pattern, as opposed to active record where the entity extends `PanacheEntity`, works against ordinary `@Entity` classes. `ReviewService`'s business logic is identical between the phases; only the plumbing changed.

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// ReviewController.java, Phase A, running on quarkus-spring-web
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping
    @RolesAllowed("CUSTOMER")
    public ResponseEntity<ReviewDto> createReview(@Valid @RequestBody ReviewCreate command) {
        ReviewDto dto = service.createReview(command);
        return ResponseEntity.created(URI.create("/api/reviews/" + dto.id())).body(dto);
    }

    @GetMapping("/{id}")
    public ReviewDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<ReviewDto> listBySku(@RequestParam String sku) {
        return service.listBySku(sku);
    }
}
```

```java
// ReviewResource.java, Phase B, directly on quarkus-rest-jackson
@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewResource {

    private final ReviewService service;

    public ReviewResource(ReviewService service) {
        this.service = service;
    }

    @POST
    @RolesAllowed("CUSTOMER")
    public Response createReview(@Valid ReviewCreate command) {
        ReviewDto dto = service.createReview(command);
        return Response.created(URI.create("/api/reviews/" + dto.id())).entity(dto).build();
    }

    @GET
    @Path("/{id}")
    public ReviewDto getById(@PathParam("id") Long id) {
        return service.getById(id);
    }

    @GET
    public List<ReviewDto> listBySku(@QueryParam("sku") String sku) {
        return service.listBySku(sku);
    }
}
```

Jakarta REST has no `@RequestBody`: an unannotated parameter is the implicit entity. `@RolesAllowed("CUSTOMER")` didn't move because it was never a Spring annotation; it is `jakarta.annotation.security`, so it survived the lift unchanged. The resource is a three-method translation from HTTP verbs to `ReviewService` calls and nothing more: the hexagonal shape, with the protocol kept out of the domain core.

Data access changed more structurally, because Panache has no derived queries from method names:

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// ReviewRepository.java, Phase A, a Spring Data interface, no implementation
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByInventoryItemSku(String sku);
}
```

```java
// ReviewRepository.java, Phase B, a concrete Panache repository
@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {

    public List<Review> findAllByInventoryItemSku(String sku) {
        return list("inventoryItem.sku", sku);
    }
}
```

Spring Data parses `findAllByInventoryItemSku` into a query at startup. Panache has no such parser, so the method is an explicit `list(...)` call in simplified HQL (`"inventoryItem.sku"` is the property path, the second argument its value). That costs a few characters and gives a query you read off the method body. Two ripples reached `ReviewService`: Panache's `findById` returns the entity, nullable, where Spring Data returns `Optional<T>`, and `persist(entity)` returns `void` where `save(entity)` returns the saved instance. The call sites became `Optional.ofNullable(...)` and a direct read of the already-enriched `review` after `persist`.

Exception mapping diverges most, because the two frameworks use different mechanisms:

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// GlobalExceptionHandler.java, Phase A, one @RestControllerAdvice per app
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> badRequest(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", ex.getMessage()));
    }
}
```

```java
// GlobalExceptionMapper.java, Phase B, a plain class, no scope, no @Provider
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException ex) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("NOT_FOUND", ex.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response validationFailed(ConstraintViolationException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("VALIDATION_FAILED", ex.getMessage()))
                .build();
    }
}
```

`@ServerExceptionMapper` methods are discovered at build time and need no CDI scope and no `@Provider`. A mapper declared inside a `@Path` resource applies only to that resource, so a global mapper must be its own top-level class, which is why `GlobalExceptionMapper` isn't nested in `ReviewResource`. The mapped types are the same in both phases: `ResourceNotFoundException` to 404 and `ConstraintViolationException`, Hibernate Validator's JAX-RS exception rather than Spring MVC's `MethodArgumentNotValidException`, to 400. Phase A already mapped the right type the first time `@Valid` failed.

{% include excalidraw.html file="review-two-phase-migration" alt="A three-stage flow: Phase A, lift onto Quarkus using the Spring-compatibility extensions (quarkus-spring-web, quarkus-spring-di, quarkus-spring-data-jpa); an arrow labeled 'refactor off Spring-compat' to Phase B, idiomatic Quarkus with the compat shim removed (Quarkus REST, Panache, CDI); an arrow labeled 'container-based native build, about 75 seconds' to a native-image box. Below, a measured table: Phase A JVM 1.492s startup / about 316MB RSS; Phase B JVM 1.431 to 1.437s / about 304MB RSS; Phase B native image 0.048 to 0.049s / about 73MB RSS. Same Postgres, profile=prod." caption="Figure 15.2: The two-phase migration: lift with the Spring-compatibility extensions, then rewrite idiomatically, with the measured startup and memory of each build" %}

## The native-image defect

Phase B's first native build failed one contract assertion: a review posted with an out-of-range rating, expected to return `400 VALIDATION_FAILED` through the `@ServerExceptionMapper`, returned `500`.

Both `GlobalExceptionMapper` methods return the generic `jakarta.ws.rs.core.Response`, not a type parameterized with `ApiError`. On the JVM that is harmless: Jackson finds how to serialize `ApiError` by runtime reflection when it needs to. GraalVM's closed-world assumption has no such fallback; every type to be serialized must be registered for reflection at build time from a static scan. The scanner saw only a method returning `Response`, never registered `ApiError`, and the native executable crashed serializing an error body it had no reflective metadata for.

The fix is `@RegisterForReflection` on the `ApiError` record, left in the source with a javadoc explaining it. It generalizes: anywhere a native service serializes a type the compiler can't infer from signatures, such as a DTO reachable only through a generic `Response` or an exception-mapper path, the type needs an explicit hint. A complete 16-for-16 JVM run, which Phase B had already achieved, says nothing about this class of defect, because the JVM's reflection fallback hides it. Only a suite run against the compiled native artifact catches it.

## Cutover, reversibility and decommission

With Phase B proven on the JVM and native, the last step was making the proxy prefer the new service and proving the choice reversible before anything irreversible happened. `examples/01-strangler-proxy/CUTOVER.md` is the dated record. Flipping `strangler.review.enabled` from `false` to `true` is a configuration change and a restart, with no route code change. Both values were proven green against the full 49-assertion run, which covers every context because the collection passes through the proxy unmodified, before the monolith's Review module was touched.

The routing bug fits here from the mechanics side. The original predicate was `startsWith '/reviews'`, but with `matchOnUriPrefix=true` `platform-http` sets `CamelHttpPath` to the full incoming path, `/api/reviews`, so it never matched and every request fell through to the monolith whatever the flag said. The 49-of-49 run stayed green because Review was still in Phase A, reading and writing the same shared `reviews` table the monolith did, so a misrouted request and a correctly routed one returned indistinguishable JSON. The fix is the predicate in today's source, `startsWith '/api/reviews'`. The proof was a differential check with the monolith stopped: a correctly routed Review request keeps working, and a misrouted one fails like every other `/api/**` path. Only then did the decommission proceed.

Decommissioning the monolith's Review module was the one irreversible move. Deleted outright, with their tests: `review/Review.java`, `review/ReviewRepository.java`, `review/ReviewCreate.java`, `review/ReviewService.java`, `review/ReviewController.java` and `common/ReviewDto.java`. Kept: `security/SecurityConfig.java`, whose one authenticated rule no longer matches any route; deleting it was outside a change meant to touch only Review's files, and a vacuous rule is harmless. Kept: the `reviews` table, because the Review service is still in its Phase A data posture on that shared table, and dropping it would break the service the cutover just made sole owner of Review traffic. The service boundary and the data boundary are separable decisions, and this extraction closes only the first.

With the monolith rebuilt and its own tests green (now asserting a 404 on `/api/reviews`) and the committed flag default set to `true`, flipping the flag back is still mechanically possible but no longer reaches a working Review endpoint. The live monolith copy that made reversal meaningful is gone.

## The measured payoff

Packaged artifacts ran against the same Postgres, profile `prod`, startup read from Quarkus's own "started in X s" log line and RSS sampled a few seconds after it:

| Build | Startup | RSS | Artifact size | Features |
|---|---:|---:|---:|---:|
| Phase A: JVM, Spring-compat | 1.492 s | ~316 MB | 46 MB | 17 |
| Phase B: JVM, idiomatic | 1.431 to 1.437 s | ~304 MB | 45 MB | 14 |
| Phase B: native image | 0.048 to 0.049 s | ~73 MB | 87 MB | 14 |

JVM to JVM, Phase B's win is modest: startup is within run-to-run noise and RSS drops about four percent from no longer loading three translation extensions. Phase A was never slow, only non-idiomatic. The case for Phase B is JVM to native: roughly thirty times faster startup and a little over four times less resident memory, for a native build that takes about 75 seconds longer than a JVM package step, plus the closed-world gotcha above.

> **Decision**
>
> Extract the context with the lowest Ca and Ce first. Capture the contract before moving code, lift before you rewrite, test the native artifact if you ship native, and decommission only after a stop-the-backend check proves the routing.

> **🤖 Explore with your agent**
>
> "In `examples/02-review-service/`, list every Java type that is serialized in a response and is reachable only through a generic `Response` or an exception-mapper path, and check each has `@RegisterForReflection`. Then run the Review Context Contract from `tooling/newman/` against the native runner on `:8081` and report which assertions fail."
>
> Check: `ApiError` should already carry `@RegisterForReflection`; remove it, rebuild native, and the out-of-range rating assertion should return 500 instead of 400.

## Build, run, observe

`demos/demo-cutover.sh` reconstructs the intermediate state right after this cutover, so run it from the `stage/01-review-extracted` tag (`git checkout stage/01-review-extracted`), where the monolith still carries its other five contexts. It assumes the compose-stack Postgres on `:5432`, the monolith on `:8080`, the Review service on `:8081` and the proxy on `:8888`, with the committed default `strangler.review.enabled=true`. It checks that `/api/reviews` is served by the Quarkus service and other `/api/**` paths by the monolith, that the monolith itself returns 404 on `/api/reviews`, and that the full Newman collection passes 49 of 49 through the proxy.

- Run the proxy with `-Dstrangler.review.enabled=false`: `/api/reviews` now returns 404, because the Review module is gone from the monolith.
- Widen `ReviewCreate`'s `@Max(5)` on `rating` to `@Max(500)` and rerun `newman run ... --folder "Review Context Contract"` against `:8081`: the invalid-rating write gets `201 Created` instead of `400`, the `VALIDATION_FAILED` assertion fails too, and Newman exits `1`.

## For deeper coverage


- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): the incremental, strangler-first migration playbook this extraction follows end to end; see the [Reading List](/bibliography/).
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the canonical write-up of the pattern the Camel proxy implements.
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the content-based router, message translator, and content enricher the strangler seam is built from.
- [Quarkus: Native reference guide](https://quarkus.io/guides/native-reference): the closed-world reflection model and `@RegisterForReflection` behind the native-image defect the equivalence gate caught.
- Cloud Native Design Patterns, [Communications](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/02-communications.md): the companion book's treatment of the inter-service communication styles the proxy mediates.
