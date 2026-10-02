package dev.patterncatalyst.review;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) from the Phase A
 * Spring Data {@code JpaRepository<Customer, Long>} interface — a plain
 * Panache repository with no extra query methods. {@link Customer} remains
 * the same minimal, read-only shadow projection of the shared schema (still
 * SMELL[ch.18], deferred — see its class javadoc); only the data-access
 * mechanism changed, not the data-coupling smell.
 */
@ApplicationScoped
public class CustomerRepository implements PanacheRepository<Customer> {
}
