package dev.patterncatalyst.shipping;

import java.time.Instant;

/**
 * r07/ch.24 S5 -- this service's own copy of the {@code payment.captured}
 * wire contract (DRQ-038: plain JSON, not shared code between Maven
 * reactors -- see the monolith's {@code common.events.PaymentCaptured}
 * javadoc, which documents the same "each side authors a structurally
 * identical record" discipline), field-for-field identical to the
 * monolith's record (verified against
 * {@code examples/00-monolith/.../common/events/PaymentCaptured.java},
 * r06/ch.23 S3).
 *
 * <p><b>Produced by:</b> the payment service ({@code
 * examples/05-payment-service}, r06/ch.23 S5) after it captures a payment,
 * written atomically via its own transactional outbox (DRQ-053).
 *
 * <p><b>Consumed by:</b> this service's {@code PaymentCapturedConsumer}
 * (r07/ch.24 S5, DRQ-056/058/059) -- the trigger for the orchestrated
 * shipping saga ({@code ShippingService#processPaymentCaptured}). The
 * saga's "enrich" step separately fetches the order's shipping address
 * (this event does NOT carry it -- see {@link OrderReadClient}'s javadoc);
 * the consumer is idempotent by {@code orderId} (DRQ-064).
 *
 * @param orderId the saga correlation key
 * @param paymentId the payment service's own identifier for the captured
 *     payment row (unused by this service -- carried for 1:1 field parity)
 * @param amountCents the captured amount, in cents (unused by this service)
 * @param method the payment method used (unused by this service)
 * @param status always the literal {@code "CAPTURED"} on the wire (unused
 *     by this service -- the topic alone is this consumer's trigger)
 * @param capturedAt when the payment service captured the payment (unused
 *     by this service)
 */
public record PaymentCaptured(
        Long orderId,
        Long paymentId,
        long amountCents,
        String method,
        String status,
        Instant capturedAt) {
}
