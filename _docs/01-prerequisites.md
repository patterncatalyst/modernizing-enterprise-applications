---
title: "Prerequisites & the Toolchain"
order: 1
part: "Setting Up"
description: "SDKMAN, JDK 25, Maven, the Quarkus and Camel CLIs, the podman observability stack, and a verify-your-setup gate."
---

The introduction promised a monolith you can actually run, extractions you can
actually watch happen, and a behavior-equivalence suite you can actually fail
and fix. None of that is a thought experiment past this point — starting with
the next chapter, every claim in this book is backed by code sitting in this
repository, and this chapter's entire job is to get that code building and
talking to itself on your machine before a single pattern gets discussed.
There is no new example directory here to point at; instead, this chapter
walks you through the toolchain and then through the three pieces of code that
already exist — `examples/00-monolith/`, `examples/01-strangler-proxy/`, and
`examples/02-review-service/` — far enough to prove they run. Chapters 3, 14,
and 15 are where each one gets explained properly; this chapter's bar is
lower and more mechanical: clone, install, start, and confirm.

## What you need, and where it comes from

Seven tools make an appearance across this book, and only two of them are
required for everything that follows. The rest are scoped to specific parts,
and it is worth knowing which is which before you install anything, because
installing tools you will not touch until Part 9 is wasted effort today.

**A Java Development Kit, version 25.** Every JVM example in this
book — the Spring Boot monolith, the Quarkus services, the Camel
routes — targets JDK 25, and the monolith's `pom.xml` pins both
`<java.version>` and `<maven.compiler.release>` to `25` explicitly rather than
leaving them to whatever the build machine happens to have on its `PATH`.
That pin is deliberate: a modernization book that claims a service "starts in
0.048 seconds" (you will see that exact number again in Chapter 7) needs
readers measuring against the same baseline the book measured against, not a
JDK two major versions behind it. The recommended way to get JDK 25 is
**SDKMAN**, a shell-based version manager for the JVM ecosystem that lets you
install and switch between JDK distributions without touching your system
package manager:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
sdk use java 25-tem
java -version
```

The `-tem` suffix selects the Eclipse Temurin distribution, the same one the
project's GitHub Actions CI job installs (`actions/setup-java@v4` with
`distribution: temurin`, `java-version: "25"`) — matching your local JDK
vendor to CI's is one fewer variable the next time a build behaves
differently in the two places. If your Linux distribution or macOS package
manager already ships a JDK 25 build you are comfortable with, that works
too; SDKMAN is the recommendation because it makes it trivial to hold two or
three JDKs side by side and switch per-shell, which matters once you are
flipping between this book's JVM-mode and native-image examples in Part 5.

**Apache Maven 3.9.x**, or just the Maven Wrapper the individual projects
already carry. This is the one place in the toolchain where the two examples
in this repository differ from each other, and the difference is
worth internalizing now rather than discovering it as a build failure later.
`examples/00-monolith/` — the Spring Boot "before" picture — has no
`mvnw` script committed; it expects a Maven installation already on your
`PATH`, invoked as plain `mvn`. `examples/01-strangler-proxy/` and
`examples/02-review-service/` — the Quarkus and Camel-on-Quarkus "after"
pieces — each carry their own `mvnw` / `mvnw.cmd` wrapper and a `.mvn/wrapper/`
directory, invoked as `./mvnw` from inside that project's own directory. The
wrapper exists precisely so a Quarkus project's exact Maven version travels
with the repository instead of depending on whatever happens to be installed
globally; the monolith, as the fixed "before" reference the whole book
measures every extraction against, is simpler and just needs a
real Maven on the path. If you do not already have one, SDKMAN installs Maven
the same way it installs the JDK:

```bash
sdk install maven 3.9.9
mvn -version
```

**The Quarkus CLI — optional, but convenient.** Nothing in this book
*requires* the standalone `quarkus` command; every Quarkus project here ships
its own wrapper and builds fine with `./mvnw package` or `./mvnw quarkus:dev`
alone. The CLI is worth having anyway once you reach Part 5's extraction
chapters, because `quarkus dev`, `quarkus info`, and `quarkus ext list` are
materially faster to type and easier to script than their Maven-goal
equivalents, and a few later "ADLC in Action" callouts assume it is on your
`PATH` when they narrate an agent driving a running dev-mode instance through
the Quarkus Agent MCP server. Install it via SDKMAN alongside the JDK and
Maven:

```bash
sdk install quarkus
quarkus --version
```

**The Camel CLI and Camel TUI — optional, scoped to Part 5 onward.** You will
not need either to build or run `examples/01-strangler-proxy/` in this
chapter — it is an ordinary Quarkus Maven module and `./mvnw package` is all
it takes. The CLI becomes useful later, when Chapter 14 and beyond have you
prototyping a new Camel route interactively (`camel init`, `camel run`)
before it graduates into a committed Maven project, and the Camel MCP server
this book's agent loop leans on for route validation and EIP-catalog lookups
assumes the same JBang-based tooling underneath. If you want it installed now
rather than when Part 5 asks for it:

```bash
sdk install camel
camel --version
```

**Podman and the `podman compose` plugin — not Docker.** This is the one
substitution in the toolchain that is a fixed decision for this book, not a
convenience recommendation: everywhere a
modernization book might reach for `docker compose`, this one uses
`podman compose` instead, and `compose.yaml` at the repository root says so
directly in its own header comment — "PODMAN ONLY" — with a warning against
substituting the Docker CLI or pulling in a Docker-flavored compose file from
elsewhere. The reason is narrower than a general tooling preference: this
project's local stack (Postgres, Kafka, the Grafana LGTM observability
bundle) has to present the *exact* image tags and ports that Quarkus Dev
Services and Testcontainers expect later in the book, and keeping a single
compose source under a single container engine is what prevents a silent
image-tag mismatch between your everyday dev loop and the ephemeral
containers a test run spins up for itself. Install Podman from your
distribution's package manager (it ships in Fedora, RHEL, and most current
Linux distributions by default; macOS and Windows users should use the
official Podman Desktop installer, which also provisions the Podman machine
VM those platforms need), then confirm the `compose` subcommand resolves to
the `podman-compose-v2` plugin rather than the legacy standalone
`podman-compose` Python script:

```bash
podman --version
podman compose version
```

**Node.js and Newman — for the behavior-equivalence suite.** The suite that
gates every extraction in this book from Chapter 15 onward is a Postman
collection run through **Newman**, Postman's command-line collection runner,
which itself runs on Node.js. You do not need Node for anything else in this
book — no JavaScript application code appears anywhere in the examples — so a
recent LTS release (Node 20 or 22; the project's CI job uses Node 22 via
`actions/setup-node@v4`) installed however you normally manage Node versions
on your machine is sufficient. Install Newman globally once Node is in place:

```bash
npm install -g newman
newman --version
```

**Git**, for cloning the repository and following along with the commit
history this book cites directly — Chapter 7, for instance, walks through ten
real commit hashes from this project's own history as its evidence trail, and
you will want `git log` working locally to read them in context rather than
taking the book's word for it.

**Ruby and Bundler — only if you intend to build this Jekyll site locally.**
Reading the chapters does not require Ruby at all; this book is published as
a static GitHub Pages site and a browser is the only client most readers
need. If you want to preview chapter edits or regenerate diagrams on your own
machine, the root `Gemfile` pins Jekyll to the same gem versions GitHub Pages
runs in production (`gem "jekyll", "~> 4.3"`, plus the `jekyll-feed`,
`jekyll-sitemap`, and `jekyll-seo-tag` plugins GitHub Pages provisions
automatically), installed the ordinary Bundler way:

```bash
gem install bundler
bundle install
bundle exec jekyll serve
```

## Clone the repository and configure the stack

With the toolchain in place, start by cloning the repository and copying the
tracked environment template to a local, gitignored `.env` file. This two-step
pattern — a tracked `.env.example` with safe, documented defaults, and a
gitignored `.env` you actually run against — is how the project keeps image
tags and credentials both reproducible and out of version control:

```bash
git clone https://github.com/patterncatalyst/modernizing-enterprise-applications.git
cd modernizing-enterprise-applications
cp .env.example .env
```

Open `.env` and skim it before moving on; it is short and every line is
commented. It pins three image tags — the Grafana `otel-lgtm` bundle, the
Postgres image, and the Apache Kafka image — along with the monolith's
database name, user, and password, and a fixed Kafka KRaft cluster identity.
None of these values are secret (the password is literally
`monolith_dev_only`), but pinning them in one tracked file rather than
hard-coding them into `compose.yaml` itself is what lets a later chapter bump
an image tag in one place and have every script, every CI job, and every
service's own configuration pick the change up without a search-and-replace
across the repository.

## Bring up the infrastructure stack

The project's local infrastructure — Postgres (the monolith's and, for now,
Review's shared database), single-broker KRaft-mode Kafka (no ZooKeeper,
needed from the event-driven extractions in Part 6 onward), and the Grafana
LGTM observability bundle (Loki, Grafana, Tempo, Mimir, and an embedded
OpenTelemetry Collector) — is defined once in the root `compose.yaml` and
brought up with a single helper script:

```bash
scripts/stack-up.sh
```

Read what that script actually does rather than treating it as a black box,
because the mechanics matter the first time something does not come up
cleanly. It resolves its own location so it works regardless of your current
directory, copies `.env.example` to `.env` for you if you skipped the step
above, then runs `podman compose --env-file .env up -d` and polls
`podman compose ps` in a loop until every service's healthcheck reports
`healthy` (or sixty retries elapse, in which case it prints whatever state it
last saw and gets out of your way rather than hanging forever). Each of the
three services carries its own healthcheck in `compose.yaml`: Postgres via
`pg_isready`, Kafka via `kafka-broker-api-versions.sh` against its own
broker, and the LGTM bundle via a plain `curl` against Grafana's
`/api/health` endpoint, because the upstream
`otel-lgtm` image does not ship `wget`, and a healthcheck template that
assumes it will silently fail with exit code 127 forever. When the script
finishes, it prints the three addresses you'll use for the rest of this
chapter and every chapter after it:

```text
Grafana:    http://localhost:3000
Postgres:   localhost:5432 (see .env for db/user/password)
Kafka:      localhost:9092 (host) / kafka:9094 (compose network)
```

Open `http://localhost:3000` in a browser once the script reports healthy.
Grafana is configured for anonymous admin access in this local stack
(`GF_AUTH_ANONYMOUS_ENABLED=true`), which is appropriate for a disposable dev
environment and would be the wrong default anywhere else — you will not see
any dashboards populated yet, because nothing has emitted a trace, metric, or
log to it; that wiring is the subject of Part 9. For now, a reachable Grafana
login screen with no authentication prompt is simply your confirmation that
the stack is actually up. When you are done for the session, tear it down
with the matching script, which by default preserves the named Postgres and
Kafka volumes so your seed data and topics survive a restart:

```bash
scripts/stack-down.sh        # stop containers, keep volumes
scripts/stack-down.sh -v     # stop containers, wipe volumes (fresh state)
```

## Build and run the monolith

With Postgres up, build the Spring Boot monolith with plain Maven — no
wrapper, as noted above — from the repository root:

```bash
mvn -f examples/00-monolith package
```

This compiles the six bounded contexts (order, inventory, payment, shipping,
notification, review) into a single `monolith.jar` under
`examples/00-monolith/target/`. Before you run it, there is one fragile detail
worth documenting rather than hitting as a confusing connection failure:
`examples/00-monolith/src/main/resources/application.yml` ships a *default*
datasource password of `monolith` for convenience, but the actual Postgres
container the stack just started was provisioned from `.env`'s
`POSTGRES_PASSWORD=monolith_dev_only`. Spring Boot's standard environment-variable
override (`SPRING_DATASOURCE_PASSWORD`) is how the application's committed
defaults and the stack's actual credentials get reconciled at runtime, without
either file needing to hard-code the other's value:

```bash
cd examples/00-monolith
SPRING_DATASOURCE_PASSWORD=monolith_dev_only \
    java -jar target/monolith.jar
```

On startup, Flyway runs the monolith's own migrations
(`V1__init_schema.sql`, `V2__seed_data.sql`) against the `monolith` database
automatically — there is no separate migration step to remember — and the
application binds to port **8080**. A `GET http://localhost:8080/api/reviews?sku=SKU-WIDGET-001`
against a freshly seeded database should return a non-empty JSON array; if it
does, the monolith is up and seeded correctly.

## Build and run the Quarkus Review service and the Camel strangler proxy

The two "after" pieces this chapter asks you to stand up both use the Maven
Wrapper, invoked from inside their own project directories, which is the
pattern you will repeat for every Quarkus and Camel project in the rest of
this book:

```bash
cd examples/02-review-service
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

The Review service listens on port **8081** and, in its current form, reads
and writes the *same* `reviews` table in the *same* Postgres database the
monolith owns — true per-service data ownership is a Part 6 concern, not
this chapter's or even Part 5's — so there is nothing extra to migrate or
seed here; the monolith's Flyway run already created the table this service
needs. In a second shell, build and run the Camel strangler proxy the same
way:

```bash
cd examples/01-strangler-proxy
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

The proxy listens on port **8888** and reverse-proxies every request under
`/api/**` to one of the two backends above, chosen by the
`strangler.review.enabled` property: `/api/reviews/**` goes to the Review
service on 8081 when the flag is on (the committed default, as of this
project's own r02 cutover) and everything else always goes to the monolith on
8080. If you would rather explore either Quarkus project with live reload
instead of a packaged jar while you are getting oriented, `./mvnw quarkus:dev`
from inside either directory starts it in dev mode and recompiles changes on
the fly — a detail you will use constantly from Part 5 onward and might as
well try once now.

## Run the behavior-equivalence suite

With all three services reachable — monolith on 8080, Review on 8081, proxy
on 8888 — you are ready to run the same check every extraction chapter in
this book runs before it trusts a change: the **behavior-equivalence suite**,
a Newman collection captured once against the running monolith and re-run,
completely unmodified, against whatever backend you point it at. Install
Newman if you have not already (`npm install -g newman`), then run the
project's thin wrapper script against the proxy:

```bash
demos/demo-equivalence.sh http://localhost:8888
```

The script is a few lines of bash around a single `newman run` invocation
against `tooling/newman/mea.postman_collection.json`, parameterized entirely
by the `baseUrl` you pass it; nothing about the collection itself changes
whether you point it at the monolith directly (`http://localhost:8080`), the
Review service directly (`http://localhost:8081`), or the proxy fronting
both (`http://localhost:8888`). A clean run against the proxy reports every
assertion passing — smoke checks, a full happy-path checkout, an
out-of-stock rejection, a payment-decline rollback, and the Review service's
own read/write contract including its authentication and validation
behavior. If any assertion fails, the script's output names exactly which
request and which expectation broke, which is the entire point of
externalizing this check into a re-runnable artifact instead of a
"looks right to me" judgment call: a failing run here, right now, with
nothing else going on, is the cheapest possible place to catch a
misconfigured port, an un-migrated table, or a service that silently failed
to start, before you carry that mistake into a chapter that assumes a
working baseline.

## Finding your way around the repository

Before moving on, it is worth a quick orientation pass over the top-level
directories you will keep returning to. None of this needs to be memorized —
you will have walked every one of these paths by the time you reach
Part 3 — but knowing roughly what lives where now will save you a few
`find` commands later.

| Directory | What's in it |
|---|---|
| `examples/` | One runnable project per hands-on chapter — the monolith, the strangler proxy, each extracted service — committed in full, not as snippets. |
| `tooling/` | Shared developer tooling that spans examples, starting with `tooling/newman/` — the behavior-equivalence suite's collection and environments. |
| `demos/` | Thin, documented wrapper scripts that drive the examples for a reader — `demo-equivalence.sh`, `demo-cutover.sh` — rather than raw tool invocations buried in prose. |
| `infra/` | Configuration consumed by `compose.yaml` — the OpenTelemetry Collector config, Grafana datasource provisioning, and the monolith's database init scripts. |
| `scripts/` | Project-level automation — `stack-up.sh` / `stack-down.sh` for the local infrastructure, plus the diagram generator used across chapters. |
| `_docs/` | The chapters themselves — this file is `_docs/01-prerequisites.md`. |
| `_plans/` | The project's own ADLC ledger — `decisions.md`, `build-plan.md`, and per-iteration plans — introduced properly as a reader-facing concept in the next chapter. |
| `assets/` | Site styling, images, and the paired SVG/Excalidraw diagrams embedded throughout the chapters. |

## Ports at a glance

You will see this same quartet of addresses recur across most of Part 3
onward, so it is worth having them in one place rather than re-deriving them
from `compose.yaml` and each project's `application.properties` every time:

| Service | Port | Source |
|---|---|---|
| Monolith (Spring Boot) | 8080 | `examples/00-monolith/src/main/resources/application.yml` |
| Review service (Quarkus) | 8081 | `examples/02-review-service` |
| Strangler proxy (Camel on Quarkus) | 8888 | `examples/01-strangler-proxy`, `quarkus.http.port` |
| Grafana (LGTM bundle) | 3000 | `compose.yaml`; OTLP on 4317/4318, Mimir on 9090, Loki on 3100, Tempo on 3200 |

## CI runs this same gate

Everything in this chapter also runs unattended in GitHub Actions. The
project's `code-ci.yml` workflow builds the Review service, applies the
monolith's own committed Flyway SQL directly against a disposable Postgres
service container (rather than booting the monolith's JVM just to get
tables), starts the packaged service, and runs the behavior-equivalence
suite's "Review Context Contract" folder against it — failing the job on any
non-zero Newman exit code. That workflow was deliberately validated both
ways before it was trusted: a temporarily widened validation rule
(`@Max(5)` loosened to `@Max(500)` on a review rating) made the exact same
`newman` command this job runs fail on cue, proving the gate actually gates
rather than merely running green by default, before the change was reverted
and the workflow committed. The practical upshot for you, right now, is that
the commands in this chapter are not a one-off onboarding ritual distinct
from how the project verifies itself — they are the same gate, run by a
different caller.

## What you learned

- The toolchain for this book splits into two required pieces (a JDK 25
  install and a Maven on your `PATH`, or the per-project wrapper) and several
  scoped-later pieces (Quarkus CLI, Camel CLI, Ruby/Bundler) you can defer
  without losing anything today.
- Podman, not Docker, is this project's fixed container toolchain
  (`compose.yaml`'s own header enforces it) specifically to keep local
  image tags in lockstep with what Quarkus Dev Services and Testcontainers
  expect later in the book.
- `scripts/stack-up.sh` brings up Postgres, Kafka, and the Grafana LGTM
  bundle from one `compose.yaml`, gated by real healthchecks rather than a
  fixed sleep.
- The behavior-equivalence suite — one Newman collection, parameterized
  entirely by `--baseUrl` — is the same check this chapter asks you to run
  by hand and the one GitHub Actions runs unattended; getting it green here
  means you are starting Part 3 from a verified baseline, not a hopeful one.

The next chapter, **"The Project Ledger,"** introduces the three documents
this project uses as its own memory across the chapters you are about to
read — `decisions.md`, `build-plan.md`, and its reconciliation record — and
shows you how to keep the same kind of ledger for your own migration.

---

*Verification status: <span class="status status--unverified">unverified</span>.
This chapter's commands are transcribed directly from the project's own
tracked scripts, pom files, and workflow definitions (`compose.yaml`,
`.env.example`, `scripts/stack-up.sh`, `scripts/stack-down.sh`,
`demos/demo-equivalence.sh`, `tooling/newman/README.md`, and
`.github/workflows/code-ci.yml`), not improvised for this chapter, but they
have not been re-run end-to-end from a clean clone in the authoring loop that
produced this page. The highest-risk things for a reader to confirm on a
real run: that `scripts/stack-up.sh` reports all three services healthy on a
fresh machine, that the `SPRING_DATASOURCE_PASSWORD` override above
actually matches whatever you set `POSTGRES_PASSWORD` to in your own `.env`,
and that `demos/demo-equivalence.sh http://localhost:8888` reports a fully
green run against your own freshly built services before you continue to
Chapter 2.*
