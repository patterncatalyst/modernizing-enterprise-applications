#!/usr/bin/env python3
"""ch.19 figure: cdc-debezium-backfill-pipeline — the CDC backfill MECHANISM
itself: Postgres WAL -> Debezium connector -> Kafka topic -> InventoryCdcConsumer,
plus the replication-slot/publication lifecycle this one connector owned from
registration through retirement. Distinct from Figure 19.1
(inventory-extraction-topology), which is the whole-system steady-state seam
with CDC as one retired corner of a bigger picture; this figure is the pipeline
on its own, end to end.

TOP band — the pipeline, active during the transition window (S3-S10):
`wal_level=logical` + the `pgoutput` plugin give the monolith's Postgres write-
ahead log enough decoding detail for Debezium to read; `REPLICA IDENTITY FULL`
on `inventory_items` is what makes the "before" image on update/delete
complete rather than primary-key-only. One replication slot
(`mea_inventory_slot`) and one publication (`mea_inventory_publication`),
scoped to exactly `public.inventory_items`, feed the `mea-inventory-connector`
(`io.debezium.connector.postgresql.PostgresConnector`, running on Kafka
Connect). `snapshot.mode=initial` is the backfill itself: one `op=r` event per
existing row, emitted once, before the connector switches to streaming
`op=c`/`u`/`d` events as the monolith keeps writing. Both land on the same
Kafka topic (`topic.prefix=mea` + `public.inventory_items` ->
`mea.public.inventory_items`); `schemas.enable=false` strips Kafka Connect's
schema wrapper, so the envelope arrives flat. `InventoryCdcConsumer`
(`@Incoming("inventory-cdc")`) parses that flat JSON by hand and funnels every
operation through one idempotent `ON CONFLICT` upsert keyed by the CDC-
assigned `id`, so Kafka's at-least-once redelivery never double-applies a
change. That upsert is what backfills, then keeps current, the inventory
service's own schema while the monolith is still the writer of record.

BOTTOM band — the slot/publication's retirement: once the gRPC `Reserve`/
`Release` seam (Figure 19.1) makes the inventory service the sole writer of
its own data, the connector has nothing left to replicate.
`scripts/retire-debezium.sh`, run once at the S11 cutover, deletes the
`mea-inventory-connector` and drops both `mea_inventory_slot` and
`mea_inventory_publication` — an un-drained slot would otherwise retain WAL on
the monolith's Postgres indefinitely, a disk-fill risk with no offsetting
benefit once CDC's job is done. This pipeline ran once, for one backfill
window, and was deliberately torn down rather than left running.

Sourced from `_docs/19-cdc-and-extraction-3-inventory.md` ("Transaction log
tailing: Debezium reads the write-ahead log" and "Owned data, then own-seed"
sections), `infra/debezium/` (`inventory-connector.json` and its `README.md`'s
retirement record), `examples/04-inventory-service/.../InventoryCdcConsumer.java`
and its `InventoryCdcConsumerTest`, and `scripts/retire-debezium.sh`. No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1600, 760

# ============================================================================
# TOP band — the pipeline itself, active S3-S10
# ============================================================================
band_pipeline = {"x": 20, "y": 56, "w": 1560, "h": 300,
                  "label": "The CDC backfill pipeline — active during the transition window (S3–S10)",
                  "fill": "#eaf4ec"}

wal = node(40, 140, 230, 120,
           ["Postgres (monolith) WAL", "wal_level=logical, pgoutput,",
            "REPLICA IDENTITY FULL"], style="box")
slot_pub = node(290, 140, 230, 120,
                ["Replication slot + publication", "mea_inventory_slot /",
                 "mea_inventory_publication"], style="accent")
debezium = node(540, 140, 230, 120,
                ["Debezium Postgres connector", "mea-inventory-connector,",
                 "snapshot.mode=initial"], style="box")
kafka_topic = node(790, 140, 230, 120,
                   ["Kafka topic", "mea.public.inventory_items",
                    "op=r backfill, then c/u/d"], style="ink")
cdc_consumer = node(1040, 140, 230, 120,
                    ["InventoryCdcConsumer", "@Incoming(\"inventory-cdc\")",
                     "upsert ON CONFLICT by id"], style="accent")
own_schema = node(1290, 140, 230, 120,
                  ["Inventory service's own schema", "inventory.inventory_items",
                   "backfilled once, kept current"], style="sub")

pipeline_nodes = [wal, slot_pub, debezium, kafka_topic, cdc_consumer, own_schema]
pipeline_edges = [
    connect(wal, slot_pub, label="registers, scoped to one table", ly=-70),
    connect(slot_pub, debezium, label="logical decoding feed", ly=-70),
    connect(debezium, kafka_topic, label="op=r x N = the backfill, then streaming op=c/u/d", ly=-70),
    connect(kafka_topic, cdc_consumer, label="at-least-once delivery", ly=-70),
    connect(cdc_consumer, own_schema, label="idempotent upsert", ly=-70),
]

pipeline_subnotes = [
    {"x": 40, "y": 288, "text": "wal_level=logical + pgoutput;",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 301, "text": "REPLICA IDENTITY FULL = full row.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 290, "y": 288, "text": "One slot, one publication,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 290, "y": 301, "text": "scoped to inventory_items only.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 540, "y": 288, "text": "snapshot.mode=initial:",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 540, "y": 301, "text": "op=r once, then streaming.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 790, "y": 288, "text": "schemas.enable=false ->",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 790, "y": 301, "text": "flat JSON envelope.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1040, "y": 288, "text": "Hand-parsed JSON;",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1040, "y": 301, "text": "ON CONFLICT upsert by id.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1290, "y": 288, "text": "Backfilled once by snapshot,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1290, "y": 301, "text": "kept current until retired.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": 338,
     "text": "CDC never sat in the checkout path — it only ever moved data sideways to seed, then keep current, a database the inventory service owns outright.",
     "anchor": "start", "size": 11, "color": "#1d1d1d", "bold": True},
]

# ============================================================================
# BOTTOM band — the slot/publication's retirement, S11
# ============================================================================
band_retire = {"x": 20, "y": 390, "w": 1560, "h": 300,
               "label": "Retirement — scripts/retire-debezium.sh, run once at the S11 cutover",
               "fill": "#f4f4f4"}

ghost_slot = node(100, 480, 380, 120,
                  ["mea_inventory_slot +", "mea_inventory_publication",
                   "DROPPED"], style="ghost")
retire_script = node(610, 480, 380, 120,
                     ["scripts/retire-debezium.sh", "run once, at S11 cutover",
                      "after gRPC Reserve/Release is sole writer"], style="ink")
ghost_connector = node(1120, 480, 380, 120,
                       ["mea-inventory-connector", "Kafka Connect",
                        "DELETED"], style="ghost")

retire_nodes = [ghost_slot, retire_script, ghost_connector]
retire_edges = [
    connect(retire_script, ghost_slot, label="DROP slot + publication", dashed=True, amber=True, ly=-56),
    connect(retire_script, ghost_connector, label="DELETE connector", dashed=True, amber=True, ly=-56),
]

retire_subnotes = [
    {"x": 100, "y": 625, "text": "An un-drained slot would otherwise retain",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 100, "y": 638, "text": "WAL on the monolith's Postgres indefinitely —",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 100, "y": 651, "text": "a disk-fill risk with no offsetting benefit.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1120, "y": 625, "text": "Once Reserve/Release makes the inventory",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1120, "y": 638, "text": "service the sole writer of its own data,",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 1120, "y": 651, "text": "the connector has nothing left to replicate.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.19 — The CDC backfill pipeline: WAL to Debezium to Kafka to InventoryCdcConsumer, then the slot's retirement",
     "anchor": "middle", "bold": True, "size": 17},
] + pipeline_subnotes + retire_subnotes + [
    {"x": 40, "y": 740,
     "text": "Sourced from _docs/19-cdc-and-extraction-3-inventory.md; infra/debezium/ (inventory-connector.json, README.md); examples/04-inventory-service/.../InventoryCdcConsumer.java; scripts/retire-debezium.sh.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "cdc-debezium-backfill-pipeline", W, H,
    bands=[band_pipeline, band_retire],
    nodes=pipeline_nodes + retire_nodes,
    edges=pipeline_edges + retire_edges,
    notes=notes,
)
