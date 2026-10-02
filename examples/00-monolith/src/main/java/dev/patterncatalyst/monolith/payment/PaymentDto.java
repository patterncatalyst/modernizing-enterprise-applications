package dev.patterncatalyst.monolith.payment;

import java.time.Instant;

public record PaymentDto(
        Long id, Long orderId, long amountCents, String method, PaymentStatus status, Instant createdAt) {
}
