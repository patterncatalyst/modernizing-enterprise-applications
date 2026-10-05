#!/usr/bin/env python3
"""ch.19 figure: inventory-extraction-topology — the Inventory extraction's
steady-state seam, plus the now-retired CDC backfill path that got it there.

Live path: Client -> Camel strangler proxy (:8888) -> either the Spring
monolith (:8080, `/api/orders`) or the Quarkus inventory service (:8084,
`/api/inventory`, REST read). The checkout SEAM (curing SMELL #5): the
monolith's `OrderService#placeOrder`, via `RemoteInventoryClient`, calls the
inventory service's gRPC `Reserve` (:9004) to atomically check-and-decrement
stock in the inventory service's OWN schema (never a local table); on a
post-reserve failure (insufficient stock on a later line, a payment decline,
a shipping failure) it calls the compensating gRPC `Release`. `inventory.proto`
is the ACL -- its wire vocabulary (`stock_keeping_unit`/`on_hand_qty`/
`reservation_ok`) is deliberately distinct from the monolith's internal
`StockDto`/`InventoryItem` shape (`sku`/`quantityOnHand`/`priceCents`).

Historical path (bottom band, RETIRED): during the S3-S10 transition window,
a Debezium Postgres connector read the monolith's `public.inventory_items`
table's WAL and streamed it through Kafka to the inventory service's
`InventoryCdcConsumer`, which upserted it into the inventory service's OWN
database -- a one-time initial-snapshot backfill, then keep-current sync,
while the monolith was still the writer of record. At S11 cutover
(`scripts/retire-debezium.sh`), once gRPC `Reserve`/`Release` made the
inventory service the sole writer of its own data, the connector was deleted
and its replication slot + publication dropped -- CDC is NOT part of the
checkout path at any point; it only ever moved data sideways to seed/sync a
read copy during the transition.

Sourced from: examples/04-inventory-service/.../InventoryGrpcServiceImpl.java,
InventoryCdcConsumer.java, src/main/proto/.../inventory.proto;
examples/00-monolith/.../inventory/RemoteInventoryClient.java,
.../order/OrderService.java (placeOrder, compensateRemoteReservations),
SMELLS.md (smell #5); examples/01-strangler-proxy/.../StranglerProxyRoute.java
(the /api/inventory seam); infra/debezium/README.md (RETIRED post-cutover,
S11); and _plans/iterations/inventory-plan.md. No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 760

# ---- live path: client, proxy, monolith --------------------------------
client = node(40, 200, 200, 100, ["Client", "(browser / API caller)"], style="user")
proxy = node(300, 100, 260, 300, ["Camel strangler proxy", ":8888",
                                  "/api/orders -> monolith",
                                  "/api/inventory -> inventory svc"], style="accent")

monolith = node(620, 170, 260, 110, ["Spring monolith", ":8080",
                                     "OrderService#placeOrder()",
                                     "checkout @Transactional"], style="box")
monolith_pg = node(620, 300, 260, 90, ["PostgreSQL", "monolith's own schema",
                                        "orders, customers, ..."], style="sub")

# ---- the extracted inventory service, :8084 / :9004 ---------------------
inv_band = {"x": 960, "y": 40, "w": 380, "h": 400,
            "label": "Quarkus inventory service :8084", "fill": "#eaf4ec"}

rest_api = node(990, 60, 320, 80, ["InventoryResource (REST)", ":8084 HTTP",
                                   "serves /api/inventory via proxy"], style="box")
grpc_server = node(990, 160, 320, 90, ["InventoryGrpcServiceImpl", ":9004 gRPC server",
                                       "Reserve / Release / CheckStock / GetStock"], style="accent")
own_schema = node(990, 270, 320, 80, ["Own schema (PostgreSQL)", "inventory_items table",
                                      "sole writer post-cutover"], style="sub")
cdc_consumer = node(990, 370, 320, 60, ["InventoryCdcConsumer", "RETIRED post-cutover (S11)"], style="ghost")

# ---- CDC backfill band (bottom, RETIRED) ---------------------------------
cdc_band = {"x": 40, "y": 480, "w": 1300, "h": 200,
            "label": "Debezium CDC -- ONE-TIME BACKFILL during transition (S3-S10), RETIRED post-cutover (S11)",
            "fill": "#f4f4f4"}

old_table = node(80, 530, 300, 100, ["public.inventory_items", "monolith's OLD local inventory table",
                                     "same Postgres instance as monolith_pg",
                                     "REMOVED (SMELL #5 cured, S11)"], style="ghost")
debezium = node(440, 530, 260, 100, ["Debezium Postgres connector", "Kafka Connect, pgoutput",
                                     "RETIRED -- scripts/retire-debezium.sh"], style="ghost")
kafka_topic = node(760, 530, 240, 100, ["Kafka topic", "mea.public.inventory_items",
                                        "RETIRED -- no new events"], style="ghost")

nodes = [client, proxy, monolith, monolith_pg,
         rest_api, grpc_server, own_schema, cdc_consumer,
         old_table, debezium, kafka_topic]

# ---- edges: hand-routed so nothing passes through an unrelated box -------
edges = [
    {"x1": 240, "y1": 250, "x2": 300, "y2": 250, "label": "HTTP"},
    {"x1": 560, "y1": 225, "x2": 620, "y2": 225, "label": "/api/orders"},
    {"x1": 560, "y1": 120, "x2": 990, "y2": 120, "label": "/api/inventory", "amber": True},
    {"x1": 880, "y1": 205, "x2": 990, "y2": 205,
     "label": "gRPC Reserve / Release -- :9004", "amber": True, "bidir": True, "lx": -66, "ly": -72},
    {"x1": 1150, "y1": 250, "x2": 1150, "y2": 270, "label": "atomic conditional UPDATE (own schema)"},
    {"x1": 1150, "y1": 370, "x2": 1150, "y2": 350, "label": "upsert (idempotent) -- historical S3-S10", "dashed": True},
    {"x1": 750, "y1": 280, "x2": 750, "y2": 300, "label": "orders, customers (own schema)"},
    {"x1": 380, "y1": 580, "x2": 440, "y2": 580, "label": "WAL (pgoutput), replication slot", "dashed": True},
    {"x1": 700, "y1": 580, "x2": 760, "y2": 580, "label": "publish", "dashed": True},
    {"x1": 1000, "y1": 530, "x2": 1150, "y2": 430, "label": "backfill + sync (S3-S10 only)", "dashed": True, "lx": 40},
]

notes = [
    {"x": W / 2, "y": 30, "text": "ch.19 -- Extracting Inventory: the synchronous gRPC seam (Reserve/Release) + a retired CDC backfill",
     "anchor": "middle", "bold": True, "size": 17},

    {"x": 300, "y": 414,
     "text": "The checkout SEAM: gRPC Reserve atomically decrements stock in the inventory service's OWN schema; a post-reserve failure",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 300, "y": 428,
     "text": "(insufficient stock on a later line, a payment decline, a shipping failure) triggers the compensating gRPC Release.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": 444,
     "text": "ACL honesty: inventory.proto's wire vocabulary (stock_keeping_unit/on_hand_qty/reservation_ok) is deliberately distinct from the",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 40, "y": 458,
     "text": "monolith's internal StockDto (sku/priceCents/quantityOnHand) -- RemoteInventoryClient and InventoryGrpcServiceImpl are the ACL's two sides.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 50, "y": 660,
     "text": "CDC never sat in the checkout path -- it only ever moved data sideways to seed/sync the inventory service's own copy while the monolith was still the writer.",
     "anchor": "start", "size": 11, "color": "#1d1d1d", "bold": True},

    {"x": 40, "y": 712,
     "text": "Sourced from examples/04-inventory-service (InventoryGrpcServiceImpl, InventoryCdcConsumer, inventory.proto), examples/00-monolith",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 726,
     "text": "(RemoteInventoryClient, OrderService, SMELLS.md #5), examples/01-strangler-proxy (/api/inventory route), infra/debezium/README.md,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 740,
     "text": "and _plans/iterations/inventory-plan.md.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "inventory-extraction-topology", W, H,
    bands=[inv_band, cdc_band],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
