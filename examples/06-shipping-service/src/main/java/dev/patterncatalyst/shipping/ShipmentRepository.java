package dev.patterncatalyst.shipping;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * REFACTORED to idiomatic Quarkus (r07/ch.24 S5, Phase B, DRQ-063) from the
 * Phase A Spring Data {@code JpaRepository<Shipment, Long>} interface --
 * mirrors payment-service's Phase B {@code PaymentRepository}: the Panache
 * REPOSITORY pattern (not active-record), a thin, testable, CDI-managed
 * data-access class. The derived-query method becomes an explicit
 * simplified-HQL {@code find(...)} call -- Panache has no Spring-Data-style
 * method-name-parsing convention.
 *
 * <p>{@link #findByOrderId(Long)} is net-new for S5 -- used by both the
 * saga consumer's idempotency guard ({@code ShippingService#processPaymentCaptured},
 * DRQ-064: a redelivered {@code payment.captured} is a no-op if a shipment
 * already exists for that order) and the compensation route ({@code
 * ShipmentSagaSteps#compensate}: correlate the saga's {@code orderId} back
 * to the shipment row created by the dispatch step, if any, so it can be
 * cancelled). The {@code uq_shipments_order_id} unique constraint
 * ({@code V3__shipments_unique_order_id.sql}) guarantees at most one
 * result.
 */
@ApplicationScoped
public class ShipmentRepository implements PanacheRepository<Shipment> {

    public List<Shipment> findAllByOrderId(Long orderId) {
        return list("orderId", orderId);
    }

    public Optional<Shipment> findByOrderId(Long orderId) {
        return find("orderId", orderId).firstResultOptional();
    }
}
