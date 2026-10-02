# review-service

**ch.15 Phase B ("make it idiomatic") — r02 S9.** The monolith's
(`examples/00-monolith`) Review bounded context, extracted onto Quarkus in
two phases (DRQ-029): **Phase A** (r02 S8, preserved in git history at commit
`5479d49`) lifted the Spring-shaped source onto Quarkus largely unchanged via
the Quarkiverse Spring-compatibility extensions. **This step (Phase B)**
refactors off that compatibility shim to idiomatic Quarkus — Quarkus REST
(RESTEasy Reactive) instead of `quarkus-spring-web`, Panache repositories
instead of Spring Data `JpaRepository`, plain CDI `@ApplicationScoped` +
constructor injection instead of `@Service`, and a `@ServerExceptionMapper`
instead of `@RestControllerAdvice` — with the exact same external HTTP
contract. See `MIGRATION.md` for the before/after measurements.

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

## CI equivalence gate (r02-plan S-CI, DRQ-030)

`.github/workflows/code-ci.yml` builds this service, points it at a disposable
GitHub Actions Postgres service container (schema/seed applied from the
monolith's committed `V1__init_schema.sql`/`V2__seed_data.sql`, since this
service owns no migrations of its own), and runs the behavior-equivalence
suite's **"Review Context Contract"** folder
(`tooling/newman/mea.postman_collection.json`) against it. A non-zero
`newman` exit code fails the job — this is the project's equivalence gate
running in CI for the first time.

Before this workflow was committed it was validated locally both ways:

- **Green:** with the unmodified service running against the podman-stack
  Postgres, `newman run ... --folder "Review Context Contract"` passed all 16
  assertions, exit code `0`.
- **Red:** `ReviewCreate`'s `@Max(5)` on `rating` was temporarily widened to
  `@Max(500)`. Re-running the identical `newman` command then failed:
  `4e. Authenticated write with invalid rating -> 400 validation` got a `201
  Created` instead of `400`, and the `error=VALIDATION_FAILED` assertion
  failed too — exit code `1`. The widened annotation was reverted immediately
  afterward (confirmed via `git diff` showing no residual change), and the
  suite was re-run green before the workflow file was committed — proving the
  gate actually gates, not just runs.

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
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplified JPA/Hibernate data access
- Writing REST services with Quarkus REST ([guide](https://quarkus.io/guides/rest)): Jakarta REST (JAX-RS) endpoints and `@ServerExceptionMapper`
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- Elytron Security Properties File ([guide](https://quarkus.io/guides/security-properties)): Secure your applications using properties files
