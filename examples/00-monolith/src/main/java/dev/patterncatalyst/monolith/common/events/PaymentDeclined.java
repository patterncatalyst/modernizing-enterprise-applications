package dev.patterncatalyst.monolith.common.events;

import java.time.Instant;

/**
 * ch.23 (r06/S3) — the JSON wire contract for the {@code payment.declined}
 * event (topic: {@link dev.patterncatalyst.monolith.common.Topics#PAYMENT_DECLINED}),
 * the other outcome of the choreographed payment saga (DRQ-048).
 *
 * <p><b>Produced by:</b> the payment service ({@code examples/05-payment-service},
 * r06/S4+S5) after it consumes {@code order.placed} and the capture is
 * declined (the same deterministic {@code DECLINE}-in-method demo rule the
 * monolith's synchronous {@code PaymentService} uses today), written
 * atomically via the payment service's own transactional outbox (DRQ-053).
 * No funds are captured on this path.
 *
 * <p><b>Consumed by:</b> the monolith's order-saga reaction (r06/S6, {@code
 * order.OrderSagaListener} — not yet built as of this contract-only step),
 * which transitions the order to {@code OrderStatus.PAYMENT_DECLINED} and
 * issues the compensating inventory {@code Release} for every sku the order
 * reserved — replacing the ch.19 in-line {@code catch} compensation
 * (DRQ-042/DRQ-049). The reaction must be idempotent: a redelivered {@code
 * payment.declined} for an order that already left {@code PENDING} is a
 * no-op, and the compensating Release fires only on the first delivery
 * (DRQ-051).
 *
 * <p>This is a plain record, not shared code between the two Maven reactors —
 * the monolith and the payment service each author a structurally identical
 * record against the same agreed JSON shape (DRQ-038: plain JSON; Avro +
 * Apicurio deferred to ch.28). Field names/casing here are the source of
 * truth for that shape within this reactor.
 *
 * @param orderId the saga correlation key — the order this payment belongs to
 *     (matches {@code OrderPlacedEvent#orderId} that triggered the capture
 *     attempt)
 * @param paymentId the payment service's own identifier for the declined
 *     payment row (or attempt record)
 * @param amountCents the amount that was attempted, in cents
 * @param method the payment method used (mirrors the monolith's {@code
 *     Payment.method})
 * @param status always the literal {@code "DECLINED"}, carried on the wire
 *     (not just implied by the topic) so a consumer reading a merged/replayed
 *     stream can branch on the payload alone
 * @param reason why the payment was declined (e.g. the demo's deterministic
 *     decline rule, or a gateway-style decline message) — carried for
 *     observability/logging even though the current order-saga reaction
 *     (DRQ-049) does not branch on its value, only on the event's occurrence
 * @param declinedAt when the payment service recorded the decline
 */
public record PaymentDeclined(
        Long orderId,
        Long paymentId,
        long amountCents,
        String method,
        String status,
        String reason,
        Instant declinedAt) {
}
