package dev.patterncatalyst.shipping;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * r07/ch.24 S4 (DRQ-063, Phase A). Lifted byte-for-byte (same paths, same
 * {@link ShipmentDto} JSON shape, same required-{@code orderId}/404
 * semantics) from the monolith's {@code shipping.ShippingController} via the
 * Quarkiverse {@code quarkus-spring-web} compatibility extension -- the
 * strangler proxy's future transparent {@code /api/shipments} route (S7)
 * depends on this contract staying unchanged (DRQ-065, ACL honesty).
 */
@RestController
@RequestMapping("/api/shipments")
public class ShippingController {

    private final ShippingService service;

    public ShippingController(ShippingService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ShipmentDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<ShipmentDto> listByOrderId(@RequestParam Long orderId) {
        return service.listByOrderId(orderId);
    }
}
