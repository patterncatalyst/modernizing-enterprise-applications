package dev.patterncatalyst.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * r06/ch.23 S4 (DRQ-052, Phase A). Lifted from the monolith's
 * {@code dev.patterncatalyst.monolith.payment.Payment} with ONE deliberate
 * change: the monolith's {@code SMELL[ch.18]} {@code @ManyToOne Order}
 * cross-context FK/join (a direct join into the order context's table in the
 * SAME shared schema) is decomposed here to a plain {@code orderId} VALUE
 * column. This service owns its own {@code payment} Postgres schema and
 * cannot reach across into the monolith's {@code orders} table -- {@code
 * orderId} is the event-carried reference the choreography (S5: consume
 * {@code order.placed}, emit {@code payment.captured}/{@code
 * payment.declined}) will use to correlate a payment back to its order. This
 * is the same FK-decomposition discipline ch.19 applied to
 * {@code OrderItem}/inventory, lighter here because both sides agree on a
 * simple numeric id (same-direction correlation, not a richer snapshot).
 *
 * <p>{@code @Table(schema = "payment")} is explicit (mirrors
 * inventory-service's {@code InventoryItem}) because the Dev Services /
 * Testcontainers Postgres connection's default search_path does not resolve
 * to this service's own schema -- without the explicit schema, Hibernate
 * would read/write the wrong (default/public) schema even though Flyway
 * correctly created and migrated {@code payment.payments}.
 */
@Entity
@Table(name = "payments", schema = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(Long orderId, long amountCents, String method, PaymentStatus status) {
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.method = method;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
