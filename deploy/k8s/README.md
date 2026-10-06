# deploy/k8s — kustomize manifests (ch.29)

Kubernetes manifests for the full 8-service running topology
(`examples/01-strangler-proxy` through `examples/08-graphql-gateway`;
`examples/09-schema-registry-demo` is excluded — it is not part of the
running topology). Adapted from the house layout at
`~/Dev/datamesh-reference-arch-quarkus/k8s/` (`base/` per-service
Deployment+Service, a shared ConfigMap, `kustomization.yaml`,
`overlays/minikube`), stripped of that repo's `datamesh` namespace/codenames
and its 4-service scope.

This tree is additive only — it does not change any Java code,
`application.properties`, `pom.xml`, the Newman suite, CI, or any existing
demo. It has been verified **headlessly** (no live cluster): the gate is
`kubectl kustomize ... | kubectl apply --dry-run=client -f -`, not a real
`kubectl apply` against a running minikube.

## Layout

```
deploy/k8s/
  base/
    namespace.yaml              # Namespace "mea"
    app-config.yaml             # ConfigMap mea-app-config — shared %prod env contract
    app-secret.yaml             # Secret mea-postgres-app — dev-only DB credential
    strangler-proxy.yaml        # Deployment + Service (edge router, :8888)
    review-service.yaml         # Deployment + Service (:8081, shared `public` schema)
    notification-service.yaml   # Deployment + Service (:8083, `notification` schema)
    inventory-service.yaml      # Deployment + Service (:8084 http + :9004 grpc, `inventory` schema)
    payment-service.yaml        # Deployment + Service (:8085, `payment` schema)
    shipping-service.yaml       # Deployment + Service (:8088, `shipping` schema)
    order-service.yaml          # Deployment + Service (:8087, `order_service` schema)
    graphql-gateway.yaml        # Deployment + Service (:8090 http + :9093 grpc)
    infra/
      postgres-statefulset.yaml # ConfigMap (schema initdb) + headless Service + StatefulSet
      kafka-statefulset.yaml    # headless Service + StatefulSet (single-broker KRaft)
    kustomization.yaml
  overlays/
    minikube/
      kustomization.yaml        # ../../base + image newTags + strangler-proxy -> NodePort
```

## Build + load images into minikube (podman driver)

This repo's toolchain is **podman**, not docker (see `compose.yaml`'s header
and `lgtm-docker-stack`/project convention) — use `minikube podman-env`, not
`minikube docker-env`.

```bash
eval $(minikube podman-env)

# JVM-mode image per service (repeat for all 8 — build context is each
# service's own examples/<svc> directory, not the repo root, since none of
# these services share a reactor module the way datamesh's domain-model does)
podman build -f examples/01-strangler-proxy/src/main/docker/Dockerfile.jvm \
  -t mea/strangler-proxy:latest examples/01-strangler-proxy
podman build -f examples/02-review-service/src/main/docker/Dockerfile.jvm \
  -t mea/review-service:latest examples/02-review-service
podman build -f examples/03-notification-service/src/main/docker/Dockerfile.jvm \
  -t mea/notification-service:latest examples/03-notification-service
podman build -f examples/04-inventory-service/src/main/docker/Dockerfile.jvm \
  -t mea/inventory-service:latest examples/04-inventory-service
podman build -f examples/05-payment-service/src/main/docker/Dockerfile.jvm \
  -t mea/payment-service:latest examples/05-payment-service
podman build -f examples/06-shipping-service/src/main/docker/Dockerfile.jvm \
  -t mea/shipping-service:latest examples/06-shipping-service
podman build -f examples/07-order-service/src/main/docker/Dockerfile.jvm \
  -t mea/order-service:latest examples/07-order-service
podman build -f examples/08-graphql-gateway/src/main/docker/Dockerfile.jvm \
  -t mea/graphql-gateway:latest examples/08-graphql-gateway
```

Each `Dockerfile.jvm` expects `target/quarkus-app/` to already exist (run
`mvn package` in that service's directory first). Alternatively, build
anywhere and sideload with `minikube image load mea/<svc>:latest`, which
avoids the `podman-env` shell-env dance entirely:

```bash
mvn -f examples/04-inventory-service/pom.xml package
podman build -f examples/04-inventory-service/src/main/docker/Dockerfile.jvm \
  -t mea/inventory-service:latest examples/04-inventory-service
minikube image load mea/inventory-service:latest
```

Every image name is registry-free (`mea/<svc>`, no registry host) and every
Deployment sets `imagePullPolicy: IfNotPresent` (`deploy/k8s/base/*.yaml`),
so once the exact `name:tag` exists in minikube's image store the kubelet
never attempts a network pull. `Dockerfile.native` is also available per
service (native-image builds, `mvn package -Pnative`) for the lighter/
faster-startup path; swap `Dockerfile.jvm` for `Dockerfile.native` above if
you build native.

`examples/04-inventory-service` and `examples/05-payment-service` did not
previously have a `src/main/docker/` directory (04) or had an empty one
(05) — both `Dockerfile.jvm`/`Dockerfile.native` pairs were added verbatim
from `examples/03-notification-service/src/main/docker/` (generic Quarkus
UBI templates, byte-identical — confirmed via `diff`/`sha256sum` at authoring
time) so every one of the 8 services has the same two build paths.

## Headless verification (no live cluster)

```bash
# 1. Both layers render with no error (pure client-side templating, no
#    cluster needed):
kubectl kustomize deploy/k8s/base
kubectl kustomize deploy/k8s/overlays/minikube

# 2. Client-side admission validation of every rendered object:
kubectl kustomize deploy/k8s/overlays/minikube | kubectl apply --dry-run=client -f -
```

Step 2 needs a reachable Kubernetes API server for its RESTMapping
discovery step (`kubectl apply`, unlike `kubectl kustomize`, has to resolve
each `kind` to a REST resource before it can validate) — a sandbox with
*zero* reachable apiserver (no current `kubectl config current-context`, no
cached discovery, no local control plane) will fail at that discovery step
with a `dial tcp ...: connection refused` error, not a manifest-validity
error. Any real target — a running `minikube`, `kind`, or a cached
discovery doc from a previous connection — lets step 2 complete and report
the client-side-valid object count (24: 1 Namespace, 2 ConfigMaps, 1 Secret,
10 Services, 8 Deployments, 2 StatefulSets). Do **not** substitute
`--dry-run=server` for this — that performs real server-side admission and
needs a live cluster, which this step is explicitly scoped to avoid.

## Apply (once a real minikube cluster is up)

```bash
kubectl apply -k deploy/k8s/overlays/minikube
```

## Env contract

`deploy/k8s/base/app-config.yaml`'s header comment documents the full
ordinal-override vs. native-placeholder split; see that file for the
authoritative mapping. Summary:

| Env var(s) | Consumed by | Mechanism |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | notification/inventory/payment/shipping/order | native `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` placeholder |
| `QUARKUS_PROFILE` | all 8 | Quarkus's own profile-selection env var |
| `*_SERVICE_JDBC_URL` (per service, via `configMapKeyRef` into `QUARKUS_DATASOURCE_JDBC_URL`) | review/notification/inventory/payment/shipping/order | ordinal-override (env ordinal 300 > application.properties ordinal 250) of each service's hardcoded `quarkus.datasource.jdbc.url` |
| `QUARKUS_DATASOURCE_USERNAME`/`PASSWORD` (via `secretKeyRef` on `mea-postgres-app`) | same six | ordinal-override of each service's hardcoded datasource credentials |
| `STRANGLER_REVIEW_BASE_URL` .. `STRANGLER_ORDER_BASE_URL` | strangler-proxy | ordinal-override of `strangler.<ctx>.base-url` (MicroProfile's env-var name mapping) |
| `ORDER_SERVICE_BASE_URL` | shipping-service | native `${ORDER_SERVICE_BASE_URL:http://localhost:8087}` placeholder |
| `ORDER_SERVICE_URL` / `PAYMENT_SERVICE_URL` / `SHIPPING_SERVICE_URL` / `REVIEW_SERVICE_URL` | graphql-gateway | native REST-client placeholders |
| `INVENTORY_GRPC_HOST`/`PORT` | graphql-gateway | native gRPC-client placeholders |
| `QUARKUS_GRPC_CLIENTS_INVENTORY_HOST`/`PORT` | order-service | ordinal-override of its hardcoded `quarkus.grpc.clients.inventory.host`/`.port` |

Every Deployment pulls the whole ConfigMap via `envFrom`; a service
harmlessly receives keys it has no matching property for.

## Deferred to ch.30 / demo-only

- **Connect/CDC** — `compose.yaml`'s `connect` service (Debezium Postgres
  connector, `infra/debezium/`) has no k8s equivalent here. `postgres`'s
  `wal_level=logical` is still enabled so a future Connect deployment can
  tail the WAL without a Postgres restart; `inventory-service`'s
  `inventory-service-cdc` consumer group just sits idle until that lands.
- **Apicurio Schema Registry** — used only by the excluded
  `examples/09-schema-registry-demo`; no k8s resource here.
- **LGTM observability stack** (Loki/Grafana/Tempo/Mimir + OTel Collector)
  — `compose.yaml`'s `lgtm` service has no k8s equivalent here.
- **Istio / mesh** — `deploy/k8s/overlays/minikube/kustomization.yaml`
  leaves a commented `resources:` seam (`../../istio`, `../../observability`)
  for exactly this, mirroring how
  `~/Dev/datamesh-reference-arch-quarkus/k8s/istio` composes on top of that
  repo's `base` as a sibling overlay rather than editing `base` in place.
- **Health on strangler-proxy / review-service** — neither carries
  `quarkus-smallrye-health` (ch.27's six-of-eight note), so both use
  `tcpSocket` probes instead of `httpGet /q/health/*`; see the comment block
  at the top of each manifest for the upgrade path.
