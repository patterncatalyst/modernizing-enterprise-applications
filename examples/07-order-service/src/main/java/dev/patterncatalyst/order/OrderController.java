package dev.patterncatalyst.order;

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

/**
 * Lifted BYTE-FOR-BYTE from the monolith's {@code order.OrderController}
 * (ch.26 S4, DRQ-068/073, Phase A) via quarkus-spring-web. {@code
 * /api/orders} — the Order Context Contract read+command surface.
 *
 * <p>{@link #placeOrder} returns {@code 202 Accepted} with a {@code Location}
 * header, same as the monolith's current (post-decommission) contract: the
 * order resource is created synchronously and immediately pollable via
 * {@link #getById}, but {@link OrderService#placeOrder} always returns it
 * {@code PENDING} — there is no synchronous {@code 201}/{@code 402} path to
 * lift (the monolith removed that along with its in-process payment call,
 * ch.23 S9). In THIS service's Phase A, an order placed here has no way to
 * leave {@code PENDING} yet (no saga reactions exist — S5) — see {@link
 * OrderService}'s javadoc.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

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
