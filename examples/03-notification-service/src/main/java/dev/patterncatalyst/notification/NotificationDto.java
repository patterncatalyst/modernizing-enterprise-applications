package dev.patterncatalyst.notification;

import java.time.Instant;

/**
 * UNCHANGED across ch.17 Phase A -&gt; Phase B (DRQ-029/DRQ-035): identical
 * field shape, so the external read contract (the JSON the
 * behavior-equivalence suite's "Notification Context Contract" folder
 * asserts against) is byte-for-byte preserved, even though every layer
 * underneath it (resource, service, repository) was refactored off the
 * Spring-compat shim onto idiomatic Quarkus. This is also the exact shape
 * the net-new {@link OrderPlacedConsumer} (S5) produces from a consumed
 * {@code order.placed} event — channel {@code EMAIL}, message
 * {@code "Order #<id> confirmed..."} — so a row written by the async
 * pipeline and a row read back via {@code GET /api/notifications} look
 * identical to a client either way.
 */
public record NotificationDto(
        Long id,
        Long customerId,
        Long orderId,
        String channel,
        String message,
        Instant sentAt) {
}
