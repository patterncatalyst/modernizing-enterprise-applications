---
title: "Appendix A: Deploying to OpenShift"
order: 33
part: "Appendices"
description: "The finished eight-workload system on OpenShift: a Helm chart, Routes, Security Context Constraints, and the internal registry — built and verified live on OpenShift Local (CRC)."
duration: 40 minutes
---

Chapter 30 brought the modernized system up on a real cluster — minikube, with
Istio and the LGTM stack — and Chapter 31 wired its CI/CD and supply-chain gates.
Both targeted vanilla Kubernetes. This appendix does the same thing on **OpenShift**,
because "deploy it to Kubernetes" and "deploy it to OpenShift" are not the same
sentence. OpenShift adds opinions — Security Context Constraints that reject pinned
UIDs, Routes instead of hand-rolled ingress, an integrated image registry — and
those opinions are exactly what you have to design for.

Everything below ran on a live cluster. The eight application workloads plus
Postgres and Kafka came up `1/1` in namespace `mea`; an external `curl` through an
OpenShift Route reached the Camel strangler proxy, which routed to inventory-service,
which read its own Flyway-migrated Postgres schema and returned seeded JSON — the
whole stack, end to end. The packaging is a single Helm chart under
[`openshift/helm/mea/`](https://github.com/patterncatalyst/modernizing-enterprise-applications/tree/main/openshift/helm/mea);
this appendix explains what is in it, what changed from `deploy/k8s/`, and how it
was verified.

## Prerequisites

You do not need a paid subscription or a cloud account. **OpenShift Local** (CRC —
"CodeReady Containers") runs a real, single-node OpenShift cluster in a local VM.

1. **Create a free Red Hat Developer account.** Go to
   [developers.redhat.com](https://developers.redhat.com), click *Register* /
   *Log in*, and create an account. It is free and gives you access to OpenShift
   Local and the pull secret.
2. **Download OpenShift Local and its pull secret** from
   [console.redhat.com/openshift/create/local](https://console.redhat.com/openshift/create/local):
   - the **OpenShift Local** archive for your platform (the `crc` binary), and
   - the **pull secret** (a small JSON file). Save it as `~/Downloads/pull-secret.txt`.
3. **Install `crc`.** Extract the archive and put the `crc` binary on your `PATH`
   (e.g. `~/.local/bin/crc`). This appendix used **CRC 2.64.0**, which bundles
   **OpenShift 4.22.14**.
4. **Size the host.** CRC's defaults (4 vCPU / ~10.5 GB RAM) are not enough for
   eight Quarkus services *plus* Postgres and Kafka *plus* OpenShift itself — the
   infra pods will sit `Pending` with `Insufficient memory`. Give it more:

   ```sh
   crc config set memory 20480   # 20 GB
   crc config set cpus 8
   ```

5. **Set it up and start it.**

   ```sh
   crc setup                                               # one-time; configures the VM + networking
   crc start --pull-secret-file ~/Downloads/pull-secret.txt
   ```

   `crc setup` needs root for a couple of steps (it installs a small setuid helper
   and configures the libvirt network), so run it in a terminal where `sudo` can
   prompt you. The first `crc start` provisions the VM and waits for the cluster to
   settle — budget 10–15 minutes. When it finishes it prints the `kubeadmin`
   console URL and credentials.

6. **Get `oc`.** CRC ships a version-matched `oc`; `eval $(crc oc-env)` puts it on
   your `PATH`. (There is no supported `oc` package in Fedora's repositories — use
   CRC's, or the tarball from `mirror.openshift.com`.) Then log in:

   ```sh
   eval $(crc oc-env)
   oc login -u kubeadmin -p <printed-password> https://api.crc.testing:6443
   ```

   You will also need `helm` (v3+; this appendix used v4). Right after `crc start`
   returns, the API server may briefly reset connections while its operators roll
   out — if `oc login` fails with `EOF`, wait a minute and retry.

## What changes when the target is OpenShift

The `deploy/k8s/base/` manifests from Chapter 29 are plain Kubernetes. Three of
their assumptions do not survive contact with OpenShift, and the chart in
`openshift/helm/mea/` is built around fixing exactly those three.

### 1. Security Context Constraints reject pinned UIDs

Every app Deployment in `deploy/k8s/base/` pins `runAsUser: 185` — the UID the
Quarkus UBI images are built around. OpenShift admission runs each pod through a
**Security Context Constraint (SCC)**. The default, `restricted-v2`, *assigns* each
pod a high UID from a per-namespace range and **rejects** a pod that demands a
specific one. Pin `185` and the pod never admits.

The fix is counterintuitive: **drop `runAsUser` entirely** and let OpenShift pick.
The UBI9 OpenJDK images are designed for this — their files are group-`0` readable
and writable, so they run fine as an arbitrary UID. On the live cluster the app
pods were assigned UID `1000660000`:

```
graphql-gateway   scc=restricted-v2   runAsUser=1000660000
order-service     scc=restricted-v2   runAsUser=1000660000
strangler-proxy   scc=restricted-v2   runAsUser=1000660000
```

Postgres and Kafka are the exception. Their upstream images *do* need specific
non-root UIDs (Postgres as `70`, the Apache Kafka image as `1000`) because those
UIDs own the data directories. For them, dropping `runAsUser` is wrong — instead
you grant them an SCC that *allows* their UID. The chart creates a ServiceAccount,
`mea-infra`, and binds it to the `nonroot-v2` SCC; the two StatefulSets run under
that account:

```
postgres-0   scc=nonroot-v2   runAsUser=70
kafka-0      scc=nonroot-v2   runAsUser=1000
```

The binding is the one piece of OpenShift RBAC worth knowing: OpenShift
auto-generates a ClusterRole named `system:openshift:scc:<name>` for every SCC,
whose single permission is "use this SCC". Binding your ServiceAccount to that
ClusterRole is what grants the SCC (the CLI shorthand is
`oc adm policy add-scc-to-user nonroot-v2 -z mea-infra`).

### 2. Routes, not port-forward

The `deploy/k8s/` story ends at `kubectl port-forward`. OpenShift has first-class
ingress: a `Route` object hands a Service a real external hostname served by the
cluster router. The chart defines two — the strangler proxy (the system's permanent
REST edge) and the GraphQL gateway:

```
strangler-proxy   strangler-proxy-mea.apps-crc.testing   edge/Redirect
graphql-gateway   graphql-gateway-mea.apps-crc.testing   edge/Redirect
```

`edge/Redirect` means TLS terminates at the router and plain HTTP is redirected to
HTTPS — no certificate wiring in the application.

### 3. The integrated registry

OpenShift runs its own image registry. Images are pushed there and referenced by
its in-cluster Service DNS, `image-registry.openshift-image-registry.svc:5000`.
No external registry account is required for a local cluster.

## The chart: eight services from one template

The chart does not carry eight near-identical Deployment files. It carries **one**,
and describes the eight workloads as *data* in `values.yaml`:

```yaml
services:
  strangler-proxy: { port: 8888, probe: tcp,  size: small, route: true }
  review-service:  { port: 8081, probe: tcp,  size: small, db: REVIEW_SERVICE_JDBC_URL }
  inventory-service: { port: 8084, grpcPort: 9004, probe: http, size: large, db: INVENTORY_SERVICE_JDBC_URL, kafka: true }
  # ...five more
```

Each key captures only what differs between services: its HTTP port, whether it
exposes a second gRPC port, whether its health probe is a real `/q/health`
`httpGet` or a `tcpSocket` (strangler-proxy and review-service ship without
`quarkus-smallrye-health`, so a TCP probe is what actually reflects readiness),
whether it owns a database
schema, whether it is a Kafka consumer (and so needs a graceful-shutdown window),
and its resource tier.

`templates/app-deployment.yaml` then ranges over that map. The interesting parts
are the conditionals — this is where "data drives the template" earns its keep:

{% raw %}
```yaml
{{- range $name, $svc := .Values.services }}
    spec:
      {{- if $svc.kafka }}
      terminationGracePeriodSeconds: 45     # let in-flight records commit
      {{- end }}
      containers:
        - name: {{ $name }}
          image: "{{ $root.Values.image.registry }}/{{ $name }}:{{ $root.Values.image.tag }}"
          {{- if $svc.db }}
          env:
            - name: QUARKUS_DATASOURCE_JDBC_URL
              valueFrom:
                configMapKeyRef: { name: mea-app-config, key: {{ $svc.db }} }
          {{- end }}
          {{- if eq $svc.probe "http" }}
          readinessProbe:
            httpGet: { path: /q/health/ready, port: http }
          {{- else }}
          readinessProbe:
            tcpSocket: { port: http }
          {{- end }}
          securityContext:
            runAsNonRoot: true            # NOTE: no runAsUser — restricted-v2 assigns it
            allowPrivilegeEscalation: false
            capabilities: { drop: ["ALL"] }
            seccompProfile: { type: RuntimeDefault }
{{- end }}
```
{% endraw %}

The shared environment contract — the cross-service URLs, the per-service datasource
URLs, the gRPC coordinates — is a single ConfigMap, `mea-app-config`, ported from
`deploy/k8s/base/app-config.yaml`. The one change worth noting is that every
in-cluster address is built from the release namespace
{% raw %}(`<svc>.{{ .Release.Namespace }}.svc.cluster.local`){% endraw %}, so the
chart installs into any namespace without editing. Postgres and Kafka keep every
hard-won fix from Chapter 30's live apply — Postgres's `args`-not-`command`
entrypoint and `PGDATA` subdirectory, Kafka's `publishNotReadyAddresses` and
pod-stable quorum-voter DNS and 10-second probe timeout — because those bugs bite
on OpenShift exactly as they did on minikube.

## Building and pushing the images

The eight services were already built to `examples/*/target/quarkus-app/` earlier
in the book, and each ships a `src/main/docker/Dockerfile.jvm`. Build each image and
push it to the internal registry (after exposing its route and logging `podman` in):

```sh
REG=default-route-openshift-image-registry.apps-crc.testing
podman login -u kubeadmin -p "$(oc whoami -t)" --tls-verify=false "$REG"

for name in strangler-proxy review-service notification-service inventory-service \
            payment-service shipping-service order-service graphql-gateway; do
  dir="examples/$(ls examples | grep -- "-$name$")"
  podman build -f "$dir/src/main/docker/Dockerfile.jvm" -t "$REG/mea/$name:1.0" "$dir"
  podman push --tls-verify=false "$REG/mea/$name:1.0"
done
```

Pushing to the registry auto-creates an ImageStream per image in the namespace.

**One real snag worth planning for:** the CRC VM could not reach Docker Hub
(`dial tcp registry-1.docker.io:443: i/o timeout`), so Postgres and Kafka —
upstream images — landed in `ImagePullBackOff`. The *host* could reach Docker Hub,
so the fix was to mirror them through the host into the internal registry and point
the chart at the mirror:

```sh
for ref in library/postgres:16-alpine apache/kafka:3.8.0; do
  podman pull "docker.io/$ref"
  podman tag  "docker.io/$ref" "$REG/mea/${ref##*/}"
  podman push --tls-verify=false "$REG/mea/${ref##*/}"
done
```

On a cluster with Docker Hub egress you would skip this and reference the upstream
images directly — `values.yaml` has both forms, one commented.

## Deploying, and watching it come up

```sh
oc new-project mea
helm upgrade --install mea openshift/helm/mea --namespace mea
oc get pods -n mea -w
```

Two things are worth expecting. First, if you skipped the memory bump, the infra
pods sit `Pending` — `oc describe pod postgres-0` says `Insufficient memory`, and the
`oc describe node` "Allocated resources" line shows memory requests at 99%. Stop the
cluster, raise `crc config set memory`, start again; the pods schedule.

Second, the app pods **crash-loop before the infrastructure is ready**, and that is
fine. A Quarkus service with `quarkus.flyway.migrate-at-start=true` fails fast if
Postgres is not yet reachable (`UnknownHostException` while the headless Service has
no ready endpoints, then connection refused). As Postgres and Kafka go `1/1`, each
service recovers on its next back-off. The restart counts are the scar tissue of
that ordering — the end state is all green:

```
NAME                   READY   STATUS    RESTARTS
graphql-gateway        1/1     Running   0
inventory-service      1/1     Running   4
kafka-0                1/1     Running   0
notification-service   1/1     Running   9
order-service          1/1     Running   4
payment-service        1/1     Running   4
postgres-0             1/1     Running   0
review-service         1/1     Running   1
shipping-service       1/1     Running   4
strangler-proxy        1/1     Running   0
```

(If you want a clean start with no restart churn, you can deploy the infra
StatefulSets first and wait for them before the apps — but the self-healing path
above is what a plain `helm install` does, and it converges.)

## Verifying it works

Health, through the GraphQL gateway's Route (edge TLS):

```sh
$ curl -sk https://graphql-gateway-mea.apps-crc.testing/q/health/ready
{ "status": "UP", "checks": [ ] }
```

The full path, through the strangler proxy's Route — external client → Route →
Camel edge router → inventory-service → its own Postgres `inventory` schema:

```sh
$ curl -sk https://strangler-proxy-mea.apps-crc.testing/api/inventory
[{"sku":"SKU-WIDGET-001","name":"Standard Widget","priceCents":1999,"quantityOnHand":100},
 {"sku":"SKU-GADGET-002","name":"Deluxe Gadget","priceCents":4999,"quantityOnHand":50},
 {"sku":"SKU-GIZMO-003","name":"Pocket Gizmo","priceCents":999,"quantityOnHand":5}]
```

That is a 200 with seeded data: the Route, the SCC-assigned UID, the ConfigMap
wiring, the Flyway migration, and the Camel route all working together.

**One real gap.** `GET /api/reviews` through the proxy returns **500**, and the
review-service log says `relation "reviews" does not exist`. This is not an
OpenShift problem — review-service is the one service still in the *shared-data*
phase (Chapter 18): it reads the monolith's `public.reviews` table rather than
owning its own schema, and a fresh cluster has no monolith to create that table.
Seeding it (or finishing review-service's extraction) is the fix; the deployment
mechanics are sound.

## The managed counterpart: GitOps and Pipelines

Everything above was imperative — you ran `helm install`. The OpenShift-native way
to *keep* a cluster matching the chart is **OpenShift GitOps** (Argo CD): an
`Application` object points at `openshift/helm/mea` in this repo, and Argo
reconciles the cluster to it, continuously, pruning drift and self-healing. A
starter manifest is committed at
[`openshift/gitops/application.yaml`](https://github.com/patterncatalyst/modernizing-enterprise-applications/blob/main/openshift/gitops/application.yaml).
Its symmetric partner is **OpenShift Pipelines** (Tekton) for the build half — the
`podman build`/`push` loop above becomes a `Pipeline` triggered on push, the
managed counterpart to the GitHub Actions workflows of Chapter 31. Both are
deliberately left as starting points rather than part of the verified deploy: each
needs its operator installed, and GitOps needs this repo reachable from the cluster.

---

*Verification status: **verified live**, 2026-10-06, on OpenShift Local (CRC
2.64.0, OpenShift 4.22.14, single node, 20 GB / 8 vCPU). All ten workloads reached
`1/1 Running` in namespace `mea` (eight application Deployments + the Postgres and
Kafka StatefulSets). The SCC behaviour was confirmed by reading each pod's
`openshift.io/scc` annotation and effective UID: application pods under
`restricted-v2` with assigned UID `1000660000`, `postgres-0`/`kafka-0` under
`nonroot-v2` with UIDs `70`/`1000`. The GraphQL gateway's `/q/health/ready` returned
`{"status":"UP"}` and the strangler-proxy Route returned seeded inventory JSON, both
over the external `apps-crc.testing` hostnames. The full capture is committed at
`openshift/evidence/verification.txt`. **Known gap:** `review-service`'s
`/api/reviews` returns 500 (`relation "reviews" does not exist`) because it reads
the monolith's shared `public` schema, absent on a monolith-free cluster — a
data-seeding matter, not a deployment defect. **Not verified:** the GitOps
`Application` and any Tekton pipeline were authored but not applied (no operators
installed). The image mirror step is CRC-network-specific; on a cluster with Docker
Hub egress, the upstream `postgres`/`kafka` refs apply unchanged. Cited:
`openshift/helm/mea/**` (the chart), `openshift/README.md`, `openshift/evidence/verification.txt`,
`openshift/gitops/application.yaml`; `deploy/k8s/base/**` (the Kubernetes manifests
this chart ports); `examples/*/src/main/docker/Dockerfile.jvm`. Re-confirm by
re-running `helm upgrade --install` against a fresh `crc start`, then re-driving the
two Route `curl`s and re-reading the pod SCC annotations — the assigned UID will
differ per cluster, but the SCC names and the 200s should not.*
