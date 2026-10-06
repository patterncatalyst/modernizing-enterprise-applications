package dev.patterncatalyst.order;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) from Phase A's
 * Spring Data {@code JpaRepository<Customer, Long>} interface -- mirrors
 * {@link OrderRepository}'s Phase B shape.
 */
@ApplicationScoped
public class CustomerRepository implements PanacheRepository<Customer> {
}
