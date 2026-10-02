# review-service

**ch.15 Phase A ("lift onto Quarkus") — r02 S8.** The monolith's (`examples/00-monolith`)
Review bounded context, lifted onto Quarkus largely unchanged via the
Quarkiverse Spring-compatibility extensions (DRQ-029). This is **Phase A only**
— the Spring annotations and class shapes are kept as-is on purpose; the
idiomatic Quarkus rewrite (Quarkus REST, Panache, native CDI) is Phase B
(r02 S9).

- Port `8081`. Endpoints (identical external contract to the monolith):
  `GET /api/reviews?sku=...`, `GET /api/reviews/{id}`, `POST /api/reviews`
  (HTTP Basic, `demo-customer`/`demo-pass`, role `CUSTOMER`).
- Persistence: the SAME podman-stack Postgres the monolith uses
  (`localhost:5432`, db `monolith`), reading/writing the existing `reviews`
  table plus minimal read-only projections of the shared `customers`/
  `inventory_items` tables. This service owns **no** schema of its own yet —
  true database decomposition is deferred to the data-across-the-seam
  chapters (ch.18/19), not this step.
- Verified against the project's behavior-equivalence suite
  (`tooling/newman/mea.postman_collection.json`, "Review Context Contract"
  folder) — unchanged, all assertions green.

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

You can then execute your native executable with: `./target/review-service-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- Hibernate Validator ([guide](https://quarkus.io/guides/validation)): Bean validation using Hibernate Validator and Jakarta Validation annotations
- Quarkus Extension for Spring DI API ([guide](https://quarkus.io/guides/spring-di)): Define your dependency injection with Spring DI
- Quarkus Extension for Spring Data JPA API ([guide](https://quarkus.io/guides/spring-data-jpa)): Use Spring Data JPA annotations to create your data access layer
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- Elytron Security Properties File ([guide](https://quarkus.io/guides/security-properties)): Secure your applications using properties files
- Quarkus Extension for Spring Web API ([guide](https://quarkus.io/guides/spring-web)): Use Spring Web annotations to create your REST services
