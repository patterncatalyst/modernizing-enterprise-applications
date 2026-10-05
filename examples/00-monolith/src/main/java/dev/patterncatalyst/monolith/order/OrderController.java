package dev.patterncatalyst.monolith.order;

import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * ch.23 (r06/S9, DRQ-047, DECOMMISSION): the resource is always created
     * synchronously and is immediately pollable via {@code GET
     * /api/orders/{id}}, but the checkout outcome is NOT — {@link
     * OrderService#placeOrder} now always returns the order {@code PENDING}
     * (payment is captured out-of-process, over the choreographed saga), so
     * this always returns {@code 202 Accepted} with a {@code Location}
     * header. The synchronous {@code 201 Created}/{@code 402} contract this
     * endpoint used to also support (the {@code payment.mode=synchronous}
     * path) was removed along with that flag; the real-time terminal status
     * ({@code CONFIRMED}/{@code PAYMENT_DECLINED}) is reached eventually and
     * observed by polling {@code GET /api/orders/{id}}.
     */
    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody OrderCreate command) {
        OrderDto dto = service.placeOrder(command);
        URI location = URI.create("/api/orders/" + dto.id());
        return ResponseEntity.accepted().location(location).body(dto);
    }

    @GetMapping("/{id}")
    public OrderDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<OrderDto> listAll() {
        return service.listAll();
    }
}
