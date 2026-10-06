package dev.patterncatalyst.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Lifted byte-for-byte (minus the table's schema qualifier) from the
 * monolith's {@code order.OrderItem} (ch.26 S4, DRQ-068/073, Phase A). The
 * DRQ-043 decomposition that removed this entity's cross-context FK onto
 * {@code inventory.InventoryItem} already happened in the monolith
 * (r05/ch.19 S8) — this lift KEEPS that denormalized snapshot ({@link #sku}
 * — a soft reference only, no DB FK, no JPA association —, {@link
 * #productName}, {@link #unitPriceCents}) unchanged, per this step's
 * instructions. {@code OrderDto.Item}'s external shape
 * (sku/quantity/unitPriceCents) is unchanged.
 */
@Entity
@Table(name = "order_items", schema = "order_service")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Soft reference only — a plain string, no DB FK and no JPA association onto inventory data. */
    @Column(nullable = false)
    private String sku;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(String sku, String productName, int quantity, long unitPriceCents) {
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
    }

    void assignTo(Order order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public String getSku() {
        return sku;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }
}
