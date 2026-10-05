package dev.patterncatalyst.payment;

import java.time.Instant;

/**
 * r06/ch.23 S5 -- the JSON wire contract this service PRODUCES for the
 * {@code payment.declined} topic (DRQ-048), field-for-field identical to the
 * monolith's {@code common.events.PaymentDeclined} (the consumer-side copy
 * the future order-saga reaction, S6, will deserialize against to drive the
 * compensating inventory {@code Release}, DRQ-049). Per DRQ-038, this is a
 * plain record, not shared code between the two Maven reactors.
 *
 * <p>Written atomically with the DECLINED {@link Payment} row via this
 * service's own transactional outbox ({@link PaymentOutboxEvent}, DRQ-053),
 * then published to Kafka by {@link PaymentOutboxRelay}. No funds are
 * captured on this path.
 *
 * @param orderId the saga correlation key (matches the triggering {@link
 *     OrderPlacedEvent#orderId()})
 * @param paymentId this service's own identifier for the declined payment row
 * @param amountCents the amount that was attempted, in cents
 * @param method the payment method used
 * @param status always the literal {@code "DECLINED"}, carried on the wire
 * @param reason why the payment was declined (the demo's deterministic
 *     CARD-DECLINE rule message)
 * @param declinedAt when this service recorded the decline
 */
public record PaymentDeclined(
        Long orderId,
        Long paymentId,
        long amountCents,
        String method,
        String status,
        String reason,
        Instant declinedAt) {

    public static final String STATUS = "DECLINED";
}
