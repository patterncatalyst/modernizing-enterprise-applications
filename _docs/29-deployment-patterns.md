---
title: "Deployment Patterns"
order: 29
part: "Operating the Modernized System"
description: "Rolling, breaking-schema-change, and blue-green deployment, tied to the cutover; which fits synchronous vs. event-driven services."
---

Every chapter since Part 5 has treated "running the system" as something that
happens on a developer's own machine: `mvn quarkus:dev` for a service, `docker
compose up -d` for the shared infrastructure that service talks to. That split
has held for the whole migration so far — compose.yaml's own header
comment says it directly: the six extracted services and the strangler proxy
"always ran as plain Java/Quarkus processes outside this compose stack," with
compose providing only Postgres, Kafka, Connect, Apicurio, and the LGTM
observability backend. The monolith was never a compose service either. Eight
application processes, running on the host, reaching `localhost:5432` and
`localhost:9092` for the infrastructure compose stood up alongside them.

This chapter moves all of it — the eight application processes *and* the two
pieces of infrastructure they depend on — into one Kubernetes namespace. The
manifests that do this already exist in this repository, under `deploy/k8s/`,
built as an additive tree: a kustomize `base/` describing eight Deployments,
two StatefulSets, a ConfigMap, and a Secret, plus a minikube `overlay/` that
pins image tags and exposes one Service to the host. Two Dockerfiles that
didn't exist before this chapter — `examples/04-inventory-service` and
`examples/05-payment-service` each needed their own `src/main/docker/`
directory — round out the eight services' container-build story to match the
other six. None of that tree or those Dockerfiles changes in this chapter;
this chapter's job is to read them accurately and explain why they're shaped
the way they are.

One scope note before any of that: everything claimed below about the
manifests was checked **headlessly** — `kubectl kustomize` rendering both the
base and the minikube overlay with no error, and a client-side dry-run
reporting a clean, well-formed object count. No live minikube cluster was
running while this chapter was written. `kubectl apply`, image builds loaded
into a real cluster, pods reaching Ready, and an end-to-end request through
the edge router are Chapter 30's job, not this one's. Where this chapter
states something as verified, it means verified against the rendered YAML —
not against a running pod.

## From compose to cluster

The "from compose to cluster" move in this project's case is really two moves
stacked on top of each other, and it's worth separating them because they
have different costs. The first move is containerizing eight processes that
were never containers before — every one of the eight `src/main/docker/`
directories already had `Dockerfile.jvm` and `Dockerfile.native` templates
(the standard Quarkus-generated UBI image recipes), but nothing in this
project had built or run one of those images until this chapter's manifests
gave them a Deployment to run inside. The second move is relocating the two
infrastructure dependencies those processes already talked to — Postgres and
Kafka — from compose's host-reachable `localhost` ports into the same cluster,
as StatefulSets reachable only by in-cluster DNS.

Lay the two worlds side by side and the shape of the change is a short table:

| Concern | compose (host process + compose infra) | k8s (`deploy/k8s/`) |
|---|---|---|
| Application processes | `mvn quarkus:dev` / packaged jar on the host, one per service | `Deployment`, one pod template per service, in namespace `mea` |
| Postgres | compose `postgres` service, host port via `.env` | `StatefulSet` `postgres`, headless `Service`, PVC-backed |
| Kafka | compose `kafka` service, host port via `.env` | `StatefulSet` `kafka`, headless `Service`, PVC-backed |
| How a service finds its DB | `jdbc:postgresql://localhost:5432/monolith?currentSchema=...` | the same JDBC URL string, with `localhost` replaced via an env-var override (next section) |
| How a service finds another service | `http://localhost:<port>` (strangler's six base-urls, shipping's order-service client) | `http://<svc>-service.mea.svc.cluster.local:<port>` via the same override mechanism |
| Grouping/identity | nothing — eight independent OS processes | one namespace (`mea`), one `app.kubernetes.io/part-of: mea` label on every object |
| Config | `application.properties` + `%dev`/`%prod` profile prefixes | the same files, unedited, plus a ConfigMap/Secret that outrank them at runtime |
| Edge entry | whichever port a developer curled directly | `strangler-proxy` Service, the one NodePort in the minikube overlay |

The right-hand column is `deploy/k8s/base/` and `deploy/k8s/overlays/minikube/`
in full. Nothing in the left-hand column goes away — compose.yaml is untouched
by this chapter, and a developer who wants the Chapter 3-through-28 workflow
back still has it. What's new is a second, parallel way to run the same eight
services, as containers, in one cluster, reachable by cluster DNS instead of
`localhost`.

{% include excalidraw.html file="k8s-deployment-topology" alt="A Kubernetes namespace diagram for the mea namespace. Eight application Deployments — strangler-proxy (edge router, NodePort-exposed, port 8888), review-service (8081), notification-service (8083), inventory-service (8084 HTTP plus 9004 gRPC), payment-service (8085), shipping-service (8088), order-service (8087), and graphql-gateway (8090 HTTP plus 9093 gRPC) — each paired with its own ClusterIP Service, all labeled app.kubernetes.io/part-of: mea. Two StatefulSets, postgres and kafka, each behind a headless Service, sit alongside the Deployments as shared in-cluster infrastructure. A ConfigMap (mea-app-config) and a Secret (mea-postgres-app) feed every Deployment. Arrows show strangler-proxy fanning out to all six bounded-context services (review, notification, inventory, payment, shipping, order), order-service calling inventory-service over gRPC, graphql-gateway calling four services over REST and inventory-service over gRPC, and the five Kafka-consuming services (notification, inventory, payment, shipping, order) connecting to the kafka StatefulSet; the six schema-owning services connect to the postgres StatefulSet." caption="Figure 29.1 — The mea namespace: eight application Deployments, two infrastructure StatefulSets, one ConfigMap/Secret pair, one NodePort entry point" %}

## The deploy tree: kustomize base and a minikube overlay

`deploy/k8s/` picked kustomize over Helm, and the reason is scope rather than
a technology preference: this project has exactly one target environment
today (minikube), no templating language already in the stack, and nothing a
Helm chart's parameterization would buy that a plain overlay doesn't already
cover. Kustomize's whole model is "patch a base, don't template it" — every
object in `base/` is complete, valid YAML on its own, and an overlay layers
small, explicit changes on top without introducing a second syntax (Go
templates and their double-curly-brace interpolation) alongside the YAML
itself. `deploy/k8s/README.md`
documents this tree as adapted from an existing house layout
(`~/Dev/datamesh-reference-arch-quarkus/k8s/`) — the same
base/overlays split, stripped of that project's four-service scope and
`datamesh` namespace, widened to this project's full eight-service running
topology and its `mea` namespace.

The layout itself:

```
deploy/k8s/
  base/
    namespace.yaml              # Namespace "mea"
    app-config.yaml             # ConfigMap mea-app-config
    app-secret.yaml             # Secret mea-postgres-app
    strangler-proxy.yaml        # Deployment + Service, :8888
    review-service.yaml         # Deployment + Service, :8081
    notification-service.yaml   # Deployment + Service, :8083
    inventory-service.yaml      # Deployment + Service, :8084 http + :9004 grpc
    payment-service.yaml        # Deployment + Service, :8085
    shipping-service.yaml       # Deployment + Service, :8088
    order-service.yaml          # Deployment + Service, :8087
    graphql-gateway.yaml        # Deployment + Service, :8090 http + :9093 grpc
    infra/
      postgres-statefulset.yaml
      kafka-statefulset.yaml
    kustomization.yaml
  overlays/
    minikube/
      kustomization.yaml
```

Eleven resource files, one `kustomization.yaml` listing all eleven, in
`base/`. One overlay today. `base/kustomization.yaml` sets `namespace: mea`
once, at the kustomization level, rather than repeating `namespace: mea` in
every object's own `metadata` — every manifest in this tree still carries it
explicitly anyway, which is redundant with the kustomization-level setting but
makes each file correct read in isolation, outside kustomize entirely.

`deploy/k8s/overlays/minikube/kustomization.yaml` does three things, and all
three are visible in the file itself. It references `../../base` as its only
resource today. It sets `images: ... newTag: latest` for all eight
application images — `mea/strangler-proxy`, `mea/review-service`, and so on
through `mea/graphql-gateway` — which is the kustomize idiom for "pin the tag
without touching the Deployment YAML that names the image." And it patches
exactly one Service, `strangler-proxy`, from `ClusterIP` to `NodePort`, via a
JSON-patch `op: replace` on `/spec/type`.

The overlay also carries a seam for Chapter 30, left commented out rather
than wired up:

```yaml
# deploy/k8s/overlays/minikube/kustomization.yaml
# ch.30 SEAM (left deliberately commented, not wired up): this overlay is
# where a future Istio/observability layer would be added, the same way
# ~/Dev/datamesh-reference-arch-quarkus/k8s/istio is a sibling overlay (not
# nested inside base) composed on top of it. Uncomment once those resources
# exist:
# resources:
#   - ../../base
#   - ../../istio
#   - ../../observability
```

That comment is the chapter's answer to "how does this extend." A service
mesh or an observability stack doesn't get bolted into `base/` — it becomes
its own sibling directory, added to the overlay's `resources:` list alongside
`../../base`, the same composition pattern the overlay already uses once for
the app layer. Nothing about `base/` has to change for that to happen; that's
the entire point of putting the seam in the overlay rather than the base.

## Containerizing the services: JVM and native images

All eight services ship two Dockerfiles each: `Dockerfile.jvm` and
`Dockerfile.native`, the pair every `quarkus-container-image`-adjacent Quarkus
project scaffolds by default. Six of the eight — `01-strangler-proxy` through
`03-notification-service` and `06-shipping-service` through
`08-graphql-gateway` — already had both files, plus a `Dockerfile.legacy-jar`
and a `Dockerfile.native-micro` this chapter doesn't use. Two didn't:
`examples/04-inventory-service` had no `src/main/docker/` directory at all,
and `examples/05-payment-service`'s was effectively empty. Both now have the
identical `Dockerfile.jvm`/`Dockerfile.native` pair — copied verbatim from
`examples/03-notification-service/src/main/docker/`, confirmed byte-identical
by diff, since every one of these is the same generic Quarkus/UBI template
with nothing service-specific in it. Filling those two gaps is the only
change this chapter's artifacts make outside `deploy/k8s/` itself, and it's
additive: two new files, nothing edited.

`Dockerfile.jvm` is a four-layer copy from `target/quarkus-app/` onto
`registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24-15`, running as UID 185
(the same non-root UID the Kubernetes manifests' `securityContext.runAsUser`
pins), launched through `run-java.sh`. `Dockerfile.native` starts from
`registry.access.redhat.com/ubi10/ubi-minimal:10.2-1791444377`, copies one file —
`target/*-runner`, the statically-compiled native executable `mvn package
-Pnative` produces — and runs it directly as UID 1001, no JVM in the image at
all.

The choice this chapter's manifests make is JVM by default: every Deployment
in `deploy/k8s/base/` names its image as `mea/<svc>:latest`, built from
`Dockerfile.jvm` (the minikube overlay pins the tag to `1.0` and sets
`imagePullPolicy: Never`). Chapter 27 already put a number on what native buys over
that default for one service — review-service's own measured numbers, not
re-derived here:

| Build | Startup time | Resident memory |
|---|---|---|
| JVM | 1.492 s | ~316 MB |
| Native | 0.048–0.049 s | ~73 MB |

Roughly thirty-one times faster startup, about a quarter of the resident
memory, same equivalence suite passing either way. Nothing about that payoff
changes moving from a bare process to a container — if anything a cluster
makes the memory number matter more, since requests/limits (two sections from
here) are what a scheduler actually bin-packs against. `deploy/k8s/README.md`
names `Dockerfile.native` explicitly as "the lighter/faster-startup path" and
tells you exactly what to change to get it: swap `Dockerfile.jvm` for
`Dockerfile.native` in the build command, rebuild with `-Pnative`, nothing in
the Deployment YAML itself needs editing since both Dockerfiles produce the
same image name and port. JVM stays the default here for the same reason it's
been the default since Chapter 3 introduced the two-phase extraction
discipline: native's build is slower and its failure modes are sharper (ch.27's
`@RegisterForReflection` gap on six services' `ApiError` is exactly the kind
of thing that only shows up under native), so the default path optimizes for
getting a correct container running first.

Building into minikube itself is a `docker build` on the host's Docker Engine
followed by `minikube -p mea image load mea/<svc>:1.0`, which copies the image
into the node's containerd store (the profile runs `--driver=docker
--container-runtime=containerd`, so there is no shared image store to build
into directly). `deploy/k8s/scripts/build-images.sh` does both for all eight
services, and the overlay's `imagePullPolicy: Never` makes a missing load fail
loudly as `ErrImageNeverPull` instead of as a pull from `docker.io/mea/...`.
`deploy/k8s/README.md` spells out the whole sequence, and one detail stands out: every build context is the service's own
`examples/<svc>` directory, not the repo root — these eight Maven
reactors share no parent POM or common module, so there's no reactor-root
context to build from the way a multi-module project might.

## Configuration as data: ConfigMap and Secret

The single hardest thing about moving eight `application.properties` files
into a cluster is that none of them were written with a cluster in mind.
Chapter 27 already drew the line this chapter now has to cross: some
properties in this codebase are **native placeholders** —
`${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, `${ORDER_SERVICE_BASE_URL:...}` —
already reading from an environment variable with a `localhost` fallback. Set
the env var, the placeholder resolves to it, nothing clever required. Other
properties are **hardcoded** — `strangler.review.base-url=http://localhost:8081`,
`quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/...` — with no
`${...}` anywhere in sight. A hardcoded property can't be overridden by
setting an env var with a different name; it has no placeholder to bind to.

What `deploy/k8s/base/app-config.yaml` uses for the hardcoded half is an
**ordinal override**, not interpolation, and the mechanism is worth being
precise about because it's easy to misstate. MicroProfile Config resolves a
property from whichever registered `ConfigSource` has the highest ordinal
that also defines a value for that key. Quarkus's own
`PropertiesConfigSource` — the thing that reads `application.properties`,
`%prod`-prefixed keys included — registers at ordinal 250.
`EnvConfigSource` — the thing that reads process environment variables,
after mapping the property's dots and dashes to underscores and upper-casing
the result — registers at ordinal 300. Set `QUARKUS_DATASOURCE_JDBC_URL` as
an environment variable and it wins over
`quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith` in
`application.properties`, in every profile, with zero code change — not
because anything in the property file changed, but because a higher-ordinal
source answered the lookup first. `app-config.yaml`'s own header comment
states this mechanism explicitly, flagging which of its keys are
ordinal-overrides versus native placeholders key by key.

The caveat belongs right next to that explanation: this is override, not
`${VAR}` interpolation. The `%prod.quarkus.datasource.jdbc.url` line in
`examples/07-order-service/src/main/resources/application.properties` still
reads `jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service`
today, unedited by this chapter, and it would resolve to exactly that
`localhost` URL if `QUARKUS_DATASOURCE_JDBC_URL` weren't set in the
container's environment. Nothing about the property file itself is
environment-aware. The cluster's correct value only wins because
`EnvConfigSource` outranks `PropertiesConfigSource`, a fact about
MicroProfile Config's resolution order rather than anything visible in the
`.properties` file. The same is true of strangler-proxy's six
`strangler.<ctx>.base-url` properties and order-service's
`quarkus.grpc.clients.inventory.host`/`.port` — all hardcoded to
`localhost`-shaped values in their source, all overridden the identical way.

The wiring, concretely: every Deployment in `deploy/k8s/base/` pulls the
entire ConfigMap via `envFrom`, and the six database-owning services add one
explicit `env:` block on top, mapping a per-service ConfigMap key onto the
one container env var name every one of them shares
(`QUARKUS_DATASOURCE_JDBC_URL`) — because six services can't all read a
key literally named `QUARKUS_DATASOURCE_JDBC_URL` out of one shared
ConfigMap without colliding on each other's values. Database credentials come
from the `mea-postgres-app` Secret the identical way, via `secretKeyRef`:

{% include codetabs.html langs="order-service — compose-era local process (application.properties, %prod profile)|order-service — k8s Deployment (envFrom + ordinal-override env block)" %}

```properties
# examples/07-order-service/src/main/resources/application.properties
%prod.quarkus.datasource.db-kind=postgresql
%prod.quarkus.datasource.username=monolith
%prod.quarkus.datasource.password=monolith_dev_only
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service
```

```yaml
# deploy/k8s/base/order-service.yaml
envFrom:
  - configMapRef:
      name: mea-app-config
env:
  - name: QUARKUS_DATASOURCE_JDBC_URL
    valueFrom:
      configMapKeyRef:
        name: mea-app-config
        key: ORDER_SERVICE_JDBC_URL
  - name: QUARKUS_DATASOURCE_USERNAME
    valueFrom:
      secretKeyRef:
        name: mea-postgres-app
        key: username
  - name: QUARKUS_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: mea-postgres-app
        key: password
```

`ORDER_SERVICE_JDBC_URL` itself, in `app-config.yaml`, is
`jdbc:postgresql://postgres.mea.svc.cluster.local:5432/monolith?currentSchema=order_service`
— the same database name and the same `order_service` schema the hardcoded
property names, with only the host swapped from `localhost` to the
in-cluster DNS name of the `postgres` headless Service. Nothing about the
schema-per-service topology Chapter 19 and Chapter 26 built changes; the
cluster just has to tell every service how to find the one Postgres server
that still hosts all six schemas.

The `mea-postgres-app` Secret itself deserves a direct description rather
than a gloss: it is a plaintext `stringData` Secret, checked into git,
republishing the exact `monolith`/`monolith_dev_only` demo credential every
service's `application.properties` already hardcodes. `app-secret.yaml`'s own
header comment is explicit that this is a dev-only artifact, not a production
pattern — a real deployment would replace it with a SealedSecret or an
ExternalSecret synced from a vault, work this tree defers alongside the rest
of a production-hardening pass.

{% include excalidraw.html file="k8s-config-probe-wiring" alt="A wiring diagram centered on one Deployment pod template (notification-service) showing both the configuration path and the probe path. Configuration path: the mea-app-config ConfigMap feeds the pod two ways — a blanket envFrom block, and one explicit QUARKUS_DATASOURCE_JDBC_URL env entry sourced via configMapKeyRef from the per-service NOTIFICATION_SERVICE_JDBC_URL key, labeled 'ordinal override: env ConfigSource (300) beats application.properties PropertiesConfigSource (250)'; the mea-postgres-app Secret feeds QUARKUS_DATASOURCE_USERNAME/PASSWORD via secretKeyRef. Probe path, shown as two contrasting pod templates side by side: notification-service (quarkus-smallrye-health on the classpath) wired to three httpGet probes against /q/health/started, /q/health/ready, /q/health/live; review-service (no quarkus-smallrye-health) wired to three tcpSocket probes against the same HTTP port, with a note that tcpSocket only proves the listener is up, not that routing or the database connection works." caption="Figure 29.2 — Configuration reaching a pod via ordinal-override env vars, and the probe split between the six health-enabled services and the two that use tcpSocket instead" %}

## Liveness and readiness

Chapter 27 closed its health-check accounting with a specific number: six of
the eight Quarkus services in this system carry `quarkus-smallrye-health` on
their classpath — notification, inventory, payment, shipping, order, and the
graphql-gateway — and two don't — review-service and the strangler proxy.
That same six-of-eight split determines the probe shape in every one of the
eight Deployment manifests, and it's a direct, mechanical consequence rather
than an independent decision made twice.

The six health-enabled services get `httpGet` probes against the real
MicroProfile Health endpoints — `startupProbe` against `/q/health/started`,
`readinessProbe` against `/q/health/ready`, `livenessProbe` against
`/q/health/live` — because those endpoints exist, respond with real status
codes, and, for the five on the Kafka side, roll up SmallRye Reactive
Messaging's own broker-connectivity health check automatically. The two that
don't carry the dependency get `tcpSocket` probes against the same HTTP port
instead, because `/q/health/*` would 404 on either service — the port exists
and the Vert.x HTTP listener answers it, but there is no health framework
mounted on it to ask. Each manifest's own header comment states the reason
directly rather than leaving a reader to guess why one service's probe block looks
different from another's:

{% include codetabs.html langs="notification-service — httpGet against real MicroProfile Health|review-service — tcpSocket, no health extension to ask" %}

```yaml
# deploy/k8s/base/notification-service.yaml
startupProbe:
  httpGet:
    path: /q/health/started
    port: http
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 30
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: http
  initialDelaySeconds: 5
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3
```

```yaml
# deploy/k8s/base/review-service.yaml
startupProbe:
  tcpSocket:
    port: http
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 30
readinessProbe:
  tcpSocket:
    port: http
  initialDelaySeconds: 5
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:
  tcpSocket:
    port: http
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3
```

Same timing on both — a generous 30-attempt, 5-second-interval startup
window (150 seconds before Kubernetes gives up and restarts a pod that never
came up), a 10-second-interval readiness check that gates traffic, and a
10-second-interval liveness check that gates restarts. What differs is only
what question each probe type can actually answer. `tcpSocket` proves the
listener is accepting connections; it says nothing about whether
`StranglerProxyRoute`'s six routes actually dispatch correctly, or whether
review-service can reach its slice of the shared `public` schema. `httpGet`
against a real health endpoint proves more — for the Kafka-consuming
services in particular, a broken broker connection fails `/q/health/ready`
automatically, pulling the pod out of a Service's endpoint list before a
request can ever reach a consumer that can't actually process it. The
upgrade path for the two `tcpSocket` services is named directly in both
manifests' own comments: add `quarkus-smallrye-health` to
`examples/01-strangler-proxy/pom.xml` and `examples/02-review-service/pom.xml`
— ch.27's exact six-of-eight gap — and both probe blocks become `httpGet`
with no other change. That's a `pom.xml` edit, which is outside this
manifests-only chapter's scope.

## Resource requests and limits

Every container in `deploy/k8s/base/` sets both `requests` and `limits`, and
the eight application services split into two tiers rather than one uniform
number. The lighter tier — strangler-proxy, review-service, and
graphql-gateway — requests 100m CPU / 256Mi memory and caps at 500m / 512Mi.
The heavier tier — notification, inventory, payment, shipping, and
order-service, the five services on the Kafka side plus their database
connections — requests 150m / 320Mi and caps at 750m / 640Mi. The
distinction tracks a real difference: the heavier tier runs a Kafka consumer
loop, a Hibernate ORM/Panache layer against its own schema, and (for four of
the five) an `@Scheduled` outbox relay poll, on top of the same JAX-RS/Jackson
stack every service shares — more moving parts per pod, a correspondingly
larger request. Postgres and Kafka themselves, in `infra/`, both request more
again (250m/512Mi and 250m/768Mi respectively, each capped higher), which
matches the obvious fact that a database server and a broker carry more
baseline memory and I/O than any one of the application pods talking to them.

Setting both numbers, not just one, is what makes a Kubernetes scheduler's
bin-packing decision and a pod's own out-of-memory behavior predictable
rather than accidental. A `requests` value with no matching `limits` lets a
pod burst into whatever memory happens to be free on its node, with no
ceiling, no QoS class above `Burstable`, and no mechanism gating one noisy
service's memory use against its seven siblings — the standard "noisy
neighbor" failure mode a shared cluster is supposed to prevent. The JVM-mode
images' `run-java.sh` entrypoint reads `CONTAINER_MAX_MEMORY` and sizes the
heap against it automatically via the `JAVA_MAX_MEM_RATIO` mechanism
documented in that Dockerfile's own comment block, so the container's
`limits.memory` isn't just an eviction threshold — it's the number the JVM
itself uses to decide how much heap to claim in the first place.

## Rolling updates and graceful shutdown

All eight application Deployments set the identical rolling-update strategy:

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

`maxUnavailable: 0` means a rollout never drops below the existing replica
count before a new pod is Ready — at `replicas: 1` across every service
today, that forces the new pod up and passing its readiness probe before the
old one is torn down, rather than tearing the old one down first and hoping
the new one starts. `maxSurge: 1` is what makes that possible at
`replicas: 1`: the Deployment is allowed one extra pod during the transition,
so "new pod Ready before old pod terminates" doesn't require a second
permanent replica. That's the right default for every one of these
services' synchronous HTTP surface — a client mid-request against the old
pod finishes against it; a new request lands on whichever pod the Service's
endpoint list currently includes, and the old pod only leaves that list once
it's actually gone.

It is not, on its own, enough for the five services on the Kafka side.
notification, inventory, payment, shipping, and order-service are all
members of a Kafka consumer group, and a consumer group member that
disappears without telling the broker first — a bare SIGKILL, or a SIGTERM
the process never gets a chance to react to — forces the broker to wait out
the full `session.timeout.ms` before it notices the member is gone and
rebalances the group's partitions to whoever's left. That's dead time every
partition that member owned sits unconsumed, for every rolling update, on
every one of five services. The fix each of those five manifests applies is
the same pair of settings, and both are necessary together:

```yaml
spec:
  terminationGracePeriodSeconds: 45
  containers:
    - name: notification-service
      # ...
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 10"]
```

`terminationGracePeriodSeconds: 45` extends the window between Kubernetes
sending SIGTERM and giving up and sending SIGKILL, well past the default 30
seconds. The `preStop` hook's 10-second `sleep` runs *before* SIGTERM is even
delivered — it exists to give an in-flight request or an in-progress Kafka
poll loop a moment to finish its current unit of work and start winding down
cleanly, rather than being interrupted mid-poll the instant the container
starts receiving traffic withdrawal and the termination signal
simultaneously. Between the `preStop` sleep and the extended grace period,
the consumer loop has room to notice the shutdown is happening and send a
clean `LeaveGroupRequest` to the broker before the container is actually
killed — the same discipline this project's own Kafka demos already depend
on for a clean rebalance, now reproduced as pod lifecycle configuration
rather than left to whatever a bare `kill` happens to interrupt.
strangler-proxy, review-service, and graphql-gateway are not Kafka consumers
and carry neither setting — their manifests' own comments say so directly,
and there's nothing for a `LeaveGroupRequest` to protect that doesn't exist
for them.

## The edge router as ingress

`deploy/k8s/overlays/minikube/kustomization.yaml` exposes exactly one
Service off-cluster: `strangler-proxy`, patched from the base's `ClusterIP`
default to `NodePort`. Every one of the other nine Services in this
topology — the six bounded-context services, the gateway, and the two
infrastructure StatefulSets' headless Services — stays `ClusterIP`-only,
reachable from inside the cluster and nowhere else. That split isn't an
oversight limited to one overlay; it's the real topology this project has
built since Chapter 1 named the strangler fig pattern and order-plan.md's S10
confirmed it as the system's permanent edge router rather than a transitional
scaffold: every client request is supposed to enter through
`StranglerProxyRoute`'s URI-prefix dispatch, never by dialing
review-service or order-service directly. Making only that one Service
reachable from outside the cluster is the k8s-native way of enforcing a rule
this project already had.

It's worth being precise about what "ingress" means in this chapter, because
the Kubernetes ecosystem has a specific `Ingress` resource this tree doesn't
use. There's no `Ingress` object, no ingress controller, no hostname-based
routing layer here — `NodePort` is the plainest exposure mechanism
Kubernetes offers, binding a port on every node directly to the Service, with
a fixed `nodePort: 30888` that `deploy/k8s/scripts/setup-profile.sh` publishes
on the host's loopback when it creates the profile
(`minikube start --ports=127.0.0.1:30888:30888`), so
`http://127.0.0.1:30888` reaches the edge router directly — no
`port-forward`, no tunnel, no `minikube service`. Host ports are fixed when the
profile is created; adding another NodePort means recreating it. That's a deliberate
minimum for a single-node minikube target, not a production ingress
strategy — an `Ingress` resource with a real controller (and, past that,
whatever an Istio `VirtualGateway` would add on top) is exactly the kind of
platform capability that belongs in the commented `../../istio` seam this
chapter already pointed at, layered onto this same `strangler-proxy` Service
rather than replacing it.

## Infrastructure in-cluster

`infra/postgres-statefulset.yaml` and `infra/kafka-statefulset.yaml` bring
the same two infrastructure dependencies compose.yaml has provisioned since
Chapter 15 into the cluster itself, as StatefulSets rather than Deployments —
each gets a stable network identity (a headless Service, `clusterIP: None`)
and its own `PersistentVolumeClaim` via `volumeClaimTemplates`, the two
properties a StatefulSet gives a workload that a Deployment doesn't.

Postgres mirrors compose.yaml's own `postgres` service closely: the same
`postgres` alpine image (`18.6-alpine` since DRQ-077), the same `POSTGRES_DB=monolith` database
(now sourced from the `mea-postgres-app` Secret rather than plain compose
env, so there's one credential source for both the server and its six
client Deployments), and the identical `wal_level=logical`,
`max_replication_slots=4`, `max_wal_senders=4` command-line flags Chapter 19
added for Debezium CDC. A `postgres-initdb` ConfigMap mounted at
`/docker-entrypoint-initdb.d` runs one defensive, idempotent
`CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION monolith` statement per
owned-schema service (`notification`, `inventory`, `payment`, `shipping`,
`order_service`) — a belt-and-suspenders bootstrap, not a replacement for
each service's own Flyway migration, which would create its schema on first
run regardless. Kafka is a single-broker KRaft deployment (no ZooKeeper),
the same `apache/kafka` image (`4.3.1` since DRQ-077) and topic defaults
(`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`, three partitions, replication
factor 1 — a single broker can't replicate past one anyway) compose.yaml
already runs, with its advertised listener pointed at the in-cluster DNS name
`kafka.mea.svc.cluster.local` instead of compose's `localhost`/container-alias
split.

What doesn't come along is named explicitly, in both manifests' own comments
and in `deploy/k8s/README.md`'s own section on the subject: Kafka Connect and
the Debezium CDC connector (`compose.yaml`'s `connect` service), the Apicurio
schema registry Chapter 28 built against, and the LGTM observability stack
all have no Kubernetes equivalent in this tree. `wal_level=logical` stays
enabled specifically so a future Connect deployment can tail the write-ahead
log without a Postgres restart, and inventory-service's
`inventory-service-cdc` consumer group sits idle, harmlessly, with nothing
producing to the topic it's waiting on — the same "deferred, not broken"
posture Chapter 28 already modeled for the schema registry itself. All three
are demo-only or Chapter 30 concerns; none of them is required for the eight
application services and two StatefulSets this chapter's manifests already
describe to run.

## Verifying without a cluster

Every claim in this chapter about the shape of these manifests was checked
the same way, and the method matters as much as the result: `kubectl
kustomize` is pure client-side templating — it reads the kustomization tree
and renders final YAML, with no API server involved at all, so it ran clean
against both `deploy/k8s/base` and `deploy/k8s/overlays/minikube` with no
cluster reachable at all. Piping that output into `kubectl apply
--dry-run=client -f -` goes one step further: it validates every rendered
object's shape against the client's own cached API discovery, which is why
that one step needs *some* reachable context (even a stale cached discovery
document from a previous connection) to resolve each `kind` to a REST
resource, or it fails at the discovery step with a connection-refused error
rather than a manifest-validity error.

```bash
# 1. Pure client-side rendering, no cluster needed:
kubectl kustomize deploy/k8s/base
kubectl kustomize deploy/k8s/overlays/minikube

# 2. Client-side admission validation of every rendered object:
kubectl kustomize deploy/k8s/overlays/minikube | kubectl apply --dry-run=client -f -
```

Run against this tree, both layers render with no error, and the overlay's
rendered output is 24 well-formed objects: 1 Namespace, 2 ConfigMaps, 1
Secret, 10 Services, 8 Deployments, 2 StatefulSets. That count is exactly
what the manifest inventory above adds up to — one `postgres-initdb`
ConfigMap plus `mea-app-config`, eight application Services plus two
infrastructure headless Services, eight application Deployments, two
infrastructure StatefulSets — and it's the number worth re-checking on a
fresh clone, since it's the cheapest single signal that nothing in this tree
is missing a resource or duplicating one.

What this workflow explicitly does not verify, and what Chapter 30 picks up
instead: whether `kubectl apply -k deploy/k8s/overlays/minikube` against a
real running minikube actually schedules ten pods successfully; whether the
images referenced by those eight Deployments exist in minikube's
containerd image store (they don't yet — nothing in this chapter builds or loads
one); whether any startup, readiness, or liveness probe actually passes
against a live container; whether a rolling update's `maxUnavailable: 0`
behaves the way this chapter describes under a real rollout; and whether a
request entering through the `strangler-proxy` NodePort actually reaches all
the way through to a bounded-context service and back. `kubectl kustomize |
kubectl apply --dry-run=client` proves the YAML is correct. It proves nothing
about whether the system described by that YAML actually comes up.

## What you learned

- **Containerizing eight services and relocating two infrastructure
  dependencies are two separate moves** — compose.yaml never ran the eight
  application processes as containers at all (its own header comment says
  so); this chapter's manifests both containerize them for the first time
  and move Postgres/Kafka from host-reachable compose services into
  in-cluster StatefulSets, replacing `localhost` with cluster DNS
  (`<svc>.mea.svc.cluster.local`) throughout.
- **An ordinal override is not interpolation** — `EnvConfigSource`'s ordinal
  300 beats `PropertiesConfigSource`'s 250, so setting
  `QUARKUS_DATASOURCE_JDBC_URL` as a container env var overrides a hardcoded
  `jdbc:postgresql://localhost:5432/...` property with zero code change, but
  the property file itself still reads `localhost` today — the mechanism is
  MicroProfile Config's resolution order, not a `${...}` placeholder anyone
  added.
- **The health-probe split traces directly to ch.27's six-of-eight
  finding** — the six services with `quarkus-smallrye-health` get real
  `httpGet` probes against `/q/health/{started,ready,live}`; the two
  without it (review-service, strangler-proxy) get `tcpSocket` probes that
  prove a listener is up and nothing more, because there's no health
  endpoint for `httpGet` to ask.
- **Graceful shutdown for a Kafka consumer takes two settings working
  together** — a `preStop` sleep that runs before SIGTERM, and an extended
  `terminationGracePeriodSeconds`, give the five Kafka-consuming services'
  consumer loops room to send a clean `LeaveGroupRequest` before the
  container dies, avoiding the full `session.timeout.ms` rebalance delay a
  bare kill would force.
- **Headless verification and a live cluster test different things** —
  `kubectl kustomize | kubectl apply --dry-run=client` proves 24 objects
  render correctly and validate client-side; it says nothing about whether
  those objects, applied to a real minikube, actually schedule, pass their
  probes, or route a request end to end. That gap is a stated boundary of
  this chapter's scope, not an oversight.

Chapter 30 is where the gap this chapter named gets closed: a real minikube
cluster, images built and loaded, `kubectl apply` run against a live API
server, and the first end-to-end request traced through the strangler proxy
and out the other side.

---

*Verification status: <span class="status status--verified">verified</span>
headlessly, <span class="status status--deferred">deferred</span> for live
behavior. `kubectl kustomize deploy/k8s/base` and `kubectl kustomize
deploy/k8s/overlays/minikube` were both run and rendered clean, with no
error, against this repository's actual `deploy/k8s/` tree. `kubectl
kustomize deploy/k8s/overlays/minikube | kubectl apply --dry-run=client -f -`
was run and reported 24 client-side-valid objects (1 Namespace, 2 ConfigMaps,
1 Secret, 10 Services, 8 Deployments, 2 StatefulSets), matching the manifest
inventory this chapter describes. No live minikube cluster was running
during this verification — `kubectl apply` against a live API server, image
builds loaded into a cluster, pod scheduling, probe behavior, rolling-update
behavior, and
an end-to-end request through `strangler-proxy`'s NodePort are explicitly
deferred to Chapter 30. Cited: `deploy/k8s/base/namespace.yaml`,
`app-config.yaml`, `app-secret.yaml`, `strangler-proxy.yaml`,
`review-service.yaml`, `notification-service.yaml`, `inventory-service.yaml`,
`payment-service.yaml`, `shipping-service.yaml`, `order-service.yaml`,
`graphql-gateway.yaml`, `infra/postgres-statefulset.yaml`,
`infra/kafka-statefulset.yaml`, `kustomization.yaml`;
`deploy/k8s/overlays/minikube/kustomization.yaml`; `deploy/k8s/README.md`;
`examples/04-inventory-service/src/main/docker/Dockerfile.jvm`/`.native` and
`examples/05-payment-service/src/main/docker/Dockerfile.jvm`/`.native` (both
pairs diffed byte-identical against `examples/03-notification-service`'s);
`examples/07-order-service/src/main/resources/application.properties`
(`%prod.quarkus.datasource.jdbc.url`); `examples/01-strangler-proxy/src/main/resources/application.properties`
(the six hardcoded `strangler.*.base-url` properties); `compose.yaml`'s own
header comment (the "always ran as plain Java/Quarkus processes outside this
compose stack" statement); and `_docs/27-quarkus-microprofile-chassis.md`
(the six-of-eight `quarkus-smallrye-health` finding and the JVM/native
startup-and-memory table this chapter reuses rather than re-measures).
Re-confirm: re-run both `kubectl kustomize` invocations and the dry-run
pipeline against a fresh clone — the 24-object count is the cheapest signal
that nothing in this tree has drifted; `diff` the two filled-in Dockerfile
pairs against `examples/03-notification-service/src/main/docker/`'s
originals to confirm they're still byte-identical; and grep every
`application.properties` under `examples/01` through `examples/08` for
`localhost` to confirm which properties this chapter's ConfigMap overrides
by ordinal versus which already read a native `${VAR:...}` placeholder,
since that split is asserted here, not re-derived from a fresh search.*
