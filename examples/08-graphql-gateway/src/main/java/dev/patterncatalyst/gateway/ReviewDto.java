package dev.patterncatalyst.gateway;

/**
 * The gateway's deserialization target for review-service's {@code GET
 * /api/reviews?sku=} response -- field-for-field the same shape as
 * {@code examples/02-review-service}'s {@code ReviewDto}.
 */
public record ReviewDto(Long id, Long customerId, String sku, int rating, String comment, String createdAt) {
}
