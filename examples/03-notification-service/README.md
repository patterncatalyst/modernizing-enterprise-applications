# notification-service

**ch.17 Phase A ("lift onto Quarkus") — r04 S4.** The monolith's
(`examples/00-monolith`) Notification bounded context's **read surface**,
lifted onto Quarkus via the Quarkiverse Spring-compatibility extensions
(DRQ-035), mirroring review-service's Phase A (`examples/02-review-service`,
preserved at commit `5479d49`). `NotificationController` → `NotificationService`
→ `NotificationRepository` → `Notification` moved onto Quarkus largely
unchanged (`quarkus-spring-web`, `quarkus-spring-di`, `quarkus-spring-data-jpa`).

**The one deliberate difference from Review's Phase A: this service OWNS ITS
OWN SCHEMA from day one**, not the monolith's shared schema. See
"Why own-schema" below and `Notification.java`'s javadoc for the full
rationale; this is DRQ-035's own-schema decision, not a speculative addition.

- Port `8083`. Endpoint (identical external contract to the monolith):
  `GET /api/notifications?customerId=...` → `NotificationDto[]`.
- No write endpoint and no Kafka consumer in this step — see "Deferred to S5" below.

## Why own-schema (contrast with Review)

review-service's Phase A could stay in the monolith's **shared** Postgres
schema because Review only ever serves a synchronously-consistent REST
read/write contract against data the monolith also owns and migrates.
Notification is different: ch.17 S5 will add a **SmallRye Reactive Messaging
consumer** that asynchronously persists notifications from an `order.placed`
Kafka event. An event-driven read model that shared the upstream aggregate's
table would have two independent writers (the monolith's now-decommissioned
synchronous path, and this service's eventual consumer) racing on the same
rows, with no way to reconcile them. Owning the table avoids that split-brain
entirely — it is the honest "why does an event-driven extraction need its own
data" teaching point of DRQ-035.

Concretely:

- Same podman-stack Postgres **instance** every example in this repo uses
  (`localhost:5432`, db `monolith`) — **not** a separate database or server.
- Own Postgres **schema**: `notification`, created and migrated by this
  service's own Flyway history (`src/main/resources/db/migration/`), never
  the monolith's `public` schema.
- `notification.notifications` is a **distinct table** from the monolith's
  `public.notifications` — different schema, different rows, no FK/join
  between them. Verified live: `psql -c "\dn"` shows both `notification` and
  `public` schemas; `information_schema.tables` shows two separate
  `notifications` tables, one per schema.
- `Notification.java`'s `customerId`/`orderId` are plain `Long` columns, NOT
  `@ManyToOne` JPA relations into the monolith's `customers`/`orders` tables
  (which don't exist in the `notification` schema at all) — see that class's
  javadoc for the full one-line adaptation this forces on the Phase-A lift.

## Verified (ch.17 S4 DoD)

- `./mvnw -q package`: **BUILD SUCCESS**, 5/5 tests green
  (`NotificationServiceTest`: 2 Mockito unit tests; `NotificationResourceTest`:
  3 real `@QuarkusTest` cases against a Dev-Services-provisioned, ephemeral
  Testcontainers Postgres — this service's own Flyway migrations applied
  fresh into that container's `notification` schema each run, proving the
  own-schema contract is portable, not just true against the one long-lived
  podman instance).
- Packaged JVM run (`java -jar target/quarkus-app/quarkus-run.jar`, profile
  `prod`) against the real podman-stack Postgres on `:8083`:
  `GET /api/notifications?customerId=1` → `200` + the seeded
  `NotificationDto` (`V2__seed_demo_notification.sql`); `customerId=42`
  (unseeded, no cross-schema read into the monolith's `customers` table) →
  `200` + `[]`; missing `customerId` → `400`.

## Deferred to S5 (ch.17 Phase B)

- The **SmallRye Reactive Messaging consumer** (`@Incoming("order-placed")`)
  that idempotently persists notifications from the monolith's outbox→Kafka
  `order.placed` event — **net-new, idiomatic Quarkus from the start**
  (DRQ-035: "you cannot lift code that does not exist," since the monolith
  never consumed events).
- The **idiomatic Phase B refactor** off the Spring-compat shim (Quarkus REST,
  Panache, plain CDI — mirroring `examples/02-review-service/MIGRATION.md`),
  with measured before/after metrics.
- The **WebSocket push** (`quarkus-websockets-next`, `/ws/notifications`),
  adapted from `~/Dev/datamesh-reference-arch-quarkus/examples/notification-service`
  (JSON, not Avro — DRQ-038).
- Until S5 lands, `notification.notifications` has no other population
  mechanism than the `V2__seed_demo_notification.sql` demonstration seed.

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8083/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/notification-service-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- Quarkus Extension for Spring Data JPA API ([guide](https://quarkus.io/guides/spring-data-jpa)): Use Spring Data JPA annotations to create your data access layer
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Quarkus Extension for Spring DI API ([guide](https://quarkus.io/guides/spring-di)): Define your dependency injection with Spring DI
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- Quarkus Extension for Spring Web API ([guide](https://quarkus.io/guides/spring-web)): Use Spring Web annotations to create your REST services
