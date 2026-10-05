package dev.patterncatalyst.order;

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
 * ch.26 S5 (DRQ-073, mirroring DRQ-053) -- this service's OWN transactional
 * outbox, replacing the monolith's {@code common.outbox.OutboxRelay} relay as
 * the external producer of {@code order.placed} (see EVENTS.md). Mirrors
 * payment-service's proven {@code PaymentOutboxEvent} row shape exactly (own
 * Maven reactor, own schema, own Flyway history -- the table itself was
 * already reserved empty by S4's {@code V2__order_outbox.sql}, so no new
 * migration is needed here).
 *
 * <p>Written by {@link OrderService#placeOrder} in the SAME {@code
 * @Transactional} as the {@link Order} row write (and the pre-handoff
 * inventory reservations), so the emitted {@code order.placed} event is
 * atomic with the business change it describes: either the order row AND
 * this outbox row both commit, or neither does -- no dual-write.
 *
 * <p>{@link OrderOutboxRelay} is a separate, asynchronous reader of this
 * table -- it polls for rows where {@code publishedAt IS NULL}, publishes
 * each to Kafka, and stamps {@code publishedAt} on success. That stamp is an
 * AT-LEAST-ONCE guarantee, not exactly-once (same documented limitation as
 * every other outbox in this repo): a crash between the Kafka ack and the
 * stamp commit republishes the identical row. Downstream consumers (the
 * payment service's {@code OrderPlacedConsumer}) are expected to dedupe by
 * {@code aggregateId} (the order id).
 *
 * <p>{@code @Table(schema = "order_service")} is explicit for the same
 * reason {@link Order}/{@link OrderItem}/{@link Customer} need it: the Dev
 * Services / Testcontainers Postgres connection's default search_path does
 * not resolve to this service's own schema.
 */
@Entity
@Table(name = "outbox", schema = "order_service")
public class OrderOutboxEvent {

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
     * The serialized {@link OrderPlacedEvent} payload (JSON text), stored in
     * a native Postgres {@code jsonb} column via Hibernate 6's {@link
     * JdbcTypeCode}. {@link OrderOutboxRelay} forwards this string verbatim
     * as the Kafka message value.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OrderOutboxEvent() {
        // JPA
    }

    public OrderOutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
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
