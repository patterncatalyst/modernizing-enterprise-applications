package dev.patterncatalyst.notification;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * LIFTED from {@code dev.patterncatalyst.monolith.notification.NotificationService}
 * (ch.17 Phase A, DRQ-035) — only the read path,
 * {@code listByCustomerId}/{@code toDto}; the monolith's synchronous
 * {@code sendOrderConfirmation} write path is NOT lifted here at all (per the
 * S4 scope: the consumer that will populate this table is net-new,
 * idiomatic-from-start Quarkus authored in S5 — "you cannot lift code that
 * does not exist," DRQ-035).
 *
 * <p>Same two one-line adaptations review-service's Phase A {@code ReviewService}
 * needed: {@code @Service} alone maps to a CDI {@code @Singleton} under
 * {@code quarkus-spring-di} (a pseudo-scope that cannot be client-proxied), so
 * the plain jakarta {@code @ApplicationScoped} is added alongside it purely
 * for {@code io.quarkus.test.InjectMock} testability — not a business-logic
 * change.
 */
@Service
@ApplicationScoped
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<NotificationDto> listByCustomerId(Long customerId) {
        return repository.findAllByCustomerId(customerId).stream().map(NotificationService::toDto).toList();
    }

    private static NotificationDto toDto(Notification n) {
        return new NotificationDto(
                n.getId(),
                n.getCustomerId(),
                n.getOrderId(),
                n.getChannel(),
                n.getMessage(),
                n.getSentAt());
    }
}
