package dev.patterncatalyst.monolith.notification;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A record of a notification sent to a customer. SMELL[ch.18]: joins directly to
 * both {@code customers} and {@code orders} in the shared schema.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String message;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected Notification() {
        // JPA
    }

    public Notification(Customer customer, Order order, String channel, String message) {
        this.customer = customer;
        this.order = order;
        this.channel = channel;
        this.message = message;
        this.sentAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Order getOrder() {
        return order;
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
