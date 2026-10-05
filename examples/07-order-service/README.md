# order-service

**ch.26 Phase A ("lift the read+command surface onto Quarkus") — S4
(DRQ-068/073).** The monolith's Spring MVC / Spring DI / Spring Data JPA order
READ+COMMAND surface (`OrderController` → `/api/orders`,
`OrderService#getById/listAll/placeOrder`, `Order`/`OrderItem` + repo,
`Customer` + repo) is lifted onto Quarkus largely unchanged via the
Quarkiverse Spring-compatibility extensions (`quarkus-spring-web`,
`quarkus-spring-di`, `quarkus-spring-data-jpa`), mirroring
`examples/05-payment-service`'s and `examples/06-shipping-service`'s Phase A.
See `MIGRATION.md` for the component-by-component table.

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
  `GET /api/orders/{id}` → `OrderDto` (200/404); `GET /api/orders` → array.
- `src/main/proto` carries a verbatim copy of the inventory service's
  `.proto`; `RemoteInventoryClient` (`quarkus-grpc`) is WIRED (not a no-op
  stub) into `placeOrder`'s reserve/release/getStock path, same shape the
  monolith used — see `MIGRATION.md`/`RemoteInventoryClient`'s javadoc for
  what live end-to-end proof is deferred to S5.
- No Kafka consumer/producer, no saga reactions, no outbox relay, and no
  read-model projection yet — that is ch.26 S5 (idiomatic refactor + CQRS
  write model + lifted saga reactions + own outbox) and S6 (read-model
  projection into the `order_view` table reserved below). This service's
  `outbox` and `order_view` tables are RESERVED via Flyway, empty and
  unwritten, so S5/S6 can add the Java-side wiring without a schema
  migration of their own. The store starts empty and is forward-filled only
  — deliberately NO CDC backfill from the monolith's existing data (see
  `MIGRATION.md`).

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

- Quarkus Extension for Spring Data JPA API ([guide](https://quarkus.io/guides/spring-data-jpa)): Use Spring Data JPA annotations to create your data access layer
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health
- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Bean validation using Hibernate Validator and Jakarta Validation annotations
- Flyway ([guide](https://quarkus.io/guides/flyway)): Handle your database schema migrations
- Quarkus Extension for Spring DI API ([guide](https://quarkus.io/guides/spring-di)): Define your dependency injection with Spring DI
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- Quarkus Extension for Spring Web API ([guide](https://quarkus.io/guides/spring-web)): Use Spring Web annotations to create your REST services
- gRPC ([guide](https://quarkus.io/guides/grpc-service-consumption)): Consume a gRPC service (RemoteInventoryClient's `@GrpcClient` blocking stub)
