package dev.patterncatalyst.monolith.common;

import java.time.Instant;

/** Review read model (reuse-map.md section 6: {@code ReviewDto}). */
public record ReviewDto(
        Long id,
        Long customerId,
        String sku,
        int rating,
        String comment,
        Instant createdAt) {
}
