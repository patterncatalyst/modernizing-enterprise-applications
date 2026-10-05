package dev.patterncatalyst.monolith.common.events;

import java.time.Instant;

/**
 * ch.24 (r07/S3) — the JSON wire contract for the {@code shipment.dispatched}
 * event (topic: {@link dev.patterncatalyst.monolith.common.Topics#SHIPMENT_DISPATCHED}),
 * the successful outcome of the ORCHESTRATED shipping saga (DRQ-056/058).
 *
 * <p><b>Produced by:</b> the shipping service ({@code
 * examples/06-shipping-service}, r07/S4+S5) after its Camel Saga EIP
 * coordinator consumes {@code payment.captured}, enriches the order
 * details, persists the local {@code Shipment} row as {@code DISPATCHED},
 * and successfully books the carrier — written atomically with that state
 * change via the shipping service's own transactional outbox (DRQ-063,
 * mirroring DRQ-053).
 *
 * <p><b>Consumed by:</b> the monolith's order-saga reaction ({@code
 * order.OrderSagaListener}, r07/S6), which transitions the order from
 * {@code OrderStatus.AWAITING_SHIPMENT} to {@code OrderStatus.CONFIRMED}
 * (DRQ-061) — the order's {@code CONFIRMED} transition moves one hop later
 * than ch.23, where it fired directly on {@code payment.captured}
 * (DRQ-058). The reaction must be idempotent: a redelivered {@code
 * shipment.dispatched} for an order that already left {@code
 * AWAITING_SHIPMENT} is a no-op (DRQ-064, reusing DRQ-051).
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
 * @param orderId the saga correlation key — the order this shipment belongs
 *     to (matches {@code PaymentCaptured#orderId} that triggered the saga)
 * @param shipmentId the shipping service's own identifier for the
 *     dispatched shipment row
 * @param address the shipping address the shipment was dispatched to
 * @param status always the literal {@code "DISPATCHED"}, carried on the
 *     wire (not just implied by the topic) so a consumer reading a
 *     merged/replayed stream can branch on the payload alone
 * @param occurredAt when the shipping service's saga dispatched the
 *     shipment
 */
public record ShipmentDispatched(
        Long orderId,
        Long shipmentId,
        String address,
        String status,
        Instant occurredAt) {
}
