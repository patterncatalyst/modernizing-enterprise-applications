package dev.patterncatalyst.monolith.common.outbox;

import dev.patterncatalyst.monolith.common.Topics;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ch.17 (r04/S3) — the "simple polling relay" half of the transactional
 * outbox pattern (DRQ-034: polling, NOT Debezium/CDC — see
 * {@code V3__outbox.sql} for the full tradeoff writeup). On a fixed delay,
 * this reads a small batch of unpublished {@link OutboxEvent} rows and
 * publishes each one to Kafka.
 *
 * <p>This relay runs regardless of {@code notification.mode}: it is cheap
 * infrastructure that is simply idle (an empty query result) whenever the
 * monolith is in {@code synchronous} mode and nothing is ever written to the
 * outbox. Only {@code order.OrderService#placeOrder} decides whether a row
 * exists to relay at all.
 *
 * <p><b>Delivery guarantee: AT LEAST ONCE.</b> The Kafka send and the
 * {@code published_at} stamp are two separate operations, not one atomic
 * unit (that is the one guarantee a POLLING relay cannot give you for free —
 * CDC reading the WAL has the same limitation, for what it's worth). If the
 * process dies after the broker acks the send but before the stamp commits,
 * the next poll republishes the identical payload. This is safe ONLY because
 * the future consumer (ch.17/S5) is expected to be idempotent — dedicating by
 * {@code aggregateId} (the order id), exactly as DRQ-034/DRQ-037 call out.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${outbox.relay.topic:" + Topics.ORDER_PLACED + "}") String topic) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:2000}")
    public void publishUnpublishedEvents() {
        List<OutboxEvent> batch = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            // Block until the broker acks the send so we only stamp
            // published_at on confirmed delivery. A crash/timeout here
            // leaves the row unpublished — it is picked up again next tick
            // (at-least-once; see the class Javadoc).
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished();
            outboxRepository.save(event);
            log.info(
                    "outbox relay: published event id={} aggregateType={} aggregateId={} eventType={} -> topic={}",
                    event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), topic);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("outbox relay: failed to publish event id={} to topic={}; will retry on next poll",
                    event.getId(), topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("outbox relay: interrupted while publishing event id={}; will retry on next poll",
                    event.getId(), e);
        }
    }
}
