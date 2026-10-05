package dev.patterncatalyst.monolith.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ch.17 (r04/S3) — a row in the transactional outbox. Written by {@code
 * order.OrderService#placeOrder} in the SAME {@code @Transactional} as the
 * order/payment/shipment writes (when {@code notification.mode=outbox}), so
 * the event is atomic with the business change it describes: either both the
 * order and this row commit, or neither does (a payment decline rolls both
 * back together, same as every other write in that transaction).
 *
 * <p>{@link OutboxRelay} is a separate, asynchronous reader of this table — it
 * polls for rows where {@code publishedAt IS NULL}, publishes each to Kafka,
 * and stamps {@code publishedAt} on success. That stamp is an AT-LEAST-ONCE
 * guarantee, not exactly-once: if the process crashes after the Kafka publish
 * succeeds but before the stamp commits, the next poll republishes the same
 * row. The future consumer (ch.17/S5) is expected to dedupe by {@code
 * aggregateId} (the order id) — see DRQ-034/DRQ-037 in {@code decisions.md}.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /**
     * The serialized event payload (JSON text), stored in a native Postgres
     * {@code jsonb} column via Hibernate 6's {@link JdbcTypeCode}. The relay
     * forwards this string verbatim as the Kafka message value — it never
     * needs to deserialize it, only to republish it byte-for-byte.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
