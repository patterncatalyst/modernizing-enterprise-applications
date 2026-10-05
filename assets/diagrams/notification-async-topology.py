#!/usr/bin/env python3
"""ch.17 figure: notification-async-topology — the end-to-end async pipeline
for the Notification extraction (cures SMELL #4).

Write side: Client -> Camel strangler proxy (:8888) -> Spring monolith
(:8080), whose `OrderService#placeOrder` writes the order row AND an
`order.placed` outbox row in the SAME `@Transactional` against the shared
PostgreSQL instance. A separate `@Scheduled` `OutboxRelay` polls that table
and publishes unpublished rows to the Kafka topic `order.placed`
(at-least-once). The Quarkus notification service (:8083) runs TWO SmallRye
`@Incoming` consumers off that one topic: `OrderPlacedConsumer` (shared
consumer group) persists idempotently (dedupe by orderId) into its OWN
schema; `OrderPlacedPushConsumer` (unique group per replica) fans the same
event out over a WebSocket (`/ws/notifications`) — a second, direct client
touchpoint that is NOT proxied (it isn't under the `/api` prefix the proxy
matches on).

Read side: the proxy's `/api/notifications` route (flag
`strangler.notification.enabled`) forwards straight to the notification
service's own schema + REST resource — never to the monolith.

The behavior-equivalence suite's "Notification Context Contract" folder
exercises the read route through the proxy with a bounded-wait
eventual-consistency poll (up to 10 x 500ms), proving the externally
observable contract (`GET /api/notifications?customerId=`) holds whether
the backend answers synchronously (old) or asynchronously (now).

Sourced from: examples/00-monolith/.../common/outbox/{OutboxEvent,
OutboxRelay,OrderPlacedEvent}.java, examples/00-monolith/.../order/
OrderService.java (writeOrderPlacedOutboxEvent, called inside placeOrder's
@Transactional), examples/03-notification-service/.../{OrderPlacedConsumer,
OrderPlacedPushConsumer,NotificationService,NotificationResource}.java,
examples/01-strangler-proxy/.../StranglerProxyRoute.java (the notification
seam), and _plans/iterations/notification-plan.md (S2/S3/S5, DRQ-034/-035/
-037). No codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1560, 1040

# ---- left column: client + behavior-equivalence suite ----------------------
suite = node(40, 120, 220, 110,
             ["Behavior-equivalence suite", "\"Notification Context Contract\"",
              "bounded-wait poll", "(up to 10 x 500ms)"], style="sub")
client = node(40, 260, 220, 90, ["Client", "(browser / API caller)"], style="user")

# ---- the strangler proxy ----------------------------------------------------
proxy = node(320, 190, 260, 200,
             ["Camel strangler proxy", ":8888",
              "/api/orders -> monolith",
              "/api/notifications -> notification svc",
              "flag: strangler.notification.enabled"],
             style="accent")

# ---- write path: monolith + shared Postgres + relay -------------------------
monolith = node(650, 70, 280, 120,
                 ["Spring monolith", ":8080",
                  "OrderService#placeOrder()",
                  "one @Transactional checkout"], style="box")
pg = node(1000, 70, 260, 120,
          ["PostgreSQL (shared instance)", "orders table",
           "outbox table", "— written in the SAME txn —"], style="sub")
relay = node(1000, 230, 260, 90,
             ["OutboxRelay", "@Scheduled poller (2000ms)",
              "publishes unpublished rows"], style="box")
kafka = node(1000, 360, 260, 90,
             ["Kafka topic", "order.placed", "at-least-once delivery"], style="ink")

# ---- read + consume path: the notification service --------------------------
notif_band = {"x": 620, "y": 490, "w": 900, "h": 230,
              "label": "Quarkus notification service :8083", "fill": "#eaf4ec"}

ownschema = node(650, 530, 260, 140,
                  ["Own schema (PostgreSQL)", "notifications table",
                   "+ REST read (NotificationResource)",
                   "separate from the monolith's DB"], style="sub")
consumer1 = node(950, 530, 260, 110,
                  ["OrderPlacedConsumer", "@Incoming(\"order-placed\")",
                   "shared group — persist, idempotent (dedupe orderId)"], style="box")
consumer2 = node(1240, 530, 260, 110,
                  ["OrderPlacedPushConsumer", "@Incoming(\"order-placed-push\")",
                   "unique group/replica — WebSocket fan-out"], style="box")

wsclient = node(1240, 750, 260, 100,
                ["Client", "WebSocket /ws/notifications",
                 "direct — NOT proxied (outside /api)"], style="user")

nodes = [suite, client, proxy, monolith, pg, relay, kafka,
         ownschema, consumer1, consumer2, wsclient]

edges = [
    connect(client, proxy, label="HTTP", ly=18),
    connect(suite, proxy, label="run suite via proxy"),
    connect(proxy, monolith, label="/api/orders"),
    connect(proxy, ownschema, label="/api/notifications (read)", amber=True),
    connect(monolith, pg, label="order + outbox, one commit"),
    connect(pg, relay, label="poll WHERE published_at IS NULL"),
    connect(relay, kafka, label="publish; stamp published_at on ack"),
    connect(kafka, consumer1, label="@Incoming (shared group)"),
    connect(kafka, consumer2, label="@Incoming (unique group)"),
    connect(consumer1, ownschema, label="persist (idempotent)"),
    connect(consumer2, wsclient, label="push (fire-and-forget)", dashed=True),
]

notes = [
    {"x": W / 2, "y": 30, "text": "ch.17 — Extracting Notification: the transactional-outbox + async event-driven pipeline",
     "anchor": "middle", "bold": True, "size": 18},

    {"x": 650, "y": 215,
     "text": "Write side: checkout writes order + outbox atomically; a scheduled relay is the only thing that talks to Kafka",
     "anchor": "start", "bold": True, "size": 12.5, "color": "#1d1d1d"},
    {"x": 630, "y": 478,
     "text": "Read + consume side: two independent consumers off the SAME topic — persistence (shared group) and WebSocket push (unique group)",
     "anchor": "start", "bold": True, "size": 12, "color": "#1d1d1d"},

    {"x": 40, "y": 940,
     "text": "CURED (r04/S8): SMELL #4's synchronous, in-transaction NotificationService call is gone —",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 956,
     "text": "see transactional-outbox-sequence.svg for the old-vs-new contrast.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 992,
     "text": "The proxy's /api/notifications route (read side) + the monolith's unconditional outbox write (write side)",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 1008,
     "text": "are the two halves of one cutover; notification.mode=synchronous|outbox has since been removed.",
     "anchor": "start", "size": 11, "color": "#555555"},
]

g.emit(
    "notification-async-topology", W, H,
    bands=[notif_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
