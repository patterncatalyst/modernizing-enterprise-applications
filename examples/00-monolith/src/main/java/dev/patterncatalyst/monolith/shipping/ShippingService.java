package dev.patterncatalyst.monolith.shipping;

import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.order.Order;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ShippingService {

    private final ShipmentRepository repository;

    public ShippingService(ShipmentRepository repository) {
        this.repository = repository;
    }

    /**
     * Called directly and synchronously from {@code order.OrderService#placeOrder}
     * inside the same ACID transaction — see {@code SMELL[ch.22]}.
     */
    public Shipment dispatch(Order order, String address) {
        return repository.save(new Shipment(order, address, ShipmentStatus.DISPATCHED));
    }

    public ShipmentDto getById(Long id) {
        return toDto(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No shipment with id " + id)));
    }

    public List<ShipmentDto> listByOrderId(Long orderId) {
        return repository.findAllByOrderId(orderId).stream().map(ShippingService::toDto).toList();
    }

    private static ShipmentDto toDto(Shipment s) {
        return new ShipmentDto(s.getId(), s.getOrder().getId(), s.getAddress(), s.getStatus(), s.getCreatedAt());
    }
}
