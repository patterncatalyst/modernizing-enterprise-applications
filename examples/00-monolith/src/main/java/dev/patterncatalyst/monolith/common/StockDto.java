package dev.patterncatalyst.monolith.common;

/** Inventory read model (reuse-map.md section 6: {@code StockDto}). */
public record StockDto(String sku, String name, long priceCents, int quantityOnHand) {
}
