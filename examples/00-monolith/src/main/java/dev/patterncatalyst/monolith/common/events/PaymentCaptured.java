package dev.patterncatalyst.monolith.common.events;

import java.time.Instant;

/**
 * ch.23 (r06/S3) — the JSON wire contract for the {@code payment.captured}
 * event (topic: {@link dev.patterncatalyst.monolith.common.Topics#PAYMENT_CAPTURED}),
 * one of the two outcomes of the choreographed payment saga (DRQ-048).
 *
 * <p><b>Produced by:</b> the payment service ({@code examples/05-payment-service},
 * r06/S4+S5) after it consumes {@code order.placed} and successfully captures
 * funds, written atomically with the {@code Payment} row via the payment
 * service's own transactional outbox (DRQ-053).
 *
 * <p><b>Consumed by:</b> the monolith's order-saga reaction ({@code
 * order.OrderSagaListener}, r06/S6, the ONLY path since r06/S9
 * decommission), which transitions the order to {@code
 * OrderStatus.CONFIRMED} and dispatches shipping (DRQ-049/DRQ-050). The
 * reaction must be idempotent: a redelivered {@code payment.captured} for an
 * order that already left {@code PENDING} is a no-op (DRQ-051).
 *
 * <p>This is a plain record, not shared code between the two Maven reactors —
 * the monolith and the payment service each author a structurally identical
 * record against the same agreed JSON shape (DRQ-038: plain JSON; Avro +
 * Apicurio deferred to ch.28). Field names/casing here are the source of
 * truth for that shape within this reactor.
 *
 * @param orderId the saga correlation key — the order this payment belongs to
 *     (matches {@code OrderPlacedEvent#orderId} that triggered the capture)
 * @param paymentId the payment service's own identifier for the captured
 *     payment row
 * @param amountCents the captured amount, in cents
 * @param method the payment method used (the checkout's {@code paymentMethod},
 *     carried on the {@code order.placed} handoff — see {@code OrderCreate})
 * @param status always the literal {@code "CAPTURED"}, carried on the wire
 *     (not just implied by the topic) so a consumer reading a merged/replayed
 *     stream can branch on the payload alone
 * @param capturedAt when the payment service captured the payment
 */
public record PaymentCaptured(
        Long orderId,
        Long paymentId,
        long amountCents,
        String method,
        String status,
        Instant capturedAt) {
}
