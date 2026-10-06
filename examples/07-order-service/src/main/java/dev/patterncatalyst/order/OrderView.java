package dev.patterncatalyst.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ch.26 S6 (DRQ-067/074) — the CQRS READ model: one denormalized row per
 * order, maintained EXCLUSIVELY by {@link OrderViewProjector} (never
 * mutated by any other class) and read EXCLUSIVELY by {@link
 * OrderViewRepository} (never {@link OrderRepository}/{@link Order} — see
 * {@link OrderService#getById}/{@link OrderService#listAll}). This is the
 * "two models, one store" CQRS variant (DRQ-067): same Postgres database as
 * the {@link Order} write-model aggregate, own table
 * ({@code order_service.order_view}, {@code
 * V4__order_view_denormalized.sql}), projected from the SAME lifecycle
 * transitions the write model already commits — not a second datastore, not
 * event sourcing.
 *
 * <p>{@link #paymentStatus}/{@link #shipmentStatus} are the latest known
 * outcome of the two cross-service sagas this order participates in
 * (projected by {@link OrderSagaListener}'s four reactions). They are
 * denormalized read-model columns the CQRS shape calls for (DRQ-067) — NOT
 * part of the external {@link OrderDto} contract, which stays byte-for-byte
 * unchanged.
 *
 * <p>{@link #items} is a JSON array of {@code {sku, quantity,
 * unitPriceCents}} objects via Hibernate 6's {@link JdbcTypeCode} — the same
 * technique {@link OrderOutboxEvent#getPayload()} already uses for this
 * service's {@code jsonb} columns — carrying the item SUMMARY a reader
 * needs, not a normalized join back to {@code order_items}.
 */
@Entity
@Table(name = "order_view", schema = "order_service")
public class OrderView {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String status;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    /** JSON array of {@code {sku, quantity, unitPriceCents}} — see {@link OrderDto.Item}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String items;

    @Column(name = "payment_status")
    private String paymentStatus;

    @Column(name = "shipment_status")
    private String shipmentStatus;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderView() {
        // JPA
    }

    OrderView(
            Long orderId,
            Long customerId,
            String status,
            long totalCents,
            Instant createdAt,
            String shippingAddress,
            String items,
            String paymentStatus,
            String shipmentStatus) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.status = status;
        this.totalCents = totalCents;
        this.createdAt = createdAt;
        this.shippingAddress = shippingAddress;
        this.items = items;
        this.paymentStatus = paymentStatus;
        this.shipmentStatus = shipmentStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Mutates every projected field in place — called by {@link
     * OrderViewProjector} when a row for this {@code orderId} already
     * exists (the UPDATE half of its upsert). The actual {@code UPDATE}
     * statement is Hibernate's own dirty-checking flush at transaction
     * commit, not an explicit call here — {@code this} is already a managed
     * entity (loaded inside the caller's transaction), so setting its state
     * is sufficient.
     */
    void update(
            Long customerId,
            String status,
            long totalCents,
            String shippingAddress,
            String items,
            String paymentStatus,
            String shipmentStatus) {
        this.customerId = customerId;
        this.status = status;
        this.totalCents = totalCents;
        this.shippingAddress = shippingAddress;
        this.items = items;
        this.paymentStatus = paymentStatus;
        this.shipmentStatus = shipmentStatus;
        this.updatedAt = Instant.now();
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getItems() {
        return items;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getShipmentStatus() {
        return shipmentStatus;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
