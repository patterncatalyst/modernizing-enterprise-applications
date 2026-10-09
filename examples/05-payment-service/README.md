# payment-service

**ch.23 Phase A ("lift the read surface onto Quarkus") — r06 S4 (DRQ-052).**
The monolith's Spring MVC / Spring DI / Spring Data JPA payment READ surface
(`PaymentController` → `/api/payments` + `/api/payments/{id}`,
`PaymentService#getById/listByOrderId`, `Payment` entity + repository) plus
the `charge()`/CARD-DECLINE capture logic is lifted onto Quarkus largely
unchanged via the Quarkiverse Spring-compatibility extensions
(`quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`),
mirroring `examples/02-review-service`'s and `examples/03-notification-service`'s
Phase A. See `MIGRATION.md` for the component-by-component table.

**This service OWNS ITS OWN SCHEMA from day one** (`payment`, not the
monolith's shared schema) — same discipline as notification-service and
inventory-service, NOT review-service's shared-schema approach.

- Port `8085`. Read endpoints (identical external contract to the monolith):
  `GET /api/payments/{id}` → `PaymentDto` (200/404);
  `GET /api/payments?orderId=...` → `PaymentDto[]` (200/400 if the param is
  missing).
- No Kafka consumer/producer yet — that is ch.23 S5 (Phase B, the hard part):
  a SmallRye Reactive Messaging consumer of `order.placed` that calls
  `PaymentService#charge`, persists the outcome, and emits
  `payment.captured`/`payment.declined` via this service's own transactional
  outbox. This service's owned `payments` table is currently populated only
  by a small Flyway demo seed (`V2__seed_demo_payment.sql`) — payment rows
  are created **forward** by the future saga, not backfilled (contrast
  inventory-service's CDC backfill — there is no pre-existing monolith data
  to migrate, so no backfill consumer is needed here).

## The FK decomposition: `orderId` is a value, not a join

The monolith's `Payment` entity carries `SMELL[ch.18]`: `order_id` is a real
JPA `@ManyToOne` / `@JoinColumn` into the `orders` table in the **same**
shared Postgres schema. This service cannot do that — it owns its own
`payment` schema and has no `orders` table to join against. `Payment.java`
here holds `orderId` as a plain `Long` column: no foreign key, no
cross-schema reference. It is the event-carried correlation key the future
choreography (S5) uses to tie a captured/declined payment back to the order
that triggered it — the same FK-decomposition discipline ch.19 applied to
`OrderItem`/inventory, lighter here because both sides agree on a simple
numeric id rather than needing a richer reserved-lines snapshot.

Note `PaymentDto` needed **zero** changes for this: the monolith's DTO
already exposed `orderId` as a plain `Long` on the wire (only the JPA entity
had the join). The FK decomposition is purely an entity/schema-level change,
invisible to the read contract.

## Deferred to S5 (Phase B, idiomatic — NOT part of this step)

- Refactor off the Spring-compat shim to Quarkus REST (`PaymentResource`) +
  Panache + plain CDI, mirroring review/notification/inventory's Phase B.
- The net-new choreography core: a SmallRye Reactive Messaging
  `@Incoming("order-placed")` consumer that calls `PaymentService#charge`,
  persists the `Payment` row, and — atomically via this service's own
  transactional outbox (mirrors the monolith's `OutboxRelay`, DRQ-034) —
  writes a `payment.captured` or `payment.declined` outbox row for a relay to
  publish to Kafka. Idempotent by `orderId` (the `uq_payments_order_id`
  unique index already created in V1 is the database-level backstop).
- Measured before/after metrics (`MIGRATION.md` Phase A → B table).

## Verified (ch.23 S4 DoD)

- `./mvnw -q package`: **BUILD SUCCESS**.
- `./mvnw -q test`: `PaymentServiceTest` (unit, Mockito — the CARD-DECLINE
  demo rule preserved + the 404 branch) and `PaymentControllerTest`
  (`@QuarkusTest`, Dev Services Testcontainers Postgres, self-seeded via
  `PaymentRepository` — real end-to-end `GET /api/payments/{id}` and
  `GET /api/payments?orderId=` against this service's own Flyway-migrated
  `payment` schema, asserting the exact `PaymentDto` JSON shape and the 404
  contract).
- Started on `:8085` against the real compose-stack Postgres
  (`quarkus.flyway.schemas=payment`, own schema, own migration history) and
  confirmed live: `GET http://localhost:8085/api/payments/1` → `200` with the
  `V2__seed_demo_payment.sql` row's `PaymentDto` shape.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8085/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it's not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/payment-service-1.0.0-SNAPSHOT-runner`

## Related Guides

- Spring Web compatibility ([guide](https://quarkus.io/guides/spring-web)): Use Spring Web annotations
- Spring Data JPA compatibility ([guide](https://quarkus.io/guides/spring-data-jpa)): Use Spring Data JPA repositories
- Spring DI compatibility ([guide](https://quarkus.io/guides/spring-di)): Use Spring dependency injection annotations
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health
