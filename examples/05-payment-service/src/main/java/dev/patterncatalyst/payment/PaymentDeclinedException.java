package dev.patterncatalyst.payment;

/**
 * Lifted from the monolith's {@code common.exception.PaymentDeclinedException}.
 * Thrown by {@link PaymentService#charge} for the deterministic demo decline
 * rule. NOT wired to any caller in S4 (Phase A) -- there is no consumer or
 * controller endpoint that invokes {@code charge} yet, since the monolith's
 * order context still charges in-process (synchronous mode) and the
 * choreography's {@code order.placed} consumer doesn't exist until S5. This
 * is the capture logic S5 will call from the Kafka consumer, at which point
 * a decline becomes an emitted {@code payment.declined} event rather than an
 * exception propagated to an HTTP caller (the monolith's in-process 402
 * behavior does not carry over -- see the payment extraction plan's DRQ-047).
 */
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
