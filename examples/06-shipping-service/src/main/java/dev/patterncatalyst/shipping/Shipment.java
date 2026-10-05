package dev.patterncatalyst.shipping;

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
 * r07/ch.24 S4 (DRQ-063, Phase A). Lifted from the monolith's
 * {@code dev.patterncatalyst.monolith.shipping.Shipment} with ONE deliberate
 * change: the monolith's {@code SMELL[ch.18]} {@code @ManyToOne Order} FK/
 * join (a direct join into the order context's table in the SAME shared
 * schema) is decomposed here to a plain {@code orderId} VALUE column. This
 * service owns its own {@code shipping} Postgres schema and cannot reach
 * across into the monolith's {@code orders} table -- {@code orderId} is the
 * event-carried reference the future orchestrated saga (S5: consume
 * {@code payment.captured}, dispatch, emit {@code shipment.dispatched}/
 * {@code shipment.failed}) will use to correlate a shipment back to its
 * order. This is the exact same FK-decomposition move payment's
 * {@code Payment} entity made (DRQ-052) and ch.19 applied to
 * {@code OrderItem}/inventory.
 *
 * <p>{@code @Table(schema = "shipping")} is explicit (mirrors payment-service's
 * {@code Payment} / inventory-service's {@code InventoryItem}) because the
 * Dev Services / Testcontainers Postgres connection's default search_path
 * does not resolve to this service's own schema -- without the explicit
 * schema, Hibernate would read/write the wrong (default/public) schema even
 * though Flyway correctly created and migrated {@code shipping.shipments}.
 *
 * <p>r07/ch.24 S5 (DRQ-059/DRQ-063): {@link #markDispatched()}/
 * {@link #markCancelled()} are net-new status-transition mutators used by
 * the saga body ({@code ShipmentSagaSteps#emitDispatched}, step 4 --
 * PENDING-&gt;DISPATCHED, atomically with the {@code shipment.dispatched}
 * outbox write) and its compensation ({@code ShipmentSagaSteps#compensate}
 * -- PENDING-&gt;CANCELLED, atomically with the {@code shipment.failed}
 * outbox write). A row is persisted as {@link ShipmentStatus#PENDING} by
 * the saga's "dispatch shipment" step (step 2) -- BEFORE the "book
 * carrier" step (step 3, the deterministic {@code SHIP-FAIL} throw point,
 * DRQ-062) runs -- precisely so that on a forced failure there is always a
 * persisted row for the compensation to find and cancel (DRQ-062: "the
 * injection fires after the Shipment row is persisted so that both
 * compensations... are exercised"). Choosing PENDING-then-transition
 * (rather than persisting DISPATCHED immediately in step 2) is what gives
 * DRQ-063's "written atomically with the Shipment state change" guarantee
 * real teeth for BOTH outcomes, not just the happy path -- see
 * {@code ShipmentSagaSteps}'s class javadoc for the full rationale.
 */
@Entity
@Table(name = "shipments", schema = "shipping")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Shipment() {
        // JPA
    }

    public Shipment(Long orderId, String address, ShipmentStatus status) {
        this.orderId = orderId;
        this.address = address;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getAddress() {
        return address;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markDispatched() {
        this.status = ShipmentStatus.DISPATCHED;
    }

    public void markCancelled() {
        this.status = ShipmentStatus.CANCELLED;
    }
}
