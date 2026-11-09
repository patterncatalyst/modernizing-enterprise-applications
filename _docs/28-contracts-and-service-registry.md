---
title: "Contracts & the Service Registry"
order: 28
part: "Communication & Contracts"
description: "A schema registry replaces three hand-copied event records with one governed contract and rejects a breaking change at registration, shown with Avro and Apicurio on a parallel topic."
---

## Learning objectives

By the end of this chapter you can:

- Say what a schema registry gives you that a hand-maintained JSON contract cannot, and why `paymentMethod` drifted out of the notification service's copy of `OrderPlacedEvent`.
- Write an Avro `.avsc` that mirrors a Java record field for field, using `timestamp-millis` to document what a number means.
- Wire `quarkus-avro` and `quarkus-apicurio-registry-avro` through `application.properties` alone: registry URL, `auto-register`, `use-specific-avro-reader`.
- Trace register, serialize and deserialize: auto-registration on first publish, a global schema id on the wire, and the consumer's lookup of that id.
- Attach a BACKWARD, FORWARD or FULL compatibility rule and predict whether a schema change is accepted or rejected.
- Explain why the registry is proven on a parallel topic and what a real cutover of `order.placed` would add.

## Why this matters

The `order.placed` event is JSON, and no Maven reactor in the project shares a module with another. A contract three services must agree on is therefore authored three times, by hand, in three `OrderPlacedEvent.java` files. `examples/07-order-service` has seven fields: `orderId`, `customerId`, `customerEmail`, `totalCents`, `paymentMethod`, `confirmationMessage`, `placedAt`. `examples/05-payment-service` has the same seven, because its decline rule needs `paymentMethod`. `examples/03-notification-service` has six. `paymentMethod` is missing.

Nothing catches it. Jackson binds by field name, so the notification consumer never sees a key the producer has been sending, and there is no deserialization error, startup check or red test. The record's javadoc still claims to be the service's copy of the monolith's payload shape. This drift is in the code today. It is the cost of an earlier decision to keep the event as hand-written JSON and defer Avro and a registry until the outbox pattern was proven.

The fix here is additive. The live flows stay JSON, and `examples/00-monolith` through `examples/07-order-service` and every consumer of `order.placed` are untouched. A new module, `examples/09-schema-registry-demo`, publishes a field-for-field Avro mirror of `OrderPlacedEvent` to its own topic behind a real Apicurio registry. That shows the two things a registry buys, a central contract of record and compatibility enforcement that rejects a breaking change before it ships, without touching a system that works.

{% include excalidraw.html file="hand-duplicated-vs-registry-schema" alt="Two side-by-side panels. Left, today: three independent Java records, order-service's seven-field OrderPlacedEvent, payment-service's matching copy, and notification-service's six-field copy missing paymentMethod, with a drift arrow at the missing field. Right, the demonstrator: one order-placed-v1.avsc registered once with Apicurio, a producer and a consumer both generated from it, and a compatibility rule between any future version and the registry." caption="Figure 28.1: Three hand-duplicated, silently drifting records versus one registry-governed schema, demonstrated in parallel to the live flows" %}

## What a schema registry is for

When a producer and a consumer in two deployables must agree on a message shape, where does the agreement live and who enforces it? In the live flows the answer is nowhere. It exists as three authors' understanding of the original event, with nothing checking that the understandings stay equal. A registry makes the agreement explicit: a schema is registered once under an artifact id, and the registry, not a comment or a reviewer's memory, knows the contract.

That buys two different things:

- **Governance.** One place answers "what does the `order.placed` contract look like today, and three versions ago?" instead of diffing three Java files across repositories.
- **Compatibility enforcement.** A rule (BACKWARD, FORWARD, FULL, each with a transitive variant) makes the registry refuse a new version that violates it, at registration, before a message is published under the broken shape. A hand-maintained record cannot do this: nothing stops a committed retyped field, and nothing reports it until a deserialization exception reaches production.

The payoff is decoupled deploys. Adding an optional field needs no simultaneous consumer release when the rule and the field's default make it provably safe. "Is it safe to deploy the producer first" stops being answered by reading code and hoping.

Apicurio governs Avro, Protobuf and JSON Schema as data formats and OpenAPI and AsyncAPI as API descriptions, all under one artifact, version and rule model. The demonstrator uses Avro on one Kafka topic, the corner where the drift lives. A Protobuf gRPC contract or an OpenAPI spec would run on the same rule engine.

## The contract as an `.avsc`

An Avro schema is a JSON document describing a record's fields, types, defaults and docs. Side by side, the hand-written record and the single schema:

{% include codetabs.html langs="Today: three hand-duplicated Java records (JSON binds by name)|One order-placed-v1.avsc, generated once" %}

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

The shape is the same seven fields, `paymentMethod` included. The one structural difference is `placedAt`: a `long` with the `timestamp-millis` logical type, so the schema says the number is a millisecond epoch timestamp where the Java record left that to a comment.

What matters more is how often each is authored. The Java record is written three times with nothing keeping the copies in sync, and that is where `paymentMethod` vanished. The `.avsc` is written once, and every producer and consumer gets the generated `OrderPlaced` class from the same file, compiled by `quarkus-avro` at build time.

## Adding Apicurio to the stack

Apicurio is one more service in `compose.yaml`. Its comment states the blast radius: only `examples/09-schema-registry-demo` talks to it, and the `order.placed` JSON flows are unchanged.

```yaml
# compose.yaml
apicurio:
  image: quay.io/apicurio/apicurio-registry:${APICURIO_IMAGE_TAG:-3.3.3}
  container_name: mea-apicurio
  ports:
    - "${APICURIO_HOST_PORT:-8095}:8080"
  environment:
    - APICURIO_STORAGE_KIND=sql
    - APICURIO_STORAGE_SQL_KIND=h2
  mem_limit: 512m
  healthcheck:
    test: ["CMD", "curl", "-sf", "http://localhost:9000/health/ready"]
    interval: 5s
    timeout: 3s
    retries: 12
    start_period: 20s
  restart: unless-stopped
```

Three details each resolve a real surprise:

- **API line.** The server image, the client that `quarkus-apicurio-registry-avro` pulls in transitively (3.1.7, confirmed with `mvn dependency:tree`) and the application's `.../apis/registry/v3` URL must share a major version. Apicurio's v2 and v3 REST APIs are not wire-compatible, and a mismatch shows up as confusing 404s on the first registration. The 3.3 server still speaks the v3 API the 3.1.7 client uses, but moved the health endpoints to the management port, so the healthcheck probes `:9000/health/ready` inside the container.
- **Storage.** Apicurio 3.1.7 removed the plain `mem` storage kind that 3.0.x accepted; `APICURIO_STORAGE_KIND=mem` fails to start against a live container. Embedded H2 is the closest equivalent. It is ephemeral: restarting the container wipes every artifact, rule and version. That suits a teaching module and disqualifies it for anything whose compatibility guarantees must survive.
- **Reach.** The registry is a host-reachable HTTP service on `localhost:8095`, like every other dependency in the compose stack. The demo module, a developer's `curl` and the Dev Services tests all reach it over plain HTTP, with no cluster DNS or mesh.

## Serde wiring in Quarkus

`quarkus-avro` compiles an `.avsc` into a generated `SpecificRecord` class at build time. `quarkus-apicurio-registry-avro` supplies the Kafka serializer and deserializer that talk to the registry. Both are managed by the Quarkus platform BOM the other services already use. The wiring is entirely `application.properties`, with no serializer class and no manual Kafka client configuration:

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

Four properties carry the design:

- **Connector-level registry URL.** Set once on the connector, both channels share one endpoint.
- **Explicit serializer.** The build also pulls an older Apicurio serde transitively, which makes autodetection ambiguous and risks a silent fallback to the Jackson JSON serializer, the outcome this module exists to rule out.
- **`auto-register=true`.** The first publish registers the schema, with no separate registration step.
- **`use-specific-avro-reader=true`.** The consumer gets the generated `OrderPlaced`, so it calls `event.getOrderId()` with compile-time checking instead of `event.get("orderId")` and a cast.

The topic is `order.events.avro.demo`, a name chosen so it cannot be confused with the real `order.placed`.

Neither the registry URL nor the bootstrap servers are set for the `%test` profile. As with the datasource on every other service, Quarkus Dev Services then starts disposable Testcontainers instances: here a Kafka broker and an Apicurio registry, torn down when the build finishes. `mvn verify` on the module is self-contained and needs only a container runtime.

## Register, serialize, deserialize

The producer is small and standalone: no outbox, no database, no tie to the real order aggregate.

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

It is the same `@Channel`/`Emitter` idiom the outbox relay in `examples/07-order-service` uses. What differs is downstream of `send()`. On the first send, `AvroKafkaSerializer` contacts Apicurio, registers the schema under the artifact `order.events.avro.demo-value` (topic name plus Apicurio's `-value` convention), receives a global schema id, and prefixes the Avro bytes with a fixed-width pointer to that id. The schema text never travels in the message.

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

`AvroKafkaDeserializer` reads the id prefix off each message, looks it up against the same registry URL, caches the result (an id's schema never changes), and decodes the remaining bytes into the generated `OrderPlaced`. Avro's binary encoding is schema-less on its own, so the registry is what both sides trust to resolve an id into a schema.

A round trip over Kafka and Apicurio, both Dev Services containers, returns an event equal to the one sent in all seven fields, `paymentMethod` included. `placedAt` is compared at millisecond precision, because `timestamp-millis` truncates on the wire.

{% include excalidraw.html file="schema-registry-contract-flow" alt="A left-to-right sequence. OrderAvroProducer sends an OrderPlaced record through the order-avro-out channel; AvroKafkaSerializer auto-registers the schema with Apicurio under order.events.avro.demo-value and prefixes the Kafka message with a global schema id. Apicurio stores the schema and, with a BACKWARD rule attached, enforces it on every later version: a v2 that adds giftMessage with a default is accepted, and a v3 that retypes totalCents from long to string is rejected with RuleViolationException. OrderAvroConsumer's AvroKafkaDeserializer reads the id, fetches the schema from Apicurio and deserializes into a field-equal OrderPlaced." caption="Figure 28.2: The contract flow: auto-register, store and enforce, fetch by id, deserialize, with the accepted v2 and rejected v3" %}

## Compatibility rules and schema evolution

One schema round-tripping proves serialization. A registry pays off by catching a breaking change, which needs two versions and a rule that can tell them apart. The rules:

| Rule | A new schema must |
| --- | --- |
| BACKWARD | read data written by the previous schema |
| FORWARD | be readable by the previous schema's readers |
| FULL | satisfy both |

Each has a transitive variant that checks every prior version, not just the last. BACKWARD is the usual first choice because it matches upgrading consumers before producers: a consumer on the new schema must read what a still-old producer writes.

The scenario registers `order-placed-v1.avsc` as a fresh artifact, attaches a BACKWARD `COMPATIBILITY` rule, then tries two evolutions. The first adds one field:

```json
// examples/09-schema-registry-demo/src/test/resources/avro/order-placed-v2-backward-compatible.avsc
{ "name": "giftMessage", "type": ["null", "string"], "default": null,
  "doc": "NEW in v2: an optional gift message, defaulted to null so
          v1-written data (which has no such field) deserializes cleanly
          under this schema." }
```

Because `giftMessage` has a default, a v2 reader fills the missing field in v1-written data. That is what BACKWARD requires, and the registry accepts it: HTTP 200, version `"2"` recorded.

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

The second keeps the field name and changes its type:

```json
// examples/09-schema-registry-demo/src/test/resources/avro/order-placed-v3-incompatible.avsc
{ "name": "totalCents", "type": "string",
  "doc": "INCOMPATIBLE CHANGE: retyped from 'long' (v1/v2) to 'string';
          no valid Avro promotion path, breaks BACKWARD compatibility." }
```

Avro schema resolution allows a narrow set of promotions (`int` to `long`, `float` or `double`; `long` to `float` or `double`). `long` to `string` is not among them, so a v3 reader has no defined way to read bytes a v1 writer laid down as a long. The same request, artifact and rule now fail:

```java
given()
        .body("""
                { "version": "3", "content": { "content": %s, "contentType": "application/json" } }
                """.formatted(jsonQuote(v3Schema)))
        .when()
        .post("/groups/default/artifacts/" + ARTIFACT_ID + "/versions")
        .then()
        .statusCode(400)
        .body("name", equalTo("RuleViolationException"));
```

The registry answers HTTP 400 with `RuleViolationException` on Apicurio 3.3 (3.1.7 answered 409; the `name` is the same). The registration never happens, so the break surfaces when someone tries to publish it, not as a deserialization failure in a consumer weeks later.

The v2 and v3 request and response shapes were verified by hand against a live registry container, because the v3 REST contract is young enough that guessing from memory was a real risk.

## Why this stays a demonstrator

Proving the mechanism in isolation does not justify retrofitting a system that works. The live flows stay JSON: `examples/00-monolith` through `examples/07-order-service` and every consumer of `order.placed` are unedited, and the demo module publishes to a topic nobody else reads or writes. There is no flag to flip and no cutover to reverse, because nothing is being cut over.

The parallel path buys two things. It proves auto-registration, specific-record serialization and field-for-field fidelity on real infrastructure instead of asserting them from documentation. And it lets you run the rejected case. Demonstrating what happens when someone publishes a breaking change on the production `order.placed` topic would mean breaking a contract three services depend on to capture an error message. `order.events.avro.demo` can be broken on demand at no risk.

A real cutover is more than swapping the serializer:

- Old JSON readers and new Avro writers cannot share one topic without a bridging strategy, so consumers need a coordinated upgrade window, or the BACKWARD discipline above has to govern the transition itself.
- A Debezium CDC connector that should emit an Avro payload under registry governance, instead of CDC's default JSON envelope, needs its own converter configuration pointed at the same registry.

> **Decision**
>
> Put a contract in a registry when two or more deployables must agree on a message shape and a hand-copied record has already drifted or could. Attach a BACKWARD rule and give every new field a default. Prove the mechanism on a parallel topic first, and cut a live topic over only with a plan for old readers and for CDC converters.

> **🤖 Explore with your agent**
>
> "In examples/03-notification-service, diff OrderPlacedEvent against the one in examples/07-order-service and list every missing field. Then add a v4 of examples/09-schema-registry-demo/src/main/avro/order-placed-v1.avsc that adds a `couponCode` field, choose a default that keeps it BACKWARD compatible, and register it against the BACKWARD rule with demos/demo-schema-registry.sh as the model."
>
> Check: the diff names `paymentMethod`, and the new version registers with HTTP 200 while the same field without a default returns 400 `RuleViolationException` on Apicurio 3.3.

## Build, run, observe

Run the demo (it starts the compose stack if needed and builds the demo app):

```bash
bash demos/demo-schema-registry.sh
mvn -f examples/09-schema-registry-demo/pom.xml verify
```

The script creates an artifact, applies the BACKWARD rule, registers v2 (expects 200) and v3 (expects a rejection with `RuleViolationException`; 400 on 3.3, 409 on 3.1.7), and prints a verdict from the status codes it observes. `mvn verify` runs the round trip and compatibility scenarios against Dev Services containers, so it needs no running stack. The registry is at `http://localhost:8095`.

- Delete `"default": null` from `giftMessage` in the v2 schema: the registration is rejected, because an added field with no default breaks BACKWARD.
- Remove `value.serializer` from `order-avro-out`: autodetection becomes ambiguous and the channel can fall back to a JSON serializer.
- Restart the `apicurio` container: every artifact, rule and version is gone, because storage is in-memory H2.

## For deeper coverage

- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the encoding-and-evolution treatment the BACKWARD-compatibility scenario above is a worked example of; see the [Reading List](/bibliography/).
- Gregor Hohpe & Bobby Woolf, *Enterprise Integration Patterns* (Addison-Wesley, 2003): the shared-contract thinking behind treating a message's schema as a first-class, governed artifact rather than three authors' private understanding.
- [Apache Avro: Specification](https://avro.apache.org/docs/current/specification/): the logical types and schema-resolution/type-promotion rules the `timestamp-millis` field and the `totalCents` retype both depend on.
- [Quarkus: Using Apache Kafka with Schema Registry and Avro](https://quarkus.io/guides/kafka-schema-registry-avro): the `quarkus-avro`/`quarkus-apicurio-registry-avro` wiring the `application.properties` block above follows.
- Cloud Native Design Patterns, [API Registry](https://github.com/patterncatalyst/cloud-native-design-patterns/blob/main/_docs/09-api-registry.md): the companion book's treatment of registry-governed contracts, format-agnostic across Avro, Protobuf, and OpenAPI/AsyncAPI.
