package dev.patterncatalyst.monolith.common.outbox;

import java.time.Instant;

/**
 * ch.17 (r04/S3) — the JSON payload written into the outbox for the {@code
 * order.placed} event (topic name reserved in {@code common/Topics.java}
 * since r02). This is deliberately the same information the now-deleted
 * {@code notification.NotificationService#sendOrderConfirmation} used to
 * build its synchronous confirmation message — the outbox path was a
 * straight swap of "call the method" for "write the event", not a redesign
 * of what gets communicated.
 *
 * <p>DRQ-038: serialized as plain JSON (via Jackson) for ch.17; migrating to
 * Avro + Apicurio is deferred to ch.28, same as the sibling datamesh
 * reference project.
 */
public record OrderPlacedEvent(
        Long orderId,
        Long customerId,
        String customerEmail,
        long totalCents,
        String confirmationMessage,
        Instant placedAt) {
}
