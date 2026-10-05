package dev.patterncatalyst.monolith.common;

/**
 * Event-topic names for the event-driven extractions (ch.17+).
 *
 * <p>{@code ORDER_PLACED} is published for real as of r04/S3+S8: {@code
 * order.OrderService#placeOrder} writes it to the transactional outbox on
 * every checkout, and {@code common.outbox.OutboxRelay} relays it to Kafka
 * (SMELL[ch.17] — the synchronous in-transaction notification call this
 * replaced — is now cured; see {@code SMELLS.md}). {@code PAYMENT_CAPTURED}
 * and {@code SHIPMENT_DISPATCHED} remain reserved for future extractions;
 * naming them here now (with zero messaging infrastructure attached for
 * those two) keeps the monolith's vocabulary aligned 1:1 with the sibling
 * target projects' Kafka topics (reuse-map.md section 6).
 */
public final class Topics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String SHIPMENT_DISPATCHED = "shipment.dispatched";

    private Topics() {
    }
}
