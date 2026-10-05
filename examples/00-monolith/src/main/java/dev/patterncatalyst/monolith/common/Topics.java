package dev.patterncatalyst.monolith.common;

/**
 * Event-topic names for the event-driven extractions (ch.17+).
 *
 * <p>{@code ORDER_PLACED} is published for real as of r04/S3+S8: {@code
 * order.OrderService#placeOrder} writes it to the transactional outbox on
 * every checkout, and {@code common.outbox.OutboxRelay} relays it to Kafka
 * (SMELL[ch.17] — the synchronous in-transaction notification call this
 * replaced — is now cured; see {@code SMELLS.md}).
 *
 * <p>{@code PAYMENT_CAPTURED} and {@code PAYMENT_DECLINED} are the
 * choreographed-saga payment outcomes (ch.23, DRQ-048): the payment service
 * (future {@code examples/05-payment-service}, r06/S4+S5) consumes {@code
 * order.placed}, performs the capture, and produces exactly one of these two
 * via its own transactional outbox (DRQ-053). The monolith's order-saga
 * reaction (r06/S6, not yet wired as of this contract-only step) consumes
 * them to confirm the order or trigger the compensating inventory Release
 * (DRQ-049). As of r06/S3 only the topic names and the JSON payload contract
 * ({@code common.events.PaymentCaptured} / {@code
 * common.events.PaymentDeclined}) exist — zero producer/consumer wiring is
 * attached yet; that is S5 (payment service) and S6 (monolith order-saga).
 *
 * <p>{@code SHIPMENT_DISPATCHED} remains reserved for a future extraction;
 * naming it here now (with zero messaging infrastructure attached) keeps the
 * monolith's vocabulary aligned 1:1 with the sibling target projects' Kafka
 * topics (reuse-map.md section 6).
 */
public final class Topics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_DECLINED = "payment.declined";
    public static final String SHIPMENT_DISPATCHED = "shipment.dispatched";

    private Topics() {
    }
}
