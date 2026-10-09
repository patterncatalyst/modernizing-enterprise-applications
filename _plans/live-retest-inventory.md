---
title: "Live retest inventory — Docker Engine, minikube, OpenShift Local"
description: "Every runnable script, demo and example in the repository, grouped by path, with the exact command, prerequisites, rough duration and pass condition. Written for the DRQ-077 retest (2026-10-09)."
status: "done — sections A–D re-run live 2026-10-09 (see Results)"
---

# Live retest inventory (DRQ-077, 2026-10-09)

Branch `feat/docker-newest-stable` moved every path to Docker Engine and the
newest stable pins (see `_plans/decisions.md`, DRQ-077). Offline checks passed on
the branch (`mvn verify` in all ten modules, `docker compose config`, collector
`validate` inside `otel-lgtm:0.36.0`, single-container smoke runs of
otel-lgtm/Postgres/Apicurio, `kubectl kustomize`, `istioctl manifest generate`,
`helm lint`/`helm template`, `bash -n`, Jekyll build). Everything below still needs
a live run.

All commands run from the repository root on a Fedora or RHEL host. **One local
cluster at a time**: stop minikube before CRC and the reverse; stop the compose
stack before a cluster run if memory is tight (the compose stack needs about
6 GB, the minikube profile 12 GB, CRC 20 GB).

Headless legend: **H** runs unattended; **B** needs a browser for full value
(optional); **I** waits on interactive input; **C** needs external credentials.

## Recommended order

1. Maven-only (no stack): section A. Confirms the build before anything else.
2. Compose path: section B, in the listed order (stack up, then the demos that
   use the edge-router topology on `main`). `scripts/stack-down.sh -v` at the end.
3. minikube path: section C (`deploy/k8s/scripts/teardown.sh` at the end).
4. OpenShift Local path: section D, only when CRC is free for this project
   (it is dedicated to another project's run at the time of writing).
   `openshift/teardown.sh` at the end.
5. Push the branch: section E (CI) runs on push.

## A. Maven-only (Docker Engine for Testcontainers/Dev Services; no compose stack, no cluster)

| # | What | Command | Prereqs | Duration | Pass condition | H |
|---|---|---|---|---|---|---|
| A1 | Monolith shell (Spring Boot 4.1.1) | `mvn -B -f examples/00-monolith/pom.xml verify` | JDK 25, Maven 3.9, Docker Engine | 1 min | exit 0; `Tests run: 6, Failures: 0, Errors: 0` (SixContextsSmokeTest, postgres:18.6-alpine); log shows `Successfully applied 4 migrations` | H |
| A2 | Strangler proxy | `mvn -B -f examples/01-strangler-proxy/pom.xml verify` | JDK 25, Maven | 1 min | exit 0; `Tests run: 8` | H |
| A3 | Review service | `mvn -B -f examples/02-review-service/pom.xml verify` | as A2 | 1 min | exit 0; `Tests run: 7` | H |
| A4 | Notification service | `mvn -B -f examples/03-notification-service/pom.xml verify` | + Docker Engine (Dev Services Postgres 18.6-alpine) | 2 min | exit 0; `Tests run: 10` | H |
| A5 | Inventory service | `mvn -B -f examples/04-inventory-service/pom.xml verify` | as A4 | 2 min | exit 0; `Tests run: 16` | H |
| A6 | Payment service | `mvn -B -f examples/05-payment-service/pom.xml verify` | as A4 | 2 min | exit 0; `Tests run: 18` | H |
| A7 | Shipping service | `mvn -B -f examples/06-shipping-service/pom.xml verify` | as A4 | 2 min | exit 0; `Tests run: 16` | H |
| A8 | Order service | `mvn -B -f examples/07-order-service/pom.xml verify` | as A4 | 3 min | exit 0; `Tests run: 40` | H |
| A9 | GraphQL gateway | `mvn -B -f examples/08-graphql-gateway/pom.xml verify` | JDK 25, Maven | 1 min | exit 0; `Tests run: 4` | H |
| A10 | Schema-registry demo | `mvn -B -f examples/09-schema-registry-demo/pom.xml verify` | + Docker Engine (Dev Services kafka-native 4.3.1, Apicurio 3.3.3) | 2 min | exit 0; `Tests run: 2`; no compose broker needed | H |
| A11 | Supply-chain gate | `examples/10-supply-chain/demo.sh` | `syft` and `grype` on PATH (not installed on the authoring host); builds the monolith jar and order-service if missing; network for grype's DB | 5 min | exit 1 with `GATE: FAIL` and the summary line `monolith [before] ... FAIL`, `order-service [after ] ... PASS`. An ad-hoc grype v0.120.1 scan of the Boot 4.1.1 monolith jar still found Critical=3 High=10, so the "before fails" narrative of ch.31 holds; refresh `evidence/` from this run | H |
| A12 | Native build (one service) | `mvn -B -f examples/08-graphql-gateway/pom.xml package -Dnative -Dquarkus.native.container-build=true && docker build -f examples/08-graphql-gateway/src/main/docker/Dockerfile.native-micro -t mea/graphql-gateway:native examples/08-graphql-gateway` | Docker Engine (Mandrel `ubi10-quarkus-mandrel-builder-image:jdk-25.0.4.1`), 8 GB free RAM | 5-8 min | both exit 0; `docker run --rm -p 127.0.0.1:18090:8090 mea/graphql-gateway:native` then `curl -s 127.0.0.1:18090/q/health/live` returns `"status": "UP"` | H |
| A13 | Site build | `docker run --rm -v "$PWD":/src:ro,Z docker.io/library/ruby:3.3 bash -c 'cp -r /src /work && cd /work && bundle install --quiet && bundle exec jekyll build'` (or `bundle exec jekyll build` with ruby-devel installed) | Docker Engine | 2 min | exit 0, no `Liquid Warning` lines | H |

JVM image smoke (already done once on the branch for the gateway):
`docker build -f examples/08-graphql-gateway/src/main/docker/Dockerfile.jvm -t mea/graphql-gateway:1.0 examples/08-graphql-gateway`
after A9; `docker run` + `/q/health/live` returns UP on `openjdk 25.0.4.1`.

## B. Compose path (Docker Engine + `docker compose`)

Prereqs for the whole section: Docker Engine (context `default`), the
`docker compose` plugin, JDK 25, Maven, Node 22 + `newman` (`npm i -g newman`),
`jq`, `curl`; host ports 3000, 4317, 4318, 19090, 3100, 3200, 5432, 9092, 9094,
8086, 8095 free, plus the service ports 8081-8091, 8096, 8888, 9004. A previous
Postgres 16 volume does not upgrade in place: start from `scripts/stack-down.sh -v`.

| # | What | Command | Duration | Pass condition | H |
|---|---|---|---|---|---|
| B1 | Stack up | `cp -n .env.example .env; scripts/stack-up.sh` | 2-3 min (first pull longer) | `docker compose ps` shows `mea-lgtm`, `mea-postgres`, `mea-kafka`, `mea-connect`, `mea-apicurio` all `healthy`; `curl -s localhost:3000/api/health` returns `"database": "ok"`; `curl -s localhost:19090/-/ready` returns 200 | H (B for Grafana UI) |
| B2 | Grafana datasources | `curl -s localhost:3000/api/datasources \| jq -r '.[].uid'` | seconds | prints `tempo`, `loki`, `prometheus` (and no `pyroscope`: ours replaced the image's file) | H |
| B3 | Debezium connector register | `scripts/register-debezium.sh` | 1 min | exit 0; connector `RUNNING` (Debezium 3.7.0.Final against Postgres 18.6, `pgoutput`) | H |
| B4 | CDC flowing | `scripts/verify-cdc.sh` | 1 min | exit 0; `OK: observed a CDC event for sku=... on ...` | H |
| B5 | Debezium retire | `scripts/retire-debezium.sh` then `scripts/retire-debezium.sh --status` | 1 min | exit 0; connector gone (404), replication slot and publication dropped | H |
| B6 | Final topology (capstone) | `demos/demo-final-topology.sh` | 10-15 min (builds and starts 7 services + proxy) | exit 0; last line `RESULT: the finished topology stands on its own — ...`; contract suite green through `:8888`, GraphQL aggregation OK, `:8080` closed, unknown `/api` path 404 | H |
| B7 | Inventory contract | `demos/demo-inventory-cutover.sh` | 6-8 min | exit 0; `RESULT: the inventory context serves its contract through the edge router, and the route is non-vacuous ...` | H |
| B8 | Notification contract | `demos/demo-notification-cutover.sh` | 10-12 min | exit 0; `RESULT: the notification context serves its contract ...` | H |
| B9 | Schema registry | `demos/demo-schema-registry.sh` | 3-4 min | exit 0; v2 `200`, v3 `400` + `RuleViolationException` (Apicurio 3.3.3; was 409 on 3.1.7), `RESULT: Avro + Apicurio round-trip proven ...` | H |
| B10 | Equivalence suite through the edge | with B6's topology up (or the services started per `tooling/newman/README.md`): `demos/demo-equivalence.sh http://localhost:8888` | 1 min | exit 0; newman `failed 0` | H |
| B11 | Telemetry reaches LGTM | after B6/B8 traffic: `curl -s 'localhost:3200/api/search?limit=5' \| jq '.traces \| length'` and `curl -s 'localhost:19090/api/v1/query?query=up' \| jq '.data.result \| length'` | seconds | both > 0 (services that export OTLP: inventory, order, gateway) | H (B to browse) |
| B12 | Stack down | `scripts/stack-down.sh -v` | 30 s | exit 0; `docker compose ps` empty | H |

Not runnable on `main` as written (historical, run from their stage tags):

| Script | Why | Note |
|---|---|---|
| `demos/demo-cutover.sh` | needs the monolith serving five contexts and the flagged proxy (r02 state); `main`'s monolith is a zero-context shell | `git checkout stage/01-review-extracted`; the stage tags carry the podman toolchain and the old pins — porting them is out of scope |
| `demos/demo-payment-cutover.sh` | needs the pre-decommission flagged proxy | `stage/04-payment-extracted`; same caveat |
| `demos/demo-shipping-cutover.sh` | as above | `stage/05-shipping-extracted`; same caveat |
| `demos/demo-order-cutover.sh` | as above; also edits and reverts source files in place | `stage/06-order-extracted`; same caveat |

## C. minikube path (Docker Engine, docker driver + containerd)

Prereqs: Docker Engine, minikube >= 1.39.0, kubectl 1.36.x, istioctl **1.31.1**
on PATH, JDK 25 + Maven; `fs.inotify.max_user_instances >= 256`; host ports
30888 and 30300 free; no other minikube profile and no CRC running. Stop the
compose stack first if RAM is tight.

| # | What | Command | Duration | Pass condition | H |
|---|---|---|---|---|---|
| C1 | Profile | `deploy/k8s/scripts/setup-profile.sh` | 3-5 min | exit 0; `ok: 127.0.0.1:30888`, `ok: 127.0.0.1:30300`; `kubectl --context mea get nodes` shows `v1.36.5` Ready; `docker port mea` lists both on 127.0.0.1 | H |
| C2 | Istio | `deploy/k8s/scripts/install-istio.sh` | 2-3 min | exit 0; `ok: Istio 1.31.1 ready`; `istioctl --context mea version` shows 1.31.1 control plane | H |
| C3 | Images | `deploy/k8s/scripts/build-images.sh` | 8-12 min | exit 0; eight `ok: mea/<svc>:1.0 loaded`; `minikube -p mea image ls \| grep mea/` lists 8 | H |
| C4 | Deploy | `deploy/k8s/scripts/deploy.sh` | 5-8 min | exit 0; postgres, kafka, lgtm and the eight Deployments rolled out; app pods `2/2` (istio-proxy native sidecar), postgres/kafka/lgtm `1/1` | H |
| C5 | Edge router | `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:30888/api/inventory` | seconds | `200` and three seeded SKUs in the body | H |
| C6 | Contract suite in-cluster | `demos/demo-equivalence.sh http://127.0.0.1:30888` | 1-2 min | exit 0; newman `failed 0` (saga-gated folders stay skipped unless their gate variables are set, as in ch.30) | H |
| C7 | mTLS | `istioctl --context mea x describe pod -n mea $(kubectl --context mea -n mea get pod -l app.kubernetes.io/name=order-service -o name \| head -1 \| cut -d/ -f2)` | seconds | reports STRICT mTLS (PeerAuthentication `default`), matching `deploy/k8s/observability/evidence/ch30-istioctl-mtls.txt` | H |
| C8 | Grafana + traces | `curl -s http://127.0.0.1:30300/api/health`; then a GraphQL request to the gateway from inside the mesh (ch.30 recipe) and Tempo search via Grafana | 2 min | health `ok`; a trace with gateway, order-service and inventory-service spans plus Envoy spans (compare `ch30-hero-trace.json`) | B for the trace view; health check H |
| C9 | Mesh metrics | `kubectl --context mea -n mea exec deploy/lgtm -- curl -s 'localhost:9090/api/v1/query?query=istio_requests_total' \| jq '.data.result \| length'` | seconds | > 0 | H |
| C10 | Teardown | `deploy/k8s/scripts/teardown.sh` (`--delete` to remove the profile) | 30 s | profile `Stopped` | H |

Refresh `deploy/k8s/observability/evidence/*` and the ch.30 verification footer
from C4-C9.

## D. OpenShift Local (CRC) path — appendix

Prereqs: CRC 2.64.0 (OpenShift 4.22.14) set up with the pull secret (**C**: Red
Hat account; one-time `crc setup` asks for `sudo`, **I**), sized
`crc config set cpus 8; crc config set memory 20480`; `oc` via
`eval "$(crc oc-env)"`, `helm`, `skopeo`, JDK 25 + Maven. No container engine is
used on this path. Stop minikube first. CRC must be free (it is reserved for
another project's run at the time of writing; inventory `oc get sub,csv -A` and
non-system namespaces before starting, and remove nothing foreign without the
user's approval).

| # | What | Command | Duration | Pass condition | H |
|---|---|---|---|---|---|
| D1 | Start | `crc start` | 10-15 min | `oc --context crc-admin whoami --show-server` prints `https://api.crc.testing:6443` | H after setup |
| D2 | Project | `oc --context crc-admin new-project mea` | seconds | exit 0 | H |
| D3 | Infra images | `openshift/mirror-infra-images.sh` | 2-4 min | exit 0; `ok: mea/postgres:18.6-alpine`, `ok: mea/kafka:4.3.1`; `oc -n mea get is` lists both. **First run of this script**: verify the skopeo TLS trust (`default-ingress-cert` CA) and the builder-token authfile work against the registry route | H |
| D4 | App images | `openshift/build-images.sh` | 10-15 min | exit 0; eight in-cluster Docker-strategy builds `Complete`; `oc -n mea get istag` lists `<svc>:1.0` x8. **First run**: confirm the build pod can pull `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24-15` | H |
| D5 | Deploy | `openshift/deploy.sh` | 5-10 min | exit 0; all ten rollouts complete; `postgres-0`/`kafka-0` under SCC `nonroot-v2` (UIDs 70/1000), apps under `restricted-v2` | H |
| D6 | Routes | `oc --context crc-admin get configmap default-ingress-cert -n openshift-config-managed -o jsonpath='{.data.ca-bundle\.crt}' > /tmp/ingress-ca.crt; curl -s --cacert /tmp/ingress-ca.crt https://graphql-gateway-mea.apps-crc.testing/q/health/ready; curl -s --cacert /tmp/ingress-ca.crt https://strangler-proxy-mea.apps-crc.testing/api/inventory` | seconds | `"status": "UP"`; seeded inventory JSON (200). Known gap unchanged: `/api/reviews` 500 (no monolith `public.reviews` on a fresh cluster) | H |
| D7 | Evidence | capture `oc get pods,route -n mea`, the SCC/UID listing and D6 into `openshift/evidence/verification.txt` (scrub secrets) | 2 min | file refreshed, no tokens or passwords in it | H |
| D8 | Teardown | `openshift/teardown.sh` | 3-5 min | release and project gone, registry default route hidden, `crc stop` done | H |

`openshift/gitops/application.yaml` stays illustrative (no GitOps operator is
installed by the appendix); not part of the retest.

## E. CI (GitHub Actions, on push)

| Workflow | Trigger | Pass condition |
|---|---|---|
| `.github/workflows/code-ci.yml` | push touching `examples/01-09/**`, `tooling/newman/**` | all equivalence gates green on `postgres:18.6-alpine` + `apache/kafka:4.3.1` service containers; `schema-registry-gate` green with Dev Services |
| `.github/workflows/supply-chain.yml` | push touching `examples/07-order-service/**` | order-service SBOM scan passes `severity-cutoff: high` |
| `.github/workflows/pages.yml` | any push | Jekyll build green |

The workflows were edited but not run (no push from this branch yet).

## Results — 2026-10-09 (Fedora 44, Docker Engine 29.8.2)

- **A (Maven):** `mvn verify` green in all ten modules, 127 tests.
- **B (compose), fresh volume:** B1–B12 all pass. Datasources loki/prometheus/tempo; Debezium register → CDC → retire; final topology with `--keep-running`, equivalence suite through :8888, then `--stop`; inventory, notification and schema-registry demos; Tempo held 5 traces and Prometheus answered. Fixes found: the monolith's `public` schema is applied on a fresh volume (`infra/db/init/10-monolith-public-schema.sh`), Debezium Connect heap bounded, OTLP traces endpoint configurable with a compose default, pinned `npx newman@6.2.3` fallback, stricter final-topology assertions.
- **C (minikube), fresh `mea` profile:** C1–C10 all pass. 8 images loaded; edge 200 with three SKUs; equivalence 49 requests / 140 assertions / 0 failed; sidecars injected; Grafana healthy; 9 `istio_requests_total` series. Fixes found: workload-scoped PERMISSIVE PeerAuthentication for the strangler-proxy (NodePort plaintext), `deploy.sh` bootstraps the monolith schema and seeds `order_service.customers` id=1.
- **D (CRC 2.64.0 / OpenShift 4.22.14):** D1–D8 all pass. skopeo mirror and all eight in-cluster builds succeeded on the first run; apps under `restricted-v2`, Postgres/Kafka under `nonroot-v2` (UIDs 70/1000); gateway `UP`, inventory 200, `/api/reviews` 200 after `openshift/deploy.sh` gained the same schema bootstrap (it returned 500 before). Evidence refreshed in `openshift/evidence/verification.txt`; teardown left CRC stopped.
- **Not run:** `examples/10-supply-chain/demo.sh` (syft/grype not installed), and the four `stage/*`-only cutover demos (out of scope, see Flags).
