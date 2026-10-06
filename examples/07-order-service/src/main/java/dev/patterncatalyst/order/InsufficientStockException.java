package dev.patterncatalyst.order;

/**
 * Lifted byte-for-byte from the monolith's {@code
 * common.exception.InsufficientStockException}. Thrown by {@link
 * OrderService#placeOrder} when the inventory service's gRPC {@code Reserve}
 * reply reports {@code reservation_ok=false} (see {@link
 * RemoteInventoryClient#reserve}) — a checkout line item exceeds the quantity
 * on hand. See {@link OrderService#placeOrder}'s javadoc for the pre-handoff
 * compensation this triggers for any EARLIER line already reserved in the
 * same checkout.
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
