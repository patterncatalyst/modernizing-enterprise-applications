package dev.patterncatalyst.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lifted from the monolith's {@code payment.PaymentRepository} via
 * {@code quarkus-spring-data-jpa} (Phase A). The derived query method names
 * are unchanged ({@code findByOrderId}/{@code findAllByOrderId}), but since
 * {@link Payment#getOrderId()} is now a plain scalar column (FK decomposed,
 * not a nested {@code order.id} traversal), the binding is actually simpler
 * than the monolith's original.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    List<Payment> findAllByOrderId(Long orderId);
}
