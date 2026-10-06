package dev.patterncatalyst.gateway;

/** The gateway's own mirror of shipping-service's {@code ShipmentStatus} wire vocabulary. */
public enum ShipmentStatus {
    PENDING,
    DISPATCHED,
    CANCELLED,
    FAILED
}
