package dev.patterncatalyst.payment;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Lifted from the monolith's {@code payment.PaymentService} via
 * {@code quarkus-spring-di} (Phase A, {@code @Service}).
 */
@Service
public class PaymentService {

    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    /**
     * Lifted byte-for-byte in behavior from the monolith's {@code
     * PaymentService#charge} -- same deterministic demo decline rule (any
     * {@code method} value containing {@code DECLINE}, case-insensitively)
     * preserved for the decline path. The one structural change is the
     * parameter: the monolith took an {@code Order} entity (same-schema
     * join); this service takes a plain {@code orderId} value, since it owns
     * its own schema and has no {@code Order} entity to reference (FK
     * decomposed, see {@link Payment}'s javadoc).
     *
     * <p>NOT YET CALLED by anything in S4 (Phase A) -- no consumer or
     * controller endpoint invokes this. It exists now so S5's SmallRye
     * {@code order.placed} consumer has the exact capture logic, including
     * the decline rule, ready to call.
     */
    public Payment charge(Long orderId, long amountCents, String method) {
        if (method.toUpperCase(Locale.ROOT).contains("DECLINE")) {
            throw new PaymentDeclinedException("Payment method '%s' was declined".formatted(method));
        }
        Payment payment = new Payment(orderId, amountCents, method, PaymentStatus.CAPTURED);
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
        return new PaymentDto(p.getId(), p.getOrderId(), p.getAmountCents(), p.getMethod(), p.getStatus(),
                p.getCreatedAt());
    }
}
