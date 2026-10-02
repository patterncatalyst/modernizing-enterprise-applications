package dev.patterncatalyst.monolith.shipping;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    List<Shipment> findAllByOrderId(Long orderId);
}
