package dev.patterncatalyst.order;

import java.time.Instant;
import java.util.List;

/**
 * Lifted BYTE-FOR-BYTE from the monolith's {@code common.OrderDto} (ch.26 S4,
 * DRQ-068/073, Phase A) — the Order Context Contract folder depends on this
 * exact shape, including {@code shippingAddress} (added ch.24/r07/S6) and the
 * {@code Item} nested record (sku/quantity/unitPriceCents).
 *
 * <p>{@code customerId} remains a plain {@code Long} here exactly as it was
 * in the monolith's DTO — the FK decomposition (DRQ-068) changes {@link
 * Order}'s INTERNAL JPA mapping (no more {@code @ManyToOne Customer}), never
 * this external read contract.
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
