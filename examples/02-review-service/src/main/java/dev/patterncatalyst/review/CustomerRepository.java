package dev.patterncatalyst.review;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LIFTED UNCHANGED (shape) from
 * {@code dev.patterncatalyst.monolith.common.CustomerRepository} — a plain
 * Spring Data {@code JpaRepository}, supported as-is by the
 * {@code quarkus-spring-data-jpa} compatibility extension. Scoped down to the
 * minimal {@link Customer} projection; see its class comment for the deferred
 * data-decomposition note.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
