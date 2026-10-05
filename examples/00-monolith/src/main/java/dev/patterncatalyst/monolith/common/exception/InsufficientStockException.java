package dev.patterncatalyst.monolith.common.exception;

/**
 * Thrown by {@code order.OrderService#placeOrder} when the extracted inventory
 * service's gRPC {@code Reserve} reply reports {@code reservation_ok=false}
 * (see {@link dev.patterncatalyst.monolith.inventory.RemoteInventoryClient#reserve}) —
 * a checkout line item exceeds the quantity on hand. The server-side Reserve
 * is atomic, so NO decrement happens when this is thrown; no compensating
 * {@code Release} is needed for this path — contrast with
 * {@link PaymentDeclinedException}, which IS raised after a successful
 * reservation and therefore does trigger a compensating {@code Release} (see
 * {@code DRQ-042} on {@code OrderService#placeOrder}).
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
