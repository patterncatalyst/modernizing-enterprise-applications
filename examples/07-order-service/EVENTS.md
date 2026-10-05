# order-service — event contract (ch.26 S3, DRQ-073)

This service's event/DTO vocabulary is authored **field-for-field** against
the established wire shapes (DRQ-038: plain JSON, not shared code between
Maven reactors — each reactor authors a structurally identical record
against the same agreed JSON shape). `common/Topics.java` in the monolith
stays the registry of record for topic names until the monolith is frozen
at S10; this file documents how this service, specifically, produces and
consumes those topics.

**Scope of this step (ch.26 S3):** records only. No `@Incoming`/
`@Outgoing`/`@Channel`, outbox relay, consumer, or projection code exists
yet — those land in S5 (lifted saga reactions + own outbox) and S6 (CQRS
read-model projection). The channel→topic `application.properties`
mapping is likewise deferred to S5, mirroring how `examples/05-payment-
service` and `examples/06-shipping-service` only added their
`mp.messaging.*` channel config at their own wiring step, not at their
records-only S3.

**Updated at S5 (DRQ-073/074):** the wiring described below now exists.
`order.placed` is produced by `OrderOutboxRelay` (own `@Channel("order-placed")`
emitter, `outbox` table reused unchanged from S4). The four consumed events
are reacted to by `OrderSagaListener`'s `@Incoming` consumers
(`payment-captured`/`payment-declined`/`shipment-dispatched`/
`shipment-failed` channels), in this service's OWN consumer group
(defaulted from `quarkus.application.name` = `order-service`). The
read-model projection (S6) is still not wired.

## Produces

| Topic | Record | Written by |
|---|---|---|
| `order.placed` | `dev.patterncatalyst.order.OrderPlacedEvent` | This service's own transactional outbox (S5: `OrderOutboxEvent`/`OrderOutboxRelay`, DRQ-073, mirroring DRQ-053), replacing the monolith's `common.outbox.OutboxRelay` relay as the external producer of this topic. Written atomically with the `Order` row on `OrderService#placeOrder`. |

Consumed downstream by the payment service (`examples/05-payment-service`),
which reacts with exactly one of `payment.captured` / `payment.declined`.

## Consumes

| Topic | Record | Produced by | Reaction (wired at S5, `OrderSagaListener`) |
|---|---|---|---|
| `payment.captured` | `dev.patterncatalyst.order.PaymentCaptured` | `examples/05-payment-service` (its own transactional outbox, DRQ-053) | Transition order `PENDING` → `OrderStatus.AWAITING_SHIPMENT` (DRQ-049/050); shipping is driven by the shipping service's saga off `payment.captured`, not in-process |
| `payment.declined` | `dev.patterncatalyst.order.PaymentDeclined` | `examples/05-payment-service` (its own transactional outbox, DRQ-053) | Transition order to `OrderStatus.PAYMENT_DECLINED`, compensating gRPC inventory `Release` for every reserved sku (DRQ-042/049) |
| `shipment.dispatched` | `dev.patterncatalyst.order.ShipmentDispatched` | `examples/06-shipping-service` (its own transactional outbox, DRQ-063/053) | Transition order from `OrderStatus.AWAITING_SHIPMENT` to `OrderStatus.CONFIRMED` (DRQ-061) |
| `shipment.failed` | `dev.patterncatalyst.order.ShipmentFailed` | `examples/06-shipping-service` (its own transactional outbox, DRQ-063/053) | Transition order to terminal `OrderStatus.SHIPPING_FAILED`, compensating gRPC inventory `Release` for every reserved sku (DRQ-042/049/060) |

All four consumed records are field-for-field identical to the monolith's
`common.events.*` copies and to the producing services' own copies
(`examples/05-payment-service/.../PaymentCaptured.java` and
`PaymentDeclined.java`; `examples/06-shipping-service/.../
ShipmentDispatched.java` and `ShipmentFailed.java`) — JSON fields bind by
name, not position, so the independently-authored records interoperate
across the wire without shared code.

## Serialization

JSON (Jackson) per DRQ-038. Avro + Apicurio schema registry is deferred to
ch.28, same as every other extracted service in this build.
