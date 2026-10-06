package dev.patterncatalyst.order;

import java.time.Instant;

/**
 * ch.26 (r08/S3, DRQ-073) — this service's own copy of the {@code
 * shipment.failed} wire contract, field-for-field identical to the
 * monolith's {@code common.events.ShipmentFailed} (verified against
 * {@code examples/00-monolith/.../common/events/ShipmentFailed.java}) and
 * to the shipping service's producer-side copy
 * ({@code examples/06-shipping-service/.../ShipmentFailed.java}). Per
 * DRQ-038, this is a plain record, not shared code between Maven reactors.
 *
 * <p><b>Produced by:</b> the shipping service ({@code
 * examples/06-shipping-service}) via its Camel Saga EIP coordinator's
 * compensation route, invoked on any abort/timeout, via its own
 * transactional outbox (DRQ-063, mirroring DRQ-053).
 *
 * <p><b>Consumed by:</b> this service's saga reaction (the S5 lifted {@code
 * OrderSagaListener}, DRQ-074), which transitions the order to the terminal
 * {@code OrderStatus.SHIPPING_FAILED} (DRQ-061) and issues the compensating
 * gRPC inventory {@code Release} for every reserved sku (DRQ-042/049/060) —
 * the order context, not the shipping service, performs this undo because
 * it owns the reserved-line snapshot. Not wired in this step: this record
 * is authored contract-only (ch.26 S3); the {@code @Incoming} consumer
 * lands in S5.
 *
 * @param orderId the saga correlation key — the order this shipment attempt
 *     belongs to
 * @param shipmentId the shipping service's own identifier for the cancelled
 *     shipment row (or attempt record, if the failure occurred before a row
 *     was persisted)
 * @param address the shipping address the dispatch attempt was for
 * @param status always the literal {@code "FAILED"}, carried on the wire
 * @param occurredAt when the shipping service's saga compensation ran
 * @param reason why the dispatch failed
 */
public record ShipmentFailed(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt,
        String reason) {
}
