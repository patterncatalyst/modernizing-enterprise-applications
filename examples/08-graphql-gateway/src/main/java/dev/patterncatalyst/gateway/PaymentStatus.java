package dev.patterncatalyst.gateway;

/** The gateway's own mirror of payment-service's {@code PaymentStatus} wire vocabulary. */
public enum PaymentStatus {
    CAPTURED,
    DECLINED
}
