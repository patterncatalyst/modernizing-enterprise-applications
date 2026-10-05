package dev.patterncatalyst.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The core aggregate; extracted last in the roadmap (ch.26) because the
 * monolith's {@code OrderService} reached directly into every other context
 * (SMELL[ch.26]). Lifted from the monolith's {@code order.Order} (ch.26 S4,
 * DRQ-068/073, Phase A).
 *
 * <p><b>FK decomposition (DRQ-068), curing SMELL[ch.18] for this seam:</b> the
 * monolith's {@code @ManyToOne Customer customer} JPA association + DB-level
 * {@code customer_id} FK into the shared {@code customers} table is replaced
 * with a plain {@link #customerId} value and a {@link #customerEmail}
 * snapshot — no JPA association onto {@link Customer} at all, even though
 * {@link Customer} is now co-owned by this SAME service (own schema,
 * own Flyway history, {@code V1__create_order_schema.sql}). The value
 * reference (not an association) is deliberate: it keeps {@code Order}'s
 * aggregate boundary decoupled from {@code Customer}'s lifecycle, mirroring
 * the precedent {@link OrderItem}'s sku/name/price snapshot already set for
 * the order-&gt;inventory seam (DRQ-043) — a future change in how customer
 * data is owned (e.g. if it ever becomes its own extraction) would not
 * require touching this entity's mapping. There is deliberately no DB-level
 * FK from {@code orders.customer_id} to {@code customers.id} either, for the
 * same reason (see {@code V1__create_order_schema.sql}'s comment). The email
 * snapshot is captured once, at order-placement time, so a later customer
 * email change never retroactively alters a historical order's record —
 * same read-model argument {@link OrderItem}'s javadoc makes for its
 * snapshot fields.
 */
@Entity
@Table(name = "orders", schema = "order_service")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Snapshot of {@code Customer#getEmail()} at order-placement time (DRQ-068) — not a live join. */
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // JPA
    }

    public Order(Long customerId, String customerEmail, String shippingAddress) {
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING;
        this.totalCents = 0L;
        this.createdAt = Instant.now();
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.assignTo(this);
        this.totalCents += item.getUnitPriceCents() * (long) item.getQuantity();
    }

    public void confirm() {
        this.status = OrderStatus.CONFIRMED;
    }

    public void declinePayment() {
        this.status = OrderStatus.PAYMENT_DECLINED;
    }

    /** Preserved from the monolith for lifecycle-vocabulary fidelity; no caller yet in Phase A (S5 wires the saga reactions). */
    public void awaitShipment() {
        this.status = OrderStatus.AWAITING_SHIPMENT;
    }

    /** Preserved from the monolith for lifecycle-vocabulary fidelity; no caller yet in Phase A (S5 wires the saga reactions). */
    public void failShipping() {
        this.status = OrderStatus.SHIPPING_FAILED;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
