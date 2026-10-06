package dev.patterncatalyst.order;

import java.time.Instant;

/**
 * ch.26 (r08/S3, DRQ-073) — this service's own copy of the {@code
 * shipment.dispatched} wire contract, field-for-field identical to the
 * monolith's {@code common.events.ShipmentDispatched} (verified against
 * {@code examples/00-monolith/.../common/events/ShipmentDispatched.java})
 * and to the shipping service's producer-side copy
 * ({@code examples/06-shipping-service/.../ShipmentDispatched.java}). Per
 * DRQ-038, this is a plain record, not shared code between Maven reactors.
 *
 * <p><b>Produced by:</b> the shipping service ({@code
 * examples/06-shipping-service}) after its Camel Saga EIP coordinator
 * successfully books the carrier, via its own transactional outbox
 * (DRQ-063, mirroring DRQ-053).
 *
 * <p><b>Consumed by:</b> this service's saga reaction (the S5 lifted {@code
 * OrderSagaListener}, DRQ-074), which transitions the order from {@code
 * OrderStatus.AWAITING_SHIPMENT} to {@code OrderStatus.CONFIRMED} (DRQ-061).
 * Not wired in this step: this record is authored contract-only (ch.26 S3);
 * the {@code @Incoming} consumer lands in S5.
 *
 * @param orderId the saga correlation key — the order this shipment belongs to
 * @param shipmentId the shipping service's own identifier for the dispatched
 *     shipment row
 * @param address the shipping address the shipment was dispatched to
 * @param status always the literal {@code "DISPATCHED"}, carried on the wire
 * @param occurredAt when the shipping service's saga dispatched the shipment
 */
public record ShipmentDispatched(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt) {
}
