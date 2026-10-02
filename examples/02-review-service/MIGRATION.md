# review-service — Migration Notes (ch.15 Phase A → Phase B, DRQ-029)

This file is the measured record of the two-phase Review extraction required
by the r02 walking-skeleton plan (`_plans/iterations/r02-plan.md`, step S9) and
the build-plan's two-phase migration strategy (`_plans/build-plan.md` §E,
DRQ-029). It exists alongside `README.md` so the chapter (ch.15) can cite real
numbers instead of placeholders.

- **Phase A** ("lift onto Quarkus") — preserved in git history at commit
  `5479d49` (`feat(r02): extract Review to Quarkus — Phase A lift via
  Spring-compat (equivalence 16/16 green)`). Spring MVC / Spring DI / Spring
  Data JPA source moved onto Quarkus largely unchanged via the Quarkiverse
  Spring-compatibility extensions (`quarkus-spring-web`, `quarkus-spring-di`,
  `quarkus-spring-data-jpa`).
- **Phase B** ("make it idiomatic") — this step (r02 S9). The compatibility
  shim is removed; the service runs on Quarkus REST (RESTEasy Reactive),
  Panache repositories, native CDI, and a `@ServerExceptionMapper`, with the
  exact same external HTTP contract.

## Per-component refactor (A → B)

| Component | Phase A (Spring-compat lift) | Phase B (idiomatic Quarkus) |
|---|---|---|
| HTTP layer | `ReviewController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@PostMapping`, `ResponseEntity<T>` (via `quarkus-spring-web`) | `ReviewResource` — Jakarta REST `@Path`/`@GET`/`@POST`, plain `Response`/DTO returns, directly on `quarkus-rest-jackson` |
| Data access | `ReviewRepository`/`CustomerRepository`/`InventoryRepository` — Spring Data `JpaRepository<T, Long>` interfaces (via `quarkus-spring-data-jpa`) | Same class names, now concrete `@ApplicationScoped` classes implementing `PanacheRepository<T>`; derived queries (`findAllByInventoryItemSku`, `findBySku`) rewritten as explicit simplified-HQL `find(...)`/`list(...)` calls |
| Entities | `Review`/`Customer`/`InventoryItem` — plain JPA | **Unchanged** — Panache's repository pattern (as opposed to active-record `PanacheEntity`) works against ordinary `@Entity` classes, so zero entity edits were needed |
| Service | `ReviewService` — `@Service` (Spring stereotype) + `@ApplicationScoped` dual-annotated, constructor injection | `@ApplicationScoped` only; Quarkus's simplified constructor injection (single constructor ⇒ no `@Inject` needed); repository calls adapted to Panache's `persist(entity)` (void) and nullable `findById` instead of Spring Data's `save()`/`Optional<T>` |
| Exception mapping | `GlobalExceptionHandler` — Spring `@RestControllerAdvice`/`@ExceptionHandler`, `ResponseEntity<ApiError>` | `GlobalExceptionMapper` — plain class (no CDI scope, no `@Provider`), two `@ServerExceptionMapper` methods, declared outside any `@Path` class so it applies application-wide; same `ApiError` body, same status codes |
| DTOs (`ReviewDto`, `ReviewCreate`, `ApiError`) | Plain records | Plain records — **one addition**: `@RegisterForReflection` added to `ApiError` (see "Native-image finding" below); a real gap found while measuring, not speculative |
| Security | `@RolesAllowed("CUSTOMER")` + Elytron properties-file embedded realm | **Unchanged** — already idiomatic Jakarta security in Phase A; not a Spring API, nothing to refactor |
| Config (`application.properties`) | SmallRye Config, port 8081, same Postgres coords | **Unchanged** — no Spring config prefixes were ever present |
| Build (`pom.xml`) | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa` present | **Removed** all three; added `quarkus-hibernate-orm-panache` |
| Tests | `ReviewControllerTest` (`@QuarkusTest` + REST Assured + `@InjectMock`), `ReviewServiceTest` (plain Mockito) | Renamed `ReviewControllerTest` → `ReviewResourceTest` to match the resource class; same four/three cases respectively; `ReviewServiceTest` updated for Panache's `persist`/nullable-`findById` method shapes; `ReviewResourceTest`'s Location-header assertion relaxed from exact-match to `endsWith(...)` (see finding below) |

## Native-image finding (not speculative — found while measuring)

The first native build of Phase B **failed test 4e of the equivalence suite**
(invalid rating → expected 400, got 500). Root cause: `GlobalExceptionMapper`'s
`@ServerExceptionMapper` methods return the generic `jakarta.ws.rs.core.Response`,
not a type parameterized with `ApiError`, so Quarkus's build-time Jackson
reflection scan — which is what drives serialization under GraalVM's
closed-world native image (no ad-hoc runtime reflection) — never sees
`ApiError` as a resource method return type and skips registering it. This is
invisible on the JVM build (Jackson falls back to ordinary runtime reflection),
so it passed every JVM test including the full equivalence gate, and only
surfaced running the actual native executable. Fix: `@RegisterForReflection`
on the `ApiError` record. Rebuilt native, re-ran the equivalence suite: 16/16
green. This is exactly the class of Phase-B "why does idiomatic Quarkus look
different from idiomatic Spring" lesson ch.15 exists to teach — kept in the
code as a documented javadoc note on `ApiError`, not silently fixed.

A second, minor, non-blocking observation: JAX-RS's `Response.created(URI)`
resolves a relative location URI against the request's base URI (producing an
absolute `Location` header), whereas Spring's `ResponseEntity.created(URI)`
echoed the literal string unchanged. The behavior-equivalence suite only
asserts the header is *present* (it never pinned the exact value), so this is
not a contract break — but `ReviewResourceTest`'s stricter exact-match
assertion from Phase A was relaxed to `endsWith(...)` to match the new
(still-correct) JAX-RS behavior.

## Verification

- `./mvnw clean verify`: **BUILD SUCCESS**, `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
  (4 in `ReviewResourceTest`, 3 in `ReviewServiceTest`).
- No Spring-compat dependencies remain: `grep -n "quarkus-spring-" pom.xml` matches
  only a `<build>` comment documenting the removal, not a dependency.
- No Spring annotations/imports remain in `src/main` or `src/test`:
  `grep -rn "^import org.springframework" src/` → no matches.
- `newman run tooling/newman/mea.postman_collection.json --folder "Review Context Contract"`
  against the running JVM build on `:8081` (podman-stack Postgres): **16/16
  assertions green**, unchanged from the Phase A run.
- Same newman run repeated against the **native executable** on `:8081`: also
  **16/16 green** (after the `@RegisterForReflection` fix above).

## Before / after metrics

Method: packaged (`./mvnw package [-Dnative ...]`) artifacts run directly —
`java -jar target/quarkus-app/quarkus-run.jar` for JVM mode, the generated
`target/*-runner` executable for native — against the SAME podman-stack
Postgres on `localhost:5432`, profile `prod`. Startup time is Quarkus's own
"started in `X`s" log line. RSS is `ps -o rss` on the running process, sampled
a few seconds after the ready log line (JVM: ~12–15s elapsed, to let class
loading/JIT settle; native: ~3s elapsed, since native has no warm-up curve to
speak of). Phase A was rebuilt from its preserved commit `5479d49` via
`git archive 5479d49 -- examples/02-review-service` into a scratch directory
(read-only extraction, no checkout/branch switch) and packaged the same way.

| Build | Startup time | RSS | Packaged artifact size | Installed features |
|---|---|---|---|---|
| **Phase A** — JVM, Spring-compat (`5479d49`) | 1.492 s | ~316 MB | 46 MB (`quarkus-app/`) | 17 (incl. `spring-data-jpa`, `spring-di`, `spring-web`) |
| **Phase B** — JVM, idiomatic | 1.431–1.437 s | ~304 MB | 45 MB (`quarkus-app/`) | 14 (spring-compat extensions removed) |
| **Phase B** — native image | 0.048–0.049 s | ~73 MB | 87 MB (single executable) | 14 |

**Reading the numbers:** Phase A → Phase B on the JVM is a modest win —
startup is about the same (±0.05s, within run-to-run noise) and RSS drops
~4% simply from no longer loading the three Spring-compat translation
extensions at boot. The real "why Quarkus" payoff is JVM → **native**:
~30x faster startup (1.4s → 0.05s) and ~4.2x less resident memory (304MB →
73MB), at the cost of a ~75-second container-based native build
(`./mvnw package -Dnative -Dquarkus.native.container-build=true`, using the
`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` builder image via
podman) and the closed-world reflection gotcha documented above. Native image
was **attempted, not deferred** — both the JVM and native comparisons in this
table are from real runs on this machine, not estimates.
