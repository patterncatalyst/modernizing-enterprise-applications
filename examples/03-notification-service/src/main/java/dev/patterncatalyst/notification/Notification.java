package dev.patterncatalyst.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * UNCHANGED across ch.17 Phase A -&gt; Phase B (DRQ-029/DRQ-035), same as
 * review-service's entities: Panache's REPOSITORY pattern (as opposed to
 * active-record {@code PanacheEntity}) works against ordinary {@code @Entity}
 * classes, so zero entity edits were needed for the idiomatic refactor — see
 * {@link NotificationRepository} for where the Phase A -&gt; B change actually
 * lives.
 *
 * <p>The own-schema decision (plain {@code customerId}/{@code orderId}
 * {@code Long} columns instead of {@code @ManyToOne} relations into the
 * monolith's {@code customers}/{@code orders} tables) was made back in
 * Phase A (S4) and is untouched here: a service that owns its own schema
 * cannot hold a JPA relation into tables that live in a schema it does not
 * own. {@link OrderPlacedConsumer} (S5) populates this table directly from
 * the {@code order.placed} event's {@code customerId}/{@code orderId}
 * fields — no synchronous lookup into another service's data required.
 */
@Entity
@Table(name = "notifications", schema = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String message;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected Notification() {
        // JPA
    }

    public Notification(Long customerId, Long orderId, String channel, String message) {
        this.customerId = customerId;
        this.orderId = orderId;
        this.channel = channel;
        this.message = message;
        this.sentAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
