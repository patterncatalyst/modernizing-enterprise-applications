package dev.patterncatalyst.shipping;

import java.time.Instant;

/**
 * r07/ch.24 S5 -- this service's own copy of the {@code shipment.failed}
 * wire contract (DRQ-038), field-for-field identical to the monolith's
 * mirror record (verified against
 * {@code examples/00-monolith/.../common/events/ShipmentFailed.java},
 * r07/ch.24 S3).
 *
 * <p><b>Produced by:</b> {@code ShipmentSagaSteps#compensate} -- the
 * coordinator-invoked compensation route ({@code direct:ship-compensate}),
 * run by the Camel Saga EIP coordinator on ANY abort or timeout (the
 * deterministic {@code SHIP-FAIL} injection in the book-carrier step,
 * DRQ-062, or a saga timeout). Written atomically with the {@link Shipment}
 * row's PENDING-&gt;CANCELLED transition (if a row was created) via this
 * service's own transactional outbox ({@link ShipmentOutboxEvent},
 * DRQ-063/DRQ-053).
 *
 * <p><b>Consumed by:</b> the monolith's future order-saga reaction (r07/S6,
 * not yet wired), which will transition the order to the terminal
 * {@code SHIPPING_FAILED} and issue the compensating gRPC inventory
 * {@code Release} for every reserved sku (DRQ-060/061).
 *
 * @param orderId the saga correlation key
 * @param shipmentId this service's own identifier for the cancelled
 *     shipment row, or {@code null} if the failure occurred before any
 *     {@link Shipment} row was persisted (e.g. the enrich step failed)
 * @param address the shipping address the dispatch attempt was for, or
 *     {@code null} under the same condition as {@code shipmentId}
 * @param status always the literal {@code "FAILED"} on the wire
 * @param occurredAt when the saga's compensation ran
 * @param reason why the dispatch failed -- the deterministic {@code
 *     SHIP-FAIL} sentinel, a saga timeout, or (if no shipment row exists at
 *     all) an earlier-step failure; see {@code ShipmentSagaSteps#compensate}
 *     for exactly how this is classified
 */
public record ShipmentFailed(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt,
        String reason) {

    public static final String STATUS = "FAILED";
}
