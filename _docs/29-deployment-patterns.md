---
title: "Deployment Patterns"
order: 29
part: "Operating the Modernized System"
description: "Moving eight Quarkus processes and their Postgres and Kafka into one namespace with kustomize: config by ordinal override, probes, resource tiers, rolling updates and graceful consumer shutdown."
---

## Learning objectives

By the end of this chapter you can:

- Separate the two moves in going from compose to cluster, containerizing eight processes and relocating Postgres and Kafka into StatefulSets, and say why their costs differ.
- Read a kustomize `base` and `overlays` tree and say why a single-environment deployment uses patch-a-base instead of Helm templating.
- Tell MicroProfile Config's ordinal override (`EnvConfigSource` at 300 over `PropertiesConfigSource` at 250) from `${VAR}` interpolation, and override a hardcoded `jdbc:postgresql://localhost:5432/...` from a Deployment's `env` block with no code change.
- Map a service's `quarkus-smallrye-health` dependency to its probe shape: `httpGet` against `/q/health/{started,ready,live}`, or a `tcpSocket` fallback.
- Combine a rolling update (`maxUnavailable: 0`, `maxSurge: 1`) with a `preStop` sleep and a longer `terminationGracePeriodSeconds` to avoid a full `session.timeout.ms` rebalance.
- Explain why only `strangler-proxy` is a `NodePort` and every other Service is `ClusterIP`.
- State what `kubectl kustomize` plus `kubectl apply --dry-run=client` does and does not verify.

## Why this matters

Until now the system ran on a developer's machine: `mvn quarkus:dev` for a service and `docker compose up -d` for the infrastructure behind it. The compose file's own header says the six extracted services and the strangler proxy "always ran as plain Java/Quarkus processes outside this compose stack"; it supplies only Postgres, Kafka, Connect, Apicurio and the LGTM backend. The monolith was never a compose service either. Eight application processes ran on the host and reached `localhost:5432` and `localhost:9092`.

The manifests under `deploy/k8s/` move all of it into one Kubernetes namespace: a kustomize `base/` of eight Deployments, two StatefulSets, a ConfigMap and a Secret, plus a minikube `overlay/` that pins image tags and exposes one Service. `examples/04-inventory-service` and `examples/05-payment-service` each gained a `src/main/docker/` directory so all eight services build the same way.

## From compose to cluster

Two moves are stacked here. The first containerizes eight processes that were never containers: every service had `Dockerfile.jvm` and `Dockerfile.native` templates, but nothing had built or run one. The second relocates Postgres and Kafka from host-reachable `localhost` ports into StatefulSets reachable only by in-cluster DNS.

| Concern | compose (host process + compose infra) | k8s (`deploy/k8s/`) |
|---|---|---|
| Application processes | `mvn quarkus:dev` or a packaged jar on the host | one `Deployment` per service, namespace `mea` |
| Postgres | compose `postgres`, host port via `.env` | `StatefulSet` `postgres`, headless `Service`, PVC |
| Kafka | compose `kafka`, host port via `.env` | `StatefulSet` `kafka`, headless `Service`, PVC |
| Service finds its DB | `jdbc:postgresql://localhost:5432/monolith?currentSchema=...` | the same URL with `localhost` replaced by an env-var override |
| Service finds another service | `http://localhost:<port>` | `http://<svc>-service.mea.svc.cluster.local:<port>`, same override |
| Identity | eight independent OS processes | one namespace, `app.kubernetes.io/part-of: mea` on every object |
| Config | `application.properties` with `%dev`/`%prod` | the same files, unedited, plus a ConfigMap and Secret that outrank them |
| Edge entry | whichever port a developer curled | `strangler-proxy`, the one NodePort in the minikube overlay |

compose.yaml is untouched. The cluster is a second, parallel way to run the same eight services.

{% include excalidraw.html file="k8s-deployment-topology" alt="A Kubernetes namespace diagram for the mea namespace. Eight application Deployments (strangler-proxy: edge router, NodePort-exposed, port 8888; review-service: 8081; notification-service: 8083; inventory-service: 8084 HTTP plus 9004 gRPC; payment-service: 8085; shipping-service: 8088; order-service: 8087; and graphql-gateway: 8090 HTTP plus 9093 gRPC), each paired with its own ClusterIP Service, all labeled app.kubernetes.io/part-of: mea. Two StatefulSets, postgres and kafka, each behind a headless Service, sit alongside the Deployments as shared in-cluster infrastructure. A ConfigMap (mea-app-config) and a Secret (mea-postgres-app) feed every Deployment. Arrows show strangler-proxy fanning out to all six bounded-context services (review, notification, inventory, payment, shipping, order), order-service calling inventory-service over gRPC, graphql-gateway calling four services over REST and inventory-service over gRPC, and the five Kafka-consuming services (notification, inventory, payment, shipping, order) connecting to the kafka StatefulSet; the six schema-owning services connect to the postgres StatefulSet." caption="Figure 29.1: The mea namespace: eight application Deployments, two infrastructure StatefulSets, one ConfigMap and Secret pair, one NodePort entry point" %}

## Kustomize base and overlay

`deploy/k8s/` uses kustomize, not Helm, for scope reasons: one target environment (minikube), no templating language in the stack, and nothing a chart's parameters would buy over an overlay. Every object in `base/` is complete, valid YAML on its own, and an overlay layers small explicit changes on top without a second syntax.

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

`base/kustomization.yaml` lists the eleven resource files and sets `namespace: mea` once. Every manifest also carries the namespace itself, which is redundant but keeps each file correct read outside kustomize.

The minikube overlay does three things. It references `../../base`. It sets `images: ... newTag: "1.0"` for all eight application images, the kustomize idiom for pinning a tag without touching the Deployment YAML. And it patches one Service, `strangler-proxy`, from `ClusterIP` to `NodePort` with a JSON-patch `op: replace` on `/spec/type`.

The overlay also holds a commented seam for a service mesh and observability layer:

```yaml
# deploy/k8s/overlays/minikube/kustomization.yaml
# SEAM (left commented, not wired up): this overlay is where a future
# Istio/observability layer would be added, the same way
# ~/Dev/datamesh-reference-arch-quarkus/k8s/istio is a sibling overlay (not
# nested inside base) composed on top of it. Uncomment once those resources
# exist:
# resources:
#   - ../../base
#   - ../../istio
#   - ../../observability
```

A mesh or observability stack becomes a sibling directory in the overlay's `resources:` list, so `base/` never changes.

## Containerizing the services: JVM and native images

Each service ships `Dockerfile.jvm` and `Dockerfile.native`. Six of the eight already had both; `examples/04-inventory-service` had no `src/main/docker/` directory and `examples/05-payment-service`'s was effectively empty. Both now carry the pair, copied from `examples/03-notification-service/src/main/docker/` and confirmed byte-identical by diff, since these are the generic Quarkus UBI templates. Those two files are the only change outside `deploy/k8s/`.

`Dockerfile.jvm` copies four layers from `target/quarkus-app/` onto `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24-15`, runs as UID 185 (the UID the manifests' `securityContext.runAsUser` pins) and launches through `run-java.sh`. `Dockerfile.native` starts from `registry.access.redhat.com/ubi10/ubi-minimal:10.2-1791444377`, copies the one `target/*-runner` executable that `mvn package -Pnative` produces, and runs it as UID 1001 with no JVM in the image.

Every Deployment names `mea/<svc>:latest` built from `Dockerfile.jvm`; the minikube overlay pins the tag to `1.0` and sets `imagePullPolicy: Never`. The measured payoff of native for review-service:

| Build | Startup time | Resident memory |
|---|---|---|
| JVM | 1.492 s | ~316 MB |
| Native | 0.048 to 0.049 s | ~73 MB |

That is about thirty-one times faster startup and a quarter of the memory; in a cluster the memory number matters more, because the scheduler bin-packs on requests and limits. Switching needs only `Dockerfile.native` in the build command and a `-Pnative` rebuild; both Dockerfiles produce the same image name and port. JVM stays the default because native builds are slower and fail more sharply, as in the `@RegisterForReflection` gap on six services' `ApiError` that appears only under native.

Building into minikube is a `docker build` on the host's Docker Engine followed by `minikube -p mea image load mea/<svc>:1.0`, which copies the image into the node's containerd store (the profile runs `--driver=docker --container-runtime=containerd`, so there is no shared store to build into). `deploy/k8s/scripts/build-images.sh` does both for all eight. `imagePullPolicy: Never` makes a missing load fail loudly as `ErrImageNeverPull` instead of a pull from `docker.io/mea/...`. Each build context is the service's own `examples/<svc>` directory, because the eight Maven reactors share no parent POM.

## Configuration as data: ConfigMap and Secret

None of the eight `application.properties` files was written for a cluster. Some properties are native placeholders, such as `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` and `${ORDER_SERVICE_BASE_URL:...}`: set the env var and the placeholder resolves to it. Others are hardcoded, such as `strangler.review.base-url=http://localhost:8081` and `quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/...`. A hardcoded property has no placeholder to bind to.

For those, `deploy/k8s/base/app-config.yaml` relies on an **ordinal override**, not interpolation. MicroProfile Config resolves a key from the registered `ConfigSource` with the highest ordinal that defines it. `PropertiesConfigSource`, which reads `application.properties` including `%prod` keys, sits at 250. `EnvConfigSource`, which reads environment variables after mapping dots and dashes to underscores and upper-casing, sits at 300. Set `QUARKUS_DATASOURCE_JDBC_URL` and it wins over `quarkus.datasource.jdbc.url` in every profile with no code change. The ConfigMap's header comment marks each key as an ordinal override or a native placeholder.

The property file stays unaware of the environment. The `%prod.quarkus.datasource.jdbc.url` line in `examples/07-order-service` still reads `jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service` and would resolve to it if the env var were unset. The same holds for strangler-proxy's six `strangler.<ctx>.base-url` properties and order-service's `quarkus.grpc.clients.inventory.host` and `.port`.

Every Deployment pulls the whole ConfigMap through `envFrom`. The six database-owning services add an `env:` block that maps a per-service ConfigMap key onto the shared variable name `QUARKUS_DATASOURCE_JDBC_URL`, because six services cannot read one key of that name from a shared ConfigMap without colliding. Credentials come from the `mea-postgres-app` Secret through `secretKeyRef`:

{% include codetabs.html langs="order-service: local process (application.properties, %prod profile)|order-service: k8s Deployment (envFrom + ordinal-override env block)" %}

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

`ORDER_SERVICE_JDBC_URL` is `jdbc:postgresql://postgres.mea.svc.cluster.local:5432/monolith?currentSchema=order_service`: the same database and `order_service` schema, with only the host swapped for the in-cluster DNS name of the `postgres` headless Service. The schema-per-service topology is unchanged; one Postgres server still hosts all six schemas.

The `mea-postgres-app` Secret is a plaintext `stringData` Secret checked into git that republishes the `monolith`/`monolith_dev_only` demo credential every `application.properties` already hardcodes. Its header comment marks it dev-only. A real deployment replaces it with a SealedSecret or an ExternalSecret synced from a vault.

{% include excalidraw.html file="k8s-config-probe-wiring" alt="A wiring diagram centered on one Deployment pod template (notification-service) showing both the configuration path and the probe path. Configuration path: the mea-app-config ConfigMap feeds the pod two ways, a blanket envFrom block and one explicit QUARKUS_DATASOURCE_JDBC_URL env entry sourced via configMapKeyRef from the per-service NOTIFICATION_SERVICE_JDBC_URL key, labeled 'ordinal override: env ConfigSource (300) beats application.properties PropertiesConfigSource (250)'; the mea-postgres-app Secret feeds QUARKUS_DATASOURCE_USERNAME/PASSWORD via secretKeyRef. Probe path, shown as two contrasting pod templates side by side: notification-service (quarkus-smallrye-health on the classpath) wired to three httpGet probes against /q/health/started, /q/health/ready, /q/health/live; review-service (no quarkus-smallrye-health) wired to three tcpSocket probes against the same HTTP port, with a note that tcpSocket only proves the listener is up, not that routing or the database connection works." caption="Figure 29.2: Configuration reaching a pod through ordinal-override env vars, and the probe split between the six health-enabled services and the two on tcpSocket" %}

## Liveness and readiness

Six of the eight services carry `quarkus-smallrye-health` on the classpath (notification, inventory, payment, shipping, order and the graphql-gateway); review-service and the strangler proxy do not. That split decides the probe shape mechanically.

The six get `httpGet` probes: `startupProbe` on `/q/health/started`, `readinessProbe` on `/q/health/ready`, `livenessProbe` on `/q/health/live`. For the five on the Kafka side the readiness endpoint also rolls up SmallRye Reactive Messaging's broker-connectivity check. The other two get `tcpSocket` probes on the same HTTP port, because `/q/health/*` would 404 there. 

{% include codetabs.html langs="notification-service: httpGet against real MicroProfile Health|review-service: tcpSocket, no health extension to ask" %}

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

Timing is identical: a 30-attempt, 5-second startup window (150 seconds before a restart), a 10-second readiness check that gates traffic and a 10-second liveness check that gates restarts. What differs is the question each probe can answer. `tcpSocket` proves the listener accepts connections. It says nothing about whether `StranglerProxyRoute`'s six routes dispatch or whether review-service reaches its slice of the shared `public` schema. A real health endpoint proves more, and for Kafka consumers a broken broker connection fails `/q/health/ready`, pulling the pod from the Service's endpoints before a request reaches a consumer that cannot process it. Adding `quarkus-smallrye-health` to the `pom.xml` of `examples/01-strangler-proxy` and `examples/02-review-service` turns both probe blocks into `httpGet` with no other change, as both manifests' comments note.

## Resource requests and limits

Every container sets both `requests` and `limits`, in two application tiers:

| Tier | Services | Requests | Limits |
|---|---|---|---|
| Lighter | strangler-proxy, review-service, graphql-gateway | 100m CPU / 256Mi | 500m / 512Mi |
| Heavier | notification, inventory, payment, shipping, order | 150m / 320Mi | 750m / 640Mi |
| Postgres | StatefulSet | 250m / 512Mi | higher |
| Kafka | StatefulSet | 250m / 768Mi | higher |

The heavier tier runs a Kafka consumer loop, a Hibernate ORM/Panache layer against its own schema and, for four of the five, an `@Scheduled` outbox relay poll, on top of the JAX-RS and Jackson stack every service shares.

Setting both numbers makes scheduling and out-of-memory behavior predictable. Requests without limits let a pod burst into whatever memory its node has free, with no ceiling and no QoS class above `Burstable`, and one noisy service can starve its seven siblings. In JVM images `run-java.sh` reads `CONTAINER_MAX_MEMORY` and sizes the heap through `JAVA_MAX_MEM_RATIO`, so `limits.memory` also sets the JVM's heap, not only an eviction threshold.

## Rolling updates and graceful consumer shutdown

All eight Deployments set the same strategy:

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

`maxUnavailable: 0` means a rollout never drops below the current replica count before a new pod is Ready. At `replicas: 1` that forces the new pod to pass readiness before the old one is torn down, and `maxSurge: 1` allows the one extra pod that makes it possible. A client mid-request finishes against the old pod. That is enough for synchronous HTTP.

It is not enough for the five Kafka-consuming services. A consumer-group member that vanishes without telling the broker, through a bare SIGKILL or a SIGTERM it never reacts to, makes the broker wait out the full `session.timeout.ms` before it rebalances. Every partition that member owned sits unconsumed for that time, on every rolling update. All five manifests apply the same two settings, which work only together:

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

`terminationGracePeriodSeconds: 45` stretches the window between SIGTERM and SIGKILL well past the default 30 seconds. The `preStop` 10-second sleep runs before SIGTERM is delivered, giving an in-flight request or poll loop time to finish its unit of work instead of being interrupted as traffic withdrawal and the termination signal arrive together. Between the two, the consumer has room to send a clean `LeaveGroupRequest` before the container is killed. strangler-proxy, review-service and graphql-gateway are not Kafka consumers and carry neither setting.

## The edge router as ingress

The minikube overlay exposes exactly one Service off-cluster: `strangler-proxy`, patched from `ClusterIP` to `NodePort`. The other nine (six bounded-context services, the gateway, and the two headless infrastructure Services) stay `ClusterIP`. That mirrors the system's design: the strangler fig is the permanent edge router, and every client request should enter through `StranglerProxyRoute`'s URI-prefix dispatch, never by dialing review-service or order-service directly. Exposing one Service enforces that rule at the Kubernetes layer.

There is no `Ingress` object, controller or hostname routing. `NodePort` binds a fixed `nodePort: 30888` on every node, and `deploy/k8s/scripts/setup-profile.sh` publishes it on the host's loopback when it creates the profile (`minikube start --ports=127.0.0.1:30888:30888`). `http://127.0.0.1:30888` then reaches the edge router with no `port-forward`, tunnel or `minikube service`. Host ports are fixed at profile creation, so another NodePort means recreating the profile. That is the plain minimum for single-node minikube, not a production ingress strategy; an `Ingress` with a controller, or an Istio gateway, belongs in the commented `../../istio` seam layered onto the same Service.

## Infrastructure in-cluster

`infra/postgres-statefulset.yaml` and `infra/kafka-statefulset.yaml` bring compose's two dependencies into the cluster as StatefulSets, which give a workload a stable network identity (a headless Service, `clusterIP: None`) and its own PVC through `volumeClaimTemplates`.

Postgres mirrors compose's `postgres` service: the `18.6-alpine` image, `POSTGRES_DB=monolith` (now sourced from the `mea-postgres-app` Secret, so server and clients share one credential source) and the `wal_level=logical`, `max_replication_slots=4`, `max_wal_senders=4` flags that Debezium CDC needs. A `postgres-initdb` ConfigMap mounted at `/docker-entrypoint-initdb.d` runs an idempotent `CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION monolith` for each owned schema (`notification`, `inventory`, `payment`, `shipping`, `order_service`). Each service's Flyway migration creates its schema on first run regardless. Kafka is a single-broker KRaft deployment on the `apache/kafka` `4.3.1` image with the compose topic defaults (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`, three partitions, replication factor 1), and its advertised listener is `kafka.mea.svc.cluster.local` instead of compose's `localhost`.

Not carried over, as both manifests and `deploy/k8s/README.md` state: Kafka Connect and the Debezium connector, the Apicurio registry, and the LGTM observability stack. `wal_level=logical` stays on so a later Connect deployment can tail the write-ahead log without a Postgres restart. In the meantime inventory-service's `inventory-service-cdc` consumer group sits idle, harmlessly, on a topic nothing produces to. None of the three is needed for the eight services and two StatefulSets.

## Verifying without a cluster

`kubectl kustomize` is client-side templating: it renders the tree to final YAML with no API server. `kubectl apply --dry-run=client` validates each rendered object against the client's cached API discovery, so it needs some reachable context, even stale cached discovery, or it fails at discovery with connection refused instead of a manifest error.

```bash
# 1. Pure client-side rendering, no cluster needed:
kubectl kustomize deploy/k8s/base
kubectl kustomize deploy/k8s/overlays/minikube

# 2. Client-side admission validation of every rendered object:
kubectl kustomize deploy/k8s/overlays/minikube | kubectl apply --dry-run=client -f -
```

Both layers render without error, and before the mesh and observability layers were composed in, the overlay yielded 24 well-formed objects (the same 24 that `kubectl kustomize deploy/k8s/base` renders today): 1 Namespace, 2 ConfigMaps, 1 Secret, 10 Services, 8 Deployments, 2 StatefulSets. The count is the cheapest signal that no resource is missing or duplicated: `postgres-initdb` plus `mea-app-config`; eight application Services plus two headless; eight Deployments; two StatefulSets.

This proves the YAML is well formed. It does not prove that `kubectl apply -k deploy/k8s/overlays/minikube` schedules ten pods, that the images exist in minikube's containerd store (nothing here builds or loads one), that any probe passes against a live container, that `maxUnavailable: 0` behaves as described under a real rollout, or that a request through the `strangler-proxy` NodePort reaches a bounded-context service and returns.

> **Decision**
>
> Use kustomize base plus overlay while there is one target environment; move to Helm only when one set of manifests must parameterize across environments. Override hardcoded properties with environment variables, not by editing the property files. Give a service `httpGet` probes only if it has a health endpoint. Give every Kafka consumer both a `preStop` sleep and a longer grace period, and expose only the edge router.

> **🤖 Explore with your agent**
>
> "In deploy/k8s/base, find every Deployment that runs a Kafka consumer and confirm each has the preStop sleep and terminationGracePeriodSeconds 45, then add the missing health-probe upgrade for examples/02-review-service by adding quarkus-smallrye-health to its pom.xml and switching its three tcpSocket probes in review-service.yaml to httpGet on /q/health/started, /q/health/ready and /q/health/live."
>
> Check: `kubectl kustomize deploy/k8s/base | grep -c '^kind:'` still prints 24, the overlay still passes `kubectl apply --dry-run=client`, and review-service.yaml contains no tcpSocket.

## Build, run, observe

Render and validate without a cluster using the two commands above. To run it, create the minikube profile with the published NodePort, then build and load the images:

```bash
bash deploy/k8s/scripts/setup-profile.sh
bash deploy/k8s/scripts/build-images.sh
```

`deploy/k8s/scripts/deploy.sh` applies the overlay and waits for rollouts. The overlay in the current tree also composes the mesh and observability layers, so `install-istio.sh` must run first. The edge router answers at `http://127.0.0.1:30888`; every other Service is reachable only inside the cluster.

- Skip `build-images.sh`: pods fail with `ErrImageNeverPull` because `imagePullPolicy: Never` forbids a registry pull.
- Remove the `env` override for `QUARKUS_DATASOURCE_JDBC_URL` from a Deployment: the service falls back to the `localhost` URL in its properties file and cannot reach Postgres.
- Drop the `preStop` and extended grace period from a Kafka consumer and roll it out: its partitions sit idle until `session.timeout.ms` expires.

## For deeper coverage

- [Kubernetes documentation: Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/): the patch-a-base model the `base`/`overlays/minikube` tree is built on.
- [Kubernetes documentation: Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/): the `httpGet`/`tcpSocket` probe split mapped here to the six-of-eight health-dependency split.
- [Quarkus: Container Image guide](https://quarkus.io/guides/container-image): the `Dockerfile.jvm`/`Dockerfile.native` pair every one of the eight services ships.
- Michael Nygard, *Release It!* (2nd ed., Pragmatic Bookshelf, 2018): the operational discipline behind graceful shutdown and bounded rollout risk the `preStop` and `terminationGracePeriodSeconds` pairing and the `maxUnavailable: 0` strategy both apply; see the [Reading List](/bibliography/).
- Martin Fowler, [*StranglerFigApplication*](https://martinfowler.com/bliki/StranglerFigApplication.html): the single-edge-router discipline the exposed Service enforces at the Kubernetes layer by exposing exactly one `NodePort` Service.
