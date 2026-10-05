# notification-service

**ch.17 Phase B ("make it idiomatic" + "make it live") — r04 S5.** Builds on
Phase A (r04 S4: the monolith's Notification read surface lifted onto
Quarkus via Spring-compat extensions, DRQ-035). Phase B removes the
compat shim — `NotificationResource` → `NotificationService` →
`NotificationRepository` → `Notification` now run on Quarkus REST, Panache,
and plain CDI, mirroring `examples/02-review-service`'s Phase B — **and**
makes the async pipeline live: a net-new SmallRye Reactive Messaging
`@Incoming("order-placed")` consumer idempotently persists notifications
from the monolith's outbox→Kafka `order.placed` event, plus a
`quarkus-websockets-next` push at `/ws/notifications` (adapted from
`~/Dev/datamesh-reference-arch-quarkus/examples/notification-service`,
JSON not Avro — DRQ-038). See `MIGRATION.md` for the full component-by-component
Phase A → B table and measured before/after metrics.

**This service OWNS ITS OWN SCHEMA from day one**, not the monolith's shared
schema (DRQ-035) — see "Why own-schema" below and `Notification.java`'s
javadoc for the full rationale.

- Port `8083`. Read endpoint (identical external contract to the monolith):
  `GET /api/notifications?customerId=...` → `NotificationDto[]` (200/400).
- `/ws/notifications` — WebSocket push, sends `{"type":"connected"}` on open,
  then a `NotificationDto` whenever an `order.placed` event is consumed.
- Kafka: consumes topic `order.placed` (same topic the monolith's
  `OutboxRelay` publishes to), idempotent by `orderId` (check-then-insert +
  a DB-level partial unique index — see `MIGRATION.md`).

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

## Verified (ch.17 S5 DoD)

- `./mvnw -q package`: **BUILD SUCCESS**. No `quarkus-spring-*` dependency
  remains in `pom.xml`; no `org.springframework` import remains anywhere
  under `src/`.
- `./mvnw -q test`: **10/10 tests green** — `NotificationResourceTest` (3,
  read surface against Dev Services Postgres), `NotificationServiceTest` (4,
  Mockito unit tests incl. `recordOrderPlaced` idempotent-write logic),
  `OrderPlacedConsumerTest` (2, feeds an `order.placed` event through the
  real `@Incoming` pipeline via SmallRye's in-memory connector: happy path +
  an idempotency test proving redelivery yields exactly one notification),
  `OrderNotificationSocketTest` (1, WebSocket handshake).
- **End-to-end** (podman-stack Kafka + Postgres up, monolith started with
  `NOTIFICATION_MODE=outbox`, this service packaged and run on `:8083`): a
  real checkout against the monolith (`POST :8080/api/orders`) produced an
  `order.placed` outbox row, the `OutboxRelay` published it to Kafka, and
  `GET :8083/api/notifications?customerId=1` observed the resulting
  notification within ~5 seconds (consistent with the relay's 2-second poll
  interval). Resetting the outbox row's `published_at` to simulate an
  at-least-once redelivery caused a second Kafka publish and a second
  consume — the notification count stayed at exactly one. See `MIGRATION.md`
  for the full before/after metrics and verification record.

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

- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplified Hibernate ORM via the active record or the repository pattern
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- SmallRye Reactive Messaging - Kafka Connector ([guide](https://quarkus.io/guides/kafka)): Connect to Kafka with Reactive Messaging
- WebSockets Next ([guide](https://quarkus.io/guides/websockets-next-tutorial)): Build reactive WebSocket endpoints
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health
