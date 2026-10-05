package dev.patterncatalyst.monolith.order;

import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.OrderStatus;
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
     * ch.23 (r06/S6, DRQ-047/H1): the resource is always created
     * synchronously and is immediately pollable via {@code GET
     * /api/orders/{id}} — only the STATUS CODE differs by outcome. When
     * {@code payment.mode=synchronous} (default) {@link OrderService
     * #placeOrder} always returns a terminal order (CONFIRMED, or it threw
     * before this point) — {@code 201 Created}. When {@code
     * payment.mode=choreographed} the order comes back {@code PENDING}
     * (payment outcome arrives later over the choreography) — {@code 202
     * Accepted}. Branching on the returned {@link OrderDto#status()} avoids
     * threading the {@code payment.mode} flag through this layer too.
     */
    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody OrderCreate command) {
        OrderDto dto = service.placeOrder(command);
        URI location = URI.create("/api/orders/" + dto.id());
        if (dto.status() == OrderStatus.PENDING) {
            return ResponseEntity.accepted().location(location).body(dto);
        }
        return ResponseEntity.created(location).body(dto);
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
