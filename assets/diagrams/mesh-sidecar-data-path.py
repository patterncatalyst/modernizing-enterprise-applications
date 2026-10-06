#!/usr/bin/env python3
"""ch.30 figure: mesh-sidecar-data-path — how a request actually moves
through the Istio mesh in the `mea` namespace, for the three hops that
produced the captured ch.30 hero trace.

Every one of the 8 app Deployments carries a pod-template label
`sidecar.istio.io/inject: "true"` (deploy/k8s/overlays/minikube/
kustomization.yaml) — Istio's injection webhook matches on that label, not
an annotation. This figure draws the 4 pods on the captured path in full
(app-container + istio-proxy sidecar each) and summarizes the other 4
(review-, notification-, payment-, shipping-service — same pattern, omitted
for space) in one box.

Data path, straight from the evidence:
  - client -> strangler-proxy, over the NodePort patch (overlays/minikube);
    the client has no Istio identity, so this hop is plain HTTP, not mTLS.
  - strangler-proxy -> order-service (ch30-metrics.txt:
    mtls_policy=mutual_tls, 33 requests across 3 codes)
  - graphql-gateway -> order-service, REST :8087 (ch30-metrics.txt: count=16,
    mutual_tls; ch30-hero-trace.json: two envoy spans, CLIENT then SERVER,
    5.4ms / 4.5ms)
  - graphql-gateway -> inventory-service, gRPC :9004 (ch30-hero-trace.json:
    envoy CLIENT/SERVER spans at graphql-gateway.mea / inventory-service.mea,
    4.9ms / 4.0ms; the InventoryGrpcService/GetStock app span wraps it)

Every meshed hop above is mTLS (security.istio.io/v1 PeerAuthentication
`default` in `mea`, mode STRICT — deploy/k8s/istio/peer-authentication.yaml,
confirmed live in ch30-istioctl-mtls.txt: "Workload mTLS mode: STRICT").
PeerAuthentication is enforced by the receiving Envoy, so it only ever
applies between two sidecars — never to the client's first hop in.

postgres and kafka (deploy/k8s/base/infra/*-statefulset.yaml) carry no
inject label, so they sit outside the mesh entirely: no sidecar, no mTLS,
plain JDBC/Kafka wire protocol — same reasoning the lgtm Deployment's own
comment gives for staying unmeshed (deploy/k8s/observability/
lgtm-deployment.yaml: "it is the thing receiving mesh telemetry, not a
participant being observed").

Every istio-proxy sidecar ships its own spans + access logs to the lgtm pod
over OTLP (Telemetry `mesh-default`, deploy/k8s/istio/telemetry.yaml,
randomSamplingPercentage: 100 — see observability-trace-pipeline.py for the
pipeline this feeds).

Sourced from: deploy/k8s/istio/{peer-authentication,telemetry}.yaml,
deploy/k8s/overlays/minikube/kustomization.yaml,
deploy/k8s/observability/{lgtm-deployment,lgtm-service}.yaml,
deploy/k8s/observability/evidence/{ch30-hero-trace.json,ch30-metrics.txt,
ch30-istioctl-mtls.txt}. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1820, 1180

# ============================================================================
# client — outside the cluster, outside the mesh
# ============================================================================
client = node(80, 80, 300, 80,
              ["External client", "curl / browser -> NodePort :8888"], style="user")

# ============================================================================
# namespace band — everything else in this figure lives in `mea`
# ============================================================================
ns_band = {"x": 40, "y": 225, "w": 1740, "h": 845,
           "label": "Kubernetes namespace: mea", "fill": "#ffffff"}

mesh_note = {"x": 70, "y": 268,
             "text": "Istio mesh — sidecar.istio.io/inject: \"true\" on all 8 app Deployments; "
                     "PeerAuthentication \"default\" in mea, mode STRICT (4 of 8 pods shown)",
             "anchor": "start", "size": 12, "color": "#2f5f3d", "bold": True}

# ---- 4 pods on the captured path, each its own band -------------------------
POD_W, GAP, X0, POD_Y, POD_H = 300, 25, 70, 295, 300
STRIDE = POD_W + GAP


def col(i):
    return X0 + i * STRIDE


def pod(i, title, app_lines, proxy_lines):
    band = {"x": col(i), "y": POD_Y, "w": POD_W, "h": POD_H,
            "label": f"Pod: {title}", "fill": "#eaf4ec"}
    app_box = node(col(i) + 20, POD_Y + 45, POD_W - 40, 105, app_lines, style="ink")
    proxy_box = node(col(i) + 20, POD_Y + 175, POD_W - 40, 95, proxy_lines, style="accent")
    return band, app_box, proxy_box


strangler_band, strangler_app, strangler_proxy = pod(
    0, "strangler-proxy",
    ["app-container: strangler-proxy", "Camel REST DSL — edge router"],
    ["istio-proxy", "Envoy sidecar"])

order_band, order_app, order_proxy = pod(
    1, "order-service",
    ["app-container: order-service", "REST :8087 — app-level OTel"],
    ["istio-proxy", "Envoy sidecar"])

gateway_band, gateway_app, gateway_proxy = pod(
    2, "graphql-gateway",
    ["app-container: graphql-gateway", "GraphQL :8090 — app-level OTel"],
    ["istio-proxy", "Envoy sidecar"])

inventory_band, inventory_app, inventory_proxy = pod(
    3, "inventory-service",
    ["app-container: inventory-service", "gRPC :9004 — app-level OTel"],
    ["istio-proxy", "Envoy sidecar"])

other_band = {"x": col(4), "y": POD_Y, "w": POD_W, "h": POD_H,
              "label": "+4 more meshed pods", "fill": "#eaf4ec"}
other_box = node(col(4) + 20, POD_Y + 45, POD_W - 40, POD_H - 65,
                  ["review-service", "notification-service", "payment-service",
                   "shipping-service", "— same app-container +", "istio-proxy pattern"],
                  style="ghost")

pod_bands = [strangler_band, order_band, gateway_band, inventory_band, other_band]
pod_nodes = [strangler_app, strangler_proxy, order_app, order_proxy,
             gateway_app, gateway_proxy, inventory_app, inventory_proxy, other_box]

# in-pod intercept: app-container <-> its own istio-proxy (iptables redirect,
# not mTLS — same pod, same network namespace)
intercept_edges = [
    connect(strangler_app, strangler_proxy),
    connect(order_app, order_proxy),
    connect(gateway_app, gateway_proxy),
    connect(inventory_app, inventory_proxy),
]

# ============================================================================
# the mesh data path — sidecar to sidecar, every hop mTLS (STRICT)
# ============================================================================
client_edge = {**connect(client, strangler_band), "label": "NodePort :8888 (client not meshed — plain HTTP)"}

mesh_edges = [
    {**connect(strangler_proxy, order_proxy, amber=True, lx=0, ly=-22), "label": "mTLS (STRICT)"},
    {**connect(gateway_proxy, order_proxy, amber=True, lx=0, ly=-22),
     "label": "mTLS (STRICT) · REST :8087"},
    {**connect(gateway_proxy, inventory_proxy, amber=True, lx=0, ly=-22),
     "label": "mTLS (STRICT) · gRPC :9004"},
]

# ============================================================================
# telemetry fan-out — every sidecar ships spans + access logs to lgtm
# ============================================================================
LGTM_Y = POD_Y + POD_H + 70
lgtm = node((W - 360) / 2, LGTM_Y, 360, 110,
            ["lgtm (Deployment)", "otel-lgtm:0.8.1 — Collector+Tempo+Mimir(+Loki)+Grafana",
             "no sidecar — receives telemetry, isn't measured"], style="sub")

telemetry_sources = [strangler_proxy, order_proxy, gateway_proxy, inventory_proxy, other_box]
TELEMETRY_BUS_Y = POD_Y + POD_H + 36
telemetry_edges = []
lgtm_top_cx = lgtm["x"] + lgtm["w"] / 2
xs = [s["x"] + s["w"] / 2 for s in telemetry_sources] + [lgtm_top_cx]
for src in telemetry_sources:
    src_cx = src["x"] + src["w"] / 2
    telemetry_edges.append({
        "x1": src_cx, "y1": src["y"] + src["h"],
        "x2": src_cx, "y2": TELEMETRY_BUS_Y,
        "dashed": True,
    })
telemetry_edges.append({
    "x1": min(xs), "y1": TELEMETRY_BUS_Y, "x2": max(xs), "y2": TELEMETRY_BUS_Y,
    "dashed": True,
    "label": "telemetry (OTLP spans + access logs), not request traffic",
})
telemetry_edges.append({
    "x1": lgtm_top_cx, "y1": TELEMETRY_BUS_Y, "x2": lgtm_top_cx, "y2": lgtm["y"],
    "dashed": True,
})

# ============================================================================
# outside the mesh — postgres + kafka, no sidecar
# ============================================================================
INFRA_Y = LGTM_Y + 110 + 70
infra_band = {"x": 70, "y": INFRA_Y - 40, "w": 1680, "h": 170,
              "label": "Outside the Istio mesh — no sidecar, plaintext (deploy/k8s/base/infra)",
              "fill": "#fafafa"}
postgres = node(260, INFRA_Y, 600, 90,
                ["postgres", "StatefulSet — no sidecar.istio.io/inject label"], style="sub")
kafka = node(1000, INFRA_Y, 600, 90,
             ["kafka", "StatefulSet — no sidecar.istio.io/inject label"], style="sub")

infra_note = {"x": 90, "y": INFRA_Y + 125,
              "text": "order-service and inventory-service also reach postgres (:5432) and kafka (:9092) directly — "
                      "plaintext, since neither carries a sidecar.",
              "anchor": "start", "size": 11, "color": "#555555"}

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.30 — mesh sidecar data path: the captured hops, sidecar to sidecar",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "client -> strangler-proxy -> order-service, and graphql-gateway -> order-service (REST) + inventory-service (gRPC) — every meshed hop is mTLS (STRICT)",
     "anchor": "middle", "size": 11.5, "color": "#555555"},
    mesh_note,
    infra_note,
    {"x": 60, "y": H - 20,
     "text": "Sourced from deploy/k8s/istio/{peer-authentication,telemetry}.yaml, overlays/minikube/kustomization.yaml, "
             "deploy/k8s/observability/{lgtm-deployment,lgtm-service}.yaml, and evidence/{ch30-hero-trace.json,ch30-metrics.txt,ch30-istioctl-mtls.txt}.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "mesh-sidecar-data-path", W, H,
    bands=[ns_band, infra_band] + pod_bands,
    nodes=[client, lgtm, postgres, kafka] + pod_nodes,
    edges=[client_edge] + intercept_edges + mesh_edges + telemetry_edges,
    notes=notes,
)
