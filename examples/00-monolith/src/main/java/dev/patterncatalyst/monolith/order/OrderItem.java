package dev.patterncatalyst.monolith.order;

import dev.patterncatalyst.monolith.inventory.InventoryItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * SMELL[ch.18]: {@code OrderItem} holds a direct JPA {@code @ManyToOne} FK/join
 * onto {@code inventory.InventoryItem} — an order-context table referencing an
 * inventory-context table in the one shared schema. Once inventory owns its own
 * database (ch.19), this join is replaced by a denormalized copy of the fields the
 * order context actually needs (sku, name, price-at-time-of-order), kept current
 * via CDC.
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

    @ManyToOne(optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(InventoryItem inventoryItem, int quantity, long unitPriceCents) {
        this.inventoryItem = inventoryItem;
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

    public InventoryItem getInventoryItem() {
        return inventoryItem;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }
}
