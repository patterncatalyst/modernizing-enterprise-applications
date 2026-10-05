package dev.patterncatalyst.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * ADAPTED from {@code dev.patterncatalyst.monolith.notification.Notification}
 * (ch.17 Phase A, DRQ-035). Unlike review-service's Phase A entities (which
 * were lifted with their {@code @ManyToOne}/{@code @JoinColumn} relations
 * UNCHANGED, because Review stays in the monolith's SHARED schema — see
 * {@code examples/02-review-service/Review.java}), this entity's relations to
 * {@code Customer} and {@code Order} are deliberately NOT lifted.
 *
 * <p>ONE deliberate, documented adaptation (forced by the own-schema decision,
 * not a Phase-B idiomatic rewrite): the monolith's {@code @ManyToOne Customer}
 * and {@code @ManyToOne Order} associations become plain {@code customerId}/
 * {@code orderId} {@code Long} columns. A service that owns its own schema
 * cannot hold a JPA relation into tables that live in a schema it does not
 * own (and must not reach across into) — the monolith's {@code customers}/
 * {@code orders} tables are not present in the {@code notification} schema at
 * all. Keeping only the foreign *values* (not JPA relations) is exactly what
 * an event-driven read model needs: ch.17 S5's SmallRye consumer populates
 * this table from the {@code order.placed} event's {@code customerId}/
 * {@code orderId} fields, with no synchronous lookup into another service's
 * data required. {@link NotificationRepository#findAllByCustomerId(Long)}
 * still works unchanged under {@code quarkus-spring-data-jpa} — Spring Data's
 * derived-query mechanism matches on the property name, whether that property
 * is a scalar column or a relation's id.
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
