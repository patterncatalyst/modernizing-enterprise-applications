package dev.patterncatalyst.gateway;

import java.util.List;

/**
 * The gateway's deserialization target for order-service's {@code GET
 * /api/orders/{id}} response -- field-for-field the same shape as
 * {@code examples/07-order-service}'s {@code OrderDto} (itself lifted
 * byte-for-byte from the monolith). {@code createdAt} is carried as the raw
 * JSON string Jackson already serialized the {@code Instant} to on the wire,
 * rather than re-parsed to {@code Instant} here -- this module never needs
 * to do date arithmetic on it, only pass it through to the GraphQL response.
 */
public record OrderDto(
        Long id,
        Long customerId,
        OrderStatus status,
        long totalCents,
        String createdAt,
        String shippingAddress,
        List<Item> items) {

    public record Item(String sku, int quantity, long unitPriceCents) {
    }
}
