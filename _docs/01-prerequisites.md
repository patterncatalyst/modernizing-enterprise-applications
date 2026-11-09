---
title: "Prerequisites & the Toolchain"
order: 1
part: "Setting Up"
description: "SDKMAN, JDK 25, Maven, the Quarkus and Camel CLIs, Docker Engine and the compose observability stack, and a verify-your-setup gate."
---

Everything from here on is backed by code in this repository. Setup gets that code building and talking to itself on your machine: the toolchain, then the three projects that already exist (`examples/00-monolith/`, `examples/01-strangler-proxy/` and `examples/02-review-service/`), far enough to prove they run. Clone, install, start, confirm.

{% include excalidraw.html file="toolchain-and-repo-layout" alt="The local toolchain split into required (JDK 25, Maven) and optional (Quarkus CLI, Camel CLI) pieces, the docker compose stack on Docker Engine (Postgres, Kafka in KRaft mode, the Grafana LGTM bundle), and the examples/, tooling/, demos/, infra/, scripts/, and _docs/ directories those two feed." caption="Figure 1.1: The local toolchain and the repository layout it builds, runs, and stands up." %}

## What you need

Only two tools are required for everything that follows. The rest are scoped to specific parts, so install them when you reach them.

| Tool | Required | Used for |
|---|---|---|
| JDK 25 | Yes | Every JVM example |
| Maven 3.9 or the per-project wrapper | Yes | Builds |
| Docker Engine with the `compose` plugin | Yes, for the stack and tests | Compose stack, Testcontainers, Dev Services, native builds, minikube |
| Node.js 22 or newer, Newman | Yes, for the equivalence suite | Running the Postman collection |
| Git | Yes | Cloning, and reading the cited commits |
| Quarkus CLI | Optional | `quarkus dev`, `quarkus info`, `quarkus ext list` |
| Camel CLI and TUI | Optional | Prototyping routes with `camel init` and `camel run` |
| Ruby and Bundler | Only to build this site locally | Jekyll preview and diagram regeneration |

The code targets **JDK 25**. The monolith's `pom.xml` pins both `<java.version>` and `<maven.compiler.release>` to `25` instead of leaving them to the build machine's `PATH`, so startup measurements such as 0.048 seconds for the native Review service come from the same baseline. **SDKMAN**, a shell version manager for the JVM ecosystem, installs and switches JDKs without touching the system package manager:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
sdk use java 25-tem
java -version
```

`-tem` selects Eclipse Temurin, the distribution CI installs (`actions/setup-java@v4` with `distribution: temurin`, `java-version: "25"`), so local and CI builds differ in one fewer variable. A JDK 25 package from your Fedora or RHEL host works too. SDKMAN makes it easy to hold several JDKs side by side and switch per shell, which helps when moving between JVM-mode and native-image examples.

### Maven and the wrapper

The projects differ here. `examples/00-monolith/` has no `mvnw` and expects a Maven on your `PATH`, invoked as `mvn`. `examples/01-strangler-proxy/` and `examples/02-review-service/` each carry `mvnw`, `mvnw.cmd` and a `.mvn/wrapper/` directory, invoked as `./mvnw` from inside the project, so a Quarkus project's Maven version travels with the repository. If you have no Maven:

```bash
sdk install maven 3.9.9
mvn -version
```

### Quarkus and Camel CLIs

No chapter requires the standalone `quarkus` command; every Quarkus project builds with `./mvnw package` or `./mvnw quarkus:dev`. The CLI is faster to type and to script, and some agent traces assume it's on your `PATH` when they drive a running dev-mode instance through the Quarkus Agent MCP server. The Camel CLI is for prototyping a route interactively before it graduates into a Maven project, and the Camel MCP server used for route validation and EIP-catalog lookups relies on the same JBang tooling. You don't need it to build the strangler proxy.

```bash
sdk install quarkus
quarkus --version
sdk install camel
camel --version
```

### Docker Engine

The local stack (Postgres, Kafka, the Grafana LGTM bundle), Testcontainers and Quarkus Dev Services in the tests, native-image container builds and the minikube cluster all run on **Docker Engine**: the `docker-ce` packages from Docker's repository, talking to `/var/run/docker.sock` through the docker context named `default`. Docker Desktop is never required. Compose is the v2 plugin, invoked as `docker compose`, not the legacy hyphenated binary. With one engine for every path, the image tags `.env` pins for the compose stack are the same ones the Testcontainers images and the cluster pull, so the dev loop and the containers a test spins up can't drift apart. The material is verified on Fedora and RHEL hosts, bare metal or VM. Fedora (dnf5) is shown; on RHEL, add the repository with `sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo`:

```bash
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager addrepo --from-repofile=https://download.docker.com/linux/fedora/docker-ce.repo
sudo dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"        # then log out and back in
```

Remove `podman-docker` first if it's installed, because it shadows the `docker` command. Then confirm the engine, the context and the plugin:

{% raw %}
```bash
docker context show        # default
docker version --format '{{.Server.Version}}'
docker compose version
```
{% endraw %}

On the default socket, Testcontainers and Dev Services find the engine with no `DOCKER_HOST`.

### Node.js and Newman

The behavior-equivalence suite is a Postman collection run by **Newman**, Postman's command-line runner, which needs Node.js. Nothing else here uses Node, so any recent LTS works (CI uses Node 22 via `actions/setup-node@v4`): `sudo dnf install -y nodejs npm` on Fedora and RHEL.

```bash
npm install -g newman
newman --version
```

### Git and Ruby

Git clones the repository and lets you read the cited commit history with `git log`. Ruby and Bundler are only for previewing the site: the root `Gemfile` pins Jekyll to the version GitHub Pages runs (`gem "jekyll", "~> 4.3"`, plus `jekyll-feed`, `jekyll-sitemap` and `jekyll-seo-tag`).

```bash
gem install bundler
bundle install
bundle exec jekyll serve
```

## Clone the repository and configure the stack

Copy the tracked `.env.example`, which holds safe documented defaults, to a gitignored `.env` you run against:

```bash
git clone https://github.com/patterncatalyst/modernizing-enterprise-applications.git
cd modernizing-enterprise-applications
cp .env.example .env
```

`.env` is short and every line is commented. It pins the image tags (Grafana `otel-lgtm` 0.36.0, Postgres 18.6-alpine, Apache Kafka 4.3.1, Debezium Connect 3.7.0.Final, Apicurio Registry 3.3.3), the host ports, the monolith's database name, user and password (`monolith_dev_only`, not a secret) and a fixed Kafka KRaft cluster identity. Pinning them in one file, not in `compose.yaml`, lets a later change bump a tag in one place and have every script, CI job and service configuration pick it up.

## Bring up the infrastructure stack

The root `compose.yaml` defines Postgres (the monolith's and, for now, Review's shared database), single-broker KRaft-mode Kafka with no ZooKeeper (needed from the event-driven extractions onward), and the Grafana LGTM bundle (Loki, Grafana, Tempo, Mimir and an embedded OpenTelemetry Collector):

```bash
scripts/stack-up.sh
```

The script resolves its own location, so it works from any directory, copies `.env.example` to `.env` if you skipped that step, checks for `docker` and the `compose` plugin, runs `docker compose --env-file .env up -d`, and polls `docker compose ps` until every healthcheck reports `healthy` or sixty retries pass, then prints the last state it saw. The healthchecks:

| Service | Healthcheck | Why |
|---|---|---|
| Postgres | `pg_isready -h 127.0.0.1` | Over TCP: the image's first-boot init server answers on the Unix socket before the real server listens |
| Kafka | `kafka-broker-api-versions.sh` against its own broker | Confirms the broker answers |
| LGTM bundle | `curl` against Grafana's `/api/health` | The upstream `otel-lgtm` image ships no `wget`, and a healthcheck that assumes it fails with exit code 127 forever |

When it finishes it prints the addresses:

```text
Grafana:    http://localhost:3000
Prometheus: http://localhost:19090 (host 19090; Cockpit owns 9090)
Postgres:   localhost:5432 (see .env for db/user/password)
Kafka:      localhost:9092 (host) / kafka:9094 (compose network)
```

A reachable Grafana at `http://localhost:3000` with no login prompt means the stack is up. Anonymous admin access (`GF_AUTH_ANONYMOUS_ENABLED=true`) suits a disposable dev environment and is the wrong default anywhere else. No dashboards are populated until services emit telemetry. Tear down with the matching script; by default it keeps the named Postgres and Kafka volumes:

```bash
scripts/stack-down.sh        # stop containers, keep volumes
scripts/stack-down.sh -v     # stop containers, wipe volumes (fresh state)
```

## Build and run the monolith

On `main` the monolith module is a frozen shell: every context has been extracted, only `config`, `security` and `MonolithApplication` remain, and every former `/api/**` path returns 404. The runnable monolith with all six contexts is on the `reference/monolith-before` branch (tag `v0-monolith`); check it out to build it and query it on 8080. The three-process topology that follows (a monolith with Review decommissioned, Review on 8081, the proxy on 8888) is the repository at `stage/01-review-extracted`, and `demos/demo-cutover.sh` walks it. The finished system, with no monolith, runs from `demos/demo-final-topology.sh`.

```bash
git checkout reference/monolith-before
```

With Postgres up, build the Spring Boot monolith with plain Maven from the repository root:

```bash
mvn -f examples/00-monolith package
```

This compiles the six bounded contexts (order, inventory, payment, shipping, notification, review) into one `monolith.jar` under `examples/00-monolith/target/`. The committed `application.yml` defaults the datasource password to `monolith`, but the Postgres container was provisioned from `.env` with `POSTGRES_PASSWORD=monolith_dev_only`. Spring Boot's environment-variable override reconciles them without either file hard-coding the other's value:

```bash
cd examples/00-monolith
SPRING_DATASOURCE_PASSWORD=monolith_dev_only \
    java -jar target/monolith.jar
```

Flyway runs the migrations (`V1__init_schema.sql`, `V2__seed_data.sql`) against the `monolith` database on startup, and the application binds to port **8080**. `GET http://localhost:8080/api/reviews?sku=SKU-WIDGET-001` against a freshly seeded database returns a non-empty JSON array.

## Build and run the Review service and the strangler proxy

Both Quarkus projects exist from `stage/01-review-extracted` onward and use the wrapper from inside their own directories:

```bash
cd examples/02-review-service
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

Review listens on **8081** and, for now, reads and writes the same `reviews` table in the same Postgres database the monolith owns, so there's nothing extra to seed; the monolith's Flyway run created the table. Per-service data ownership comes later. In a second shell:

```bash
cd examples/01-strangler-proxy
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

The proxy listens on **8888** and reverse-proxies every request under `/api/**` to one of two backends, chosen by the `strangler.review.enabled` property. `/api/reviews/**` goes to Review on 8081 when the flag is on (the committed default after cutover), and everything else goes to the monolith on 8080. `./mvnw quarkus:dev` in either directory starts dev mode with live reload.

## Run the behavior-equivalence suite

With the monolith on 8080, Review on 8081 and the proxy on 8888, run the check every extraction runs before it trusts a change. The **behavior-equivalence suite** is a Newman collection captured once against the running monolith and re-run unmodified against whatever backend you point it at:

```bash
demos/demo-equivalence.sh http://localhost:8888
```

The script wraps one `newman run` of `tooling/newman/mea.postman_collection.json`, parameterized only by the `baseUrl`. The same collection runs against the monolith (`http://localhost:8080`), Review (`http://localhost:8081`) or the proxy fronting both. A clean run against the proxy covers smoke checks, a full happy-path checkout, an out-of-stock rejection, a payment-decline rollback, and Review's read/write contract including authentication and validation. On a failure the output names the request and the expectation that broke. A failing run here, with nothing else going on, is the cheapest place to catch a wrong port, an unmigrated table or a service that failed to start.

## Repository layout

| Directory | Contents |
|---|---|
| `examples/` | One runnable project per hands-on chapter: the monolith, the strangler proxy, each extracted service, committed in full |
| `tooling/` | Shared tooling, starting with `tooling/newman/`, the equivalence collection and environments |
| `demos/` | Documented wrapper scripts that drive the examples (`demo-equivalence.sh`, `demo-cutover.sh`) |
| `infra/` | Configuration consumed by `compose.yaml`: the OpenTelemetry Collector config, Grafana datasource provisioning, the monolith's database init scripts |
| `scripts/` | `stack-up.sh` and `stack-down.sh`, plus the diagram generator |
| `_docs/` | The chapters |
| `_plans/` | The project ledger: `decisions.md`, `build-plan.md` and the per-iteration plans |
| `assets/` | Site styling, images, and the paired SVG and Excalidraw diagrams |

## Ports

| Service | Port | Source |
|---|---|---|
| Monolith (Spring Boot) | 8080 | `examples/00-monolith/src/main/resources/application.yml` |
| Review service (Quarkus) | 8081 | `examples/02-review-service` |
| Strangler proxy (Camel on Quarkus) | 8888 | `examples/01-strangler-proxy`, `quarkus.http.port` |
| Grafana (LGTM bundle) | 3000 | `compose.yaml`; OTLP on 4317 and 4318, Prometheus on host **19090** (container 9090; Cockpit owns host 9090 on Fedora Server and RHEL), Loki on 3100, Tempo on 3200 |

## CI runs the same gate

The `code-ci.yml` workflow builds Review, applies the monolith's committed Flyway SQL to a disposable Postgres service container (no monolith JVM needed for tables), starts the packaged service, and runs the equivalence collection's "Review Context Contract" folder against it, failing the job on a non-zero Newman exit. The CI check was validated both ways: widening a review rating validation from `@Max(5)` to `@Max(500)` made the same `newman` command fail on cue, and the change was reverted before the workflow was committed. The commands above are the same gate, run by a different caller.

## Build, run, observe

The cutover topology, from the repository root:

```bash
git checkout stage/01-review-extracted
scripts/stack-up.sh
mvn -f examples/00-monolith package
(cd examples/00-monolith && SPRING_DATASOURCE_PASSWORD=monolith_dev_only java -jar target/monolith.jar)   # 8080
(cd examples/02-review-service && ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar)       # 8081
(cd examples/01-strangler-proxy && ./mvnw package && java -jar target/quarkus-app/quarkus-run.jar)      # 8888
demos/demo-equivalence.sh http://localhost:8888
```

Run each service in its own shell. The suite through the proxy reports 49/49 assertions green, with Review served by Quarkus and the other five contexts by the monolith. Experiments:

- On `reference/monolith-before`, point `demos/demo-equivalence.sh` at `http://localhost:8080`: the baseline the collection was captured from passes with no other process running.
- On `stage/01-review-extracted`, start the proxy with `-Dstrangler.review.enabled=false`. `GET /api/reviews?sku=SKU-WIDGET-001` through 8888 returns 404: it routes to the monolith, which has no Review module left, so the reversibility window is closed (`examples/01-strangler-proxy/CUTOVER.md`).
- Start the monolith without `SPRING_DATASOURCE_PASSWORD`. It fails to connect, because the committed default is `monolith`, not the `.env` password.

## For deeper coverage

- `.env.example` and `compose.yaml` for the pinned images and healthchecks.
- `demos/demo-equivalence.sh` and `tooling/newman/` for the collection.
- The project ledger, covered next, for how decisions and plans are recorded.
