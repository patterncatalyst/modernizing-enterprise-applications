package dev.patterncatalyst.gateway;

import org.eclipse.microprofile.graphql.Description;

/**
 * GraphQL view of stock for a single sku, resolved over gRPC from
 * inventory-service's read-only {@code GetStock} RPC (the same contract
 * {@code examples/07-order-service}'s {@code RemoteInventoryClient} dials --
 * see {@code src/main/proto/.../inventory.proto}). Nested under {@link
 * OrderItemView#stock} rather than fetched top-level, so the figure is
 * always scoped to that line item's own sku.
 */
@Description("Point-in-time stock for a sku, as reported by inventory-service over gRPC.")
public record StockView(String sku, int quantityOnHand, boolean available) {
}
