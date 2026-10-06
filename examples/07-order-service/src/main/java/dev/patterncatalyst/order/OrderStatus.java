package dev.patterncatalyst.order;

/**
 * Lifted byte-for-byte from the monolith's {@code common.OrderStatus} (ch.26
 * S4, DRQ-068/073, Phase A). {@code AWAITING_SHIPMENT}/{@code
 * SHIPPING_FAILED} are carried over even though this service's Phase A never
 * transitions an order into them — no saga reactions exist yet (S5) — so the
 * full lifecycle vocabulary is preserved for the read contract from day one.
 *
 * <p>Persistence note: {@code orders.status} is a plain {@code VARCHAR(32)}
 * column (see {@code db/migration/V1__create_order_schema.sql}), same as the
 * monolith — {@code @Enumerated(EnumType.STRING)} on {@link Order#status} is
 * the only validation.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PAYMENT_DECLINED,
    AWAITING_SHIPMENT,
    SHIPPING_FAILED
}
