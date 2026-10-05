package dev.patterncatalyst.monolith.common;

/**
 * Shared order-lifecycle vocabulary (reuse-map.md section 6: {@code
 * OrderStatus}).
 *
 * <p>ch.24 (r07/S6, DRQ-061): {@code AWAITING_SHIPMENT} and {@code
 * SHIPPING_FAILED} are added for the orchestrated shipping saga — see
 * {@code order.OrderSagaListener}. r07/ch.24 S9 (DECOMMISSION): orchestrated
 * is now the ONLY shipping path — every order handed off via {@code
 * payment.captured} passes through {@code AWAITING_SHIPMENT} on its way to
 * {@code CONFIRMED} or {@code SHIPPING_FAILED}; there is no more
 * {@code shipping.mode=inprocess} fallback where an order confirms directly.
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
