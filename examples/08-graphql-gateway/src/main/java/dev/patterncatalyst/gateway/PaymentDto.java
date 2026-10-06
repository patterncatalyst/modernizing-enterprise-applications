package dev.patterncatalyst.gateway;

/**
 * The gateway's deserialization target for payment-service's {@code GET
 * /api/payments?orderId=} response -- field-for-field the same shape as
 * {@code examples/05-payment-service}'s {@code PaymentDto}.
 */
public record PaymentDto(Long id, Long orderId, long amountCents, String method, PaymentStatus status, String createdAt) {
}
