package dev.patterncatalyst.contracts;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.patterncatalyst.contracts.avro.OrderPlaced;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * ch.28 (DRQ-038 follow-up, DRQ-076) — proves the Avro + Apicurio round trip:
 * {@link OrderAvroProducer} emits an {@link OrderPlaced} SpecificRecord,
 * Apicurio serves/registers its schema, and {@link OrderAvroConsumer}
 * deserializes a field-for-field-equal copy back out. Runs against Quarkus
 * Dev Services (Kafka + Apicurio Testcontainers, auto-started because no
 * explicit {@code kafka.bootstrap.servers}/{@code apicurio.registry.url} is
 * configured for the {@code %test} profile — see application.properties)
 * and so requires a container runtime (podman/docker) to execute.
 */
@QuarkusTest
class OrderAvroRoundTripTest {

    @Inject
    OrderAvroProducer producer;

    @Inject
    OrderAvroConsumer consumer;

    @Test
    void emittedEventIsConsumedFieldForFieldEqual() {
        OrderPlaced sent = producer.emitDemoOrder();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            OrderPlaced lastReceived = consumer.last();
            assertNotNull(lastReceived, "expected the consumer to have received an event by now");
            assertEquals(sent.getOrderId(), lastReceived.getOrderId());
        });

        OrderPlaced received = consumer.last();
        assertEquals(sent.getOrderId(), received.getOrderId());
        assertEquals(sent.getCustomerId(), received.getCustomerId());
        assertEquals(sent.getCustomerEmail(), received.getCustomerEmail());
        assertEquals(sent.getTotalCents(), received.getTotalCents());
        assertEquals(sent.getPaymentMethod(), received.getPaymentMethod());
        assertEquals(sent.getConfirmationMessage(), received.getConfirmationMessage());
        // Avro's timestamp-millis logical type truncates to millisecond
        // precision on the wire; truncate both sides before comparing so a
        // sub-millisecond difference introduced before serialization doesn't
        // produce a false failure.
        Instant sentMillis = sent.getPlacedAt().truncatedTo(ChronoUnit.MILLIS);
        Instant receivedMillis = received.getPlacedAt().truncatedTo(ChronoUnit.MILLIS);
        assertEquals(sentMillis, receivedMillis);
    }
}
