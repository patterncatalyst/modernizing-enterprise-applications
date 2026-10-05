package dev.patterncatalyst.notification;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.17 Phase B, DRQ-035) from the Phase A
 * Spring Data {@code JpaRepository<Notification, Long>} interface — mirrors
 * review-service's {@code ReviewRepository} (ch.15 Phase B, DRQ-029): the
 * Panache REPOSITORY pattern (not active-record), a thin, testable,
 * CDI-managed data-access class. The derived-query method becomes an
 * explicit simplified-HQL {@code list(...)} call — Panache has no
 * Spring-Data-style method-name-parsing convention.
 */
@ApplicationScoped
public class NotificationRepository implements PanacheRepository<Notification> {

    public List<Notification> findAllByCustomerId(Long customerId) {
        return list("customerId", customerId);
    }

    /**
     * Net-new for S5 (DRQ-035, no Spring original to lift): the consumer's
     * idempotency check. The monolith's {@code OutboxRelay} is AT LEAST
     * ONCE, so the same {@code order.placed} event can be redelivered; this
     * lookup — plus the database-level partial unique index on
     * {@code order_id} (see {@code V3__idempotent_order_id.sql}) — together
     * guarantee exactly one {@link Notification} row per order id even
     * under redelivery.
     */
    public Notification findByOrderId(Long orderId) {
        return find("orderId", orderId).firstResult();
    }
}
