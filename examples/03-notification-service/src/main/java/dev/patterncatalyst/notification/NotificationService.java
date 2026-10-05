package dev.patterncatalyst.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.17 Phase B, DRQ-029/DRQ-035). The
 * Spring {@code @Service} stereotype is dropped — plain CDI
 * {@code @ApplicationScoped} plus Quarkus's simplified constructor injection
 * (a single constructor needs no {@code @Inject}) is the whole DI story now,
 * exactly mirroring review-service's {@code ReviewService} Phase B.
 *
 * <p>{@link #listByCustomerId(Long)} is the lifted read path — same mapping
 * logic as Phase A, only the repository call shape changed (Panache's
 * {@code list(...)} instead of Spring Data's derived query method, no
 * {@code Optional} unwrapping needed since this method never looked up a
 * single entity by id).
 *
 * <p>{@link #recordOrderPlaced(OrderPlacedEvent)} is net-new for S5 — there
 * is no Spring original to lift (the monolith never consumed its own
 * {@code order.placed} event), so it is authored idiomatic Quarkus from the
 * start (DRQ-035). It is the single owner of the write-path's idempotency
 * guarantee: {@link OrderPlacedConsumer} delegates here rather than writing
 * to the repository directly, so there is exactly one place that decides
 * "have I already recorded this order."
 */
@ApplicationScoped
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<NotificationDto> listByCustomerId(Long customerId) {
        return repository.findAllByCustomerId(customerId).stream().map(NotificationService::toDto).toList();
    }

    /**
     * IDEMPOTENT by design (DRQ-034/DRQ-037): the monolith's
     * {@code OutboxRelay} is an AT-LEAST-ONCE publisher — a crash between
     * the Kafka send and the {@code published_at} stamp republishes the
     * identical event. Check-then-insert, dedup'd by {@code orderId}, means
     * a redelivered event is a safe no-op. The database-level partial
     * unique index on {@code order_id} (V3 migration) is the backstop for
     * the (currently unlikely, single-partition-consumer) case of two
     * deliveries racing concurrently.
     */
    @Transactional
    public void recordOrderPlaced(OrderPlacedEvent event) {
        if (repository.findByOrderId(event.orderId()) != null) {
            return;
        }
        Notification notification =
                new Notification(event.customerId(), event.orderId(), "EMAIL", event.confirmationMessage());
        repository.persist(notification);
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
