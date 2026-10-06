package dev.patterncatalyst.gateway;

import org.eclipse.microprofile.graphql.Description;

/**
 * GraphQL view of one order line item, resolved over REST from order-service
 * as part of {@link OrderView}. The nested {@code stock} (inventory-service,
 * gRPC) and {@code reviews} (review-service, REST) fields are resolved
 * per-item, by sku -- see {@link GatewayApi#stock(OrderItemView)} and
 * {@link GatewayApi#reviews(OrderItemView)}.
 */
@Description("One line item on an order, with a live stock lookup and that sku's reviews.")
public record OrderItemView(String sku, int quantity, long unitPriceCents) {

    static OrderItemView from(OrderDto.Item item) {
        return new OrderItemView(item.sku(), item.quantity(), item.unitPriceCents());
    }
}
