---
title: "Mesh, Observability & Distributed Tracing"
order: 30
part: "Operating the Modernized System"
description: "Istio (mesh/mTLS/canary), Kiali, OpenTelemetry + the LGTM stack, spans/causality/context propagation, and cross-context debugging — on minikube."
---

Chapter 29 closed with a specific, named gap: every manifest under
`deploy/k8s/` had been verified **headlessly** — `kubectl kustomize` rendered
clean, `kubectl apply --dry-run=client` validated 24 objects — and nothing
had actually run. The overlay even left the next step as a comment rather
than a TODO buried in prose: `deploy/k8s/overlays/minikube/kustomization.yaml`
carried a commented-out `resources:` block pointing at two directories,
`../../istio` and `../../observability`, that didn't exist yet. This
chapter brings up a real minikube cluster, builds those two directories,
uncomments the seam, and applies all of it against a live API server. Every
claim below — the mesh, the mTLS, the trace, the metrics — was captured from
that live cluster, not inferred from YAML.

The short version of what changed: a fresh minikube cluster named `mea`
(podman driver, containerd runtime, Kubernetes v1.33.3) now runs Istio
1.29.2 and the full eight-Deployment, two-StatefulSet topology Chapter 29
described. All eight application pods came up `2/2` — the second container
is `istio-proxy`, Istio's sidecar — while `postgres` and `kafka` stayed at
`1/1` and unmeshed, by deliberate choice. Getting there required fixing six
real bugs in the Chapter 29 manifests that a client-side dry-run had no way
to catch, flipping mutual TLS from permissive to strict only after
confirming it worked, and capturing one real, causally connected trace that
crosses three OpenTelemetry-instrumented services and five Envoy hops in a
single request.

## Why a service mesh

A service mesh's whole pitch is that it can add things to every request *in
this namespace* without a single line of application code changing. The
mechanism is the sidecar: Istio's injection webhook adds a second container,
`istio-proxy`, to every pod it's told to mesh, and rewrites that pod's
`iptables` rules so every inbound and outbound connection the application
container makes is transparently routed through Envoy first. The
application still thinks it's dialing
`order-service.mea.svc.cluster.local:8087` directly — it has no Istio
client library, no new dependency, nothing in its own `pom.xml` changed for
this. What actually happens is app → Envoy (same pod) → Envoy (destination
pod) → app, with both Envoy proxies free to do whatever the mesh
configuration tells them to on that hop: encrypt it, log it, time it, emit a
span for it.

This chapter's Istio install uses exactly three of those capabilities, and
is explicit about not using a fourth. `deploy/k8s/istio/peer-authentication.yaml`
turns on mutual TLS between every meshed pod. `deploy/k8s/istio/telemetry.yaml`
turns on Envoy access logging and routes Envoy's own trace spans to the
OTel collector. What this install does *not* add is any traffic policy —
no `VirtualService`, no `DestinationRule`, no retries, no circuit breaking,
no canary weighting configured anywhere in `deploy/k8s/istio/`. That's a
scope choice this chapter names the same direct way Chapter 25 named its own
fault-tolerance deferral: this chapter uses the mesh for identity and
observability, the two things a sidecar can add with zero risk of changing
request semantics, and leaves traffic-shaping Istio features for whenever
this project's own operational needs ask for them — the same
no-speculative-infrastructure discipline this book has held since Chapter 3.

{% include excalidraw.html file="mesh-sidecar-data-path" alt="A multi-pod view of the mea namespace. An external client enters over NodePort :8888 (plaintext, unmeshed) to strangler-proxy; then sidecar-to-sidecar mTLS (STRICT) hops carry strangler-proxy to order-service and graphql-gateway to order-service (REST :8087) and inventory-service (gRPC :9004). Each app pod holds an application container plus an istio-proxy Envoy sidecar — four pods shown in full and four more summarized. Every sidecar ships telemetry to an unmeshed lgtm Deployment, and postgres and kafka sit outside the mesh with no sidecar." caption="Figure 30.1 — The mesh data path: each app pod pairs its container with an Envoy sidecar, and sidecar-to-sidecar hops carry mTLS, access logs, and trace spans without the application's involvement" %}

## Installing Istio on minikube

Getting a mesh onto this cluster is a two-tool story: `istioctl` installs
the control plane and the sidecar-injection webhook; kustomize's overlay
patches opt individual Deployments into it. `deploy/k8s/istio/istio-install.yaml`
is the `IstioOperator` resource `istioctl install -f` consumes, and its own
header comment states the profile choice directly — `profile: default`,
not `minimal` (which omits the ingress gateway this chapter doesn't need
either, but keeps as the standard shape) and not `demo` (which bundles
addons this tutorial doesn't use, since the LGTM stack in
`../observability` is this project's own addon). The same file is where the
trace pipeline gets wired in before a single sidecar is injected:

```yaml
# deploy/k8s/istio/istio-install.yaml
spec:
  profile: default
  meshConfig:
    accessLogFile: /dev/stdout
    extensionProviders:
      - name: otel
        opentelemetry:
          port: 4317
          service: lgtm.mea.svc.cluster.local
    defaultConfig:
      tracing:
        sampling: 100.00
```

That `otel` extension provider is Istio's own OTLP exporter — it's how
Envoy's own spans (not the application's) reach
`lgtm.mea.svc.cluster.local:4317`, the same collector endpoint the three
OpenTelemetry-instrumented services export to directly. `telemetry.yaml`,
a mesh-wide `Telemetry` resource with no workload selector, is what actually
turns that provider on for every meshed pod in `mea`, at 100% sampling —
matching the `quarkus.otel.traces.sampler=always_on` demo sampler the three
instrumented services carry, so a request's mesh-hop spans and app-hop spans
land in Tempo at the same fidelity rather than one being randomly dropped
when the other isn't.

Istio 1.29 changed how the sidecar itself is delivered, and it shows up
directly in `kubectl get pods`: `istio-proxy` is a **native sidecar** now —
a Kubernetes-native sidecar container, injected as an `initContainer` with
`restartPolicy: Always`, rather than the older mutating-webhook pattern of
prepending an ordinary container ahead of the app container in the same
list. It still starts before the application container, still runs for the
whole lifetime of the pod, and still shows up as the second `2` in a pod's
`2/2` readiness count — but `kubectl describe pod` lists it under `Init
Containers`, not `Containers`, which is the detail worth knowing before it
looks like a misconfiguration.

Getting a Deployment meshed at all is the injection webhook's decision, and
it keys off a **label**, not an annotation — a distinction that cost real
debugging time on the reference project this tree is adapted from, and
`deploy/k8s/overlays/minikube/kustomization.yaml`'s own comment states the
lesson directly: Istio's sidecar-injection `MutatingWebhookConfiguration`
matches `sidecar.istio.io/inject In ["true"]` against the admitted object's
**labels**, never its annotations, confirmed live against a cluster in
`~/Dev/datamesh-reference-arch-quarkus/k8s/istio` — the annotation form
silently injects nothing in a namespace that isn't itself
namespace-labeled for auto-injection, which `mea` is not. The
fix is a JSON-patch `add` at `/spec/template/metadata/labels`, one per
Deployment, applied identically to all eight application Deployments and
left off `postgres` and `kafka` by design:

{% include codetabs.html langs="overlays/minikube/kustomization.yaml — sidecar-inject LABEL patch|istio/istio-install.yaml — OTLP extensionProvider (shown above, repeated for contrast)" %}

```yaml
# deploy/k8s/overlays/minikube/kustomization.yaml
  - target:
      kind: Deployment
      name: order-service
    patch: |-
      - op: add
        path: /spec/template/metadata/labels/sidecar.istio.io~1inject
        value: "true"
```

```yaml
# deploy/k8s/istio/istio-install.yaml
  meshConfig:
    extensionProviders:
      - name: otel
        opentelemetry:
          port: 4317
          service: lgtm.mea.svc.cluster.local
```

Eight identical patches, one per application Deployment —
`strangler-proxy`, `review-service`, `notification-service`,
`inventory-service`, `payment-service`, `shipping-service`,
`order-service`, `graphql-gateway` — each adding the same
`sidecar.istio.io/inject: "true"` label to that Deployment's pod template.
`postgres` and `kafka` get none of it, and the overlay's own comment names
the reason: meshing a stateful workload's own TLS and connection handling
collides with the injected sidecar's mTLS wrapping, a known issue the
`lgtm-minikube-stack` skill's own known-issues notes already flagged before
this chapter ever applied a manifest. The two StatefulSets stay exactly as
Chapter 29 left them — `1/1`, plaintext, reachable only by the in-cluster
services that already knew their DNS names.

## When dry-run lied: six bugs a live apply found

Chapter 29's closing section named the limit of its own verification
method in one sentence: `kubectl kustomize | kubectl apply --dry-run=client`
proves the YAML is well-formed and admission-valid; it proves nothing about
whether the system that YAML describes actually comes up. Running `kubectl
apply -k deploy/k8s/overlays/minikube` against this chapter's real cluster
collected on that debt directly — six bugs, all in the two infrastructure
StatefulSets, every one of them invisible to a dry-run because every one of
them is a runtime failure, not a schema failure. A dry-run checks "is this a
valid Pod spec"; none of these six bugs make an invalid Pod spec. They make
a perfectly valid Pod spec that fails to boot.

| # | Component | What Chapter 29 shipped | What happened live | The fix |
|---|---|---|---|---|
| 1 | Postgres | `command: ["postgres", "-c", ...]` | `command` replaces the image's `docker-entrypoint.sh` entirely, so `initdb` and `POSTGRES_*` env handling never ran — the process started against an uninitialized data directory | `args:` instead of `command:`, which keeps the entrypoint and appends the flags to its own `postgres` invocation |
| 2 | Postgres | `fsGroup` matched Debian's `postgres` uid, `999` | `postgres:16-alpine`'s `postgres` user is uid/gid `70`, not `999` — the alpine image uses a different uid than the Debian-based image the number was copied from | `fsGroup: 70`, matching the alpine image's real user |
| 3 | Postgres | `PGDATA` unset (default: the PVC mount root) | the PVC mount point `/var/lib/postgresql/data` is owned by `root` — `fsGroup` only sets the *group* on that mount, not the owner — so `initdb` running as uid 70 couldn't `chmod` it | `PGDATA=/var/lib/postgresql/data/pgdata`, a subdirectory the postgres process creates and owns itself, the standard fix for this image on a mounted volume |
| 4 | Kafka | `securityContext.runAsNonRoot: true`, no explicit `runAsUser` | the `apache/kafka` image's `USER` is the non-numeric name `appuser`, which `kubelet` cannot verify against `runAsNonRoot` (it needs a numeric uid) — the pod failed with `CreateContainerConfigError` before a container process ever started | `runAsUser: 1000`, the image's real uid, pinned explicitly |
| 5 | Kafka | headless Service default DNS; `KAFKA_CONTROLLER_QUORUM_VOTERS` pointed at the Service name; `KAFKA_LOG_DIRS` unset (image default `/tmp/kafka-logs`) | a headless Service only publishes DNS for *ready* endpoints by default — but a single-node KRaft broker must resolve its own controller address (`kafka-0.kafka...`) to *become* ready in the first place, a startup deadlock; separately, broker state was landing in the container's ephemeral filesystem instead of the mounted PVC, so it wouldn't survive a restart | `publishNotReadyAddresses: true` on the headless Service breaks the DNS deadlock; `KAFKA_CONTROLLER_QUORUM_VOTERS` repointed at the pod-stable DNS name (`kafka-0.kafka.mea.svc.cluster.local:9093`), which resolves at startup, rather than the Service name, which doesn't until Ready; `KAFKA_LOG_DIRS=/var/lib/kafka/data` plus `fsGroup: 1000` so the broker can actually write the mounted volume |
| 6 | Kafka | `readinessProbe`/`livenessProbe.exec` with the default `timeoutSeconds: 1` | the probe command, `kafka-broker-api-versions.sh`, launches its own JVM — which cannot start and answer inside the 1-second default — so an otherwise-healthy broker kept failing both probes and was killed by its liveness probe | `timeoutSeconds: 10` on both probes, a realistic window for a check that boots a JVM |

Every one of these six fixes is marked in the manifests themselves with the
identical comment tag, `ch.30 live-apply fix`, so the gap between what
Chapter 29 shipped and what Chapter 30 corrected is traceable line by line
rather than lost in a diff. The pattern across all six is the same: none of
them is a YAML *syntax* problem, and none of them is something a schema
validator has any way to know about — `command` versus `args` are both
valid fields on a container spec; `runAsNonRoot: true` with no `runAsUser`
is valid API shape; an unset `PGDATA` or a 1-second probe timeout are valid
defaults. Every one of these bugs lives in the gap between "this object is
well-formed" and "this object, applied to a real container image on a real
kubelet, actually starts" — exactly the gap Chapter 29 named and deferred,
closed here the only way it can be closed: by applying it and watching what
breaks.

## mTLS across the seam

`deploy/k8s/istio/peer-authentication.yaml` is a namespace-wide
`PeerAuthentication` named `default` — the specific name Istio requires for
a namespace-scoped policy to take effect — and its own header comment
documents the rollout sequence this chapter actually followed rather than
the end state alone: the policy shipped as `PERMISSIVE` first, traffic was
driven through the mesh and confirmed to be using mutual TLS anyway, and
only then was the mode flipped to `STRICT`. `PERMISSIVE` accepts both
plaintext and mTLS connections on the same port, which makes it the safe
default for a first rollout — a client that hasn't been meshed yet, or a
probe that bypasses Envoy, doesn't get locked out while the policy is being
validated. `STRICT` is the enforcement mode: a meshed pod will refuse a
plaintext connection outright once every client reaching it is confirmed to
be going through its own sidecar.

The live verification for both states is `istioctl x describe pod`, which
reports the mTLS mode Envoy is actually enforcing on a given workload — not
what the YAML says should be happening, but what the sidecar's own xDS
configuration currently has loaded. Run against `order-service` after the
flip, the captured output is unambiguous:

```
# deploy/k8s/observability/evidence/ch30-istioctl-mtls.txt
Pod: order-service-748b9bd7db-d4sb5
Effective PeerAuthentication:
   Workload mTLS mode: STRICT
Applied PeerAuthentication:
   default.mea
```

`PeerAuthentication` is enforced by the *receiving* sidecar, which is the
detail that makes the StatefulSets' exclusion from the mesh a non-issue
rather than a contradiction: `postgres` and `kafka` carry no
`istio-proxy`, so there's no Envoy on their side to enforce anything, and
they keep accepting plaintext connections from their own meshed clients
exactly as before — a mesh-wide `STRICT` policy has no way to reach a pod
that was never injected in the first place. The same `istio_requests_total`
metric this chapter uses to read traffic (next section) carries a
`connection_security_policy` label per edge, and every application-to-
application edge in the captured data — `strangler-proxy → review-service`,
`order-service → inventory-service`, `graphql-gateway →`
{`order`, `payment`, `review`, `shipping`}-service — reports
`connection_security_policy="mutual_tls"`. That label is Istio's own
confirmation, independent of `istioctl x describe pod`, that every one of
those requests was actually encrypted in transit, not merely eligible to
be.

## The LGTM stack on Kubernetes

`deploy/k8s/observability/` ports the same `grafana/otel-lgtm` all-in-one
image compose.yaml has run since Chapter 19 — Collector, Tempo, Loki, Mimir,
and Grafana bundled into a single container — onto a single-replica
Deployment, rather than standing up four separate backends. The Collector
and Grafana-datasource configs aren't rewritten for the cluster; they're the
same files compose.yaml bind-mounts, now delivered as ConfigMaps mounted at
the identical in-container paths the image's startup script already
expects:

```yaml
# deploy/k8s/observability/lgtm-deployment.yaml
      containers:
        - name: lgtm
          image: docker.io/grafana/otel-lgtm:0.8.1
          volumeMounts:
            - name: otelcol-config
              mountPath: /otel-lgtm/otelcol-config.yaml
              subPath: otelcol-config.yaml
            - name: grafana-datasources
              mountPath: /otel-lgtm/grafana/conf/provisioning/datasources/datasources.yaml
              subPath: datasources.yaml
```

One addition exists on top of the ported compose config, and it's the piece
that makes mesh metrics visible at all: a second Prometheus receiver in the
Collector config, scraping every `istio-proxy` sidecar's own stats endpoint
via Kubernetes service discovery.

```yaml
# deploy/k8s/observability/otelcol-configmap.yaml
      prometheus/istio-mesh:
        config:
          scrape_configs:
            - job_name: 'istio-proxy'
              metrics_path: /stats/prometheus
              kubernetes_sd_configs:
                - role: pod
                  namespaces:
                    names: ['mea']
              relabel_configs:
                - source_labels: [__meta_kubernetes_pod_container_name]
                  action: keep
                  regex: istio-proxy
```

Every Envoy sidecar exposes Prometheus-format stats on `:15090/stats/prometheus`
— `istio_requests_total`, `istio_request_duration_milliseconds`, and the
rest of Istio's standard L7 metric set — without any configuration inside
the mesh itself; this receiver is purely a Collector-side scrape job that
discovers which pods carry an `istio-proxy` container (native-sidecar
`initContainer` or legacy sidecar, Kubernetes service discovery surfaces
both the same way) and points at that one port. Making that discovery work
needs the Collector's own pod to be allowed to list and watch pods in `mea`
— `deploy/k8s/observability/rbac.yaml` grants exactly that, no more, via a
dedicated `lgtm` ServiceAccount bound to a `ClusterRole` scoped to
`pods`/`endpoints`/`services` `get`/`list`/`watch`. The `lgtm` Deployment
itself carries no `sidecar.istio.io/inject` label, by the same reasoning
that excludes `postgres` and `kafka` — it's the thing receiving telemetry
from the mesh, not a participant being observed.

A real naming bug sits in the history of this stack, and it traces back to
how Istio decides what a port even is: the Service port Istio's own OTLP
trace exporter and every instrumented service's
`quarkus.otel.exporter.otlp.traces.endpoint` both target was originally
named `otlp-grpc`. Istio's Envoy auto-detects a port's protocol
from the prefix before the first hyphen in its *name* — `grpc`, `http`,
`tcp`, and a short list of others are recognized tokens, and `otlp` isn't
one of them. With the wrong name, Envoy silently treated the connection as
opaque TCP, dropped HTTP/2 framing, and the OTLP/gRPC export failed on
every call — confirmed live via `istioctl proxy-config cluster`, which
showed the `outbound|4317||lgtm.mea.svc.cluster.local` cluster's
`rq_total` equal to its `rq_error`, zero successes. `deploy/k8s/observability/lgtm-service.yaml`'s
port is named `grpc-otlp` today specifically to put the recognized token
first; the file's own comment documents the failure mode so the next person
porting an OTLP-over-gRPC endpoint into a meshed namespace doesn't lose the
same hour to it.

## The limit of mesh-only tracing

It's tempting to read "Istio emits trace spans for every hop" as "Istio
gives you distributed tracing without any application work," and the
captured evidence in this cluster is the clearest demonstration of why that
reading is wrong. Envoy's
own spans are real, and they appear for *every* meshed pod regardless of
whether the application inside it knows OpenTelemetry exists —
`review-service`, `payment-service`, and `shipping-service` all show up as
Envoy client/server span pairs in the captured trace below, named things
like `payment-service.mea.svc.cluster.local:8085/*`, with no application
span nested inside any of them. None of those three services carries
`quarkus-opentelemetry`; the mesh produced a span for each hop anyway.

What the mesh cannot do on its own is tell you *why* a request went where
it went, in business terms, or connect spans from two different
applications into one trace unless something upstream already started that
trace and propagated its context forward. Envoy reads and forwards the
trace-context headers (`traceparent` and friends) a request arrives with —
it doesn't invent a new, unrelated trace for every hop, and it doesn't
retroactively stitch hops together if no one ever handed it a trace ID in
the first place. The three modules this chapter actually instruments —
`graphql-gateway`, `order-service`, `inventory-service` — are the ones that
start a trace and attach meaningful span names (`POST /graphql`, `GET
/api/orders/{id}`, the gRPC method name) to it; every hop downstream of one
of those three, instrumented or not, rides on the trace context that
already exists. Context propagation is squarely the application's job. The
mesh amplifies a trace that already started; it does not start one.

## Instrumenting the trace path

Three Quarkus modules — `examples/04-inventory-service`,
`examples/07-order-service`, `examples/08-graphql-gateway` — carry
`quarkus-opentelemetry` as an additive dependency, chosen because they're
the services on the one request path this chapter wanted a complete,
human-readable trace across: a GraphQL aggregation query fanning out from
the gateway into order, inventory, review, payment, and shipping. Adding
the extension and three properties is the entire change; nothing about any
service's business logic moved.

{% include codetabs.html langs="order-service/pom.xml — the one new dependency|order-service/application.properties — OTLP exporter + demo sampler" %}

```xml
<!-- examples/07-order-service/pom.xml -->
<!-- Emits OTLP traces to the LGTM collector in the mea namespace;
     see application.properties for exporter/service-name/sampler. -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

```properties
# examples/07-order-service/src/main/resources/application.properties
# ch.30 (additive): OpenTelemetry traces to the mesh LGTM collector. Demo
# sampler (always_on) -- not production-appropriate, this is for a live
# connected-trace capture across exactly 3 instrumented modules.
quarkus.otel.exporter.otlp.traces.endpoint=http://lgtm.mea.svc.cluster.local:4317
quarkus.otel.service.name=order-service
quarkus.otel.traces.sampler=always_on
```

The identical three-line block, with only `quarkus.otel.service.name`
changed, sits in `graphql-gateway`'s and `inventory-service`'s own
`application.properties`. `always_on` sampling is named a demo sampler in
the comment itself, because it isn't a production setting —
a real deployment would sample a small percentage of traffic, not every
request, to keep collector load and storage bounded. For a chapter whose
entire job is capturing one real, connected trace end to end and proving
the pipeline works, sampling everything is the correct choice; shipping
`always_on` to a production environment would not be.

Quarkus's OpenTelemetry extension instruments the JAX-RS server side, the
REST client side, and gRPC automatically once it's on the classpath — none
of `RemoteInventoryClient`'s three `withDeadlineAfter` call sites, none of
`GatewayApi`'s four `@RestClient` fields, and none of the generated GraphQL
resolvers needed a single line of manual span-creation code. That's the
same "capability Quarkus offers becomes a capability a service has the
moment someone adds the dependency" story Chapter 27 told for `@ConfigProperty`
and the native-image profile — no new code, just the extension turned on
and three properties pointed at the right collector.

## Reading the signals

Thirty synchronous checkouts through the strangler proxy and fifteen GraphQL
aggregation queries against the gateway generated the traffic this chapter's
evidence is built from; each driven checkout returned `202 Accepted` and each
query `200 OK`. (The cumulative `istio_requests_total` counters below run a
little higher on those edges — they also count earlier warm-up requests from
bringing the cluster up, including one `400` from before the customer fixture
was seeded.) One of those GraphQL queries was pulled out of Tempo by trace ID —
`af8b2d9f78b2e8a796b1fd2b472aede3` — and it's the single most concrete
artifact in this chapter: one root span, five downstream branches, Envoy
and application spans interleaved at every hop.

```
POST /graphql                                    graphql-gateway   83.75ms  (root)
  GraphQL                                        graphql-gateway   82.08ms
    GET /api/orders/{id}                         graphql-gateway    6.35ms
      order-service:8087/* (envoy, client)       graphql-gateway    5.41ms
        order-service:8087/* (envoy, server)     order-service      4.53ms
          GET /api/orders/{id}                   order-service      3.58ms
    GetStock (gRPC)                               graphql-gateway   67.70ms
      inventory-service:9004/* (envoy, client)    graphql-gateway    4.85ms
        inventory-service:9004/* (envoy, server)  inventory-service  3.97ms
          GetStock (gRPC, server)                 inventory-service  2.29ms
    GET /api/reviews                              graphql-gateway    5.91ms
      review-service:8081/* (envoy, client)       graphql-gateway    5.33ms
        review-service:8081/* (envoy, server)     review-service     4.71ms
    GET /api/payments                             graphql-gateway    5.71ms
      payment-service:8085/* (envoy, client)      graphql-gateway    5.29ms
        payment-service:8085/* (envoy, server)    payment-service    4.78ms
    GET /api/shipments                            graphql-gateway    4.44ms
      shipping-service:8088/* (envoy, client)     graphql-gateway    3.93ms
        shipping-service:8088/* (envoy, server)   shipping-service   3.33ms
```

Look at where the app-level spans stop. The `order-service` and
`inventory-service` branches both bottom out in a real application span —
`GET /api/orders/{id}`, `GetStock` — because both services carry
`quarkus-opentelemetry`. The `review-service`, `payment-service`, and
`shipping-service` branches stop one level higher, at the Envoy server
span, because none of those three services is instrumented; the mesh still
produced a span for the hop (exactly the "limit of mesh-only tracing"
point above, now visible in real captured data, not just asserted), but
there's no business-operation name underneath it because nothing inside
that pod created one. One detail in the raw export deserves a direct
mention: the captured JSON contains 20 span records for 19 distinct span IDs — the
`order-service` application span for `GET /api/orders/{id}` appears twice,
in two separate OTLP resource batches with identical content, an export
artifact rather than evidence of a second request. The trace tree above
reflects the 19 distinct spans.

Metrics tell the same story from a different angle. `istio_requests_total`,
scraped off every `istio-proxy` sidecar by the Collector's
`prometheus/istio-mesh` job and stored in Mimir, reports every
application-to-application edge this traffic exercised, each one carrying
`connection_security_policy="mutual_tls"`:

```
# deploy/k8s/observability/evidence/ch30-metrics.txt (mTLS-only edges)
strangler-proxy      -> review-service        code=500   count=106
order-service        -> inventory-service     code=200   count=62
graphql-gateway      -> payment-service       code=200   count=1
graphql-gateway      -> review-service        code=500   count=16
strangler-proxy      -> order-service         code=202   count=31
graphql-gateway      -> order-service         code=200   count=16
shipping-service     -> order-service         code=200   count=31
strangler-proxy      -> order-service         code=200   count=1
strangler-proxy      -> order-service         code=400   count=1
graphql-gateway      -> shipping-service      code=200   count=16
```

Two things sit inside that same metric table rather than being
cleaned out of it. First: `strangler-proxy → review-service` and
`graphql-gateway → review-service` both show `code=500`, consistently, not
intermittently — `review-service` is returning server errors against this
live cluster. That's a real, pre-existing application defect, not a mesh or
tracing problem; the mesh faithfully reports exactly the failure happening
behind it, `connection_security_policy` and all, which is what an
observability layer is supposed to do with a broken dependency rather than
paper over it. Chasing that defect down is outside this chapter's scope —
mesh and tracing plumbing, not `review-service`'s own request handling — and
it's named here rather than quietly edited out of the evidence. Second:
`order-service → inventory-service` and `shipping-service → order-service`
both also carry a duplicate row with `mtls_policy=unknown` at the identical
count — Istio emits both a mesh-attributed and a policy-unlabeled copy of
the same underlying request count depending on which side's telemetry
reported it; the `mutual_tls`-labeled row is the one that matters for
confirming encryption, and it's present for every one of those edges.

What this cluster's evidence does *not* cover is just as much a part of the
record. Loki received no application log lines from this run — the
`otel-lgtm` container's Collector runs inside its own pod, with no Alloy or
other node-level log-shipping DaemonSet deployed anywhere in this tree, so
there was never a mechanism that could read another pod's stdout and hand
it to Loki. Application logs for this chapter are what `kubectl logs
<pod>` shows directly, not what Grafana's Loki panel shows — a real gap,
named rather than silently left for a reader to discover. And the saga
hops Chapter 23's payment extraction and Chapter 24's shipping extraction
run over Kafka — `payment.captured`, `shipment.failed`, and the rest — are
invisible to every piece of this chapter's tracing. Istio's L7 tracing
instruments HTTP and gRPC; it has no concept of a Kafka topic, and none of
the five Kafka-consuming services' consumer-side processing shows up as a
span anywhere in this evidence. A saga's event-driven hops are a distinct
observability problem — Kafka-aware instrumentation inside the consuming
services themselves — not one this chapter's mesh-plus-three-REST/gRPC-services
scope touches.

{% include excalidraw.html file="observability-trace-pipeline" alt="A two-band diagram. Top band: spans and metrics flow from application containers (app-level OpenTelemetry on graphql-gateway, order-service, and inventory-service) and from istio-proxy sidecars across the mea namespace, through the OpenTelemetry Collector's OTLP and Prometheus-scrape receivers, into Tempo (traces) and Mimir (metrics) inside the single lgtm Deployment, with Loki (logs) drawn as configured-but-not-live; Grafana reads all three. Bottom band: the captured trace — root graphql-gateway POST /graphql fanning into an order-service REST branch and an inventory-service gRPC branch, with app-level spans nested inside Envoy sidecar spans at their real durations." caption="Figure 30.2 — From sidecar and app spans to a queryable trace: the OTLP and Prometheus paths into the lgtm Deployment, and the shape of the trace they produced" %}

## What you learned

- **Headless verification and a live apply test different failure
  classes** — Chapter 29's `kubectl kustomize | kubectl apply
  --dry-run=client` proved 24 objects were well-formed; it had no way to
  catch any of the six bugs a live apply found, because every one of them
  is a runtime failure (an entrypoint bypassed, a uid mismatch, a DNS
  bootstrap deadlock, a probe timeout too short for the command it runs),
  not a schema failure. "Verified headlessly" and "verified" are different
  claims, and this chapter is the proof.
- **The sidecar injects by label, not annotation** — Istio's injection
  webhook matches `sidecar.istio.io/inject` against the admitted object's
  labels; the annotation form silently injects nothing, a lesson this
  chapter's overlay inherited from a prior project rather than rediscovering
  live.
- **`PeerAuthentication` is enforced by the receiver, not the API server**
  — flipping `mea`'s mesh-wide policy to `STRICT` only affects pods that
  carry a sidecar in the first place; `postgres` and `kafka`, never
  injected, keep accepting plaintext from their own meshed clients exactly
  as before, confirmed by `istioctl x describe pod` and by every
  `connection_security_policy="mutual_tls"` edge in the captured metrics.
- **Mesh spans and app spans answer different questions, and only one of
  them is optional** — Envoy produces a span for every meshed hop whether
  or not the application inside it knows OpenTelemetry exists, visible
  directly in the captured trace where `review-service`'s,
  `payment-service`'s, and `shipping-service`'s branches stop at the Envoy
  server span with no business-operation name underneath. Only the three
  instrumented modules — `graphql-gateway`, `order-service`,
  `inventory-service` — start a trace and propagate its context forward;
  the mesh amplifies a trace, it doesn't start one.
- **An observability layer's job is to report a real failure faithfully,
  not hide it** — `review-service`'s 500s show up in the exact same
  `istio_requests_total` table as every healthy edge, `connection_security_policy`
  label and all, because the mesh has no reason to treat a failing
  destination differently from a healthy one. The defect itself is a
  separate, pre-existing application issue, out of this chapter's scope to
  fix.

Chapter 31 is where this cluster's manifests stop being something applied
by hand from a laptop: the GitHub Actions workflows that build these eight
images, push them somewhere real, and roll a change through this same `mea`
namespace without a person running `kubectl apply -k` themselves.

---

*Verification status: <span class="status status--verified">verified
live</span> against a real minikube cluster for the mesh, mTLS, and the
captured trace/metrics; <span class="status status--deferred">deferred</span>
for Loki log aggregation and Kafka saga-hop tracing. A fresh minikube
cluster (`mea` namespace, podman driver, containerd runtime, Kubernetes
v1.33.3) was brought up, Istio 1.29.2 was installed via `istioctl install -f
deploy/k8s/istio/istio-install.yaml`, and `kubectl apply -k
deploy/k8s/overlays/minikube` was run after uncommenting the chapter's seam
and fixing the six bugs in `deploy/k8s/base/infra/postgres-statefulset.yaml`
and `kafka-statefulset.yaml` this chapter documents. All eight application
pods reached `2/2` Ready (the second container being the native-sidecar
`istio-proxy`); `postgres` and `kafka` reached `1/1` Ready, unmeshed, as
designed. `istioctl x describe pod` against `order-service` reported
`Workload mTLS mode: STRICT` (captured in full at
`deploy/k8s/observability/evidence/ch30-istioctl-mtls.txt`) after
`deploy/k8s/istio/peer-authentication.yaml` was flipped from `PERMISSIVE` to
`STRICT`. Thirty synchronous checkouts (`202`) and fifteen GraphQL
aggregation queries (`200`) were driven against the live cluster. One
resulting trace, ID `af8b2d9f78b2e8a796b1fd2b472aede3`, was pulled from
Tempo and is captured in full at
`deploy/k8s/observability/evidence/ch30-hero-trace.json` — one root span
(`graphql-gateway` `POST /graphql`) fanning out to five branches (order,
inventory via gRPC, review, payment, shipping), with application-level
spans present only in the three `quarkus-opentelemetry`-instrumented
branches (`graphql-gateway`, `order-service`, `inventory-service`) and
Envoy-only spans in the other three, reproduced faithfully in this
chapter's span tree. `istio_requests_total` was queried from Mimir and is
captured in full at `deploy/k8s/observability/evidence/ch30-metrics.txt`,
including every `connection_security_policy="mutual_tls"` edge cited above
and the `review-service` `500`s, which are a real, pre-existing application
defect this chapter reports rather than fixes. Explicitly **not** verified
live: Loki received no application log lines in this run, because no
node-level log-shipping DaemonSet (Alloy or equivalent) is deployed
anywhere in this tree — the in-pod Collector cannot read another pod's
stdout, so application logs for this chapter are documented via `kubectl
logs`, not a Loki query. Kafka saga hops (`payment.captured`,
`shipment.failed`, and the rest, extracted in Chapters 23 and 24) are not
traced by Istio's L7 instrumentation at all — no span in the captured
evidence originates from a Kafka consumer. Cited: `deploy/k8s/istio/istio-install.yaml`,
`peer-authentication.yaml`, `telemetry.yaml`, `kustomization.yaml`;
`deploy/k8s/observability/lgtm-deployment.yaml`, `lgtm-service.yaml`,
`otelcol-configmap.yaml`, `grafana-datasources-configmap.yaml`, `rbac.yaml`,
`kustomization.yaml`; `deploy/k8s/overlays/minikube/kustomization.yaml`
(the uncommented seam and the eight sidecar-inject label patches);
`deploy/k8s/base/infra/postgres-statefulset.yaml` and
`kafka-statefulset.yaml` (all six `ch.30 live-apply fix` comments);
`examples/04-inventory-service/`, `examples/07-order-service/`,
`examples/08-graphql-gateway/` (`pom.xml`'s `quarkus-opentelemetry`
dependency and `application.properties`'s OTLP exporter/service-name/sampler
block in each); and `deploy/k8s/observability/evidence/ch30-hero-trace.json`,
`ch30-metrics.txt`, `ch30-istioctl-mtls.txt`. Re-confirm: re-run `istioctl
install`, `kubectl apply -k deploy/k8s/overlays/minikube`, and `istioctl x
describe pod` against a fresh cluster to confirm the mTLS mode and pod
readiness counts still match; re-query Tempo and Mimir after driving fresh
traffic to confirm the span shape and the `mutual_tls` edges haven't
drifted, since both are live-cluster facts this chapter captured once
rather than values baked into any committed manifest.*
