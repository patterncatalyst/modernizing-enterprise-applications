#!/usr/bin/env python3
"""ch.30 figure: observability-trace-pipeline — the telemetry pipeline that
produced the captured ch.30 hero trace, and the trace shape it produced.

TOP band — the pipeline. Two independent span sources feed the same
collector over OTLP:
  - Envoy sidecar spans: every mesh hop, mesh-wide (Telemetry `mesh-default`,
    deploy/k8s/istio/telemetry.yaml, randomSamplingPercentage: 100, routed to
    the `otel` extensionProvider).
  - App-level OpenTelemetry spans: only the 3 instrumented services
    (graphql-gateway, order-service, inventory-service — the only 3 with
    quarkus.otel.exporter.otlp.traces.endpoint set, each pointed at
    lgtm.mea.svc.cluster.local:4317, quarkus.otel.traces.sampler=always_on).

A third input, the istio-proxy Prometheus scrape (otelcol-configmap.yaml's
`prometheus/istio-mesh` receiver — Kubernetes SD over pods in `mea`, scraping
:15090/stats/prometheus), lands in the same collector's metrics pipeline
alongside the OTLP metrics, both exported to Mimir.

The otel-lgtm:0.8.1 image is one container — Collector + Tempo + Mimir +
Loki + Grafana co-located (lgtm-deployment.yaml). The collector's logs
pipeline (receivers: [otlp], exporter: otlphttp/loki) is configured and
wired in otelcol-configmap.yaml, but nothing in this tree emits OTLP log
records today — no app here uses an OTel log appender — so Loki is
configured, not live, drawn ghost. Grafana reads Tempo, Mimir, and Loki as
three datasources (grafana-datasources-configmap.yaml).

BOTTOM band — the trace shape this pipeline actually produced, ground truth
from deploy/k8s/observability/evidence/ch30-hero-trace.json: root
`graphql-gateway POST /graphql` (app SERVER span) contains two call
branches, each alternating app -> Envoy -> Envoy -> app as the request
crosses the mesh and lands in the callee's own OTel instrumentation —
exactly the "envoy_sidecar_resource" row Tempo flattens into one
waterfall when the mesh and app both export to the same collector.
Durations are the real captured span durations (nanosecond timestamps in
the trace, rounded to hundredths of a ms).

Sourced from: deploy/k8s/istio/telemetry.yaml,
deploy/k8s/observability/{otelcol-configmap,lgtm-deployment,lgtm-service,
grafana-datasources-configmap}.yaml, examples/{04-inventory-service,
07-order-service,08-graphql-gateway}/src/main/resources/
application.properties, and evidence/ch30-hero-trace.json. No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1780, 1430

# ============================================================================
# TOP band — the pipeline: two span sources + one scrape, one collector,
# three backends, one reader
# ============================================================================
band_top = {"x": 30, "y": 70, "w": 1720, "h": 560,
            "label": "Telemetry pipeline — two span sources, one collector, three backends",
            "fill": "#fafafa"}

envoy_src = node(60, 150, 360, 110, style="accent", lines=[
    "Envoy sidecar spans",
    "every mesh hop — Telemetry \"mesh-default\"",
    "randomSamplingPercentage: 100",
])
app_src = node(60, 290, 360, 110, style="user", lines=[
    "App-level OpenTelemetry spans",
    "graphql-gateway, order-service, inventory-service",
    "quarkus.otel.traces.sampler=always_on",
])
prom_src = node(60, 430, 360, 110, style="sub", lines=[
    "istio-proxy Prometheus scrape",
    "prometheus/istio-mesh receiver",
    "GET :15090/stats/prometheus, pods in mea",
])

lgtm_band = {"x": 500, "y": 110, "w": 1220, "h": 480,
             "label": "Pod: lgtm — single container (grafana/otel-lgtm:0.8.1)",
             "fill": "#ffffff"}

collector = node(880, 160, 360, 110, style="ink", lines=[
    "otel-lgtm collector",
    "OTLP receiver :4317 (grpc) / :4318 (http)",
    "+ prometheus/istio-mesh scrape target",
])

tempo = node(560, 330, 320, 100, style="sub", lines=["Tempo", "traces — otlphttp :4418"])
mimir = node(920, 330, 320, 100, style="sub", lines=["Mimir", "metrics — otlphttp :9090/api/v1/otlp"])
loki = node(1280, 330, 320, 100, style="ghost", lines=["Loki", "logs — otlphttp :3100/otlp", "configured, not live"])

grafana = node(880, 480, 360, 90, style="accent", lines=[
    "Grafana", "reads Tempo + Mimir + Loki as datasources",
])

top_nodes = [envoy_src, app_src, prom_src, collector, tempo, mimir, loki, grafana]

top_edges = [
    connect(envoy_src, collector, amber=True, label="OTLP spans"),
    connect(app_src, collector, label="OTLP spans"),
    {**connect(prom_src, collector, dashed=True), "label": "scrape"},
    connect(collector, tempo, amber=True, label="traces"),
    connect(collector, mimir, label="metrics"),
    {**connect(collector, loki, dashed=True), "label": "logs (unused path)"},
    connect(tempo, grafana),
    connect(mimir, grafana),
    {**connect(loki, grafana, dashed=True)},
]

# ============================================================================
# BOTTOM band — the real captured trace shape (ch30-hero-trace.json)
# ============================================================================
band_bottom = {"x": 30, "y": 660, "w": 1720, "h": 720,
               "label": "The captured trace — app spans nested inside Envoy spans (evidence/ch30-hero-trace.json)",
               "fill": "#eaf4ec"}

root = node(660, 720, 460, 90, style="user", lines=[
    "graphql-gateway — POST /graphql (SERVER)",
    "83.75ms · resolves via GraphQL execution, 82.08ms",
])

# ---- branch A: order-service, REST ------------------------------------------
a1 = node(120, 850, 480, 90, style="user", lines=[
    "graphql-gateway — GET /api/orders/{id} (CLIENT)", "6.35ms"])
a2 = node(160, 960, 480, 90, style="accent", lines=[
    "envoy (graphql-gateway) -> order-service:8087 (CLIENT)", "5.41ms"])
a3 = node(200, 1070, 480, 90, style="accent", lines=[
    "envoy (order-service) :8087 (SERVER)", "4.53ms"])
a4 = node(240, 1180, 480, 90, style="user", lines=[
    "order-service — GET /api/orders/{id} (SERVER)", "3.58ms"])

# ---- branch B: inventory-service, gRPC --------------------------------------
b1 = node(1160, 850, 480, 90, style="user", lines=[
    "graphql-gateway — InventoryGrpcService/GetStock (CLIENT, gRPC)", "67.70ms"])
b2 = node(1120, 960, 480, 90, style="accent", lines=[
    "envoy (graphql-gateway) -> inventory-service:9004 (CLIENT)", "4.85ms"])
b3 = node(1080, 1070, 480, 90, style="accent", lines=[
    "envoy (inventory-service) :9004 (SERVER)", "3.97ms"])
b4 = node(1040, 1180, 480, 90, style="user", lines=[
    "inventory-service — GetStock (SERVER, gRPC)", "2.29ms"])

bottom_nodes = [root, a1, a2, a3, a4, b1, b2, b3, b4]
bottom_edges = [
    connect(root, a1),
    connect(a1, a2),
    connect(a2, a3),
    connect(a3, a4),
    connect(root, b1),
    connect(b1, b2),
    connect(b2, b3),
    connect(b3, b4),
]

legend = {"x": 60, "y": 700,
          "text": "blue = app-level OpenTelemetry span · green = Envoy sidecar span (mesh hop) · indent = call depth, not drawn to time scale",
          "anchor": "start", "size": 11.5, "color": "#555555"}

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.30 — observability trace pipeline: two span sources, one collector, the trace they produced",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "Envoy sidecar spans + app-level OTel spans both export OTLP to the same otel-lgtm collector — Tempo/Mimir/Loki store, Grafana reads",
     "anchor": "middle", "size": 11.5, "color": "#555555"},
    legend,
    {"x": 60, "y": H - 20,
     "text": "Sourced from deploy/k8s/istio/telemetry.yaml, deploy/k8s/observability/{otelcol-configmap,lgtm-deployment,lgtm-service,"
             "grafana-datasources-configmap}.yaml, examples/{04,07,08}'s application.properties, and evidence/ch30-hero-trace.json.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "observability-trace-pipeline", W, H,
    bands=[band_top, lgtm_band, band_bottom],
    nodes=top_nodes + bottom_nodes,
    edges=top_edges + bottom_edges,
    notes=notes,
)
