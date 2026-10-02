package dev.patterncatalyst.review;

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
 * LIFTED from {@code dev.patterncatalyst.monolith.review.Review} (ch.15 Phase
 * A, DRQ-029) — identical JPA annotations and class shape. The {@code @Entity}
 * / {@code @Table} / {@code @ManyToOne} / {@code @JoinColumn} mapping works
 * unchanged under Quarkus Hibernate ORM; {@code quarkus-spring-data-jpa}
 * requires no entity changes at all (plain JPA entities, not Panache).
 *
 * <p>SMELL[ch.18] (carried over, not cured here): still joins directly to the
 * shared {@code customers} and {@code inventory_items} tables in the SAME
 * podman-stack Postgres the monolith uses — see {@link Customer} and
 * {@link InventoryItem} for the minimal read-only projections that stand in
 * for those monolith entities. True database decomposition is deferred to the
 * data-across-the-seam chapters (ch.18/19), not this step.
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
