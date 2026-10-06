package dev.patterncatalyst.order;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

/**
 * ch.26 S5 (DRQ-073, mirroring DRQ-053) -- this service's OWN "simple polling
 * relay" half of the transactional outbox pattern, mirroring payment-service's
 * proven {@code PaymentOutboxRelay} idiomatically: {@code
 * io.quarkus.scheduler.Scheduled} + a SmallRye Reactive Messaging {@link
 * Emitter}. On a fixed delay, this reads a small batch of unpublished {@link
 * OrderOutboxEvent} rows and publishes each one to Kafka.
 *
 * <p><b>Simpler than payment's relay by design, not by omission:</b> this
 * service's outbox only ever records ONE event type ({@code order.placed} --
 * see EVENTS.md's "Produces" table), unlike payment's two-outcome relay
 * (captured/declined choosing between two emitters per row). A single {@code
 * @Channel("order-placed")} {@link Emitter} is therefore sufficient; adding a
 * {@code switch}-on-{@code eventType} dispatch table for a single, fixed
 * event type would be speculative infrastructure for an outcome this
 * aggregate can never produce (placing an order has exactly one outcome
 * worth announcing — the order exists — unlike payment's capture-or-decline
 * branch).
 *
 * <p><b>Delivery guarantee: AT LEAST ONCE</b> (identical limitation to every
 * other outbox relay in this repo): the Kafka send and the {@code
 * published_at} stamp are two separate operations, not one atomic unit. If
 * the process dies after the broker acks the send but before the stamp
 * commits, the next poll republishes the identical payload. This is safe
 * only because the payment service's {@code OrderPlacedConsumer} is
 * idempotent by {@code orderId} (DRQ-051).
 */
@ApplicationScoped
public class OrderOutboxRelay {

    private static final Logger LOG = Logger.getLogger(OrderOutboxRelay.class);

    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OrderOutboxRepository outboxRepository;

    @Channel("order-placed")
    Emitter<String> orderPlacedEmitter;

    public OrderOutboxRelay(OrderOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(every = "${order.outbox.relay.poll-interval:2s}")
    @Transactional
    void publishUnpublishedEvents() {
        List<OrderOutboxEvent> batch = outboxRepository.findUnpublished(BATCH_SIZE);
        for (OrderOutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OrderOutboxEvent event) {
        if (!OrderService.ORDER_PLACED_EVENT_TYPE.equals(event.getEventType())) {
            LOG.warnf(
                    "outbox relay: unknown eventType=%s for outbox id=%s; leaving unpublished",
                    event.getEventType(), event.getId());
            return;
        }
        try {
            // Block until the broker acks the send so we only stamp
            // published_at on confirmed delivery. A crash/timeout here
            // leaves the row unpublished -- it is picked up again next tick
            // (at-least-once; see the class Javadoc).
            orderPlacedEmitter
                    .send(event.getPayload())
                    .toCompletableFuture()
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
            LOG.infof(
                    "outbox relay: published event id=%s aggregateType=%s aggregateId=%s eventType=%s",
                    event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType());
        } catch (ExecutionException | TimeoutException e) {
            LOG.warnf(e, "outbox relay: failed to publish event id=%s eventType=%s; will retry on next poll",
                    event.getId(), event.getEventType());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf(e, "outbox relay: interrupted while publishing event id=%s; will retry on next poll",
                    event.getId());
        }
    }
}
