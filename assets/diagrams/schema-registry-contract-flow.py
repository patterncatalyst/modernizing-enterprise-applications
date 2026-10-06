#!/usr/bin/env python3
"""ch.28 figure: schema-registry-contract-flow — the registry mechanism
behind "Contracts & the Service Registry." A single concept across two
bands (per the diagram plan, this figure stays one concept, not a [SIDE]
pair).

TOP band — the round trip: `OrderAvroProducer` auto-registers the Avro
schema with Apicurio on first send (artifact `order.events.avro.demo-value`,
`apicurio.registry.auto-register=true`), then writes Avro bytes plus the
schema id to the `order.events.avro.demo` Kafka topic. `OrderAvroConsumer`
fetches the schema by id from the same registry and deserializes into the
generated `OrderPlaced` SpecificRecord
(`apicurio.registry.use-specific-avro-reader=true`).

BOTTOM band — compatibility, under a BACKWARD rule
(`SchemaCompatibilityTest`): v1 registers; v2 (adds `giftMessage` with a
default) is accepted (HTTP 200); v3 (retypes `totalCents` long to string) is
rejected (HTTP 409, `RuleViolationException`). A new field with a default
is backward compatible — old readers can still parse new writers' data. A
retyped field is not.

Sourced from examples/09-schema-registry-demo/src/main/java/dev/
patterncatalyst/contracts/{OrderAvroProducer,OrderAvroConsumer}.java,
src/main/resources/application.properties, and
src/test/java/.../SchemaCompatibilityTest.java.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 840

# ============================================================================
# TOP band — the round trip: register, write, fetch, deserialize
# ============================================================================
band_top = {"x": 20, "y": 60, "w": 1360, "h": 300,
            "label": "The round trip — register once, fetch by id on every read", "fill": "#fafafa"}

producer = node(60, 120, 280, 100, ["OrderAvroProducer", "order-avro-out channel"], style="user")
kafka = node(560, 120, 280, 100, ["order.events.avro.demo", "Avro bytes + schema id on the wire"], style="ink")
consumer = node(1060, 120, 280, 100, ["OrderAvroConsumer", "order-avro-in channel"], style="user")

registry = node(560, 270, 280, 80,
                ["Apicurio Registry", "artifact order.events.avro.demo-value"], style="accent")
specific = node(1060, 270, 280, 80,
                ["OrderPlaced (SpecificRecord)", "generated from the registered schema"], style="accent")

top_nodes = [producer, kafka, consumer, registry, specific]
top_edges = [
    connect(producer, kafka, label="Avro bytes + schema id"),
    connect(kafka, consumer, label="consume"),
    connect(producer, registry, amber=True, label="auto-register schema"),
    connect(consumer, registry, amber=True, label="fetch schema by id", lx=-14, ly=-18),
    connect(consumer, specific, label="deserialize"),
]

# ============================================================================
# BOTTOM band — compatibility under a BACKWARD rule
# ============================================================================
band_bottom = {"x": 20, "y": 400, "w": 1360, "h": 400,
               "label": "Compatibility, under a BACKWARD rule — a default makes a field optional; a retype does not",
               "fill": "#eaf4ec"}

v1 = node(60, 560, 260, 110, ["order-placed-v1.avsc", "registers — BACKWARD rule applied"], style="box")

v2 = node(400, 470, 280, 100, ["order-placed-v2.avsc", "adds giftMessage, with a default"], style="box")
v2result = node(760, 470, 280, 100, ["HTTP 200 — accepted", "new field has a default"], style="accent")

v3 = node(400, 650, 280, 100, ["order-placed-v3.avsc", "retypes totalCents: long to string"], style="box")
v3result = node(760, 650, 280, 100, ["HTTP 409 — rejected", "RuleViolationException"], style="ghost")

bottom_nodes = [v1, v2, v2result, v3, v3result]
bottom_edges = [
    connect(v1, v2, label="evolve"),
    connect(v1, v3, label="evolve"),
    connect(v2, v2result, amber=True, label="compatibility check"),
    connect(v3, v3result, amber=True, label="compatibility check"),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.28 — the registry as contract: auto-register, fetch by schema id, enforce BACKWARD compatibility",
     "anchor": "middle", "bold": True, "size": 16},

    {"x": 60, "y": 395,
     "text": "The registry is both the write path's destination and the read path's lookup — consumer and producer agree only because they ask the same registry.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 60, "y": 780,
     "text": "v2's new field carries a default, so an old reader skips it and still parses the record — backward compatible.",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": 60, "y": 796,
     "text": "v3 changes a field's type, which an old reader cannot parse at all — the registry rejects the write before it reaches the topic.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": H - 14,
     "text": "Sourced from examples/09-schema-registry-demo/src/main/java/dev/patterncatalyst/contracts/{OrderAvroProducer,OrderAvroConsumer}.java, "
             "application.properties, and src/test/.../SchemaCompatibilityTest.java.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "schema-registry-contract-flow", W, H,
    bands=[band_top, band_bottom],
    nodes=top_nodes + bottom_nodes,
    edges=top_edges + bottom_edges,
    notes=notes,
)
