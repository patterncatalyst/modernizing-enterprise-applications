package dev.patterncatalyst.gateway;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;

/**
 * GraphQL view of an order, resolved over REST from order-service. {@code
 * payments}/{@code shipments} (see {@link GatewayApi#payments}/{@link
 * GatewayApi#shipments}) are federated separately, over REST from
 * payment-service/shipping-service -- this record only carries what
 * order-service itself returns for {@code GET /api/orders/{id}}.
 */
@Description("An order, aggregated from order-service (REST) with its payments, shipments, and per-item stock/reviews federated from the other four extracted services.")
public record OrderView(
        @Id String id,
        Long customerId,
        OrderStatus status,
        long totalCents,
        String createdAt,
        String shippingAddress,
        List<OrderItemView> items) {

    static OrderView from(OrderDto dto) {
        return new OrderView(
                String.valueOf(dto.id()),
                dto.customerId(),
                dto.status(),
                dto.totalCents(),
                dto.createdAt(),
                dto.shippingAddress(),
                dto.items().stream().map(OrderItemView::from).toList());
    }
}
