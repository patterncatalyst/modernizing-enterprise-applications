package dev.patterncatalyst.monolith.common;

/** Shared order-lifecycle vocabulary (reuse-map.md section 6: {@code OrderStatus}). */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PAYMENT_DECLINED
}
