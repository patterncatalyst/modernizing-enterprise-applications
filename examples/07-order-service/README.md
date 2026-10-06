# order-service

**ch.26 Phase B ("idiomatic refactor + CQRS write model + lifted saga
reactions + own outbox") — S5 (DRQ-073/074), HARD PART H1.** The Phase A
Spring-compat shim is gone — `/api/orders` now runs on Quarkus REST
(`OrderResource`) + Panache (`OrderRepository`/`CustomerRepository`) + plain
CDI (`OrderService`) — and the net-new CQRS write core is layered on top:
`placeOrder` is the command handler (validate → reserve over gRPC → persist
`PENDING` → write `order.placed` to this service's OWN transactional outbox,
atomic with the order row, no dual-write), and `OrderSagaListener` lifts the
monolith's FOUR `OrderSagaListener` reactions as SmallRye `@Incoming`
consumers in this service's OWN consumer group, preserving the
status-guard-idempotency / at-most-once-compensating-`Release` /
mutual-exclusion guarantees exactly. Mirrors `examples/05-payment-service`'s
and `examples/06-shipping-service`'s Phase B. See `MIGRATION.md` for the
component-by-component table and measured before/after metrics.

**This service OWNS ITS OWN SCHEMA from day one** (`order_service` — not
`order`, a reserved Postgres keyword; see `V1__create_order_schema.sql`) —
same discipline as inventory/payment/shipping, NOT review's shared-schema
approach. It also owns the lifted `customers` table (customer was never its
own extraction — it comes along with order).

**FK decomposition (DRQ-068):** `Order`'s monolith `@ManyToOne Customer` is
replaced with a plain `customerId` value + a `customerEmail` snapshot — no
JPA association, no DB-level FK, even though `customers` is now co-owned by
this same schema (see `Order.java`'s javadoc).

- Port `8087` (test port `8091`). Read+command endpoints (identical external
  `OrderDto` contract to the monolith, including `shippingAddress`):
  `POST /api/orders` → `202 Accepted` + `Location` (always `PENDING`);
  `GET /api/orders/{id}` → `OrderDto` (200/404); `GET /api/orders` → array;
  409 `OUT_OF_STOCK` on insufficient inventory.
- `src/main/proto` carries a verbatim copy of the inventory service's
  `.proto`; `RemoteInventoryClient` (`quarkus-grpc`) is WIRED into
  `placeOrder`'s reserve/release/getStock path, same shape the monolith used.
- **Own transactional outbox** (`OrderOutboxEvent`/`OrderOutboxRepository`/
  `OrderOutboxRelay`): `placeOrder` writes `order.placed` atomically with the
  `Order` row; a `@Scheduled` relay publishes unpublished rows to Kafka
  (`order-placed` channel → `order.placed` topic).
- **Four lifted saga reactions** (`OrderSagaListener`, own consumer group):
  `payment-captured` → `AWAITING_SHIPMENT`; `shipment-dispatched` →
  `CONFIRMED` (the ONLY path to `CONFIRMED`); `shipment-failed` →
  `SHIPPING_FAILED` + compensating `Release` per sku; `payment-declined` →
  `PAYMENT_DECLINED` + compensating `Release` per sku. Status-guard
  idempotent, at-most-once compensation, mutually exclusive by construction
  — see `MIGRATION.md`'s Phase B section.
- No read-model projection yet — that is ch.26 S6 (`order_view`, reserved
  via Flyway, empty and unwritten).

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

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

You can then execute your native executable with: `./target/order-service-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): Jakarta REST (`OrderResource`), `@ServerExceptionMapper` (`GlobalExceptionMapper`)
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): the REPOSITORY pattern (`OrderRepository`/`CustomerRepository`/`OrderOutboxRepository`)
- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Bean validation using Hibernate Validator and Jakarta Validation annotations
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- gRPC ([guide](https://quarkus.io/guides/grpc-service-consumption)): Consume a gRPC service (`RemoteInventoryClient`'s `@GrpcClient` blocking stub)
- SmallRye Reactive Messaging - Kafka Connector ([guide](https://quarkus.io/guides/kafka)): the four `@Incoming` saga reactions (`OrderSagaListener`) + the outbox relay's `@Channel` emitter (`OrderOutboxRelay`)
- Scheduler ([guide](https://quarkus.io/guides/scheduler)): `OrderOutboxRelay`'s `@Scheduled` poll
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health
