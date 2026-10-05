package dev.patterncatalyst.notification;

import java.time.Instant;

/**
 * LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.common.NotificationDto}
 * (ch.17 Phase A, DRQ-035) — identical field shape, so the external read
 * contract (the JSON the behavior-equivalence suite's "Notification Context
 * Contract" folder asserts against) is preserved exactly, even though the
 * entity underneath it ({@link Notification}) had to change (own-schema, no
 * cross-schema joins — see that class's javadoc).
 */
public record NotificationDto(
        Long id,
        Long customerId,
        Long orderId,
        String channel,
        String message,
        Instant sentAt) {
}
