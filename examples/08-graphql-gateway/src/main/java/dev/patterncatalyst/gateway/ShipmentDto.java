package dev.patterncatalyst.gateway;

/**
 * The gateway's deserialization target for shipping-service's {@code GET
 * /api/shipments?orderId=} response -- field-for-field the same shape as
 * {@code examples/06-shipping-service}'s {@code ShipmentDto}.
 */
public record ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status, String createdAt) {
}
