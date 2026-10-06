package dev.patterncatalyst.order;

import java.time.Instant;

/**
 * ch.26 (r08/S3, DRQ-073) — this service's own copy of the {@code
 * order.placed} JSON wire contract, field-for-field identical to the
 * monolith's {@code common.outbox.OrderPlacedEvent} (verified against
 * {@code examples/00-monolith/.../common/outbox/OrderPlacedEvent.java}) and
 * to the payment service's consumer-side copy
 * ({@code examples/05-payment-service/.../OrderPlacedEvent.java}). Per
 * DRQ-038, this is a plain record, not shared code between Maven reactors —
 * each reactor authors a structurally identical record against the same
 * agreed JSON shape.
 *
 * <p><b>Produced by:</b> this service's own transactional outbox (the S5/S6
 * {@code OrderOutboxEvent}/{@code OrderOutboxRelay} machinery, DRQ-073,
 * mirroring DRQ-053) — written atomically with the {@code Order} row on
 * {@code OrderService#placeOrder}, replacing the monolith's {@code
 * common.outbox.OutboxRelay} relay as the external producer of this topic.
 * Not wired in this step: this record is authored contract-only (DRQ-073/
 * ch.26 S3); the outbox write path lands in S5.
 *
 * <p><b>Consumed by:</b> the payment service ({@code
 * examples/05-payment-service}), which reacts with exactly one of {@code
 * payment.captured} / {@code payment.declined}.
 *
 * @param orderId the saga correlation key — this order's identifier
 * @param customerId the customer who placed the order
 * @param customerEmail denormalized customer email snapshot, carried on the
 *     wire so downstream consumers never need to look up the customer
 * @param totalCents the order total, in cents
 * @param paymentMethod the checkout request's payment method (added ch.23/
 *     r06/S6 so the choreographed payment service can apply its
 *     deterministic DECLINE-in-method demo rule)
 * @param confirmationMessage the order-confirmation message text
 * @param placedAt when the order was placed
 */
public record OrderPlacedEvent(
        Long orderId,
        Long customerId,
        String customerEmail,
        long totalCents,
        String paymentMethod,
        String confirmationMessage,
        Instant placedAt) {
}
