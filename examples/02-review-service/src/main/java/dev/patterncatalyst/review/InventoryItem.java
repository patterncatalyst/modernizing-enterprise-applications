package dev.patterncatalyst.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * SMELL[ch.18] CARRIED OVER, NOT CURED HERE: Review's other {@code ManyToOne}
 * FK target in the shared schema. Minimal, read-only projection of the shared
 * {@code inventory_items} table — just {@code id} and {@code sku} (see
 * {@code dev.patterncatalyst.monolith.inventory.InventoryItem} for the full
 * entity owned by the eventual Inventory extraction, ch.19).
 *
 * <p>Same deferral as {@link Customer}: this is the SAME podman-stack
 * Postgres and the existing {@code inventory_items} table, not an owned
 * schema. Data-across-the-seam work (ch.18/19) is explicitly out of scope
 * for this step.
 */
@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    protected InventoryItem() {
        // JPA
    }

    /** Package-private test-support constructor (see {@code TestFixtures}). */
    InventoryItem(Long id, String sku) {
        this.id = id;
        this.sku = sku;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }
}
