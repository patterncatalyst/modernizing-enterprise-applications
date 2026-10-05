package dev.patterncatalyst.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lifted from the monolith's shared-kernel {@code common.Customer} (ch.26 S4,
 * DRQ-068/073, Phase A) — but UNLIKE the monolith, this is no longer a
 * shared-schema entity joined cross-context. Customer was never its own
 * bounded-context extraction (there was no {@code examples/0N-customer-
 * service}); it comes along with the order extraction because {@link Order}
 * is its only real collaborator left in the monolith (reviews/notifications
 * already dropped their Customer dependency when THOSE contexts extracted —
 * see the monolith's {@code common.Customer} javadoc). This service now OWNS
 * the {@code customers} table outright, in its own schema
 * ({@code V1__create_order_schema.sql}) — no more cross-context FK, no more
 * shared Postgres schema.
 *
 * <p>Store starts EMPTY and is forward-filled by whatever creates customers
 * going forward — deliberately NO CDC backfill from the monolith's existing
 * {@code customers} table (contrast inventory's CDC-fed backfill, DRQ-040):
 * this extraction's scope (ch.26 S4) is read+command surface + entities +
 * schema only; a migration/backfill strategy for pre-existing customer data
 * is out of scope for Phase A and not assumed.
 */
@Entity
@Table(name = "customers", schema = "order_service")
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

    /** Package-private test-support constructor — lets unit tests assign an id without a real persist/generated-value round trip. */
    Customer(Long id, String name, String email) {
        this(name, email);
        this.id = id;
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
