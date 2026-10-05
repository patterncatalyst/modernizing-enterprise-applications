package dev.patterncatalyst.monolith.common.exception;

/**
 * Thrown by {@code order.OrderService#placeOrder} when the extracted inventory
 * service's gRPC {@code Reserve} reply reports {@code reservation_ok=false}
 * (see {@link dev.patterncatalyst.monolith.inventory.RemoteInventoryClient#reserve}) —
 * a checkout line item exceeds the quantity on hand. The server-side Reserve
 * is atomic, so NO decrement happens for the line that throws this; no
 * compensating {@code Release} is needed for THAT line. But if an EARLIER
 * line in the same checkout already reserved successfully before a LATER
 * line throws this, those earlier reservations committed in the inventory
 * service's own database and must still be compensated — {@code
 * OrderService#placeOrder}'s surrounding {@code try/catch} does exactly that
 * (DRQ-042), for every sku reserved before this exception propagated. r06/
 * ch.23 S9 (DECOMMISSION): that catch is now reserve/save/outbox-failure-only
 * — the payment-decline compensation it used to also cover has moved to
 * {@code order.OrderSagaListener#onPaymentDeclined}'s reaction to the
 * choreographed saga's {@code payment.declined} event (DRQ-049), since
 * payment is no longer called synchronously from {@code placeOrder} at all.
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
