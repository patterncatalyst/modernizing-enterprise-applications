package dev.patterncatalyst.review;

import java.time.Instant;

/**
 * Review read model (reuse-map.md section 6: {@code ReviewDto}).
 *
 * <p>LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.common.ReviewDto}
 * (ch.15 Phase A, DRQ-029 — Quarkiverse Spring-compatibility bridge). No code
 * change beyond the package name.
 */
public record ReviewDto(
        Long id,
        Long customerId,
        String sku,
        int rating,
        String comment,
        Instant createdAt) {
}
