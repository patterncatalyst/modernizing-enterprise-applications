package dev.patterncatalyst.monolith.common.outbox;

import java.time.Instant;

/**
 * ch.17 (r04/S3) — the JSON payload written into the outbox for the {@code
 * order.placed} event (topic name reserved in {@code common/Topics.java}
 * since r02). This is deliberately the same information the now-deleted
 * {@code notification.NotificationService#sendOrderConfirmation} used to
 * build its synchronous confirmation message — the outbox path was a
 * straight swap of "call the method" for "write the event", not a redesign
 * of what gets communicated.
 *
 * <p>DRQ-038: serialized as plain JSON (via Jackson) for ch.17; migrating to
 * Avro + Apicurio is deferred to ch.28, same as the sibling datamesh
 * reference project.
 *
 * <p>ch.23 (r06/S6, DRQ-048): {@code paymentMethod} was added so the
 * choreographed payment service ({@code examples/05-payment-service})
 * can apply its deterministic {@code DECLINE}-in-method demo rule when it
 * consumes this event — without it, the payment service has no way to know
 * which method the checkout request specified and falls back to
 * {@code CARD-UNSPECIFIED}. JSON fields are matched by NAME, not position,
 * on the consumer side, but the field is placed here to mirror the payment
 * service's consumer-side record field order for clarity. Purely additive:
 * the only other consumer of this topic ({@code
 * examples/03-notification-service}) tolerates unknown JSON properties
 * (Quarkus/Jackson default {@code fail-on-unknown-properties=false}).
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
