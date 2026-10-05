package dev.patterncatalyst.shipping;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * r07/ch.24 S4 (DRQ-063, Phase A). Lifted from the monolith's
 * {@code shipping.ShippingService} -- only the READ paths
 * ({@link #getById(Long)}/{@link #listByOrderId(Long)}); the monolith's
 * {@code dispatch(Order, String)} write path is deliberately NOT lifted
 * here: it took the order-context's JPA {@code Order} entity directly (the
 * in-process, same-transaction call this extraction is removing, SMELL
 * [ch.22]), and this service cannot reach across into the monolith's
 * {@code orders} table anyway. The S5 orchestrated Camel Saga EIP route
 * authors the analogous "dispatch shipment" step idiomatic-from-the-start
 * (no Spring original to lift for that part, DRQ-063), writing a
 * {@link Shipment} keyed by the event-carried {@code orderId} value instead
 * of a JPA relationship.
 */
@Service
public class ShippingService {

    private final ShipmentRepository repository;

    public ShippingService(ShipmentRepository repository) {
        this.repository = repository;
    }

    public ShipmentDto getById(Long id) {
        return toDto(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No shipment with id " + id)));
    }

    public List<ShipmentDto> listByOrderId(Long orderId) {
        return repository.findAllByOrderId(orderId).stream().map(ShippingService::toDto).toList();
    }

    private static ShipmentDto toDto(Shipment s) {
        return new ShipmentDto(s.getId(), s.getOrderId(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }
}
