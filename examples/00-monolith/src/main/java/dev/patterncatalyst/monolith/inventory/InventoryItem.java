package dev.patterncatalyst.monolith.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Stock levels for a product. {@code order}, {@code review}, and (indirectly)
 * {@code payment}/{@code shipping} all join against this table across context
 * boundaries — see {@code SMELL[ch.18]} in {@code order.OrderItem} and
 * {@code review.Review}.
 */
@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    public InventoryItem(String sku, String name, long priceCents, int quantityOnHand) {
        this.sku = sku;
        this.name = name;
        this.priceCents = priceCents;
        this.quantityOnHand = quantityOnHand;
        this.updatedAt = Instant.now();
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

    public void decrement(int quantity) {
        this.quantityOnHand -= quantity;
        this.updatedAt = Instant.now();
    }

    public void restock(int quantity) {
        this.quantityOnHand += quantity;
        this.updatedAt = Instant.now();
    }
}
