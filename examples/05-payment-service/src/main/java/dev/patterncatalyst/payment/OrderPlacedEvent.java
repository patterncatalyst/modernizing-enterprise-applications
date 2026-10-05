package dev.patterncatalyst.payment;

import java.time.Instant;

/**
 * r06/ch.23 S5 -- this service's own copy of the {@code order.placed} wire
 * contract (DRQ-038: plain JSON, not shared code between Maven reactors --
 * see {@code examples/00-monolith/.../common/outbox/OrderPlacedEvent}'s
 * javadoc, which documents the same "each side authors a structurally
 * identical record" discipline for the {@code payment.captured}/{@code
 * payment.declined} contracts this service PRODUCES).
 *
 * <p><b>Deliberate field addition -- {@code paymentMethod} -- flagged for
 * S6:</b> the monolith's {@code common.outbox.OrderPlacedEvent} (as it exists
 * today, r06/S3) does NOT carry a payment method; in the still-synchronous
 * monolith, {@code OrderService#placeOrder} charges in-process via {@code
 * command.paymentMethod()} (the checkout request, never persisted onto
 * {@code Order} or the outbox payload) BEFORE writing the {@code
 * order.placed} outbox row, and writes that row only on the SUCCESS path --
 * a decline today never produces an {@code order.placed} event at all. This
 * service's choreography (DRQ-047: the sync-&gt;async checkout contract
 * change) requires the opposite: {@code order.placed} must be published
 * BEFORE the payment decision so the decision can move to this service, and
 * the payment method has to travel with it so {@link PaymentService#charge}
 * has something to apply the CARD-DECLINE demo rule to. Per this step's
 * scope constraint (touch ONLY {@code examples/05-payment-service/}), that
 * monolith-side change (adding {@code paymentMethod} to the real {@code
 * order.placed} payload, and publishing it before the synchronous charge) is
 * explicitly deferred to r06/S6 ("the monolith becomes a Kafka consumer /
 * `payment.mode` flag" -- see {@code payment-plan.md}'s H1/H4). Until S6
 * lands, a redelivered/live monolith event will deserialize with {@code
 * paymentMethod == null}; {@link PaymentService#charge} treats that as a
 * normal (non-decline) method rather than failing the consumer (see that
 * method's javadoc) so this consumer is forward-compatible with today's
 * payload shape, not merely aspirational toward tomorrow's.
 *
 * @param orderId the saga correlation key
 * @param customerId the customer who placed the order
 * @param customerEmail unused by this service (carried for 1:1 field parity
 *     with the monolith's payload; harmless to ignore)
 * @param totalCents the amount this service captures/declines against
 * @param paymentMethod the demo payment method string (DRQ-052's CARD-DECLINE
 *     rule: any value containing {@code DECLINE}, case-insensitively, is
 *     declined) -- see the class javadoc for why this field does not yet
 *     exist on the monolith's real payload (deferred to S6)
 * @param confirmationMessage unused by this service (carried for field
 *     parity; harmless to ignore)
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
