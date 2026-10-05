package dev.patterncatalyst.inventory;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.common.StockDto} (r05/ch.19 S5, Phase
 * A, DRQ-044). Same field names/types/order as the monolith's read model, so
 * the behavior-equivalence suite's "Inventory Context Contract" folder sees
 * a byte-for-byte identical JSON shape whether it hits the monolith or this
 * service. This is the clean contract the gRPC ACL ({@code StockReply} in
 * {@code inventory.proto}) translates to/from at the order<->inventory seam
 * -- never the raw {@link InventoryItem} entity.
 */
public record StockDto(String sku, String name, long priceCents, int quantityOnHand) {
}
