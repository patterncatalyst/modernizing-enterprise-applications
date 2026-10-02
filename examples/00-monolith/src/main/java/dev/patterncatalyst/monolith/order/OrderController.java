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

    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@Valid @RequestBody OrderCreate command) {
        OrderDto dto = service.placeOrder(command);
        return ResponseEntity.created(URI.create("/api/orders/" + dto.id())).body(dto);
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
