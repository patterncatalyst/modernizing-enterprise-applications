---
title: "Mesh, Observability & Distributed Tracing"
order: 30
part: "Operating the Modernized System"
description: "Istio sidecars with mTLS, the LGTM stack on minikube, and a captured trace across REST, gRPC and Envoy hops, with the six bugs only a live apply found."
---

## Learning objectives

By the end of this chapter you can:

- Explain how a sidecar adds mTLS and observability to every request with no application change, and why Istio's injection webhook matches a pod-template label, not an annotation.
- Configure a namespace-wide `PeerAuthentication`, roll it out `PERMISSIVE` before `STRICT`, and verify enforcement with `istioctl x describe pod` instead of trusting the YAML.
- Name the bugs a client-side dry-run cannot catch (an entrypoint bypassed by `command:`, a uid mismatch, a DNS bootstrap deadlock, a probe timeout shorter than its command) and what a live `kubectl apply` reveals.
- Say why Envoy emits a span for every meshed hop, instrumented or not, and why mesh tracing can amplify a trace but never start one or carry business context.
- Instrument a Quarkus service for tracing with `quarkus-opentelemetry` and three properties, with no span code in business logic.
- Read a causally connected trace across REST, gRPC and Envoy hops and tell application spans from Envoy-only spans.
- Name what this observability setup does not cover, Loki log shipping and Kafka saga hops.

## Why this matters

The manifests under `deploy/k8s/` had been verified only headlessly: `kubectl kustomize` rendered clean and `kubectl apply --dry-run=client` validated 24 objects. Nothing had run. The minikube overlay carried a commented `resources:` block pointing at `../../istio` and `../../observability`, directories that did not exist yet. This stage brings up a real cluster, builds those directories, uncomments the seam and applies everything against a live API server. Every claim below, the mesh, the mTLS, the trace and the metrics, was captured from that cluster, recorded on Kubernetes v1.33.3, Istio 1.29.2, `grafana/otel-lgtm:0.8.1` and `postgres:16-alpine`; the scripts now reproduce it on the current pins.

A minikube cluster named `mea` now runs Istio and the full eight-Deployment, two-StatefulSet topology. All eight application pods come up `2/2`, the second container being `istio-proxy`. `postgres` and `kafka` stay `1/1` and unmeshed by design. Getting there took fixing six bugs in the earlier manifests that a dry-run could not catch, flipping mutual TLS from permissive to strict only after confirming it worked, and capturing one causally connected trace that crosses three OpenTelemetry-instrumented services and five Envoy hops in a single request.

## Pattern: Sidecar

A service mesh adds behavior to every request in a namespace without changing application code. Istio's injection webhook adds a second container, `istio-proxy`, to each meshed pod and rewrites its `iptables` rules so every inbound and outbound connection passes through Envoy first. The application still dials `order-service.mea.svc.cluster.local:8087` and has no Istio client library or new dependency. The path is app, Envoy in the same pod, Envoy in the destination pod, app, and each Envoy can encrypt, log, time or emit a span for the hop.

This install uses three capabilities. `deploy/k8s/istio/peer-authentication.yaml` turns on mutual TLS between meshed pods. `deploy/k8s/istio/telemetry.yaml` turns on Envoy access logging and routes Envoy's spans to the OTel collector. It adds no traffic policy: no `VirtualService`, `DestinationRule`, retries, circuit breaking or canary weighting anywhere in `deploy/k8s/istio/`. Identity and observability are the two things a sidecar adds with no risk of changing request semantics; traffic shaping waits until the system's operational needs ask for it.

{% include excalidraw.html file="mesh-sidecar-data-path" alt="A multi-pod view of the mea namespace. An external client enters over NodePort :8888 (plaintext, unmeshed) to strangler-proxy; then sidecar-to-sidecar mTLS (STRICT) hops carry strangler-proxy to order-service and graphql-gateway to order-service (REST :8087) and inventory-service (gRPC :9004). Each app pod holds an application container plus an istio-proxy Envoy sidecar: four pods shown in full and four more summarized. Every sidecar ships telemetry to an unmeshed lgtm Deployment, and postgres and kafka sit outside the mesh with no sidecar." caption="Figure 30.1: The mesh data path: each app pod pairs its container with an Envoy sidecar, and sidecar-to-sidecar hops carry mTLS, access logs and trace spans without the application's involvement" %}

## Bringing up the cluster

The scripts in `deploy/k8s/scripts/` run on Docker Engine on a Fedora or RHEL host, with minikube on `--driver=docker --container-runtime=containerd`. Every call names the profile (`minikube -p mea`, `kubectl --context mea`). The two host-facing Services use fixed NodePorts published on the loopback when the profile is created, so nothing depends on a port-forward or tunnel staying up:

```bash
deploy/k8s/scripts/setup-profile.sh   # profile mea; 127.0.0.1:30888 (edge), 127.0.0.1:30300 (Grafana)
deploy/k8s/scripts/install-istio.sh   # istioctl 1.31.1 install -f deploy/k8s/istio/istio-install.yaml
deploy/k8s/scripts/build-images.sh    # mvn package, docker build, minikube image load (x8)
deploy/k8s/scripts/deploy.sh          # kubectl apply -k deploy/k8s/overlays/minikube + rollout status
curl -s http://127.0.0.1:30888/api/inventory
deploy/k8s/scripts/teardown.sh        # minikube stop -p mea when idle
```

Stop any other local cluster first, another minikube profile or OpenShift Local; `setup-profile.sh` refuses to start beside one.

## Installing Istio on minikube

`istioctl` installs the control plane and the injection webhook; the kustomize overlay opts individual Deployments in. `deploy/k8s/istio/istio-install.yaml` is the `IstioOperator` that `istioctl install -f` consumes. It uses `profile: default`: `minimal` omits the ingress gateway and `demo` bundles addons the LGTM stack already replaces. The same file wires the trace pipeline before any sidecar is injected:

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

The `otel` extension provider is how Envoy's own spans reach `lgtm.mea.svc.cluster.local:4317`, the endpoint the three instrumented services export to directly. `telemetry.yaml`, a mesh-wide `Telemetry` resource with no workload selector, turns the provider on for every meshed pod at 100% sampling. That matches the `always_on` sampler on the instrumented services, so a request's mesh spans and app spans reach Tempo at the same fidelity instead of one being dropped when the other is not.

Since Istio 1.29 the sidecar is a **native sidecar**: `istio-proxy` is injected as an `initContainer` with `restartPolicy: Always`, not as an ordinary container ahead of the app. It still starts before the application, runs for the pod's life and counts as the second `2` in `2/2`, but `kubectl describe pod` lists it under `Init Containers`, which can look like a misconfiguration.

Whether a Deployment is meshed at all is the webhook's decision, and it keys off a **label**. The sidecar-injection `MutatingWebhookConfiguration` matches `sidecar.istio.io/inject In ["true"]` against the admitted object's labels, never its annotations. The annotation form silently injects nothing in a namespace that is not itself labeled for auto-injection, and `mea` is not. The fix is a JSON-patch `add` at `/spec/template/metadata/labels`, one per Deployment, on all eight application Deployments:

{% include codetabs.html langs="overlays/minikube/kustomization.yaml: sidecar-inject LABEL patch|istio/istio-install.yaml: OTLP extensionProvider (repeated for contrast)" %}

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

`postgres` and `kafka` get no patch. The overlay's comment gives the reason: a stateful workload's own TLS and connection handling collides with the sidecar's mTLS wrapping, a known issue. Both StatefulSets stay `1/1`, plaintext, reachable only by in-cluster services that already know their DNS names.

## When dry-run lied: six bugs a live apply found

A dry-run proves the YAML is well-formed and admission-valid. It proves nothing about whether the system comes up. Applying the overlay to a live cluster found six bugs in the two StatefulSets. Each is a valid Pod spec that fails to boot, so each is invisible to a schema check.

| # | Component | Shipped | What happened live | The fix |
|---|---|---|---|---|
| 1 | Postgres | `command: ["postgres", "-c", ...]` | `command` replaces the image's `docker-entrypoint.sh`, so `initdb` and `POSTGRES_*` handling never ran and the process started against an uninitialized data directory | `args:` instead of `command:`, which keeps the entrypoint and appends the flags to its `postgres` invocation |
| 2 | Postgres | `fsGroup` matched Debian's `postgres` uid, `999` | `postgres:16-alpine`'s `postgres` user is uid/gid `70` | `fsGroup: 70` |
| 3 | Postgres | `PGDATA` unset (default: the PVC mount root) | the mount `/var/lib/postgresql/data` is owned by `root`, and `fsGroup` sets only the group, so `initdb` as uid 70 could not `chmod` it | `PGDATA=/var/lib/postgresql/data/pgdata`, a subdirectory postgres creates and owns, the standard fix for this image on a mounted volume |
| 4 | Kafka | `runAsNonRoot: true`, no `runAsUser` | the `apache/kafka` image's `USER` is the non-numeric `appuser`, which `kubelet` cannot verify against `runAsNonRoot`; the pod failed with `CreateContainerConfigError` before any process started | `runAsUser: 1000`, the image's real uid |
| 5 | Kafka | default headless DNS; `KAFKA_CONTROLLER_QUORUM_VOTERS` at the Service name; `KAFKA_LOG_DIRS` unset (`/tmp/kafka-logs`) | a headless Service publishes DNS only for ready endpoints, but a single-node KRaft broker must resolve its own controller address to become ready, a startup deadlock; separately, broker state landed in the ephemeral filesystem, not the PVC | `publishNotReadyAddresses: true` on the headless Service; quorum voters at the pod-stable name `kafka-0.kafka.mea.svc.cluster.local:9093`; `KAFKA_LOG_DIRS=/var/lib/kafka/data` plus `fsGroup: 1000` |
| 6 | Kafka | exec readiness and liveness probes with the default `timeoutSeconds: 1` | the probe command `kafka-broker-api-versions.sh` starts its own JVM, which cannot start and answer in one second, so a healthy broker failed both probes and was killed by liveness | `timeoutSeconds: 10` on both |

Each fix carries the same comment tag in the manifests, `live-apply fix`, so the correction is traceable line by line. `command` versus `args`, `runAsNonRoot` without `runAsUser`, an unset `PGDATA` and a 1-second timeout are all valid fields and defaults. The bugs live in the gap between a well-formed object and one that starts on a real image under a real kubelet, and only applying it closes that gap.

## Rolling out mutual TLS

`deploy/k8s/istio/peer-authentication.yaml` is a namespace-wide `PeerAuthentication` named `default`, the name Istio requires for a namespace-scoped policy. It shipped `PERMISSIVE` first, traffic was driven through the mesh and confirmed to use mutual TLS anyway, and only then was the mode flipped to `STRICT`. `PERMISSIVE` accepts plaintext and mTLS on the same port, so a client not yet meshed, or a probe that bypasses Envoy, is not locked out while the policy is validated. `STRICT` makes a meshed pod refuse plaintext once every client is confirmed to arrive through its own sidecar.

`istioctl x describe pod` reports the mode Envoy enforces, from the sidecar's loaded xDS configuration, not from the YAML. Against `order-service` after the flip:

```
# deploy/k8s/observability/evidence/ch30-istioctl-mtls.txt
Pod: order-service-748b9bd7db-d4sb5
Effective PeerAuthentication:
   Workload mTLS mode: STRICT
Applied PeerAuthentication:
   default.mea
```

The receiving sidecar enforces `PeerAuthentication`. That is why leaving `postgres` and `kafka` out of the mesh is consistent: they have no `istio-proxy`, so a mesh-wide `STRICT` policy cannot reach them, and they keep accepting plaintext from their meshed clients. The `istio_requests_total` metric carries a `connection_security_policy` label per edge. Every application-to-application edge in the captured data (`strangler-proxy` to `review-service`, `order-service` to `inventory-service`, `graphql-gateway` to the order, payment, review and shipping services) reports `mutual_tls`, which confirms independently of `istioctl` that those requests were encrypted, not merely eligible to be.

## The LGTM stack on Kubernetes

`deploy/k8s/observability/` runs the same `grafana/otel-lgtm` all-in-one image compose has run (Collector, Tempo, Loki, Mimir and Grafana in one container) as a single-replica Deployment. The Collector and Grafana datasource configs are the files compose bind-mounts, delivered as ConfigMaps at the paths the image's startup script expects:

```yaml
# deploy/k8s/observability/lgtm-deployment.yaml
      containers:
        - name: lgtm
          image: docker.io/grafana/otel-lgtm:0.36.0
          volumeMounts:
            - name: otelcol-config
              mountPath: /otel-lgtm/otelcol-config.yaml
              subPath: otelcol-config.yaml
            - name: grafana-datasources
              mountPath: /otel-lgtm/grafana/conf/provisioning/datasources/grafana-datasources.yaml
              subPath: datasources.yaml
```

Two details matter on current images. The startup script waits for the Collector's `health_check` extension on `:13133/ready`, so a custom Collector config must enable it and name its exporters as the image does (`otlp_http/traces`, `otlp_http/metrics`, `otlp_http/logs`), or the container never reports ready. And the datasources file is mounted over the image's own `grafana-datasources.yaml`, because Grafana loads the directory in lexical order and the later file wins a uid collision, so a differently named file silently lost.

One addition on top of the compose config makes mesh metrics visible: a second Prometheus receiver that scrapes every `istio-proxy` sidecar's stats endpoint through Kubernetes service discovery.

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

Each Envoy exposes Prometheus stats on `:15090/stats/prometheus`: `istio_requests_total`, `istio_request_duration_milliseconds` and the rest of the standard L7 set, with no mesh-side configuration. The receiver discovers pods carrying an `istio-proxy` container, native-sidecar or legacy, and scrapes that one port. Discovery needs the Collector's pod to list and watch pods in `mea`; `rbac.yaml` grants exactly that through a dedicated `lgtm` ServiceAccount bound to a `ClusterRole` limited to `get`, `list` and `watch` on `pods`, `endpoints` and `services`. The `lgtm` Deployment carries no inject label, for the reason `postgres` and `kafka` do not: it receives the mesh's telemetry and is not a participant being observed.

A naming bug sits in this stack's history. The Service port that Istio's OTLP exporter and every instrumented service's `quarkus.otel.exporter.otlp.traces.endpoint` target was named `otlp-grpc`. Envoy detects a port's protocol from the prefix before the first hyphen in its name, and `grpc`, `http` and `tcp` are recognized tokens where `otlp` is not. Envoy treated the connection as opaque TCP, dropped HTTP/2 framing and failed every OTLP/gRPC export. `istioctl proxy-config cluster` showed `outbound|4317||lgtm.mea.svc.cluster.local` with `rq_total` equal to `rq_error`. The port in `lgtm-service.yaml` is now `grpc-otlp`, with the recognized token first, and the file's comment records the failure.

## The limit of mesh-only tracing

Envoy emits a span for every meshed hop regardless of whether the application knows OpenTelemetry exists. `review-service`, `payment-service` and `shipping-service` appear in the captured trace as Envoy client and server span pairs, named like `payment-service.mea.svc.cluster.local:8085/*`, with no application span inside. None of the three carries `quarkus-opentelemetry`.

What the mesh cannot do alone is explain in business terms why a request went where it did, or join spans from two applications into one trace unless something upstream started the trace and propagated its context. Envoy reads and forwards the trace-context headers (`traceparent` and friends) a request arrives with. It does not invent an unrelated trace per hop, and it does not stitch hops together when nobody supplied a trace ID. The three instrumented modules (`graphql-gateway`, `order-service`, `inventory-service`) start the trace and name spans (`POST /graphql`, `GET /api/orders/{id}`, the gRPC method); every hop downstream rides on that context. Propagation is the application's job. The mesh amplifies a trace that has started and cannot start one.

## Instrumenting the trace path

`examples/04-inventory-service`, `examples/07-order-service` and `examples/08-graphql-gateway` carry `quarkus-opentelemetry`. They sit on the request path worth tracing end to end: a GraphQL aggregation query fanning out from the gateway to order, inventory, review, payment and shipping. The change is one dependency and three properties, with no business logic touched.

{% include codetabs.html langs="order-service/pom.xml: the one new dependency|order-service/application.properties: OTLP exporter + demo sampler" %}

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
# Additive: OpenTelemetry traces to the mesh LGTM collector. Demo
# sampler (always_on) -- not production-appropriate, this is for a live
# connected-trace capture across exactly 3 instrumented modules.
quarkus.otel.exporter.otlp.traces.endpoint=http://lgtm.mea.svc.cluster.local:4317
quarkus.otel.service.name=order-service
quarkus.otel.traces.sampler=always_on
```

`graphql-gateway` and `inventory-service` carry the same block with only `quarkus.otel.service.name` changed. `always_on` is a demo sampler: production samples a small percentage to bound collector load and storage. Once the extension is on the classpath, Quarkus instruments the JAX-RS server side, the REST client side and gRPC automatically. `RemoteInventoryClient`'s three `withDeadlineAfter` call sites, `GatewayApi`'s four `@RestClient` fields and the generated GraphQL resolvers needed no span code.

## Reading the signals

Thirty synchronous checkouts through the strangler proxy and fifteen GraphQL aggregation queries against the gateway generated the traffic; each checkout returned `202 Accepted` and each query `200 OK`. The cumulative `istio_requests_total` counters run slightly higher on those edges because they also count warm-up requests from bringing the cluster up, including one `400` from before the customer fixture was seeded. One query was pulled from Tempo by trace ID, `af8b2d9f78b2e8a796b1fd2b472aede3`: one root span, five downstream branches, Envoy and application spans interleaved at every hop.

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

The `order-service` and `inventory-service` branches end in an application span (`GET /api/orders/{id}`, `GetStock`). The `review-service`, `payment-service` and `shipping-service` branches stop at the Envoy server span: the mesh produced a span for the hop, with no business-operation name beneath it. The raw export holds 20 span records for 19 distinct span IDs, because the `order-service` span for `GET /api/orders/{id}` appears in two OTLP resource batches with identical content, an export artifact and not a second request. The tree shows the 19 distinct spans.

Metrics show the same traffic from another angle. `istio_requests_total`, scraped by the `prometheus/istio-mesh` job and stored in Mimir, reports every application edge the traffic used, each with `connection_security_policy="mutual_tls"`:

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

Two things stay in the table. Both `strangler-proxy` to `review-service` and `graphql-gateway` to `review-service` show `code=500`, consistently: `review-service` was returning server errors on this cluster. That is a pre-existing application defect, not a mesh or tracing problem, and the mesh reports the failure behind it faithfully, which is the job of an observability layer. And `order-service` to `inventory-service` and `shipping-service` to `order-service` each carry a duplicate row labeled `mtls_policy=unknown` at the same count. Istio emits a mesh-attributed and a policy-unlabeled copy depending on which side reported; the `mutual_tls` row is the one that confirms encryption, and it exists for every edge.

Two gaps remain:

- **Application logs.** Loki received none. The `otel-lgtm` Collector runs inside its own pod and no Alloy or other node-level log shipper exists in the tree, so nothing reads another pod's stdout. Logs come from `kubectl logs <pod>`, not Grafana's Loki panel.
- **Saga hops over Kafka.** The payment and shipping extractions run over Kafka (`payment.captured`, `shipment.failed` and the rest) and are invisible to this tracing. Istio's L7 tracing instruments HTTP and gRPC and has no concept of a Kafka topic, so none of the five consuming services' processing appears as a span. That needs Kafka-aware instrumentation inside the consumers.

{% include excalidraw.html file="observability-trace-pipeline" alt="A two-band diagram. Top band: spans and metrics flow from application containers (app-level OpenTelemetry on graphql-gateway, order-service, and inventory-service) and from istio-proxy sidecars across the mea namespace, through the OpenTelemetry Collector's OTLP and Prometheus-scrape receivers, into Tempo (traces) and Mimir (metrics) inside the single lgtm Deployment, with Loki (logs) drawn as configured-but-not-live; Grafana reads all three. Bottom band: the captured trace, root graphql-gateway POST /graphql fanning into an order-service REST branch and an inventory-service gRPC branch, with app-level spans nested inside Envoy sidecar spans at their real durations." caption="Figure 30.2: From sidecar and app spans to a queryable trace: the OTLP and Prometheus paths into the lgtm Deployment, and the shape of the trace they produced" %}

> **Decision**
>
> Mesh a namespace for mTLS and Envoy telemetry when you want identity and hop-level visibility without code changes; leave stateful workloads out. Add `quarkus-opentelemetry` to every service that must start or name a span, because the mesh cannot. Roll `PeerAuthentication` out `PERMISSIVE` and flip to `STRICT` only after `istioctl x describe pod` shows traffic using mTLS. Count a manifest verified only after a live apply.

> **🤖 Explore with your agent**
>
> "Add quarkus-opentelemetry and the three quarkus.otel properties to examples/05-payment-service with service name payment-service, following examples/07-order-service, then redeploy with deploy/k8s/scripts/build-images.sh and deploy.sh. The gateway is ClusterIP-only, so send a GraphQL `order(id)` query that selects `payments` to `http://graphql-gateway.mea.svc.cluster.local:8090/graphql` from inside the namespace with `kubectl --context mea -n mea exec`, and find its trace in Tempo."
>
> Check: on Grafana at `http://127.0.0.1:30300`, the trace's payment-service branch now ends in an application `GET /api/payments` span beneath the Envoy server span instead of stopping at Envoy, and `istioctl x describe pod` on the payment pod still reports STRICT.

## Build, run, observe

Bring the cluster up with the scripts in "Bringing up the cluster", then confirm the mesh and the evidence:

```bash
kubectl --context mea -n mea get pods          # 2/2 for the eight application pods, 1/1 for postgres, kafka
istioctl x describe pod -n mea <order-service-pod>   # Workload mTLS mode: STRICT
```

Grafana is on `http://127.0.0.1:30300`, with the edge router on `http://127.0.0.1:30888`. Tempo shows the traces and Mimir the `istio_requests_total` series.

- Opt a Deployment in with the `sidecar.istio.io/inject` annotation instead of the label: it injects nothing and stays `1/1`.
- Rename the LGTM Service's OTLP port to `otlp-grpc`: Envoy treats it as opaque TCP and every OTLP/gRPC export fails.
- Use `command:` instead of `args:` on Postgres: the entrypoint is bypassed and the data directory is never initialized.
- Set the Kafka probe `timeoutSeconds` back to 1: a healthy broker fails liveness and is killed.

## For deeper coverage

- [Istio documentation: Mutual TLS Migration](https://istio.io/latest/docs/tasks/security/authentication/mtls-migration/): the `PERMISSIVE`-before-`STRICT` rollout sequence the `PeerAuthentication` policy follows.
- [Istio documentation: Observability](https://istio.io/latest/docs/concepts/observability/): the sidecar-produced access logs, metrics, and trace spans read here off `istio-proxy` without any application involvement.
- [OpenTelemetry documentation: Context Propagation](https://opentelemetry.io/docs/concepts/context-propagation/): why a mesh can amplify a trace already underway but cannot start one or carry business-level context on its own.
- [Quarkus: OpenTelemetry guide](https://quarkus.io/guides/opentelemetry): the `quarkus-opentelemetry` extension and the three properties used here to instrument three services with no manual span code.
- Cloud Native Design Patterns, [Observability](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/11-observability.md): the companion book's treatment of the trace/metric/log pipeline built here on minikube.
