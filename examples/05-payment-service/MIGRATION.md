# payment-service — Migration Notes (ch.23 Phase A, DRQ-052)

This file is the measured record of ch.23's two-phase Payment extraction's
first step, `_plans/iterations/payment-plan.md` S4, and mirrors
`examples/03-notification-service/MIGRATION.md`'s / `examples/04-inventory-service/MIGRATION.md`'s
template.

- **Phase A** ("lift the read surface onto Quarkus") — **this step (S4)**.
  The monolith's Spring MVC / Spring DI / Spring Data JPA payment READ
  surface (`PaymentController`/`PaymentService#getById,listByOrderId`/
  `Payment` entity+repository) **plus** the `charge()` capture logic
  (including the CARD-DECLINE demo rule) was moved onto Quarkus largely
  unchanged via the Quarkiverse Spring-compatibility extensions
  (`quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`),
  already pointed at its OWN `payment` Postgres schema from day one
  (DRQ-052 — contrast with review-service, which stayed in the monolith's
  shared schema). No consumer/producer exists yet (that's S5); the table is
  populated only by a small Flyway demo seed.
- **Phase B** ("make it idiomatic" + "make it live") — **r06 S5, NOT this
  step**. The compatibility shim will be removed (Quarkus REST + Panache +
  plain CDI), and a net-new SmallRye Reactive Messaging consumer of
  `order.placed` + the payment service's own transactional outbox emitting
  `payment.captured`/`payment.declined` will be authored idiomatic-from-the-
  start (DRQ-052, no Spring original to lift for the choreography — mirrors
  notification's consumer, DRQ-035-style).

## Per-component lift (monolith → Phase A)

| Component | Monolith (Spring, shared schema) | Phase A (this step — Spring-compat, OWN schema) |
|---|---|---|
| HTTP layer | `PaymentController` — Spring MVC `@RestController`/`@RequestMapping`/`@GetMapping`/`@PathVariable`/`@RequestParam` | **Unchanged byte-for-byte** (via `quarkus-spring-web`) — same class name, same annotations, same `/api/payments` contract |
| Data access | `PaymentRepository` — Spring Data `JpaRepository<Payment, Long>`, `findByOrderId`/`findAllByOrderId` derived queries | **Unchanged method signatures** (via `quarkus-spring-data-jpa`); the derived-query binding is actually simpler now since `orderId` is a scalar column, not a nested `order.id` traversal |
| Entity (`Payment`) | `@ManyToOne(optional = false) @JoinColumn(name = "order_id") private Order order;` — **SMELL[ch.18]**, a real FK/join into the order context's table in the same shared schema | **orderId decomposed to a plain `Long` value column** — no `@ManyToOne`, no `Order` import, no cross-schema reference. This is the one deliberate structural change this step makes (DRQ-052) |
| Service | `PaymentService` — `@Service` (Spring stereotype), constructor injection, `charge(Order, long, String)` + read methods | `@Service` unchanged; `charge` signature changed to `charge(Long orderId, long, String)` to match the entity's decomposed FK — same decline rule, same persisted outcome |
| DTO (`PaymentDto`) | `record PaymentDto(Long id, Long orderId, long amountCents, String method, PaymentStatus status, Instant createdAt)` | **Unchanged byte-for-byte** — `orderId` was already a plain `Long` on the wire in the monolith; the FK decomposition is invisible to the read contract |
| Exceptions | `common.exception.ResourceNotFoundException`, `common.exception.PaymentDeclinedException`, shared `common.web.GlobalExceptionHandler` (`@RestControllerAdvice`) mapping `ResourceNotFoundException`→404 and `PaymentDeclinedException`→402 | Local (package-scoped, not shared `common`) `ResourceNotFoundException` + `PaymentDeclinedException`; a local `GlobalExceptionHandler` (`@RestControllerAdvice`, via `quarkus-spring-web`) maps **only** `ResourceNotFoundException`→404 — `PaymentDeclinedException` has no HTTP mapping here because nothing in this service calls `charge` yet (no consumer, no controller endpoint) |
| `ApiError` | `common.web.ApiError` | Local copy, `@RegisterForReflection` applied from the start (native-image lesson already learned by review-service's Phase B, applied here from day one rather than rediscovered) |
| Build (`pom.xml`) | Spring Boot starters | `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`, `quarkus-rest-jackson`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-health`, `quarkus-arc` |
| Flyway | Monolith's shared `V*` history in `public` schema | **New, independent history**: `V1__create_payments_table.sql` (own `payment` schema, `order_id` as a plain `BIGINT` column + a `uq_payments_order_id` unique index for the future idempotent-consumer backstop, DRQ-051), `V2__seed_demo_payment.sql` (one demo row so the read surface has something to return before S5's consumer exists) |
| Tests | `PaymentControllerTest` (`@WebMvcTest` + `MockitoBean`), `PaymentServiceTest` (plain Mockito, `Order`/`Customer` fixtures) | `PaymentControllerTest` — now a real end-to-end `@QuarkusTest` (RestAssured against Dev Services' isolated Testcontainers Postgres, self-seeded via `PaymentRepository`, not slice-mocked) proving the full Spring-compat + Flyway + own-schema stack together; `PaymentServiceTest` — same Mockito unit shape, simplified (no `Order`/`Customer` fixtures needed since `charge` now takes a plain `orderId`) |

## Why own-schema from day one (contrast with Review)

Same rationale as notification-service's and inventory-service's Phase A:
Payment will gain a **SmallRye Reactive Messaging consumer** in S5 that
asynchronously persists captured/declined payments from an `order.placed`
Kafka event. An event-driven read model sharing the upstream aggregate's
table would create two independent writers racing on the same rows once the
monolith's synchronous in-process charge is decommissioned. Owning the
`payment` schema from the start avoids that split-brain entirely.

Concretely:

- Same podman-stack Postgres **instance** every example in this repo uses
  (`localhost:5432`, db `monolith`) — **not** a separate database or server.
- Own Postgres **schema**: `payment`, created and migrated by this service's
  own Flyway history (`src/main/resources/db/migration/`), never the
  monolith's `public` schema.
- `payment.payments` is a **distinct table** from the monolith's
  `public.payments` — different schema, different rows, no FK/join between
  them.
- `Payment.java`'s `orderId` is a plain `Long` column, NOT a `@ManyToOne`
  relation into the monolith's `orders` table (which doesn't exist in the
  `payment` schema at all) — see that class's javadoc for the full
  one-line adaptation this forces on the Phase-A lift (the FK decomposition
  this step's acceptance criteria calls out explicitly).

## No CDC backfill needed (contrast with inventory-service)

Unlike inventory-service (which needed a Debezium CDC backfill/sync consumer
because the monolith's `inventory_items` table already held real stock data
that had to be replicated into the new schema), payment rows are **created
forward only**: every payment that will ever exist in this service's table
is the result of a future `order.placed` → capture → `payment.captured`/
`payment.declined` choreography (S5). There is no pre-existing monolith
payment history this service needs to backfill or stay in sync with — the
monolith keeps writing its own `payments` table (in choreographed mode it
will eventually stop, decommissioned at S9) and this service's table starts
empty except for the small demo seed. This is a deliberate, documented
difference from inventory's extraction template, not an oversight.

## Verification

- `./mvnw -q package`: **BUILD SUCCESS**.
- Spring-compat extensions ARE present (expected for Phase A, unlike
  Phase B's removal): `grep -n "quarkus-spring-" pom.xml` matches
  `quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`.
- `./mvnw -q test`: all tests green —
  - `PaymentServiceTest` (5): `charge` captures on a normal method; `charge`
    throws `PaymentDeclinedException` and never saves for 4 CARD-DECLINE
    variants (case-insensitive substring match, preserved exactly); `getById`
    on an unknown id throws `ResourceNotFoundException`.
  - `PaymentControllerTest` (5, `@QuarkusTest`, Dev Services Testcontainers
    Postgres, self-seeded): `GET /api/payments/{id}` existing → 200 +
    `PaymentDto` shape; unknown id → 404 + `ApiError` shape; `GET
    /api/payments?orderId=` existing order → 200 + array; unknown order → 200
    + empty array; missing `orderId` → 400.

## Live verification against the real podman-stack Postgres

Started the packaged JVM artifact (`java -jar target/quarkus-app/quarkus-run.jar`)
on `:8085` against the real podman-stack Postgres (`localhost:5432`, db
`monolith`, own `payment` schema via `currentSchema=payment`). Confirmed:

- `quarkus.flyway.schemas=payment` created the schema and ran V1+V2 against
  the real instance (not just Dev Services).
- `GET http://localhost:8085/api/payments/1` → `200` with the
  `V2__seed_demo_payment.sql` row's exact `PaymentDto` JSON shape
  (`{"id":1,"orderId":1,"amountCents":3998,"method":"CARD-VISA","status":"CAPTURED","createdAt":"2026-01-07T12:00:05Z"}`).
- Shut down cleanly afterward (`:8085` freed; no orphan JVM left listening);
  the podman stack itself was left running (no destructive reset).

## Deferred to S5 (NOT measured here — Phase A→B metrics will land with S5's MIGRATION.md update)

Before/after startup-time/RSS metrics (the style notification-service's and
review-service's MIGRATION.md captured) are deferred to S5, when Phase B's
idiomatic refactor + the net-new Kafka consumer/outbox give a real A→B
comparison point, consistent with how those two services captured their
numbers at their own Phase B step rather than at Phase A.
