package dev.patterncatalyst.monolith.common;

import java.time.Instant;

/** Notification read model (reuse-map.md section 6: {@code NotificationDto}). */
public record NotificationDto(
        Long id,
        Long customerId,
        Long orderId,
        String channel,
        String message,
        Instant sentAt) {
}
