package dev.patterncatalyst.monolith.payment;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public PaymentDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<PaymentDto> listByOrderId(@RequestParam Long orderId) {
        return service.listByOrderId(orderId);
    }
}
