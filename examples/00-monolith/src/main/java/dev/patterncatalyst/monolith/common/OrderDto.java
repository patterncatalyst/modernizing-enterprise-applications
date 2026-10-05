package dev.patterncatalyst.monolith.common;

import java.time.Instant;
import java.util.List;

/**
 * Order read model (reuse-map.md section 6: {@code OrderDto}).
 *
 * <p>ch.24 (r07/S6): {@code shippingAddress} is an ADDITIVE field — the
 * shipping service's Camel Saga EIP coordinator enriches each saga
 * invocation by reading it off {@code GET /api/orders/{id}} (r07/S5), and
 * until now this DTO didn't expose it even though {@link
 * dev.patterncatalyst.monolith.order.Order#getShippingAddress()} always
 * held it. Placed after {@code createdAt}; the behavior-equivalence suite
 * (`tooling/newman/mea.postman_collection.json`) only asserts individual
 * fields it cares about (status/total/items), never an exact key set, so
 * this addition is harmless to the existing checkout scenarios.
 */
public record OrderDto(
        Long id,
        Long customerId,
        OrderStatus status,
        long totalCents,
        Instant createdAt,
        String shippingAddress,
        List<Item> items) {

    public record Item(String sku, int quantity, long unitPriceCents) {
    }
}
