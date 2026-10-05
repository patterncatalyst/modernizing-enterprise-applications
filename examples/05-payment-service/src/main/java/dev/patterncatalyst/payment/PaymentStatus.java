package dev.patterncatalyst.payment;

/** Lifted byte-for-byte from the monolith's {@code payment.PaymentStatus}. */
public enum PaymentStatus {
    CAPTURED,
    DECLINED
}
