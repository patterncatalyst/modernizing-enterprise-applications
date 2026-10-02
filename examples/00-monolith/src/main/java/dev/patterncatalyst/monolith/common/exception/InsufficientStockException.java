package dev.patterncatalyst.monolith.common.exception;

/**
 * Thrown by {@link dev.patterncatalyst.monolith.inventory.InventoryService} when a
 * checkout line item exceeds the quantity on hand. Raised BEFORE any writes happen,
 * so no rollback is needed for this path — contrast with
 * {@link PaymentDeclinedException}, which IS raised mid-transaction and relies on
 * the single ACID transaction to undo earlier writes (see
 * {@code SMELL[ch.22]} on {@code OrderService#placeOrder}).
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
