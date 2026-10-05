package dev.patterncatalyst.monolith.order;

import dev.patterncatalyst.monolith.common.Customer;
import dev.patterncatalyst.monolith.common.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The core aggregate; extracted last in the roadmap (ch.26) because
 * {@code OrderService} reaches directly into every other context (SMELL[ch.26]).
 *
 * SMELL[ch.18]: {@code customer_id} is a direct FK/join into the shared
 * {@code customers} table rather than an owned/replicated copy.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

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

    public Order(Customer customer, String shippingAddress) {
        this.customer = customer;
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

    /**
     * ch.24 (r07/S6, DRQ-061; r07/S9 the ONLY path): {@code
     * OrderSagaListener#onPaymentCaptured} transitions a {@code PENDING}
     * order here instead of confirming it directly, handing fulfilment off
     * to the shipping service's Camel Saga EIP coordinator. The order
     * leaves this intermediate state via exactly one of {@link #confirm()}
     * (on {@code shipment.dispatched}) or {@link #failShipping()} (on
     * {@code shipment.failed}).
     */
    public void awaitShipment() {
        this.status = OrderStatus.AWAITING_SHIPMENT;
    }

    /**
     * ch.24 (r07/S6, DRQ-061/060; r07/S9 the ONLY path): {@code
     * OrderSagaListener#onShipmentFailed} transitions an {@code
     * AWAITING_SHIPMENT} order here on the shipping saga's compensating
     * {@code shipment.failed} outcome. Terminal, mirroring {@link
     * #declinePayment()}'s shape. Payment is NOT refunded by this
     * transition — see {@code OrderSagaListener#onShipmentFailed}'s javadoc
     * for the documented scope limitation (DRQ-056).
     */
    public void failShipping() {
        this.status = OrderStatus.SHIPPING_FAILED;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
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
