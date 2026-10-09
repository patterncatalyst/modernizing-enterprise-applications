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
      kustomization.yaml        # ../../base + istio + observability; image tags,
                                # pullPolicy Never, strangler-proxy -> NodePort 30888
  scripts/                      # setup-profile, install-istio, build-images, deploy, teardown
```

## Cluster, images, deploy (Docker Engine + minikube)

The minikube path runs on **Docker Engine** (docker-ce, docker context
`default`) on a Fedora or RHEL host, with minikube's `docker` driver and the
`containerd` runtime inside the node (DRQ-077, superseding the original podman
driver). Docker Desktop is never required. Every script names its target
explicitly (`minikube -p mea`, `kubectl --context mea`) and never touches
kubectl's current-context or `minikube config`.

```bash
# 1. Profile `mea`: Kubernetes v1.36.5, docker driver, containerd, NodePorts
#    30888 (strangler-proxy) and 30300 (Grafana) published on 127.0.0.1.
deploy/k8s/scripts/setup-profile.sh

# 2. Istio 1.31.1 (istioctl 1.31.1 on PATH)
deploy/k8s/scripts/install-istio.sh

# 3. mvn package -> docker build -f src/main/docker/Dockerfile.jvm
#    -> minikube -p mea image load mea/<svc>:1.0, for all eight services
deploy/k8s/scripts/build-images.sh

# 4. kubectl --context mea apply -k deploy/k8s/overlays/minikube, then wait
deploy/k8s/scripts/deploy.sh

# 5. Reach it directly on the published NodePorts (no port-forward, no tunnel)
curl -s http://127.0.0.1:30888/api/inventory
xdg-open http://127.0.0.1:30300          # Grafana

# 6. Stop when idle (one local cluster at a time)
deploy/k8s/scripts/teardown.sh            # --delete to remove the profile
```

Images are registry-free (`mea/<svc>:1.0`). The minikube overlay sets
`imagePullPolicy: Never` on all eight Deployments, so the kubelet only ever
uses the image `minikube image load` put in the node; a missing load shows as
`ErrImageNeverPull` rather than a failed pull from `docker.io/mea/...`. After
rebuilding, `build-images.sh <svc>` reloads and restarts that Deployment.
`Dockerfile.native` is also available per service (`mvn package -Dnative
-Dquarkus.native.container-build=true`, Mandrel builder on Docker); swap it for
`Dockerfile.jvm` in `build-images.sh` to run native.

Host ports are fixed when the profile is created. Adding a NodePort means
adding it to `deploy/k8s/scripts/lib.sh` and recreating the profile
(`setup-profile.sh --replace`).

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
kubectl kustomize deploy/k8s/overlays/minikube | kubectl --context mea apply --dry-run=client -f -
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
kubectl --context mea apply -k deploy/k8s/overlays/minikube   # or deploy/k8s/scripts/deploy.sh
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
