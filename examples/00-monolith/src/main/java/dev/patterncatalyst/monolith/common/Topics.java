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
 * <p>{@code SHIPMENT_DISPATCHED} and {@code SHIPMENT_FAILED} are the two
 * outcomes of the ORCHESTRATED shipping saga (ch.24, DRQ-056/058) — the
 * deliberate contrast to payment's choreography: {@code
 * payment.captured} (DRQ-048) is consumed by the new shipping service
 * (future {@code examples/06-shipping-service}, r07/S4+S5), whose Camel
 * Saga EIP route acts as the coordinator for the fulfilment steps
 * (enrich → dispatch shipment → book carrier → emit outcome, DRQ-059) and
 * produces exactly one of these two via its own transactional outbox
 * (DRQ-063, mirroring DRQ-053). As of r07/S3 only the topic names and the
 * JSON payload contracts ({@code common.events.ShipmentDispatched} / {@code
 * common.events.ShipmentFailed}) exist — zero producer/consumer wiring is
 * attached yet; that is S5 (shipping service) and S6 (monolith order-saga).
 *
 * <p>{@code SHIPMENT_DISPATCHED} was previously reserved (naming only, no
 * contract) and is now WIRED by this saga: produced by the shipping
 * service's saga on successful dispatch, and consumed by the monolith
 * order context's future reaction (r07/S6) to transition the order from
 * {@code AWAITING_SHIPMENT} to {@code CONFIRMED} — the order's {@code
 * CONFIRMED} transition moves one hop later than ch.23 ({@code
 * payment.captured} → {@code shipment.dispatched}, DRQ-058/061).
 *
 * <p>{@code SHIPMENT_FAILED} is new: produced by the shipping service's
 * saga compensation (the coordinator-decided, reverse-ordered undo invoked
 * on any abort/timeout — {@code direct:ship-compensate}, DRQ-059) after it
 * cancels the local {@code Shipment} row. It is consumed by the monolith
 * order context's future reaction (r07/S6), which transitions the order to
 * the terminal {@code SHIPPING_FAILED} state and issues the compensating
 * gRPC inventory {@code Release} for every reserved sku — the order
 * context owns that compensation because it owns the reserved-line
 * snapshot, not the shipping service (DRQ-060/043), reusing the exact
 * ch.23 {@code OrderSagaListener} + {@code RemoteInventoryClient}
 * machinery (DRQ-042/049).
 */
public final class Topics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String PAYMENT_CAPTURED = "payment.captured";
    public static final String PAYMENT_DECLINED = "payment.declined";
    public static final String SHIPMENT_DISPATCHED = "shipment.dispatched";
    public static final String SHIPMENT_FAILED = "shipment.failed";

    private Topics() {
    }
}
