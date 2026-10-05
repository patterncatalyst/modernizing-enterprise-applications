package dev.patterncatalyst.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * LIFTED UNCHANGED (modulo package) from
 * {@code dev.patterncatalyst.monolith.notification.NotificationRepository}
 * (ch.17 Phase A, DRQ-035) — the Spring Data derived query
 * ({@code findAllByCustomerId}) works as-is under {@code quarkus-spring-data-jpa}
 * against {@link Notification}'s plain {@code customerId} column (see that
 * class's javadoc for why it is a scalar column, not a JPA relation, here).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findAllByCustomerId(Long customerId);
}
