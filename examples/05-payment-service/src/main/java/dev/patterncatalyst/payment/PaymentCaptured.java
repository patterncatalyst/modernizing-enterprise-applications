package dev.patterncatalyst.payment;

import java.time.Instant;

/**
 * r06/ch.23 S5 -- the JSON wire contract this service PRODUCES for the
 * {@code payment.captured} topic (DRQ-048), field-for-field identical to the
 * monolith's {@code common.events.PaymentCaptured} (the consumer-side copy
 * the future order-saga reaction, S6, will deserialize against). Per DRQ-038,
 * this is a plain record, not shared code between the two Maven reactors --
 * each side authors a structurally identical record against the same agreed
 * JSON shape.
 *
 * <p>Written atomically with the {@link Payment} row via this service's own
 * transactional outbox ({@link PaymentOutboxEvent}, DRQ-053), then published
 * to Kafka by {@link PaymentOutboxRelay}.
 *
 * @param orderId the saga correlation key (matches the triggering {@link
 *     OrderPlacedEvent#orderId()})
 * @param paymentId this service's own identifier for the captured payment row
 * @param amountCents the captured amount, in cents
 * @param method the payment method used
 * @param status always the literal {@code "CAPTURED"}, carried on the wire
 * @param capturedAt when this service captured the payment
 */
public record PaymentCaptured(
        Long orderId,
        Long paymentId,
        long amountCents,
        String method,
        String status,
        Instant capturedAt) {

    public static final String STATUS = "CAPTURED";
}
