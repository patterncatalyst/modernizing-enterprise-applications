package dev.patterncatalyst.monolith.common.events;

import java.time.Instant;

/**
 * ch.24 (r07/S3) — the JSON wire contract for the {@code shipment.failed}
 * event (topic: {@link dev.patterncatalyst.monolith.common.Topics#SHIPMENT_FAILED}),
 * the compensating outcome of the ORCHESTRATED shipping saga (DRQ-056/058).
 *
 * <p><b>Produced by:</b> the shipping service ({@code
 * examples/06-shipping-service}, r07/S4+S5) via its Camel Saga EIP
 * coordinator's compensation route ({@code direct:ship-compensate}),
 * invoked on any abort/timeout (e.g. the deterministic {@code SHIP-FAIL}
 * injection in the book-carrier step, DRQ-062) — this is
 * coordinator-decided, reverse-ordered compensation, the deliberate
 * contrast to ch.23's choreographed compensation (DRQ-049). The
 * coordinator first cancels the local {@code Shipment} row (to {@code
 * CANCELLED}, if one was created) and then emits this event atomically via
 * the shipping service's own transactional outbox (DRQ-063, mirroring
 * DRQ-053).
 *
 * <p><b>Consumed by:</b> the monolith's order-saga reaction ({@code
 * order.OrderSagaListener}, r07/S6), which transitions the order to the
 * terminal {@code OrderStatus.SHIPPING_FAILED} (DRQ-061) and issues the
 * compensating gRPC inventory {@code Release} for every sku the order
 * reserved — reusing the exact ch.23 {@code OrderSagaListener} + {@code
 * RemoteInventoryClient} machinery (DRQ-042/049). The order context, not
 * the shipping service, performs this undo because it owns the
 * reserved-line snapshot (DRQ-060/043): the coordinator owns the
 * *decision* to compensate, but the participant that owns the data
 * performs it. The reaction must be idempotent: a redelivered {@code
 * shipment.failed} for an order that already left {@code
 * AWAITING_SHIPMENT} is a no-op, and the compensating {@code Release}
 * fires only on the first delivery (DRQ-064, reusing DRQ-051).
 *
 * <p>This is a plain record, not shared code between the two Maven reactors —
 * the monolith and the shipping service each author a structurally
 * identical record against the same agreed JSON shape (DRQ-038: plain
 * JSON; Avro + Apicurio deferred to ch.28). Field names/casing here are the
 * source of truth for that shape within this reactor. The field types
 * match the shipping service's own {@code Shipment}/{@code ShipmentDto}
 * (id: {@code Long}, address: {@code String}, createdAt: {@code Instant})
 * so the future producer/consumer bind cleanly.
 *
 * @param orderId the saga correlation key — the order this shipment
 *     attempt belongs to (matches {@code PaymentCaptured#orderId} that
 *     triggered the saga)
 * @param shipmentId the shipping service's own identifier for the
 *     cancelled shipment row (or attempt record, if the failure occurred
 *     before a row was persisted)
 * @param address the shipping address the dispatch attempt was for
 * @param status always the literal {@code "FAILED"}, carried on the wire
 *     (not just implied by the topic) so a consumer reading a
 *     merged/replayed stream can branch on the payload alone
 * @param occurredAt when the shipping service's saga compensation ran
 * @param reason why the dispatch failed (e.g. the demo's deterministic
 *     {@code SHIP-FAIL} injection, or a carrier-booking failure message) —
 *     carried for observability/logging even though the current
 *     order-saga reaction (DRQ-060) does not branch on its value, only on
 *     the event's occurrence
 */
public record ShipmentFailed(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt,
        String reason) {
}
