package dev.patterncatalyst.order;

import java.time.Instant;

/**
 * ch.26 (r08/S3, DRQ-073) — this service's own copy of the {@code
 * payment.declined} wire contract, field-for-field identical to the
 * monolith's {@code common.events.PaymentDeclined} (verified against
 * {@code examples/00-monolith/.../common/events/PaymentDeclined.java}) and
 * to the payment service's producer-side copy
 * ({@code examples/05-payment-service/.../PaymentDeclined.java}). Per
 * DRQ-038, this is a plain record, not shared code between Maven reactors.
 *
 * <p><b>Produced by:</b> the payment service ({@code
 * examples/05-payment-service}) after it consumes {@code order.placed} and
 * the capture is declined, via its own transactional outbox (DRQ-053). No
 * funds are captured on this path.
 *
 * <p><b>Consumed by:</b> this service's saga reaction (the S5 lifted {@code
 * OrderSagaListener}, DRQ-074), which transitions the order to {@code
 * OrderStatus.PAYMENT_DECLINED} and issues the compensating gRPC inventory
 * {@code Release} for every reserved sku (DRQ-042/049). Not wired in this
 * step: this record is authored contract-only (ch.26 S3); the
 * {@code @Incoming} consumer lands in S5.
 *
 * @param orderId the saga correlation key — the order this payment belongs to
 * @param paymentId the payment service's own identifier for the declined
 *     payment row (or attempt record)
 * @param amountCents the amount that was attempted, in cents
 * @param method the payment method used
 * @param status always the literal {@code "DECLINED"}, carried on the wire
 * @param reason why the payment was declined
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
