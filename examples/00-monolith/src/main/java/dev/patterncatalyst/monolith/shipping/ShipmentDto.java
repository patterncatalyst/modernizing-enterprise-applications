package dev.patterncatalyst.monolith.shipping;

import java.time.Instant;

public record ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status, Instant createdAt) {
}
