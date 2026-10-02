package dev.patterncatalyst.monolith.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Shared-kernel entity. Not one of the six bounded contexts itself, but referenced
 * by {@code order}, {@code review}, and {@code notification} via direct JPA
 * {@code @ManyToOne} associations against this table.
 *
 * SMELL[ch.18]: Customer lives in the one shared Postgres schema and is joined
 * directly (FK) from order/review/notification tables. In a decomposed system each
 * context would own (or replicate via CDC/ACL) only the customer fields it needs —
 * see ch.18 (shared data -> owned data) and ch.19 (CDC backfill).
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
        // JPA
    }

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
