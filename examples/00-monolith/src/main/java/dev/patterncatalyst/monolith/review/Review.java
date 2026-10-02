package dev.patterncatalyst.monolith.review;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.inventory.InventoryItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * SMELL[ch.18]: joins directly to {@code customers} and {@code inventory_items} in
 * the shared schema.
 *
 * <p>Note what review does NOT depend on: order, payment, shipping, or
 * notification. It is genuinely the least-coupled context in the monolith
 * (REST-only, no synchronous collaborator) — which is exactly why it is the
 * walking-skeleton extraction (ch.15). The only thing tangling it into the rest of
 * the monolith is the SHARED security filter chain — see {@code SMELL[ch.15]} on
 * {@link dev.patterncatalyst.monolith.security.SecurityConfig}.
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Column(nullable = false)
    private int rating;

    @Column
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() {
        // JPA
    }

    public Review(Customer customer, InventoryItem inventoryItem, int rating, String comment) {
        this.customer = customer;
        this.inventoryItem = inventoryItem;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public InventoryItem getInventoryItem() {
        return inventoryItem;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
