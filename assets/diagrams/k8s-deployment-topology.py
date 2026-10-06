#!/usr/bin/env python3
"""ch.29 figure: k8s-deployment-topology — the end-state ch.29 topology once
`kubectl apply -k deploy/k8s/overlays/minikube` lands: 8 app Deployments
(each behind its own ClusterIP Service; strangler-proxy's Service alone is
patched to NodePort by the minikube overlay) plus 2 infra StatefulSets, all
in the `mea` namespace.

App tier (top): strangler-proxy is the single NodePort entry (base/
strangler-proxy.yaml's Service is ClusterIP; overlays/minikube/
kustomization.yaml's patch flips only that one Service to NodePort — every
other Service here stays ClusterIP-only, per that file's own comment on
exposing "the one Service this overlay exposes off-cluster"). Its Camel
`.choice()` route (examples/01-strangler-proxy/.../StranglerProxyRoute.java)
dispatches by URI path prefix to the six extracted services — the real
`startsWith` prefixes it matches on. graphql-gateway is NOT one of those six
targets (grepped: no `/graphql` branch in StranglerProxyRoute) — it sits in
the app tier with its own ClusterIP Service and no ingress route of its own
in this manifest set.

The two gRPC edges (order-service, graphql-gateway → inventory-service
:9004) are each service's own `@GrpcClient`/REST-client config pointed at
`INVENTORY_GRPC_HOST`/`PORT` or `QUARKUS_GRPC_CLIENTS_INVENTORY_HOST`/`PORT`
in `mea-app-config` (see app-config.yaml's header comment on the
ordinal-override vs. native-placeholder split for these two keys).

Infra tier (bottom): postgres and kafka are both headless-Service-backed
StatefulSets (`clusterIP: None`), not Deployments — mirroring
infra/postgres-statefulset.yaml / infra/kafka-statefulset.yaml. Six of the
eight app Deployments reach postgres on :5432 (one schema per service,
reader review-service: the one exception, on the shared `public` schema —
see app-config.yaml's per-service `*_SERVICE_JDBC_URL` keys). Five reach
kafka on :9092 — the same five whose Deployments carry
`terminationGracePeriodSeconds: 45` + a 10s preStop sleep for a clean Kafka
`LeaveGroupRequest` (notification/inventory/payment/shipping/order; see
each one's own manifest comment on this discipline) — review-service,
strangler-proxy, and graphql-gateway are not Kafka clients.

Edges are deliberately summarized as three fan-in/fan-out buses (gRPC, DB,
Kafka) rather than one line per pair, to keep eleven DNS edges legible
instead of a wall of crossing lines.

Sourced from: deploy/k8s/base/*.yaml, deploy/k8s/base/infra/*.yaml,
deploy/k8s/base/app-config.yaml, deploy/k8s/base/app-secret.yaml,
deploy/k8s/overlays/minikube/kustomization.yaml, deploy/k8s/README.md,
examples/01-strangler-proxy/.../StranglerProxyRoute.java. No codenames;
generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1780, 1080

# ============================================================================
# namespace band — everything in this figure lives in `mea`
# ============================================================================
ns_band = {"x": 40, "y": 70, "w": 1700, "h": 950,
           "label": "Kubernetes namespace: mea  (deploy/k8s/base + overlays/minikube)",
           "fill": "#ffffff"}

# ---- client + strangler-proxy: the single NodePort entry -------------------
client = node(70, 140, 260, 70, ["External client", "reaches the cluster via NodePort"], style="user")

proxy = node(70, 240, 1380, 110,
             ["strangler-proxy", "Service: ClusterIP :8888 — NodePort (overlays/minikube patch)",
              "tcpSocket probes only — no quarkus-smallrye-health"], style="ink")

# ---- the 7 remaining app-tier boxes, one row --------------------------------
COL_W, GUTTER, COL_X0, ROW_Y, ROW_H = 210, 24, 70, 420, 120
STRIDE = COL_W + GUTTER


def col(i):
    return COL_X0 + i * STRIDE


review = node(col(0), ROW_Y, COL_W, ROW_H,
              ["review-service", "ClusterIP :8081", "tcpSocket probes only"], style="box")
notification = node(col(1), ROW_Y, COL_W, ROW_H,
                     ["notification-service", "ClusterIP :8083", "httpGet /q/health/*"], style="box")
inventory = node(col(2), ROW_Y, COL_W, ROW_H,
                 ["inventory-service", "ClusterIP :8084 + gRPC :9004", "httpGet /q/health/*"], style="box")
payment = node(col(3), ROW_Y, COL_W, ROW_H,
               ["payment-service", "ClusterIP :8085", "httpGet /q/health/*"], style="box")
shipping = node(col(4), ROW_Y, COL_W, ROW_H,
                ["shipping-service", "ClusterIP :8088", "httpGet /q/health/*"], style="box")
order = node(col(5), ROW_Y, COL_W, ROW_H,
             ["order-service", "ClusterIP :8087", "httpGet /q/health/*"], style="box")
gateway = node(col(6), ROW_Y, COL_W, ROW_H,
               ["graphql-gateway", "ClusterIP :8090 + gRPC :9093", "httpGet /q/health/*",
                "no ingress route — ClusterIP only"], style="accent")

svc_row = [review, notification, inventory, payment, shipping, order, gateway]

# ---- strangler-proxy -> six context services, by path ----------------------
path_edges = [
    connect(proxy, review, label="/api/reviews"),
    connect(proxy, notification, label="/api/notifications"),
    connect(proxy, inventory, label="/api/inventory"),
    connect(proxy, payment, label="/api/payments"),
    connect(proxy, shipping, label="/api/shipments"),
    connect(proxy, order, label="/api/orders"),
]
client_edge = [connect(client, proxy, label="single entry point")]

# ============================================================================
# three summarized buses: gRPC, DB, Kafka — fan multiple sources into one sink
# ============================================================================
def bus_fan_in(sources, sink, bus_y, label=None, dashed=False):
    edges = []
    sink_cx = sink["x"] + sink["w"] / 2
    xs = [s["x"] + s["w"] / 2 for s in sources] + [sink_cx]
    lo, hi = min(xs), max(xs)
    for s in sources:
        cx = s["x"] + s["w"] / 2
        edges.append({"x1": cx, "y1": s["y"] + s["h"], "x2": cx, "y2": bus_y, "amber": True, "dashed": dashed})
    edges.append({"x1": lo, "y1": bus_y, "x2": hi, "y2": bus_y, "amber": True, "dashed": dashed, "label": label})
    edges.append({"x1": sink_cx, "y1": bus_y, "x2": sink_cx, "y2": sink["y"], "amber": True, "dashed": dashed})
    return edges


GRPC_BUS_Y, DB_BUS_Y, KAFKA_BUS_Y = 580, 620, 670

# ---- infra tier --------------------------------------------------------------
infra_band = {"x": 60, "y": 740, "w": 1660, "h": 250,
              "label": "Infra tier — StatefulSets, headless Services (clusterIP: None)", "fill": "#fafafa"}

postgres = node(140, 800, 620, 140,
                ["postgres", "StatefulSet, 1 replica — db \"monolith\"",
                 "PVC 2Gi · wal_level=logical",
                 "6 app services connect here on :5432"], style="sub")
kafka = node(900, 800, 620, 140,
             ["kafka", "StatefulSet, 1 replica — KRaft broker",
              "PVC 2Gi · topics auto-created",
              "5 Kafka-consuming services on :9092"], style="sub")

grpc_edges = bus_fan_in([order, gateway], inventory, GRPC_BUS_Y,
                        label="order-service + graphql-gateway -> inventory gRPC :9004")
# bus_fan_in always points the merge arrow at the sink's TOP edge, which is
# correct for the DB/Kafka buses (sink below) but backwards for the gRPC bus
# (sink — inventory — is IN the row, sources are either side of it). Re-point
# the final leg at inventory's BOTTOM edge instead, since the bus sits below
# the row, and add inventory's own stub down to the bus junction.
grpc_edges[-1] = {"x1": inventory["x"] + inventory["w"] / 2, "y1": GRPC_BUS_Y,
                   "x2": inventory["x"] + inventory["w"] / 2, "y2": inventory["y"] + inventory["h"],
                   "amber": True}

db_edges = bus_fan_in([review, notification, inventory, payment, shipping, order], postgres, DB_BUS_Y)
kafka_edges = bus_fan_in([notification, inventory, payment, shipping, order], kafka, KAFKA_BUS_Y)

nodes = [client, proxy] + svc_row + [postgres, kafka]
edges = client_edge + path_edges + grpc_edges + db_edges + kafka_edges

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.29 — k8s deployment topology: the mea namespace once overlays/minikube is applied",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 54,
     "text": "8 Deployments, each behind its own ClusterIP Service (strangler-proxy's alone patched to NodePort) + 2 infra StatefulSets",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 60, "y": 108, "text": "App tier — Deployment + ClusterIP Service per box",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": col(6) + COL_W / 2, "y": ROW_Y - 14,
     "text": "not one of strangler-proxy's six path targets", "anchor": "middle", "size": 10, "color": "#2f5f3d"},

    {"x": 60, "y": H - 24,
     "text": "Sourced from deploy/k8s/base/*.yaml, deploy/k8s/base/infra/*.yaml, deploy/k8s/base/app-config.yaml, "
             "overlays/minikube/kustomization.yaml, deploy/k8s/README.md, and StranglerProxyRoute.java's .choice() prefixes.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "k8s-deployment-topology", W, H,
    bands=[ns_band, infra_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
