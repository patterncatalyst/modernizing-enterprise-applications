package dev.patterncatalyst.monolith.payment;

import dev.patterncatalyst.monolith.common.exception.PaymentDeclinedException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import dev.patterncatalyst.monolith.order.Order;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    /**
     * Simulated payment capture, called synchronously and in-process from
     * {@code order.OrderService#placeOrder} — see {@code SMELL[ch.22]} on that
     * method for why this being "just a method call inside one transaction" is a
     * smell, not a feature, once payment becomes its own service (ch.23).
     *
     * <p>Demo decline rule: any {@code method} value containing {@code DECLINE}
     * (case-insensitive) is treated as a declined card, deterministically, so the
     * future Newman payment-decline scenario doesn't need a real gateway.
     */
    public Payment charge(Order order, long amountCents, String method) {
        if (method.toUpperCase(Locale.ROOT).contains("DECLINE")) {
            throw new PaymentDeclinedException("Payment method '%s' was declined".formatted(method));
        }
        Payment payment = new Payment(order, amountCents, method, PaymentStatus.CAPTURED);
        return repository.save(payment);
    }

    public PaymentDto getById(Long id) {
        return toDto(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No payment with id " + id)));
    }

    public List<PaymentDto> listByOrderId(Long orderId) {
        return repository.findAllByOrderId(orderId).stream().map(PaymentService::toDto).toList();
    }

    private static PaymentDto toDto(Payment p) {
        return new PaymentDto(p.getId(), p.getOrder().getId(), p.getAmountCents(), p.getMethod(), p.getStatus(),
                p.getCreatedAt());
    }
}
