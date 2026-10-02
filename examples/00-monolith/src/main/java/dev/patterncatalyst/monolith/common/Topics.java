package dev.patterncatalyst.monolith.common;

/**
 * Reserved event-topic names for the future event-driven extractions (ch.17+).
 *
 * <p>The monolith does NOT publish to these topics today — see
 * {@code SMELL[ch.17]} on {@link dev.patterncatalyst.monolith.notification.NotificationService}
 * for the synchronous-call smell these names will eventually replace. Naming them
 * here now (with zero messaging infrastructure attached) keeps the monolith's
 * vocabulary aligned 1:1 with the sibling target projects' Kafka topics
 * (reuse-map.md section 6: {@code order.placed}, {@code payment.captured},
 * {@code shipment.dispatched}) without adding any speculative infrastructure.
 */
public final class Topics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String SHIPMENT_DISPATCHED = "shipment.dispatched";

    private Topics() {
    }
}
