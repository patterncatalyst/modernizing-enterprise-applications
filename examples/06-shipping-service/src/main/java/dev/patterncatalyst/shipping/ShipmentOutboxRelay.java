package dev.patterncatalyst.shipping;

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
 * r07/ch.24 S5 (DRQ-063, mirrors payment-service's {@code PaymentOutboxRelay},
 * DRQ-053) -- this service's OWN "simple polling relay" half of the
 * transactional outbox pattern: Quarkus's {@code io.quarkus.scheduler.Scheduled}
 * + SmallRye Reactive Messaging {@link Emitter}. On a fixed delay, this
 * reads a small batch of unpublished {@link ShipmentOutboxEvent} rows and
 * publishes each one to the Kafka topic matching its {@code eventType} --
 * {@code shipment.dispatched} or {@code shipment.failed} (DRQ-058) -- via a
 * programmatic {@link Emitter} (not an {@code @Outgoing} method) because the
 * choice of topic is a RUNTIME decision per row, not a static method return
 * type.
 *
 * <p><b>Delivery guarantee: AT LEAST ONCE</b> (identical limitation to
 * every other outbox relay in this repo): the Kafka send and the
 * {@code published_at} stamp are two separate operations, not one atomic
 * unit. If the process dies after the broker acks the send but before the
 * stamp commits, the next poll republishes the identical payload -- safe
 * only because the future monolith order-saga consumer (S6) is expected to
 * be idempotent by {@code orderId}, exactly the same discipline this
 * service's OWN {@code payment.captured} consumer already follows
 * (DRQ-064, see {@link ShippingService#processPaymentCaptured}).
 */
@ApplicationScoped
public class ShipmentOutboxRelay {

    private static final Logger LOG = Logger.getLogger(ShipmentOutboxRelay.class);

    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    static final String SHIPMENT_DISPATCHED_EVENT_TYPE = "shipment.dispatched";
    static final String SHIPMENT_FAILED_EVENT_TYPE = "shipment.failed";

    private final ShipmentOutboxRepository outboxRepository;

    @Channel("shipment-dispatched")
    Emitter<String> dispatchedEmitter;

    @Channel("shipment-failed")
    Emitter<String> failedEmitter;

    public ShipmentOutboxRelay(ShipmentOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(every = "${shipping.outbox.relay.poll-interval:2s}")
    @Transactional
    void publishUnpublishedEvents() {
        List<ShipmentOutboxEvent> batch = outboxRepository.findUnpublished(BATCH_SIZE);
        for (ShipmentOutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(ShipmentOutboxEvent event) {
        Emitter<String> emitter = emitterFor(event.getEventType());
        if (emitter == null) {
            LOG.warnf(
                    "outbox relay: unknown eventType=%s for outbox id=%s; leaving unpublished",
                    event.getEventType(), event.getId());
            return;
        }
        try {
            // Block until the broker acks the send so we only stamp
            // published_at on confirmed delivery. A crash/timeout here
            // leaves the row unpublished -- it is picked up again next
            // tick (at-least-once; see the class Javadoc).
            emitter.send(event.getPayload()).toCompletableFuture().get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private Emitter<String> emitterFor(String eventType) {
        return switch (eventType) {
            case SHIPMENT_DISPATCHED_EVENT_TYPE -> dispatchedEmitter;
            case SHIPMENT_FAILED_EVENT_TYPE -> failedEmitter;
            default -> null;
        };
    }
}
