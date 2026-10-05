package dev.patterncatalyst.payment;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (r06/ch.23 S5, Phase B, DRQ-052) from the
 * Phase A Spring Data {@code JpaRepository<Payment, Long>} interface --
 * mirrors notification-service's/inventory-service's Phase B repositories:
 * the Panache REPOSITORY pattern (not active-record), a thin, testable,
 * CDI-managed data-access class. The derived-query methods become explicit
 * simplified-HQL {@code find(...)} calls -- Panache has no Spring-Data-style
 * method-name-parsing convention.
 */
@ApplicationScoped
public class PaymentRepository implements PanacheRepository<Payment> {

    public Payment findByOrderId(Long orderId) {
        return find("orderId", orderId).firstResult();
    }

    public List<Payment> findAllByOrderId(Long orderId) {
        return list("orderId", orderId);
    }
}
