---
title: "Contracts & the Service Registry"
order: 28
part: "Communication & Contracts"
description: "Schema/API/shared-data-type registry use cases and enterprise requirements; Apicurio with Avro/Protobuf/JSONSchema/OpenAPI/AsyncAPI."
---

Chapter 17 introduced `order.placed` as a JSON Kafka event and, in the same
breath, introduced a cost this book has been carrying ever since: there is no
shared module between any two Maven reactors in this project, so a wire
contract that three services need to agree on gets authored three separate
times, by hand, in three separate `OrderPlacedEvent.java` files. `examples/07-order-service`'s
copy has seven fields — `orderId`, `customerId`, `customerEmail`,
`totalCents`, `paymentMethod`, `confirmationMessage`, `placedAt`.
`examples/05-payment-service`'s copy also has all seven, `paymentMethod`
included, because the payment service's choreographed decline rule needs it.
`examples/03-notification-service`'s copy — read it and count — has six.
`paymentMethod` is missing. Nothing in the build catches this. Jackson binds
JSON by field name, so the notification consumer simply never sees a
`paymentMethod` key that the real producer has been sending since Chapter 23,
and nothing fails: no deserialization error, no startup check, no red test.
The field is just quietly absent from a record that claims to be "this
service's own copy of the JSON payload shape written by the monolith's
`OrderPlacedEvent`," in that class's own javadoc.

This is not a hypothetical drift invented to motivate a chapter. It is the
state of the code in this repository right now, and it is the direct,
named consequence of a decision this book made and chose to live with,
not an accident. DRQ-038, back in Chapter 17's decision log, chose JSON-by-hand over
a schema registry for exactly the reason good scope discipline usually gives:
pulling Apicurio into the stack a chapter before the outbox pattern itself had
even been proven would have been solving a problem the project hadn't earned
yet. That decision also wrote itself a due date — "Avro + Apicurio schema
registry deferred to ch.28" — and three services and eleven chapters later,
this is the bill coming due.

It is worth being precise about what this chapter does and does not do about
that bill, because the real answer is narrower than "adopt a schema
registry." The live flows stay JSON. `examples/00-monolith` through
`examples/07-order-service`, and every consumer of `order.placed` —
notification, payment — are untouched by anything in this chapter. What
changes is additive: a new, isolated module, `examples/09-schema-registry-demo`
(DRQ-076), publishes a field-for-field Avro mirror of `OrderPlacedEvent` to
its own topic, fronted by a real Apicurio registry, so the two things a
registry actually buys — a central contract of record, and compatibility
enforcement strong enough to reject a breaking change before it ships — can
be demonstrated against live infrastructure without touching a system that
currently works. The reason for that shape, rather than a retrofit of the
real topic, is explained in full once you've seen what the registry does
(§8) — for now, hold the two facts side by side: the drift above is real, and
the fix this chapter ships is a parallel, provable mechanism rather than a
cutover.

{% include excalidraw.html file="hand-duplicated-vs-registry-schema" alt="Two side-by-side panels. Left, 'today': three independent Java records — order-service's seven-field OrderPlacedEvent, payment-service's matching seven-field copy, and notification-service's six-field copy missing paymentMethod — each hand-authored against the same intended JSON shape, with a drift arrow pointing at notification's missing field. Right, 'ch.28's demonstrator': one order-placed-v1.avsc record registered once with Apicurio, a producer and a consumer both generated from it, with a compatibility rule standing between any future version and the registry that would reject a breaking change before it ships." caption="Figure 28.1 — Three hand-duplicated, silently drifting records versus one registry-governed schema (demonstrated in parallel; the live flows don't depend on it)" %}

## What a schema registry is for

Strip away the specific product and a schema registry answers one question a
JSON-over-Kafka system like this one's live flows has no good answer for:
when a producer and a consumer in two different deployables both need to
agree on the shape of a message, where does that agreement live, and who
enforces it?

In this project's live flows today, the answer is "nowhere, and no one." The
agreement exists only as three separate authors' understanding of what the
monolith's original `OrderPlacedEvent` looked like, captured once in each
record's own javadoc, with no mechanism checking that the three
understandings stay the same over time. A registry turns that implicit,
distributed agreement into an explicit, centralized one: a schema is
registered once, under an artifact id every interested service can reference,
and from that point on the registry — not a comment, not a convention, not a
reviewer's memory — is the thing that knows what the contract is.

That central record of truth buys two things in practice, and they are
different things even though they're often sold together. The first is
**governance**: a human or a CI job can go to one place and ask "what does
the `order.placed` contract actually look like today, and what did it look
like three versions ago?" rather than diffing three Java files across three
repositories by hand. The second, sharper benefit is **compatibility
enforcement**: a registry can be configured with a rule — BACKWARD, FORWARD,
FULL, and variants of each — and then it *refuses* a new schema version that
would violate that rule, at registration time, before a single message is
published under the broken shape. That second property is the one a
hand-maintained JSON record can never give you, no matter how careful the
team: nothing stops a developer from committing a retyped field, and nothing
tells them they just broke every consumer still running the old schema until
a deserialization exception shows up in production.

The practical payoff downstream of both properties is **decoupled
producer/consumer deploys**. A producer that wants to add an optional field
doesn't need to coordinate a simultaneous consumer release if the registry's
rule and the schema's defaults make that addition provably safe; a consumer
can upgrade on its own schedule and trust that whatever it reads will either
match what it expects or has been rejected before it could ever be written.
Without a registry, "is it safe to deploy the producer first" is a question
a team answers by reading code and hoping. With one, it's a question the
registry answered at registration time, mechanically, the same way every
time.

Apicurio, the registry this project adopts, is format-agnostic by design:
it can govern Avro, Protobuf, and JSON Schema as data formats, and OpenAPI
and AsyncAPI as API-description formats, all under the same artifact/version/
rule model. This chapter scopes itself to one corner of that menu — Avro,
for one Kafka topic — because that is the corner `order.placed`'s own drift
problem lives in, and because proving the mechanism once, concretely, teaches
more than surveying all five formats shallowly. The registry itself doesn't
care; it would govern a Protobuf-serialized gRPC contract or an OpenAPI spec
with the identical rule engine underneath.

## The contract as an `.avsc`

An Avro schema is a JSON document that describes a record's fields, types,
and (optionally) defaults and documentation — and the cleanest way to see
what a registry-governed contract buys over three hand-authored Java records
is to put the two side by side. Here is `order-service`'s real
`OrderPlacedEvent`, the seven-field source of truth the other two copies are
supposed to match, next to the one `.avsc` this chapter introduces:

{% include codetabs.html langs="Today — three hand-duplicated Java records (JSON binds by name)|ch.28 — one order-placed-v1.avsc, generated once" %}

```java
// examples/07-order-service/.../order/OrderPlacedEvent.java
public record OrderPlacedEvent(
        Long orderId,
        Long customerId,
        String customerEmail,
        long totalCents,
        String paymentMethod,
        String confirmationMessage,
        Instant placedAt) {
}
```

```json
// examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc
{
  "type": "record",
  "name": "OrderPlaced",
  "namespace": "dev.patterncatalyst.contracts.avro",
  "fields": [
    { "name": "orderId", "type": "long" },
    { "name": "customerId", "type": "long" },
    { "name": "customerEmail", "type": "string" },
    { "name": "totalCents", "type": "long" },
    { "name": "paymentMethod", "type": "string" },
    { "name": "confirmationMessage", "type": "string" },
    { "name": "placedAt", "type": { "type": "long", "logicalType": "timestamp-millis" } }
  ]
}
```

Field for field, this is the same seven-field shape — `orderId`,
`customerId`, `customerEmail`, `totalCents`, `paymentMethod`,
`confirmationMessage`, `placedAt` — including `paymentMethod`, the exact
field that went missing from notification's hand-written copy. The `.avsc`
file's own doc string says this directly: it is "an Avro mirror of
`examples/07-order-service`'s `OrderPlacedEvent` JSON record, field-for-field
identical (same 7 fields, same meaning)." The one structural difference is
`placedAt`'s type — a `long` carrying Avro's
`timestamp-millis` logical type, rather than a bare numeric field, so the
schema itself documents that the number is a millisecond epoch timestamp
rather than leaving that fact to a Javadoc comment the way the Java record
does.

The difference that actually matters, though, isn't in the field list — it's
in how many times each shape gets authored. The Java-record column on the
left is written three times, once per consuming service, by three different
people or three different points in this project's own timeline, with no
mechanism keeping the copies in sync; that's the column where
`paymentMethod` silently disappeared. The `.avsc` column on the right is
written once. Every producer and consumer that wants the type-safe,
generated `OrderPlaced` class gets it from the same file, compiled by the
same `quarkus-avro` extension, at build time — not retyped by hand into
whatever shape a given service's author remembers the contract being.

## Adding Apicurio to the stack

Apicurio arrives as one more service in `compose.yaml`, and the comment
sitting above it is explicit about the blast radius: "it is used ONLY by
`examples/09-schema-registry-demo` ... no other service in this stack
(`examples/01` through `examples/08`) talks to it, and the real
`order.placed` JSON event flows (DRQ-038) are unchanged."

```yaml
# compose.yaml
apicurio:
  image: quay.io/apicurio/apicurio-registry:${APICURIO_IMAGE_TAG:-3.1.7}
  container_name: mea-apicurio
  ports:
    - "${APICURIO_HOST_PORT:-8095}:8080"
  environment:
    - APICURIO_STORAGE_KIND=sql
    - APICURIO_STORAGE_SQL_KIND=h2
  mem_limit: 512m
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:8080/apis/registry/v3/system/info"]
    interval: 5s
    timeout: 3s
    retries: 12
    start_period: 20s
  restart: unless-stopped
```

A few details here are worth reading closely rather than skimming past,
because each one is the resolution of a small, real surprise rather than an
arbitrary choice. First, the image: `quay.io/apicurio/apicurio-registry:3.1.7`,
exposed on the host at `:8095` (mapped from the container's `:8080`), which
is the v3 API/client line — confirmed, per the compose file's own comment, by
running `mvn dependency:tree` against the pinned `quarkus.platform.version`
3.40.1 and seeing `quarkus-apicurio-registry-avro` pull in
`io.apicurio:apicurio-registry-*:3.1.7` transitively. The image tag, the
healthcheck's `/apis/registry/v3/system/info` path, and the application's own
`.../apis/registry/v3` registry URL all agree on that same major version,
which matters because Apicurio's v2 and v3 REST APIs are not
wire-compatible — a mismatch here wouldn't fail loudly, it would fail as
confusing 404s the first time anything tried to register a schema.

Second, the storage: `APICURIO_STORAGE_KIND=sql` with
`APICURIO_STORAGE_SQL_KIND=h2`, an embedded, in-memory H2 database rather
than a dedicated Postgres schema the way this project's actual services own
their data. The compose comment is candid about why: "Apicurio 3.1.7 removed
the plain `mem` storage variant older 3.0.x images accepted (confirmed
empirically against a live 3.1.7 container: `APICURIO_STORAGE_KIND=mem`
fails to start) — embedded H2 is the closest equivalent, still ephemeral and
wiped on every container restart, no external dependency." That's a real gap
in the upgrade path from 3.0.x to 3.1.x, caught the only way that holds up —
starting the actual container and watching what it does — rather than
assumed from documentation. What it means for this chapter's registry is
that it's fully ephemeral: restart the `apicurio` container and every
registered artifact, every rule, every version history is gone. That's a
fine property for a teaching module and a disqualifying one for anything a
real system's compatibility guarantees would depend on — a distinction this
chapter comes back to directly in §8.

Third: nothing about this container is minikube- or Kubernetes-specific.
It's a host-reachable HTTP service on `localhost:8095`, the same shape every
other infrastructure dependency in this project's compose stack takes, and
any JVM process running on the host — the demo module, a developer's own
`curl`, this chapter's own test suite — reaches it the same way, over plain
HTTP, no service mesh or cluster DNS required.

## Serde wiring in Quarkus

Two dependencies turn a plain Quarkus service into one that can speak Avro
through Apicurio: `quarkus-avro`, which compiles an `.avsc` file into a
generated `SpecificRecord` Java class at build time, and
`quarkus-apicurio-registry-avro`, which supplies the Kafka serializer and
deserializer that know how to talk to the registry over its v3 REST API.
`examples/09-schema-registry-demo/pom.xml` declares both, pinned to the same
`3.40.1` Quarkus platform BOM every other service in this project already
uses — no version drift introduced for this one module.

With those two dependencies in place, the actual wiring is entirely
`application.properties` — no custom serializer class, no manual Kafka
producer/consumer configuration in Java:

```properties
# examples/09-schema-registry-demo/src/main/resources/application.properties
%dev.mp.messaging.connector.smallrye-kafka.apicurio.registry.url=${APICURIO_REGISTRY_URL:http://localhost:8095/apis/registry/v3}
%prod.mp.messaging.connector.smallrye-kafka.apicurio.registry.url=${APICURIO_REGISTRY_URL:http://localhost:8095/apis/registry/v3}

# ── Outgoing: order-avro-out -> order.events.avro.demo (Avro via Apicurio) ──
mp.messaging.outgoing.order-avro-out.connector=smallrye-kafka
mp.messaging.outgoing.order-avro-out.topic=order.events.avro.demo
mp.messaging.outgoing.order-avro-out.value.serializer=io.apicurio.registry.serde.avro.AvroKafkaSerializer
mp.messaging.outgoing.order-avro-out.apicurio.registry.auto-register=true

# ── Incoming: order-avro-in <- order.events.avro.demo (Avro via Apicurio) ──
mp.messaging.incoming.order-avro-in.connector=smallrye-kafka
mp.messaging.incoming.order-avro-in.topic=order.events.avro.demo
mp.messaging.incoming.order-avro-in.value.deserializer=io.apicurio.registry.serde.avro.AvroKafkaDeserializer
mp.messaging.incoming.order-avro-in.apicurio.registry.use-specific-avro-reader=true
mp.messaging.incoming.order-avro-in.group.id=schema-registry-demo
mp.messaging.incoming.order-avro-in.auto.offset.reset=earliest
```

Reading this block key by key: the registry URL is set at the *connector*
level (`mp.messaging.connector.smallrye-kafka.apicurio.registry.url`), not
repeated per-channel, so both the outgoing `order-avro-out` channel and the
incoming `order-avro-in` channel share one registry endpoint without
duplicating the property. The outgoing channel publishes to its own topic,
`order.events.avro.demo` — a name chosen specifically so it cannot be
confused with the real `order.placed` topic the live services share — and
sets `value.serializer` explicitly to `AvroKafkaSerializer`. The file's own
comment explains why that serializer is spelled out rather than left for
SmallRye Kafka to autodetect: this build also pulls an older Apicurio serde
transitively, which makes autodetection ambiguous and risks silently
falling back to a plain Jackson (JSON) serializer — exactly the outcome this
module exists to rule out. `apicurio.registry.auto-register=true` is the
property that lets the very first publish register the schema with Apicurio
automatically, rather than requiring a separate registration step before any
message can flow — more on exactly what that registration produces in §6.

The incoming channel's distinguishing property is
`apicurio.registry.use-specific-avro-reader=true`, which tells the
deserializer to materialize the generated `dev.patterncatalyst.contracts.avro.OrderPlaced`
class — the same `SpecificRecord` type the producer built — rather than a
generic, untyped `GenericRecord`. That's the difference between a consumer
that calls `event.getOrderId()` with compile-time type safety and one that
calls `event.get("orderId")` and casts the result by hand.

The last thing worth naming in this block is the split between `%dev`/`%prod`
and the unconfigured `%test` profile. Neither the registry URL
nor the Kafka bootstrap servers are set for tests, and that omission is
the point, following the exact pattern Chapter 27 already named for every
other service's datasource: when a profile has no explicit connection
target, Quarkus Dev Services stands up its own disposable Testcontainers
instance automatically. Here that means two containers, not one — a Kafka
broker *and* an Apicurio registry — both ephemeral, both torn down when the
test JVM exits, which is exactly what makes `mvn verify` on this module
self-contained: no dependency on the long-lived podman-stack Apicurio
instance at all, as long as a container runtime is available to the test run.

## Register, serialize, deserialize

The producer side of this module is kept small and standalone — no outbox,
no database, no correlation to the real order aggregate:

```java
// examples/09-schema-registry-demo/.../OrderAvroProducer.java
@Path("/demo/orders")
@ApplicationScoped
public class OrderAvroProducer {

    @Channel("order-avro-out")
    Emitter<OrderPlaced> orderAvroEmitter;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public OrderPlaced emitDemoOrder() {
        OrderPlaced event = buildDemoEvent();
        orderAvroEmitter.send(event);
        return event;
    }
}
```

A `POST /demo/orders` call builds one `OrderPlaced` — the generated
`SpecificRecord` class, built via `OrderPlaced.newBuilder()` — and sends it
through the `order-avro-out` channel's `Emitter`. That's the same
`@Channel`/`Emitter` idiom every outbox relay in this repository already
uses (`examples/07-order-service`'s `OrderOutboxRelay` included); what's
different is everything downstream of the `send()` call, which the
`AvroKafkaSerializer` now owns. On the very first send, that serializer
contacts Apicurio, registers the `OrderPlaced` schema under an artifact named
`order.events.avro.demo-value` (the topic name plus Apicurio's own
`-value` convention for a value-schema artifact), gets back a global schema
id, and prefixes the serialized Avro bytes on the wire with a reference to
that id — not the schema itself, just a small, fixed-width pointer to it.

On the consumer side, `OrderAvroConsumer` does the mirror-image work:

```java
// examples/09-schema-registry-demo/.../OrderAvroConsumer.java
@ApplicationScoped
public class OrderAvroConsumer {

    private final List<OrderPlaced> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<OrderPlaced> last = new AtomicReference<>();

    @Incoming("order-avro-in")
    public void consume(OrderPlaced event) {
        received.add(event);
        last.set(event);
    }
}
```

This is what "the consumer fetches the schema by global id" means concretely
on the wire: `AvroKafkaDeserializer` reads that id prefix off each message,
looks it up against the same Apicurio registry URL the producer used (caching
the result locally, since a given id's schema never changes), and uses the
retrieved schema to deserialize the remaining bytes back into an `OrderPlaced`
instance — specifically the generated `SpecificRecord` class, not a generic
map of field names, because `use-specific-avro-reader=true` is set. Neither
side ever has to embed the full schema text in every message — Avro's binary
encoding is schema-less on its own, and it's exactly the registry's job to be
the thing both sides trust to resolve an id back into a schema.

`OrderAvroRoundTripTest` is the proof that this chain actually holds
together, not just an architecture diagram's promise that it should:

```java
// examples/09-schema-registry-demo/src/test/java/.../OrderAvroRoundTripTest.java
@Test
void emittedEventIsConsumedFieldForFieldEqual() {
    OrderPlaced sent = producer.emitDemoOrder();

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        OrderPlaced lastReceived = consumer.last();
        assertNotNull(lastReceived, "expected the consumer to have received an event by now");
        assertEquals(sent.getOrderId(), lastReceived.getOrderId());
    });

    OrderPlaced received = consumer.last();
    assertEquals(sent.getOrderId(), received.getOrderId());
    assertEquals(sent.getCustomerId(), received.getCustomerId());
    assertEquals(sent.getCustomerEmail(), received.getCustomerEmail());
    assertEquals(sent.getTotalCents(), received.getTotalCents());
    assertEquals(sent.getPaymentMethod(), received.getPaymentMethod());
    assertEquals(sent.getConfirmationMessage(), received.getConfirmationMessage());
    // ... placedAt compared after truncating both sides to millisecond
    // precision, since Avro's timestamp-millis logical type truncates on
    // the wire
}
```

This test runs against real Kafka and a real Apicurio registry — both
Dev-Services-provisioned Testcontainers, per the `%test` profile discussion
above, no stand-in for either. It emits one event over the actual
`order-avro-out`/`order-avro-in` channel pair, waits (bounded, not a fixed
sleep, following the same eventual-consistency testing discipline Chapter 17
established for the notification cutover) for the consumer to report it, and
then asserts field-for-field equality across every one of the seven fields —
including `paymentMethod`, the exact field whose absence from notification's
hand-written copy opened this chapter. `mvn -f examples/09-schema-registry-demo/pom.xml
verify` runs this test, among others, and it was run: BUILD SUCCESS, with
this round trip passing against live infrastructure, not against a stub.

{% include excalidraw.html file="schema-registry-contract-flow" alt="A left-to-right sequence. OrderAvroProducer sends an OrderPlaced SpecificRecord through the order-avro-out channel; AvroKafkaSerializer auto-registers the schema with Apicurio (artifact order.events.avro.demo-value) and prefixes the Kafka message with a global schema id. Apicurio stores the schema under that id and, because a BACKWARD compatibility rule is attached, enforces it on every later version: a v2 that adds giftMessage with a default returns HTTP 200 and is accepted; a v3 that retypes totalCents from long to string returns HTTP 409 RuleViolationException and is rejected. OrderAvroConsumer's AvroKafkaDeserializer reads the id off an accepted message, fetches the matching schema from Apicurio, and deserializes back into a field-equal OrderPlaced instance." caption="Figure 28.2 — The contract flow: auto-register, store and enforce, fetch by id, deserialize — with the v2 accepted / v3 rejected outcomes that make enforcement concrete" %}

## Schema evolution and compatibility rules

Round-tripping one fixed schema proves serialization works. It does not
prove the thing a registry is actually for — catching a *breaking* change
before it ships. That proof needs at least two schema versions and a rule
that can tell them apart, and `SchemaCompatibilityTest` builds exactly that
scenario against a live registry.

Apicurio supports the standard compatibility rule vocabulary: BACKWARD (a
new schema can read data written by the previous one — the rule this chapter
uses), FORWARD (the previous schema can read data written by the new one),
and FULL (both directions hold at once), each with a transitive variant that
checks against every prior version rather than just the immediately
preceding one. BACKWARD is the rule most teams reach for first because it
matches the most common real deployment order: upgrade consumers before
producers, so a consumer running the new schema needs to be able to read
messages a still-old producer is still writing.

The test registers `order-placed-v1.avsc` as a fresh artifact, attaches a
BACKWARD `COMPATIBILITY` rule to it, and then exercises two differently
shaped evolutions against that same artifact. The first,
`order-placed-v2-backward-compatible.avsc`, adds exactly one new field:

```json
// examples/09-schema-registry-demo/src/test/resources/avro/order-placed-v2-backward-compatible.avsc
{ "name": "giftMessage", "type": ["null", "string"], "default": null,
  "doc": "NEW in v2 — an optional gift message, defaulted to null so
          v1-written data (which has no such field) deserializes cleanly
          under this schema." }
```

Because `giftMessage` carries a default (`null`, via the `["null", "string"]`
union), a reader using the v2 schema can still make sense of a message
written by v1 — the missing field is simply filled in with its default. That
is precisely what BACKWARD compatibility requires, and Apicurio accepts it:

```java
// SchemaCompatibilityTest.java
given()
        .body("""
                { "version": "2", "content": { "content": %s, "contentType": "application/json" } }
                """.formatted(jsonQuote(v2Schema)))
        .when()
        .post("/groups/default/artifacts/" + ARTIFACT_ID + "/versions")
        .then()
        .statusCode(200)
        .body("version", equalTo("2"));
```

HTTP 200, version `"2"` recorded. The second evolution,
`order-placed-v3-incompatible.avsc`, does something structurally different:
it keeps the same field name but changes its type.

```json
// examples/09-schema-registry-demo/src/test/resources/avro/order-placed-v3-incompatible.avsc
{ "name": "totalCents", "type": "string",
  "doc": "INCOMPATIBLE CHANGE: retyped from 'long' (v1/v2) to 'string' —
          no valid Avro promotion path, breaks BACKWARD compatibility." }
```

`totalCents` goes from `long` to `string`. Avro's schema-resolution rules
define a specific, narrow set of type promotions a reader schema is allowed
to apply when resolving a writer schema that doesn't match exactly — `int`
promotes to `long`, `float`, `double`; `long` promotes to `float` or
`double` — and `long` to `string` is not among them. A v3 reader has no
defined way to interpret bytes a v1 writer laid down as a long integer as a
string, so the same request, same artifact, same rule, gets a different
answer:

```java
given()
        .body("""
                { "version": "3", "content": { "content": %s, "contentType": "application/json" } }
                """.formatted(jsonQuote(v3Schema)))
        .when()
        .post("/groups/default/artifacts/" + ARTIFACT_ID + "/versions")
        .then()
        .statusCode(409)
        .body("name", equalTo("RuleViolationException"));
```

HTTP 409, `RuleViolationException`. This is the registration that never
happens — the schema is rejected at the moment someone tries to publish it,
not discovered as a deserialization failure in a consumer three services
downstream, days or weeks later. `SchemaCompatibilityTest`'s own class
javadoc notes that every one of these request/response shapes — the artifact
creation body, the rules endpoint, the exact 409 payload — "was verified by
hand against a live `quay.io/apicurio/apicurio-registry:3.1.7` container
before being encoded here — not guessed from documentation," which matters
because Apicurio's exact REST contract (the v3 API in particular) is young
enough that assuming shapes from memory would have been a real risk.

`demos/demo-schema-registry.sh` runs this identical progression against the
live podman stack rather than Dev Services — create an artifact, apply the
BACKWARD rule, register v2 (expect 200), register v3 (expect 409) — and
prints its own pass/fail verdict based on the actual HTTP status codes it
observes, not a canned expectation. Run end to end against the live stack,
it reports exactly this outcome: v2 accepted, v3 rejected with 409 and
`RuleViolationException`, the same two results `SchemaCompatibilityTest`
already proved under Dev Services.

## Why this stays a demonstrator

It would be easy to read §§3–7 and conclude the natural next step is cutting
`order.placed` over to Avro wholesale. This chapter does not do that, and the
reason is the same scope discipline DRQ-038 already modeled once: this
project does not retrofit infrastructure into a system that currently works
just because the infrastructure is now proven to work in isolation.

The live flows stay JSON, permanently, as DRQ-038 already decided — this
chapter does not revisit that call. `examples/00-monolith` through
`examples/07-order-service`'s outbox, and every real consumer of
`order.placed` — notification, payment — are exactly the code they were
before this chapter, unedited. `examples/09-schema-registry-demo` publishes
to its own topic, `order.events.avro.demo`, which no other service in this
project reads or writes. There is no flag to flip here and no cutover to
reverse, unlike Chapter 17's two-flag notification cutover — because there
is nothing being cut over. The demo script's own header states this as
directly as the code does: "this does NOT touch, replace, or reroute the real
`order.placed` JSON event ... There is no flag to flip and no cutover to
reverse."

What the parallel path buys that a hypothetical future cutover chapter could
not have bought more cheaply is twofold. First, it proves the mechanism —
auto-registration, specific-record (de)serialization, field-for-field
fidelity across the wire — against real infrastructure rather than asserting
it from the Apicurio documentation. Second, and this is the sharper point,
it lets this chapter show you the *rejected* case. A production system's
`order.placed` topic cannot safely be used to demonstrate "here is what
happens when someone tries to publish a breaking schema change" — that would
mean setting out to break a live contract three real services
depend on, just to capture the error message for a book. The parallel
topic's whole value is that the v3-incompatible test *can* run that
experiment, repeatedly, on demand, with zero risk to anything that matters,
because `order.events.avro.demo` is a topic this book invented specifically
so it could be broken safely.

That framing also sets up a fair expectation for what a full cutover would
actually require, if this project's own operational needs ever did justify
one. It is not "swap the serializer." Every consumer of the current JSON
topic would need a coordinated upgrade window, or the BACKWARD-compatibility
discipline this chapter demonstrates would need to govern the transition
itself — old JSON readers and new Avro writers cannot coexist on one topic
without a bridging strategy. And the harder piece: Chapter 19 already
introduced Debezium CDC as the mechanism inventory's extraction needed for
lower-latency, replication-slot-based event capture, reading Postgres's
write-ahead log directly rather than polling an outbox table. A CDC
connector that wanted to carry an Avro-encoded, registry-governed payload
instead of CDC's own default JSON envelope would need its own converter
configuration pointed at this same registry — real, non-trivial work this
chapter does not attempt, named here only so a reader doesn't assume the
rest of the migration is a smaller lift than it is.

## What you learned

- **A schema registry is a central contract of record with teeth** — it
  doesn't just document a shape, like a comment in a hand-written record
  does; configured with a compatibility rule, it *refuses* a registration
  that would break that rule, at the moment someone tries to publish it,
  which is the one property three independently authored Java records can
  never give you no matter how careful their authors are.
- **The drift this chapter opened with is real, not illustrative** —
  `examples/03-notification-service`'s `OrderPlacedEvent` is missing
  `paymentMethod`, a field both `examples/07-order-service`'s producer and
  `examples/05-payment-service`'s consumer copy carry, and nothing in this
  project's build today catches that gap.
- **Avro's logical types and default values are what make evolution
  possible at all** — `giftMessage`'s `["null", "string"]` union with a
  `null` default is what lets a v2 reader make sense of v1-written data;
  without a default, adding even an optional-sounding field can break
  BACKWARD compatibility.
- **Not every type change is a promotion** — Avro defines a specific, narrow
  set of safe type promotions (`int`→`long`, `long`→`double`, and similarly
  narrow cases), and `totalCents`'s `long`→`string` retype falls well
  outside that set, which is exactly why Apicurio's BACKWARD rule rejects it
  with HTTP 409 rather than attempting a conversion that has no defined
  meaning.
- **Proving a mechanism and adopting it project-wide are different
  commitments** — this chapter proves Avro + Apicurio registration,
  round-trip fidelity, and compatibility enforcement all work, against real
  infrastructure, while choosing not to retrofit any of this
  project's six working services onto it, because nothing about their
  current JSON contracts has failed in a way only a registry could fix.

Chapter 29 turns from the contract a service promises to the question of how
that service — and the seven others this project now runs, counting this
chapter's own demonstrator and the strangler proxy — actually gets deployed:
containers, image builds, and the first steps toward the Kubernetes substrate
Part 9 stands this whole system on.

---

*Verification status: <span class="status status--verified">verified</span>.
`mvn -f examples/09-schema-registry-demo/pom.xml verify` was run and reported
BUILD SUCCESS, including `OrderAvroRoundTripTest` (a real `OrderPlaced` event
emitted and consumed field-for-field-equal over Dev-Services-provisioned
Kafka and Apicurio) and `SchemaCompatibilityTest` (v1 registers; v2, adding
`giftMessage` with a default, accepted with HTTP 200; v3, retyping
`totalCents` from `long` to `string`, rejected with HTTP 409
`RuleViolationException`) — all run against real, ephemeral Testcontainers,
not mocks. `demos/demo-schema-registry.sh` was also run against the live
podman stack (`apicurio` on `:8095`, the shared `kafka` broker) and
reproduced the identical round-trip and v2-accepted/v3-rejected outcomes.
Cited: `examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc`,
`src/main/resources/application.properties`, `OrderAvroProducer.java`,
`OrderAvroConsumer.java`, `pom.xml`; `src/test/java/.../OrderAvroRoundTripTest.java`,
`SchemaCompatibilityTest.java`, and the `src/test/resources/avro/`
v2/v3 fixtures; `compose.yaml`'s `apicurio` service block;
`.github/workflows/code-ci.yml`'s `schema-registry-gate` job
(`mvn -f examples/09-schema-registry-demo/pom.xml verify`, no services
block — the module provisions its own Kafka and Apicurio via Dev Services);
`_plans/decisions.md` DRQ-038 and DRQ-076; and, for the drift this chapter
opened with, `examples/07-order-service/.../OrderPlacedEvent.java` (seven
fields), `examples/05-payment-service/.../OrderPlacedEvent.java` (seven
fields, `paymentMethod` included), and
`examples/03-notification-service/.../OrderPlacedEvent.java` (six fields,
`paymentMethod` absent) — all three read directly, not reconstructed from
memory. Re-confirm by reproducing: `demos/demo-schema-registry.sh` against a
live `podman compose up` stack, and `mvn -f examples/09-schema-registry-demo/pom.xml
verify` independently of the podman stack entirely (Dev Services needs only
a container runtime). The one property worth re-checking on a fresh run:
Apicurio's H2 storage is ephemeral by design, so any artifact registered by
hand against the live stack's registry (as opposed to by the test suite or
the demo script, each of which registers its own fresh artifact) will not
survive a restart of the `apicurio` container.*
