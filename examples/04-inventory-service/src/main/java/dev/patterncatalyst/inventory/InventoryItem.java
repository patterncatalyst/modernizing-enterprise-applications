package dev.patterncatalyst.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * This service's OWN copy of
 * {@code dev.patterncatalyst.monolith.inventory.InventoryItem} (r05/ch.19
 * S5, Phase A, DRQ-044), persisted in the {@code inventory} schema this
 * service owns -- NOT the monolith's shared {@code public.inventory_items}
 * table.
 *
 * <p>UNLIKE the monolith's entity, {@code id} is NOT
 * {@code @GeneratedValue} -- it is CDC-ASSIGNED, carried over verbatim from
 * the monolith's row id by {@link InventoryCdcConsumer} /
 * {@link InventoryRepository#upsert}, so this service's copy and the
 * upstream source row always agree on identity. The public read surface
 * ({@link StockDto}) never exposes {@code id}, only {@code sku}, so this is
 * an internal-only identity choice.
 */
@Entity
@Table(name = "inventory_items", schema = "inventory")
public class InventoryItem {

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItem() {
        // JPA
    }

    /** Test/seed-support constructor -- production writes go through {@link InventoryRepository#upsert}. */
    public InventoryItem(Long id, String sku, String name, long priceCents, int quantityOnHand, Instant updatedAt) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.priceCents = priceCents;
        this.quantityOnHand = quantityOnHand;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public int getQuantityOnHand() {
        return quantityOnHand;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
