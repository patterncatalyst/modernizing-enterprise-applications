package dev.patterncatalyst.payment;

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
 * r06/ch.23 S5 (DRQ-053) -- this service's OWN "simple polling relay" half of
 * the transactional outbox pattern, mirroring the monolith's proven {@code
 * common.outbox.OutboxRelay} (ch.17/r04/S3) idiomatically: Spring's {@code
 * @Scheduled} + {@code KafkaTemplate} becomes Quarkus's {@code
 * io.quarkus.scheduler.Scheduled} + SmallRye Reactive Messaging {@link
 * Emitter}. On a fixed delay, this reads a small batch of unpublished {@link
 * PaymentOutboxEvent} rows and publishes each one to the Kafka topic matching
 * its {@code eventType} -- {@code payment.captured} or {@code
 * payment.declined} (DRQ-048) -- via a programmatic {@link Emitter} (not an
 * {@code @Outgoing} method) because the choice of topic is a RUNTIME decision
 * per row, not a static method return type.
 *
 * <p><b>Delivery guarantee: AT LEAST ONCE</b> (identical limitation to the
 * monolith's relay): the Kafka send and the {@code published_at} stamp are
 * two separate operations, not one atomic unit. If the process dies after the
 * broker acks the send but before the stamp commits, the next poll
 * republishes the identical payload. This is safe ONLY because the future
 * order-saga consumer (S6) is expected to be idempotent by {@code orderId} --
 * exactly the same discipline this service's OWN {@code order.placed}
 * consumer already follows (DRQ-051, see {@link PaymentService#processOrderPlaced}).
 */
@ApplicationScoped
public class PaymentOutboxRelay {

    private static final Logger LOG = Logger.getLogger(PaymentOutboxRelay.class);

    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final PaymentOutboxRepository outboxRepository;

    @Channel("payment-captured")
    Emitter<String> capturedEmitter;

    @Channel("payment-declined")
    Emitter<String> declinedEmitter;

    public PaymentOutboxRelay(PaymentOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(every = "${payment.outbox.relay.poll-interval:2s}")
    @Transactional
    void publishUnpublishedEvents() {
        List<PaymentOutboxEvent> batch = outboxRepository.findUnpublished(BATCH_SIZE);
        for (PaymentOutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(PaymentOutboxEvent event) {
        Emitter<String> emitter = emitterFor(event.getEventType());
        if (emitter == null) {
            LOG.warnf(
                    "outbox relay: unknown eventType=%s for outbox id=%s; leaving unpublished",
                    event.getEventType(), event.getId());
            return;
        }
        try {
            // Block until the broker acks the send so we only stamp
            // published_at on confirmed delivery. A crash/timeout here leaves
            // the row unpublished -- it is picked up again next tick
            // (at-least-once; see the class Javadoc).
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
            case PaymentService.PAYMENT_CAPTURED_EVENT_TYPE -> capturedEmitter;
            case PaymentService.PAYMENT_DECLINED_EVENT_TYPE -> declinedEmitter;
            default -> null;
        };
    }
}
