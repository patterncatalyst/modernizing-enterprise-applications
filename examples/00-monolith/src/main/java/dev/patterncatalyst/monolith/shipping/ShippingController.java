package dev.patterncatalyst.monolith.shipping;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
