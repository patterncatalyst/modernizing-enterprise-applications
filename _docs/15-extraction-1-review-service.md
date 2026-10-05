---
title: "Extraction 1 — Review Service (the Walking Skeleton)"
order: 15
part: "The Strangler Fig in Practice"
description: "REST-only, HTTP Basic auth, no sync dependency — proving the full loop: ACL, Quarkus scaffold, equivalence gate pass, flag-gated cutover, decommission."
---

Chapter 7 already told you that this extraction happened, and what its safety
net caught. This chapter is the other half of that story: not the ADLC's
gate-by-gate narrative, but the actual mechanics of pulling one bounded
context out of a running Spring Boot monolith and landing it on Quarkus
without breaking a single client in between. Everything here is drawn from
code that exists and ran in this project's own r02 iteration —
`examples/02-review-service/` (the extracted service, both its Phase A and
Phase B forms), `examples/01-strangler-proxy/` (the Camel seam that fronts
the monolith), and `tooling/newman/` (the suite that gated every step). There
is no illustrative pseudocode in this chapter; every snippet is the real
file, and the numbers in the payoff table at the end are real measurements
from a real machine, not projections.

The code is in `examples/02-review-service/` and `examples/01-strangler-proxy/`.
The run script in each directory builds/sets up and runs it; each `README.md`
covers what it does and how to drive it.

{% include excalidraw.html file="strangler-review-extraction" alt="Two stacked states of the strangler proxy topology. Before cutover: client and the Newman behavior-equivalence suite both hit the Camel proxy on :8888, which forwards every request, including /api/reviews, to the Spring monolith on :8080; the Quarkus Review service on :8081 exists but is not yet reachable. After cutover: the proxy still forwards most traffic to the five-context monolith, but /api/reviews now routes to the Quarkus Review service; both backends share the same PostgreSQL instance and reviews table." caption="Figure 15.1 — The strangler proxy topology before and after the Review cutover" %}

## Why Review leaves first

Chapter 11 already measured this in terms of ports, and Chapter 13 already
measured it in terms of coupling numbers, so this chapter will not re-derive
either argument — it will just state the conclusion plainly enough to build
on. Review has an afferent coupling of approximately zero (nothing else in
the monolith calls into it) and an efferent coupling of approximately zero
(it calls into nothing else at runtime): it reads customer and inventory data
to validate a review, but only through its own repository queries against
rows it does not own, never through a call into `CustomerService` or
`InventoryService`. That combination — REST-only at the edge, zero
synchronous collaborators at runtime — is what Chapter 9's sixth deliberate
smell named explicitly before any extraction had happened: Review was
"tangled into shared security but genuinely independent," meaning its only
coupling to the rest of the monolith was accidental (one shared
`SecurityFilterChain` governing an endpoint nothing else touched) rather than
structural. Accidental coupling is cheap to cut; structural coupling is not.

That is the entire argument for sequencing, and it is worth being explicit
about what it buys and what it does not. A context with nothing calling in
and nothing calling out has no blast radius: no other service's behavior
depends on Review responding a particular way, and Review depends on no
other service's availability to do its own job. That means this extraction
can exercise the full machinery — the equivalence suite, the strangler proxy,
the two-phase Quarkus migration, the flag-gated cutover, the monolith-module
decommission — without a second bounded context's behavior confounding
whether any given step actually worked. It does not mean the extraction is
risk-free, and two sections below show exactly how it was not: a defect that
only a native build could expose, and a routing bug that forty-nine green
assertions failed to catch. Review being the easiest context to cut is
precisely why it is the right context to prove the seam machinery on first —
difficulty was deliberately deferred, not avoided, and Chapter 17's
notification extraction is where the first dose of that deferred difficulty
(a synchronous call sitting inside the monolith's one ACID transaction)
actually arrives.

## The gate comes first: capturing the equivalence suite before any code moves

Before `examples/02-review-service` existed as a Maven module, before the
Camel proxy existed, before a single line of Quarkus configuration was
written, the Review Context Contract had to exist as a re-runnable artifact —
because an extraction without a prior, independently-captured definition of
"behaves the same" is not verifiable, it is only plausible. `decisions.md`
DRQ-014 fixed this as policy project-wide: the monolith's own externally
observable HTTP contract, captured once against the running monolith, is the
behavior-equivalence suite, and it never gets edited to make a later service
pass. `tooling/newman/mea.postman_collection.json` is that artifact, and its
"Review Context Contract" folder is the five scenarios that mattered for this
specific extraction:

```
GET /api/reviews?sku=... -> 200, non-empty array, items shaped
    { id, customerId, sku, rating (1-5), comment, createdAt }
GET /api/reviews/{id}    -> 200, same shape
POST /api/reviews, no credentials             -> 401
POST /api/reviews, HTTP Basic demo-customer   -> 201 Created, echoes fields, Location header
POST /api/reviews, authenticated, bad rating  -> 400 { "error": "VALIDATION_FAILED", ... }
```

The five Review scenarios expand to sixteen assertions at the field and
status-code level, and every one of those assertions is written against
{% raw %}`{{baseUrl}}`{% endraw %} — a Postman environment variable, never a hardcoded host —
specifically so the identical collection can target `localhost:8080` (the
monolith) today and `localhost:8081` (the Quarkus service) tomorrow with zero
edits. `tooling/newman/review-service.postman_environment.json` exists for
exactly that second target, created in the same step the collection itself
was captured, well before Review's Quarkus module had a line of code in it.
That ordering is the whole discipline: write down what "the same" means while
you can still check it against ground truth, then hold every later build to
that fixed bar. The suite only ever inspects status codes, response-body
shapes, and content types — it never reaches into Postgres directly — which
is precisely the property that lets it run unmodified against a service whose
internal schema, framework, and even programming model have nothing in
common with what it was captured against.

## Standing up the seam: the Camel strangler proxy

With the equivalence contract fixed, the next piece has to exist before any
extracted code does: a seam the extraction can land behind without clients
noticing a thing. `examples/01-strangler-proxy/StranglerProxyRoute.java` is a
single Camel route, built on `platform-http`, that listens on `:8888` and —
for every path that is not `/api/reviews`, for the whole of this chapter —
forwards unchanged to the monolith on `:8080`. Clients, and the
behavior-equivalence suite, talk to `:8888` exclusively; neither one is ever
aware that a backend swap is even possible, which is what makes the switch
later a configuration change and a restart rather than a deploy clients have
to coordinate around.

The mechanism worth understanding in full, because it is the one every later
extraction in this book reuses, is Camel's **content-based router** —
`choice()`/`when()`/`otherwise()` — combined with a configuration-driven
feature flag:

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

Every piece of this earns its place. The `platform-http` consumer is declared
with `matchOnUriPrefix=true` so the single route can absorb every path under
`/api`, not just one; the first `choice()` is the content-based router
proper, and it evaluates two conditions together with `PredicateBuilder.and`
— the path has to start with `/api/reviews`, *and* the injected
`@ConfigProperty boolean reviewEnabled` has to be true — before it routes
toward Review at all. Everything else falls through `otherwise()` to a
`TARGET_MONOLITH` property. That property, not the request itself, is what
the second `choice()` block reads to pick a destination, which keeps the
routing decision and the backend dispatch as two separable steps: the first
block answers "which backend," the second block answers "how do I reach it,"
and neither block needs to know the other's internals. `bridgeEndpoint=true`
on the outbound `to()` is what makes this an actual reverse proxy rather than
a one-off relay — it tells Camel's HTTP producer to reuse the inbound
method, path, query string, and body exactly as they arrived, so nothing
about the request has to be reconstructed by hand. `throwExceptionOnFailure=false`
matters just as much in the other direction: without it, Camel turns a 4xx or
5xx backend response into a thrown exception and the caller gets Camel's own
error envelope back instead of the real status code — which would silently
break every assertion in the equivalence suite that checks for a specific
4xx, because the suite would never see the backend's actual response.

One more design choice is worth calling out because it is a security
property, not just a convenience: the two fixed base URLs,
`strangler.monolith.base-url` and `strangler.review.base-url`, are both
read from configuration, and the route only ever selects between those two
known values — never a URI built from request headers or path segments. A
dynamic-to-URI Camel route that trusted request data to choose its
destination would be an open redirect into whatever an attacker could get it
to resolve; this route cannot be steered anywhere the operator did not
already configure, by construction, independent of any input validation
elsewhere in the request path.

The flag itself — `strangler.review.enabled` — is read once per request as a
plain `@ConfigProperty boolean`, with a `defaultValue = "false"` baked into
the field so the proxy's very first deployment defaulted every request,
`/api/reviews` included, to the monolith. That default is what let the proxy
be stood up and proven transparent — a full behavior-equivalence run through
`:8888`, bit-for-bit matching a run against the monolith directly — before
Review's Quarkus code existed anywhere to route traffic toward.

## Phase A: lift onto Quarkus via the Spring-compatibility extensions

With the gate captured and the seam standing, Generate's first pass is
deliberately the least interesting possible version of a migration: take the
monolith's `review` package — its Spring MVC controller, its Spring Data
repositories, its `@Service`-annotated service class — and get it running on
Quarkus with as few edits as the Quarkiverse Spring-compatibility extensions
will allow. `quarkus-spring-web`, `quarkus-spring-di`, and
`quarkus-spring-data-jpa` are translation layers: they recognize Spring's own
annotations at build time and wire them into Quarkus's runtime, so code that
was written against Spring's API surface runs without that surface being
rewritten first. The quarkus-agent MCP server's `migrate-spring-to-quarkus`
skill drove this pass against a version-matched annotation map rather than
the agent improvising a migration plan from a possibly stale memory of how
Spring Boot and Quarkus differ — the map is what told this pass, correctly,
that `@RestController`/`@RequestMapping`/`@GetMapping`/`@PostMapping` and a
`ResponseEntity<T>` return type are all supported unchanged by
`quarkus-spring-web`.

"As few edits as the extensions will allow" still meant three, and all three
are instructive because each one marks a place where Quarkus's compatibility
story has a documented edge rather than a silent gap. First, Spring Security's
`HttpSecurity`/`SecurityFilterChain` DSL has no `quarkus-spring-security`
compatibility equivalent at all — the compat extension only reaches
method-level `@Secured`/`@PreAuthorize` — so Review's one authenticated
endpoint was expressed with the Jakarta `@RolesAllowed("CUSTOMER")`
annotation from day one of the lift, not deferred to Phase B. That single
substitution is also, not coincidentally, the cure for Chapter 9's sixth
smell: Review now carries its own standalone security configuration instead
of sharing the monolith's one global filter chain, so the fix for "this
context shouldn't share that filter chain" and the fix for "this annotation
has no compat shim" turned out to be the same line of code. Second, Spring's
own `@Transactional` (`org.springframework.transaction.annotation`) has no
compat shim either — the annotation map is explicit that Quarkus always uses
the Jakarta `jakarta.transaction.Transactional`, never Spring's — so the
import was swapped and nothing else about the method changed. Third,
`@Service` alone maps to a CDI `@Singleton` under `quarkus-spring-di` by
default, and `@Singleton` is a pseudo-scope that cannot be client-proxied, so
`@InjectMock` in the Phase A test suite could not substitute a mock for it;
stacking a plain `@ApplicationScoped` annotation alongside `@Service` fixed
that without touching a single method body — a testability fix, not a
behavior change.

That short list is the entire Phase A diff beyond framework vocabulary, and
the commit that produced it — `5479d49` in this project's history — carries
a message that names its own proof: "equivalence 16/16 green." Phase A was
not trusted because it compiled; it was trusted because the exact same
Review Context Contract that was captured against the monolith weeks earlier
ran unmodified against the lifted Quarkus service and every assertion passed.

## Phase B: the idiomatic Quarkus rewrite

Phase A proved the extraction was safe; Phase B is the slower pass that
removes the compatibility shim entirely and rewrites the same behavior in
Quarkus's own idioms — Jakarta REST instead of Spring MVC, Panache
repositories instead of Spring Data JPA interfaces, a
`@ServerExceptionMapper` instead of a `@RestControllerAdvice`. The headline
fact, documented component by component in
`examples/02-review-service/MIGRATION.md`, is how little had to change
beyond that vocabulary: the JPA entities (`Review`, `Customer`,
`InventoryItem`) needed zero edits, because Panache's **repository** pattern
— as distinct from its active-record alternative, where the entity itself
extends `PanacheEntity` — works against ordinary `@Entity` classes exactly as
Spring Data JPA does. The business logic inside `ReviewService` is
byte-for-byte identical between the two phases; only the plumbing around it
changed.

The HTTP layer is the most visible of those plumbing changes. Spring MVC's
annotation family disappears entirely, replaced by Jakarta REST's smaller
vocabulary, and the `ResponseEntity<T>` wrapper disappears along with it:

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// ReviewController.java — Phase A, running on quarkus-spring-web
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
// ReviewResource.java — Phase B, directly on quarkus-rest-jackson
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

Two details in that diff are easy to skim past but matter. First, Jakarta
REST has no `@RequestBody`-equivalent annotation: an unannotated method
parameter — `ReviewCreate command` on `createReview` — is simply JAX-RS's
implicit entity parameter, the body is assumed unless some other parameter
annotation says otherwise. Second, `@RolesAllowed("CUSTOMER")` did not move
at all between the two panels, because it was never a Spring annotation to
begin with; it is plain `jakarta.annotation.security`, which is exactly why
it survived Phase A's lift unchanged and needed no attention at all in Phase
B. The resource class is a three-method translation from HTTP verbs to calls
on `ReviewService` and nothing more — which is the hexagonal shape Chapter 11
measured directly against this same file.

Data access changed in a different, more structural way, because Panache has
no equivalent to Spring Data's derived-query-from-method-name convention:

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// ReviewRepository.java — Phase A, a Spring Data interface, no implementation
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findAllByInventoryItemSku(String sku);
}
```

```java
// ReviewRepository.java — Phase B, a concrete Panache repository
@ApplicationScoped
public class ReviewRepository implements PanacheRepository<Review> {

    public List<Review> findAllByInventoryItemSku(String sku) {
        return list("inventoryItem.sku", sku);
    }
}
```

Spring Data parses `findAllByInventoryItemSku` into a query at startup from
the method name's own grammar; Panache has no such parser, so the equivalent
method is a thin, explicit `list(...)` call using Panache's simplified-HQL
shorthand (`"inventoryItem.sku"` is the property path, the second argument is
its bound value). The trade is a few more characters of code in exchange for
a query you can read directly off the method body instead of reverse-engineering
from a naming convention — and it surfaces a second, smaller ripple in
`ReviewService`: Panache's `findById` returns the entity directly, nullable,
where Spring Data's returns `Optional<T>`, and Panache's `persist(entity)`
returns `void` where Spring Data's `save(entity)` returns the saved instance.
Both call sites in `ReviewService` were adjusted to match — `Optional.ofNullable(...)`
wrapping the nullable return, and reading the already-enriched `review`
variable directly after `persist` instead of reassigning it from a return
value — without the business logic itself moving a single line.

The exception-mapping layer is where Quarkus's extension point diverges most
visibly from Spring's, because the two frameworks solve "translate a thrown
exception into an HTTP response" with genuinely different mechanisms rather
than a thin renaming:

{% include codetabs.html langs="Spring Boot (Phase A lift)|Quarkus (Phase B idiomatic)" %}

```java
// GlobalExceptionHandler.java — Phase A, one @RestControllerAdvice per app
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
// GlobalExceptionMapper.java — Phase B, a plain class, no scope, no @Provider
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

Quarkus REST's `@ServerExceptionMapper` methods are discovered at build time
and need no CDI scope and no `@Provider` registration; the one thing that
does matter structurally is where the class lives — a mapper declared inside
a `@Path`-annotated resource class only applies to that resource, so a global
mapper has to sit outside any resource class entirely, which is exactly why
`GlobalExceptionMapper` is its own top-level class rather than a nested one
inside `ReviewResource`. The exception types being mapped are identical
across both phases — `ResourceNotFoundException` to 404,
`ConstraintViolationException` to 400 — and that second type is itself a
small, worth-noting fact: it is Hibernate Validator's own exception from its
JAX-RS integration, not Spring MVC's `MethodArgumentNotValidException`, so
Phase A already had to map the "right" exception type the very first time a
`@Valid` annotation failed under `quarkus-spring-web`, with no further
change needed when Phase B dropped the compatibility shim.

{% include excalidraw.html file="review-two-phase-migration" alt="A three-stage flow: Phase A, lift onto Quarkus using the Spring-compatibility extensions (quarkus-spring-web, quarkus-spring-di, quarkus-spring-data-jpa); an arrow labeled 'refactor off Spring-compat' to Phase B, idiomatic Quarkus with the compat shim removed (Quarkus REST, Panache, CDI); an arrow labeled 'container-based native build, about 75 seconds' to a native-image box. Below, a measured table: Phase A JVM 1.492s startup / about 316MB RSS; Phase B JVM 1.431 to 1.437s / about 304MB RSS; Phase B native image 0.048 to 0.049s / about 73MB RSS — same Postgres, profile=prod." caption="Figure 15.2 — The two-phase migration template, with the measured before/after that makes the native-image case" %}

## The native-image defect the equivalence gate caught

Phase B's first native-image build did not pass cleanly. One assertion in
the Review Context Contract failed — a review posted with an out-of-range
rating, expecting the `@ServerExceptionMapper` to answer `400
VALIDATION_FAILED`, came back `500` instead — and it is worth walking the
root cause in full because the fix that resulted is still sitting in the
codebase today as a documented javadoc note, not a silent patch.

`GlobalExceptionMapper`'s two methods are both declared to return the
generic `jakarta.ws.rs.core.Response`, not a type parameterized with
`ApiError`. On the JVM, that declaration is harmless: Jackson can discover
how to serialize `ApiError` through ordinary runtime reflection the moment it
actually needs to, regardless of what the method signature promised ahead of
time. GraalVM's native image has no such fallback. Its closed-world
assumption means every type that will ever be serialized has to be
registered for reflection at build time, from a static scan of the code —
and because the scanner only ever saw a resource method returning the
generic `Response`, it never saw `ApiError` as that method's actual return
type and never registered it. The compiled native executable crashed trying
to serialize an error body it had no build-time reflective metadata for, and
a 500 stood in for what should have been a 400 — discovered by nothing short
of actually invoking the native binary.

The fix is one annotation: `@RegisterForReflection` on the `ApiError`
record, explicitly telling Quarkus's build-time scanner to register the type
regardless of what it can infer from a generic return signature. It is left
in the source with a full javadoc explanation rather than quietly folded away,
specifically so a reader of this chapter — or a future contributor extracting
the next service — can see exactly which shape of code (a DTO reachable only
through a generic `Response` or an exception-mapper path) creates this gap.
The underlying lesson generalizes past this one record: anywhere a native
Quarkus service serializes a type that the compiler cannot infer purely from
method signatures, that type needs an explicit reflection hint, and the only
check that will ever catch a missing one is a suite that actually exercises
the compiled native artifact, not just the JVM build. A JVM-only test pass —
even a complete, 16-for-16 green equivalence run, which is exactly what Phase
B had already achieved before anyone built the native image — provides zero
evidence either way about this class of defect, because the JVM's reflection
fallback erases it completely.

## The flag cutover, reversibility, and decommission

With Phase B proven on both the JVM and a native build, the extraction's
last step was making the Camel proxy actually prefer the new service — and
proving, before anything irreversible happened, that the choice could be
reversed if it needed to be. `examples/01-strangler-proxy/CUTOVER.md` is the
dated record of exactly how that was done, and the mechanics are worth
restating plainly: flipping `strangler.review.enabled` from `false` to
`true` is a configuration change and a process restart, nothing more — no
route code changes, because the content-based router built in the seam
section above was already written to read that flag on every request. That
is the entire reversibility story while both backends still exist side by
side: either value of the flag is a config edit away from the other, and
both values were proven green against the full, forty-nine-assertion
equivalence suite (the Review scenarios plus every other context's, since the
suite runs through the proxy unmodified regardless of which single context
is mid-cutover) before the monolith's Review module was ever touched.

That reversibility is also exactly why the routing predicate bug documented
in Chapter 7 was able to hide as long as it did, and why it is worth
restating here from the mechanics side rather than the safety-net side. The
original predicate checked `${header.CamelHttpPath} startsWith '/reviews'`;
the `platform-http` consumer's actual behavior, given
`matchOnUriPrefix=true`, is to set `CamelHttpPath` to the full incoming path
— `/api/reviews`, not a path relative to the route's own `/api` prefix — so
that predicate never matched anything, ever, and every request fell through
to the monolith regardless of the flag's value. It stayed invisible under a
green 49/49 suite run specifically because Review was still in its Phase A
form, reading and writing the exact same shared `reviews` table the monolith
itself wrote to — so a request silently misrouted to the monolith and a
request correctly routed to the Quarkus service returned indistinguishable
JSON, because both were reading the same underlying row. The fix is the
one-line predicate correction visible in the proxy's source today —
`startsWith '/api/reviews'`, matching the path shape `platform-http` actually
presents — and the proof that it was a real fix and not another coincidence
was a differential check with the monolith stopped outright: a correctly
routed Review request has to keep working with the monolith dead, and an
incorrectly routed one has to fail exactly like every other `/api/**` path
does. Only after that differential result did the Review decommission
proceed.

Decommissioning the monolith's Review module was the one deliberately
irreversible move in the whole sequence, and it is worth being precise about
what was removed and what was not. `review/Review.java`,
`review/ReviewRepository.java`, `review/ReviewCreate.java`,
`review/ReviewService.java`, `review/ReviewController.java`, and
`common/ReviewDto.java` were deleted from the monolith outright, along with
their tests. `security/SecurityConfig.java` was deliberately left in place
even though its one authenticated rule no longer matches any surviving
route — deleting it was out of scope for a change that was supposed to touch
only Review's own files, and a vacuous security rule is harmless, not a
smell in its own right. The `reviews` Postgres table was deliberately *not*
dropped, because `examples/02-review-service` is still in its Phase A data
posture, reading and writing that exact shared table with no schema of its
own — true per-context data ownership is Chapter 18 and 19's subject, not
this chapter's, and dropping the table here would have broken the very
service this cutover had just promoted to sole ownership of Review traffic.
That is a real, scoped, and dated deferral, not an oversight: the service
boundary and the data boundary are separable decisions, and this chapter
only closes the first one. With the monolith rebuilt and its own test suite
green — now asserting a 404 on `/api/reviews` instead of serving it — and the
flag's committed default flipped permanently to `true`, flipping it back to
`false` today is still mechanically possible, but it no longer reaches a
working Review endpoint, because the one thing that made reversal meaningful
— a live monolith copy to fall back to — is the thing that was just
deliberately, permanently removed.

## The measured payoff

The reason Phase B exists at all, rather than shipping Phase A's
Spring-compat lift forever, is visible in one table, and every number in it
is a real measurement — packaged artifacts run directly against the same
podman-stack Postgres instance, profile `prod`, startup read from Quarkus's
own "started in `X`s" log line, RSS sampled a few seconds after that line
appears:

| Build | Startup | RSS | Artifact size | Features |
|---|---:|---:|---:|---:|
| Phase A — JVM, Spring-compat | 1.492 s | ~316 MB | 46 MB | 17 |
| Phase B — JVM, idiomatic | 1.431–1.437 s | ~304 MB | 45 MB | 14 |
| Phase B — native image | 0.048–0.049 s | ~73 MB | 87 MB | 14 |

Read the rows in order and the lesson is in the comparison, not any single
number. JVM to JVM, Phase B's win over Phase A is modest: startup is within
run-to-run noise, and RSS drops only about four percent, simply from no
longer loading three Spring-compatibility translation extensions at boot.
That is a fair result — Phase A was never slow, it was only non-idiomatic —
and it is a direct answer to "why bother with Phase B at all if the JVM
numbers barely move." The real case is the second comparison, JVM to
native: roughly thirty times faster startup and a little over four times
less resident memory, for the cost of a native build that takes on the order
of a minute and a quarter longer than a JVM package step, plus the
closed-world reflection gotcha documented above. Native image was attempted
here, not deferred or estimated — both rows of that comparison are
measurements from the same machine, which is what makes this table
citable rather than aspirational, and it is the same table Chapter 7 already
referenced in the ADLC's own terms; this chapter is where the numbers
actually come from.

## What you learned

- A content-based router (Camel's `choice()`/`when()`/`otherwise()`) paired
  with a single boolean feature flag is enough to make a service cutover a
  configuration change rather than a deploy — but only as reversible as the
  backend it falls back to, which is why decommissioning that backend is the
  one deliberately irreversible step in the sequence.
- The two-phase migration template — lift via the Spring-compatibility
  extensions first, refactor to idiomatic Quarkus second, measure both —
  de-risks a framework migration by separating "does this still behave
  correctly" from "is this idiomatic," and the per-component diffs in this
  chapter (`ReviewController`→`ReviewResource`, Spring Data→Panache,
  `@RestControllerAdvice`→`@ServerExceptionMapper`) are the repeatable shape
  every later Spring-to-Quarkus extraction in this book will reuse.
- A behavior-equivalence suite captured once, before extraction begins, and
  held fixed afterward is what makes "this is done" a checkable claim instead
  of a judgment call — but it only verifies the externally observable
  response, which is exactly why a routing defect and a correct cutover can
  look identical to it when two backends happen to share state.
- GraalVM's closed-world native-image model requires explicit
  `@RegisterForReflection` for any type reachable only through a generic
  return signature; the JVM's runtime-reflection fallback hides this class of
  defect completely, so only a suite that actually exercises the compiled
  native artifact will ever catch it.

Review's extraction proved the seam works end to end with nothing competing
for attention but the mechanism itself. Chapter 16 goes back to the smell
Review's extraction did not have to deal with — the missing anti-corruption
layer between `OrderService` and the raw JPA entities it reaches into — and
builds the Camel content-based router, message translator, and content
enricher that the next five extractions will all need at their seams.
Chapter 17 then spends that machinery on notification, the first context
whose one inbound edge is a synchronous call sitting inside the monolith's
single ACID transaction — the first extraction in this book where cutting
the seam and cutting the coupling are not the same afternoon's work.

---

*Verification status: <span class="status status--unverified">unverified</span>.
Every artifact cited above — `examples/02-review-service/` (both the Phase A
commit `5479d49` and the current Phase B tree), `examples/01-strangler-proxy/`
and its `CUTOVER.md`, and `tooling/newman/mea.postman_collection.json` — is
real, runnable code already in this repository's own history, and the
metrics table and native-image root cause are taken directly from
`MIGRATION.md`'s own measured record rather than re-derived here. What a
reader's own run should confirm independently: the exact startup/RSS numbers
on different hardware will differ from the table above even if the
qualitative JVM-vs-native gap holds; and the Camel route's behavior under
`matchOnUriPrefix=true` is worth re-checking against the Camel version
actually installed, since consumer path-matching semantics are exactly the
kind of detail this chapter already showed can silently drift.*
