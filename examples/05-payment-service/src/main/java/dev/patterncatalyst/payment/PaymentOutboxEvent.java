package dev.patterncatalyst.payment;

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
 * r06/ch.23 S5 (DRQ-053) -- this service's OWN transactional outbox, a row
 * mirroring the monolith's proven {@code common.outbox.OutboxEvent} pattern
 * (ch.17/r04/S3) rather than reusing it (own Maven reactor, own schema, own
 * Flyway history -- {@code V3__payment_outbox.sql}). Written by {@link
 * PaymentService#processOrderPlaced} in the SAME {@code @Transactional} as
 * the {@link Payment} row write, so the emitted {@code payment.captured}/
 * {@code payment.declined} event is atomic with the business change it
 * describes: either both the payment row and this outbox row commit, or
 * neither does.
 *
 * <p>{@link PaymentOutboxRelay} is a separate, asynchronous reader of this
 * table -- it polls for rows where {@code publishedAt IS NULL}, publishes
 * each to the Kafka topic matching its {@code eventType}, and stamps {@code
 * publishedAt} on success. That stamp is an AT-LEAST-ONCE guarantee, not
 * exactly-once (same documented limitation as the monolith's relay): a crash
 * between the Kafka ack and the stamp commit republishes the identical row.
 * The future order-saga consumer (S6) is expected to dedupe by {@code
 * aggregateId} (the order id), exactly as DRQ-051 requires of THIS service's
 * own {@code order.placed} consumer.
 *
 * <p>{@code @Table(schema = "payment")} is explicit for the same reason
 * {@link Payment} needs it: the Dev Services / Testcontainers Postgres
 * connection's default search_path does not resolve to this service's own
 * schema.
 */
@Entity
@Table(name = "outbox", schema = "payment")
public class PaymentOutboxEvent {

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
     * The serialized event payload (JSON text) -- either a {@link
     * PaymentCaptured} or a {@link PaymentDeclined}, chosen by {@code
     * eventType} -- stored in a native Postgres {@code jsonb} column via
     * Hibernate 6's {@link JdbcTypeCode}. {@link PaymentOutboxRelay} forwards
     * this string verbatim as the Kafka message value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected PaymentOutboxEvent() {
        // JPA
    }

    public PaymentOutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
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
