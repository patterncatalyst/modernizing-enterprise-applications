package dev.patterncatalyst.monolith.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * r05/ch.19 S8 (DRQ-043) CURE of SMELL[ch.18]: {@code OrderItem} no longer
 * holds a JPA {@code @ManyToOne}/DB FK onto {@code inventory.InventoryItem}.
 * Now that inventory is reachable only over the gRPC seam (S7) and will own
 * its own database (ch.19's end state), a cross-context DB FK from
 * {@code order_items} into {@code inventory_items} cannot survive. Instead
 * this entity holds a self-contained, denormalized SNAPSHOT of exactly the
 * fields the order context needs to render a line item — {@link #sku} (a
 * plain string, a SOFT reference only — no DB-level FK, no JPA association),
 * {@link #productName}, and {@link #unitPriceCents} — captured once, at
 * checkout time, by {@code OrderService#placeOrder} (from the local {@code
 * InventoryItem} in {@code inventory.mode=local}, or from the remote
 * inventory service's gRPC {@code GetStock} reply in {@code
 * inventory.mode=remote}). Because the snapshot is captured at order time,
 * later price/name changes in inventory never retroactively alter a
 * historical order's line items — which is also the correct read-model
 * behavior, not just a decomposition side effect. {@code common.OrderDto}
 * already projected sku/qty/unit-price from these fields, so the external
 * order read contract (GET /api/orders, GET /api/orders/{id}) is unchanged
 * (see {@code V4__decompose_order_items_fk.sql} for the matching schema cut).
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Soft reference only — a plain string, no DB FK and no JPA association onto inventory.InventoryItem. */
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
