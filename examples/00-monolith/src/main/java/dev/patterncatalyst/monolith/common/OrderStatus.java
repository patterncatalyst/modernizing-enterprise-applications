package dev.patterncatalyst.monolith.common;

/**
 * Shared order-lifecycle vocabulary (reuse-map.md section 6: {@code
 * OrderStatus}).
 *
 * <p>ch.24 (r07/S6, DRQ-061): {@code AWAITING_SHIPMENT} and {@code
 * SHIPPING_FAILED} are added for {@code shipping.mode=orchestrated} only —
 * see {@code order.OrderSagaListener}. In {@code shipping.mode=inprocess}
 * (the default) no order ever reaches either state; the lifecycle stays
 * exactly {@code PENDING} -&gt; {@code CONFIRMED}|{@code PAYMENT_DECLINED}, as
 * it was before this step (H4 — byte-for-byte baseline unregressed).
 *
 * <p>Persistence note: {@code orders.status} is a plain {@code VARCHAR(32)}
 * column (see {@code db/migration/V1__init_schema.sql}) with no DB-level
 * CHECK constraint or native enum type — {@code @Enumerated(EnumType.STRING)}
 * on {@code order.Order#status} is the only validation. Adding new constants
 * here therefore needs no Flyway migration.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PAYMENT_DECLINED,
    AWAITING_SHIPMENT,
    SHIPPING_FAILED
}
