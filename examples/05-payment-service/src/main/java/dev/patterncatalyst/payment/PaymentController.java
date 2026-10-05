package dev.patterncatalyst.payment;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lifted byte-for-byte from the monolith's {@code payment.PaymentController}
 * via {@code quarkus-spring-web} (Phase A) -- same {@code @RestController}/
 * {@code @RequestMapping}/{@code @GetMapping}/{@code @PathVariable}/
 * {@code @RequestParam} shape, same {@code /api/payments} read contract
 * (strangler-proxy's future {@code PaymentAclRoute}, S7, routes here once
 * {@code strangler.payment.enabled=true}).
 */
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
