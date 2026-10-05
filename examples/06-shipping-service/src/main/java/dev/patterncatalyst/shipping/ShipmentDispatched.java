package dev.patterncatalyst.shipping;

import java.time.Instant;

/**
 * r07/ch.24 S5 -- this service's own copy of the {@code shipment.dispatched}
 * wire contract (DRQ-038), field-for-field identical to the monolith's
 * mirror record (verified against
 * {@code examples/00-monolith/.../common/events/ShipmentDispatched.java},
 * r07/ch.24 S3).
 *
 * <p><b>Produced by:</b> {@code ShipmentSagaSteps#emitDispatched} (the
 * saga's step 4), written atomically with the {@link Shipment} row's
 * PENDING-&gt;DISPATCHED transition via this service's own transactional
 * outbox ({@link ShipmentOutboxEvent}, DRQ-063/DRQ-053).
 *
 * <p><b>Consumed by:</b> the monolith's future order-saga reaction (r07/S6,
 * not yet wired), which will transition the order {@code AWAITING_SHIPMENT}
 * -&gt; {@code CONFIRMED} (DRQ-061).
 *
 * @param orderId the saga correlation key
 * @param shipmentId this service's own identifier for the dispatched
 *     shipment row
 * @param address the shipping address the shipment was dispatched to
 * @param status always the literal {@code "DISPATCHED"} on the wire
 * @param occurredAt when the saga dispatched the shipment
 */
public record ShipmentDispatched(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt) {

    public static final String STATUS = "DISPATCHED";
}
