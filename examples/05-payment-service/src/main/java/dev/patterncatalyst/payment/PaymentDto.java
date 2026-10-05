package dev.patterncatalyst.payment;

import java.time.Instant;

/**
 * Lifted byte-for-byte (field shape unchanged) from the monolith's
 * {@code payment.PaymentDto} -- this is the read contract the strangler
 * proxy's future {@code PaymentAclRoute} (S7) must translate to/from, so it
 * is preserved exactly: {@code orderId} was already a plain {@code Long} on
 * the wire in the monolith (only the JPA entity had the FK/join, see {@link
 * Payment}'s javadoc), so this record needed zero changes for the FK
 * decomposition.
 */
public record PaymentDto(
        Long id, Long orderId, long amountCents, String method, PaymentStatus status, Instant createdAt) {
}
