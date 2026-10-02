package dev.patterncatalyst.monolith.common.exception;

/**
 * Thrown by {@link dev.patterncatalyst.monolith.payment.PaymentService} when the
 * checkout's {@code paymentMethod} simulates a decline. Because
 * {@code OrderService#placeOrder} wraps inventory decrement + order persistence +
 * payment + shipment + notification in ONE {@code @Transactional} (SMELL[ch.22]),
 * raising this mid-method rolls back everything already written in this request —
 * including the inventory decrement. That "it just rolls back" behavior is exactly
 * what a saga has to replace once these contexts are no longer one local
 * transaction (see ch.23 choreographed saga, ch.24 orchestrated saga).
 */
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
