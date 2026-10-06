package dev.patterncatalyst.gateway;

/**
 * The gateway's own copy of order-service's {@code OrderStatus} wire
 * vocabulary (see {@code examples/07-order-service}'s
 * {@code dev.patterncatalyst.order.OrderStatus}). A straight 1:1 mirror, not
 * a translation layer -- the gateway's REST client deserializes the SAME
 * JSON string order-service's own REST clients do, so a no-op ACL here would
 * be the speculative-infra trap this repo's strangler-proxy javadoc already
 * argues against (DRQ-065 precedent). Kept as the gateway's own enum (rather
 * than a shared dependency) because this module, like every other extracted
 * service, has no shared domain-model jar to depend on.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PAYMENT_DECLINED,
    AWAITING_SHIPMENT,
    SHIPPING_FAILED
}
