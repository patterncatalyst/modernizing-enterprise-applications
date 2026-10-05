package dev.patterncatalyst.shipping;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * r07/ch.24 S4 (DRQ-063, Phase A). Lifted byte-for-byte (interface shape
 * unchanged) from the monolith's {@code shipping.ShipmentRepository} via the
 * Quarkiverse {@code quarkus-spring-data-jpa} compatibility extension --
 * same lift mechanism payment-service's Phase A {@code PaymentRepository}
 * used (removed again in Phase B, S5, in favor of Panache).
 */
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    List<Shipment> findAllByOrderId(Long orderId);
}
