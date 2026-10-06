#!/usr/bin/env python3
"""ch.29 figure: k8s-config-probe-wiring — how config, secrets, probes, and
graceful shutdown actually wire into one representative pod.

TOP pod — notification-service, standing in for the five Kafka-consuming,
quarkus-smallrye-health-carrying services (notification/inventory/payment/
shipping/order; all five share the identical probe/lifecycle shape, per each
one's own base/*.yaml). Two distinct env mechanisms land in the same
container: a blanket `envFrom: configMapRef: mea-app-config` (every key,
harmless if unused) plus two explicit `env:` entries that each win over a
HARDCODED application.properties value by MicroProfile Config ordinal — the
mapped env var (ordinal 300, EnvConfigSource) outranks
application.properties (ordinal 250, PropertiesConfigSource) in every
profile, with no code change (see app-config.yaml's own header comment).
`QUARKUS_DATASOURCE_JDBC_URL` is sourced from the ConfigMap's
`NOTIFICATION_SERVICE_JDBC_URL` key (configMapKeyRef); the datasource
credentials are sourced from the `mea-postgres-app` Secret (secretKeyRef) —
never duplicated as a second ConfigMap key. All three probes are real
httpGet targets because quarkus-smallrye-health is on this service's
classpath.

BOTTOM pod — review-service / strangler-proxy, the two of the eight
Deployments whose pom.xml carries NO quarkus-smallrye-health (confirmed:
examples/02-review-service/pom.xml, examples/01-strangler-proxy/pom.xml).
`/q/health/*` would 404 there, so all three probes fall back to tcpSocket on
the container port — proving the listener is up, not that routing or the DB
connection actually works. Neither is a Kafka consumer, so neither carries
the preStop/terminationGracePeriodSeconds override below.

Sourced from: deploy/k8s/base/notification-service.yaml,
deploy/k8s/base/review-service.yaml, deploy/k8s/base/strangler-proxy.yaml,
deploy/k8s/base/app-config.yaml (header comment: ordinal-override vs.
native-placeholder), deploy/k8s/base/app-secret.yaml, deploy/k8s/README.md's
env-contract table. No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1720, 1100

# ============================================================================
# shared config/secret sources (left column)
# ============================================================================
configmap = node(60, 160, 420, 190,
                  ["ConfigMap mea-app-config", "KAFKA_BOOTSTRAP_SERVERS · QUARKUS_PROFILE",
                   "STRANGLER_<CTX>_BASE_URL x6 · gateway's *_SERVICE_URL",
                   "INVENTORY_GRPC_HOST/PORT · QUARKUS_GRPC_CLIENTS_*",
                   "NOTIFICATION_SERVICE_JDBC_URL (this pod's key)"], style="box")

secret = node(60, 420, 420, 110,
              ["Secret mea-postgres-app", "stringData: username, password",
               "type: Opaque — dev-only, plaintext-in-git"], style="box")

# ============================================================================
# TOP pod: notification-service — full health + graceful-shutdown wiring
# ============================================================================
pod_band = {"x": 560, "y": 120, "w": 1100, "h": 580,
            "label": "Pod: notification-service — quarkus-smallrye-health on classpath, terminationGracePeriodSeconds: 45",
            "fill": "#eaf4ec"}

container = node(600, 180, 420, 150, style="ink", lines=[
    "container: notification-service",
    "envFrom: mea-app-config (ConfigMap, bulk)",
    "QUARKUS_DATASOURCE_JDBC_URL <- configMapKeyRef",
    "QUARKUS_DATASOURCE_USERNAME / PASSWORD <- secretKeyRef",
    "env (ordinal 300) beats application.properties (250)",
])

startup = node(1120, 180, 480, 80, style="accent", lines=[
    "startupProbe — httpGet /q/health/started",
    "initialDelay 5s · period 5s · failureThreshold 30",
])
ready = node(1120, 275, 480, 80, style="accent", lines=[
    "readinessProbe — httpGet /q/health/ready",
    "initialDelay 5s · period 10s · failureThreshold 3",
])
live = node(1120, 370, 480, 80, style="accent", lines=[
    "livenessProbe — httpGet /q/health/live",
    "initialDelay 10s · period 10s · failureThreshold 3",
])

lifecycle = node(600, 365, 420, 110, style="box", lines=[
    "lifecycle.preStop.exec: sh -c \"sleep 10\"",
    "+ terminationGracePeriodSeconds: 45 (pod spec)",
    "-> time to send a clean Kafka LeaveGroupRequest",
])

top_nodes = [container, startup, ready, live, lifecycle]

# Three distinct wires land on the container's left edge at different
# heights so the bulk envFrom, the ConfigMap key override, and the Secret
# key override stay visually separable instead of overlapping one line.
top_edges = [
    {"x1": configmap["x"] + configmap["w"], "y1": 205, "x2": container["x"], "y2": 205,
     "label": "envFrom (bulk)"},
    {"x1": configmap["x"] + configmap["w"], "y1": 270, "x2": container["x"], "y2": 255,
     "amber": True, "dashed": True, "ly": -8,
     "label": "configMapKeyRef override"},
    {"x1": secret["x"] + secret["w"], "y1": 470, "x2": container["x"], "y2": 305,
     "amber": True, "dashed": True, "ly": -8,
     "label": "secretKeyRef override"},
    connect(startup, container),
    connect(ready, container),
    connect(live, container),
    connect(container, lifecycle, label="pod shutdown"),
]

# ============================================================================
# BOTTOM pod: review-service / strangler-proxy — tcpSocket fallback
# ============================================================================
contrast_band = {"x": 560, "y": 760, "w": 1100, "h": 280,
                  "label": "Pod: review-service / strangler-proxy — no quarkus-smallrye-health, not a Kafka consumer",
                  "fill": "#fafafa"}

container2 = node(600, 820, 420, 140, style="box", lines=[
    "container: review-service / strangler-proxy",
    "envFrom: mea-app-config (same ConfigMap)",
    "no /q/health/* endpoint on the classpath",
    "no preStop / terminationGracePeriodSeconds override",
])

startup2 = node(1120, 820, 480, 70, style="ghost", lines=[
    "startupProbe — tcpSocket :http",
    "initialDelay 5s · period 5s · failureThreshold 30",
])
ready2 = node(1120, 900, 480, 70, style="ghost", lines=[
    "readinessProbe — tcpSocket :http",
    "initialDelay 5s · period 10s · failureThreshold 3",
])
live2 = node(1120, 980, 480, 70, style="ghost", lines=[
    "livenessProbe — tcpSocket :http",
    "initialDelay 10s · period 10s · failureThreshold 3",
])

bottom_nodes = [container2, startup2, ready2, live2]
bottom_edges = [
    connect(startup2, container2),
    connect(ready2, container2),
    connect(live2, container2),
]

nodes = [configmap, secret] + top_nodes + bottom_nodes
edges = top_edges + bottom_edges

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.29 — config, secret, probe, and graceful-shutdown wiring for one representative pod",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 54,
     "text": "same ConfigMap + Secret feed every pod; the probe shape and the preStop/terminationGrace override are what differ",
     "anchor": "middle", "size": 11.5, "color": "#555555"},

    {"x": 60, "y": 110, "text": "Shared across all 8 pods", "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 600, "y": 712,
     "text": "proves the listener is up, not that routing or the DB connection actually work",
     "anchor": "start", "size": 10, "color": "#2f5f3d"},

    {"x": 60, "y": H - 24,
     "text": "Sourced from deploy/k8s/base/notification-service.yaml, review-service.yaml, strangler-proxy.yaml, "
             "app-config.yaml's ordinal-override header comment, app-secret.yaml, and deploy/k8s/README.md's env-contract table.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "k8s-config-probe-wiring", W, H,
    bands=[pod_band, contrast_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
