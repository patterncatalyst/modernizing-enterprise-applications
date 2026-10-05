package dev.patterncatalyst.order;

import java.time.Instant;

/**
 * ch.26 (r08/S3, DRQ-073) — this service's own copy of the {@code
 * payment.captured} wire contract, field-for-field identical to the
 * monolith's {@code common.events.PaymentCaptured} (verified against
 * {@code examples/00-monolith/.../common/events/PaymentCaptured.java}) and
 * to the payment service's producer-side copy
 * ({@code examples/05-payment-service/.../PaymentCaptured.java}). Per
 * DRQ-038, this is a plain record, not shared code between Maven reactors.
 *
 * <p><b>Produced by:</b> the payment service ({@code
 * examples/05-payment-service}) after it consumes {@code order.placed} and
 * successfully captures funds, via its own transactional outbox (DRQ-053).
 *
 * <p><b>Consumed by:</b> this service's saga reaction (the S5 lifted {@code
 * OrderSagaListener}, DRQ-074), which transitions the order to {@code
 * OrderStatus.CONFIRMED} and triggers shipping (DRQ-049/050). Not wired in
 * this step: this record is authored contract-only (ch.26 S3); the
 * {@code @Incoming} consumer lands in S5.
 *
 * @param orderId the saga correlation key — the order this payment belongs to
 * @param paymentId the payment service's own identifier for the captured
 *     payment row
 * @param amountCents the captured amount, in cents
 * @param method the payment method used
 * @param status always the literal {@code "CAPTURED"}, carried on the wire
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
