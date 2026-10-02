package dev.patterncatalyst.monolith.common;

import java.time.Instant;
import java.util.List;

/** Order read model (reuse-map.md section 6: {@code OrderDto}). */
public record OrderDto(
        Long id,
        Long customerId,
        OrderStatus status,
        long totalCents,
        Instant createdAt,
        List<Item> items) {

    public record Item(String sku, int quantity, long unitPriceCents) {
    }
}
