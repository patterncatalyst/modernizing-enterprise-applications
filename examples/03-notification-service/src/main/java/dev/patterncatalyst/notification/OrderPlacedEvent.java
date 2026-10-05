package dev.patterncatalyst.notification;

import java.time.Instant;

/**
 * NET-NEW for ch.17 S5 (DRQ-035, DRQ-038): this service's own copy of the
 * JSON payload shape written by the monolith's
 * {@code dev.patterncatalyst.monolith.common.outbox.OrderPlacedEvent} (the
 * outbox row the {@code OutboxRelay} publishes to the {@code order.placed}
 * Kafka topic). Field names and types match EXACTLY, byte-for-byte, so
 * Quarkus's automatic Jackson-based Kafka deserialization (generated at
 * build time for a "trivial" record type bound to an {@code @Incoming}
 * channel — see {@code application.properties}) round-trips the monolith's
 * JSON without any custom (de)serializer.
 *
 * <p>There is no shared module between the monolith and this service (each
 * example in this repo is an independently deployable, independently built
 * Maven reactor member) — this duplication is the honest cost of a JSON
 * wire contract with no schema registry (DRQ-038 defers Avro + Apicurio
 * contract enforcement to ch.28). Until then, the two copies must be kept in
 * sync by hand; a schema-registry-enforced contract is exactly the problem
 * ch.28 solves.
 */
public record OrderPlacedEvent(
        Long orderId,
        Long customerId,
        String customerEmail,
        long totalCents,
        String confirmationMessage,
        Instant placedAt) {
}
