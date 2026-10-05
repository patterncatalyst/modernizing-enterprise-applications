package dev.patterncatalyst.shipping;

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
 * r07/ch.24 S5 (DRQ-063, mirrors payment-service's {@code PaymentOutboxEvent},
 * DRQ-053) -- this service's OWN transactional outbox, in its OWN Maven
 * reactor, OWN schema, OWN Flyway history ({@code V4__shipment_outbox.sql}).
 * Written by {@code ShipmentSagaSteps#emitDispatched}
 * (happy path, {@code shipment.dispatched}) and
 * {@code ShipmentSagaSteps#compensate} (coordinator-invoked compensation,
 * {@code shipment.failed}) in the SAME {@code @Transactional} as the
 * {@link Shipment} row's status transition each describes, so the emitted
 * event is atomic with the business change: either both commit, or neither
 * does.
 *
 * <p>{@link ShipmentOutboxRelay} is a separate, asynchronous reader of this
 * table -- it polls for rows where {@code publishedAt IS NULL}, publishes
 * each to the Kafka topic matching its {@code eventType}, and stamps
 * {@code publishedAt} on success. That stamp is an AT-LEAST-ONCE guarantee,
 * not exactly-once (same documented limitation as every other outbox relay
 * in this repo): a crash between the Kafka ack and the stamp commit
 * republishes the identical row. The future monolith order-saga reaction
 * (S6) is expected to dedupe by {@code aggregateId} (the order id).
 *
 * <p>{@code @Table(schema = "shipping")} is explicit for the same reason
 * {@link Shipment} needs it: the Dev Services / Testcontainers Postgres
 * connection's default search_path does not resolve to this service's own
 * schema.
 */
@Entity
@Table(name = "outbox", schema = "shipping")
public class ShipmentOutboxEvent {

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
     * The serialized event payload (JSON text) -- either a
     * {@link ShipmentDispatched} or a {@link ShipmentFailed}, chosen by
     * {@code eventType} -- stored in a native Postgres {@code jsonb} column
     * via Hibernate 6's {@link JdbcTypeCode}. {@link ShipmentOutboxRelay}
     * forwards this string verbatim as the Kafka message value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected ShipmentOutboxEvent() {
        // JPA
    }

    public ShipmentOutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
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
