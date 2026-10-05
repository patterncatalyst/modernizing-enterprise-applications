package dev.patterncatalyst.order;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) from Phase A's
 * Spring Data {@code JpaRepository<Order, Long>} interface -- mirrors
 * payment-service's/shipping-service's Phase B repositories: the Panache
 * REPOSITORY pattern (not active-record), a thin, testable, CDI-managed
 * data-access class. {@code findById}/{@code findAll}/{@code save} become
 * {@code findByIdOptional}/{@code listAll}/{@code persist} -- Panache has no
 * Spring-Data-style derived-method convention and no {@code save} (a managed
 * entity's mutations are flushed automatically by Hibernate's dirty checking
 * at transaction commit; only a brand-new entity needs an explicit {@code
 * persist} call).
 */
@ApplicationScoped
public class OrderRepository implements PanacheRepository<Order> {
}
