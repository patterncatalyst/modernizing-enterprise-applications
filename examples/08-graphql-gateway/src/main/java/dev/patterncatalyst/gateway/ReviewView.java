package dev.patterncatalyst.gateway;

import org.eclipse.microprofile.graphql.Description;

/**
 * GraphQL view of a review for a sku, resolved over REST from
 * review-service, nested under the order item it applies to (see {@link
 * GatewayApi#reviews(OrderItemView)}).
 */
@Description("A review for a line item's sku, as reported by review-service.")
public record ReviewView(Long id, int rating, String comment) {

    static ReviewView from(ReviewDto dto) {
        return new ReviewView(dto.id(), dto.rating(), dto.comment());
    }
}
