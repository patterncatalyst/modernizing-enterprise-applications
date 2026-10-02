package dev.patterncatalyst.monolith.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Shared-kernel entity. Not one of the monolith's bounded contexts itself, but
 * referenced by {@code order} and {@code notification} via direct JPA
 * {@code @ManyToOne} associations against this table (and, at the database
 * level only, by the {@code reviews} table — see below).
 *
 * SMELL[ch.18]: Customer lives in the one shared Postgres schema and is joined
 * directly (FK) from order/notification tables. In a decomposed system each
 * context would own (or replicate via CDC/ACL) only the customer fields it needs —
 * see ch.18 (shared data -> owned data) and ch.19 (CDC backfill).
 *
 * <p><b>r02/S10 note:</b> Review's Java code was removed from this module, but
 * the {@code reviews} table (with its FK to {@code customers}) deliberately
 * remains in this shared schema — {@code examples/02-review-service} is still
 * Phase A (DRQ-029) and reads/writes that table directly against the SAME
 * database, owning no schema of its own yet. Dropping the table here would
 * break the very service r02/S10 just cut over to. True per-context data
 * ownership for Review is deferred to ch.18/19, same as every other context.
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
