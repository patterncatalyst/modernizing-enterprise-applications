package dev.patterncatalyst.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

/**
 * NET-NEW for r06/ch.23 S5 (DRQ-052, idiomatic-from-the-start -- no Spring
 * original to lift). Exercises {@link OrderPlacedConsumer} AND {@link
 * PaymentOutboxRelay} through the REAL {@code @Incoming("order-placed")} /
 * {@code @Channel("payment-captured"|"payment-declined")} Reactive Messaging
 * pipelines by feeding events into, and reading emitted messages from,
 * SmallRye's in-memory connector (see {@link InMemoryMessagingTestProfile})
 * -- not by calling {@code service.processOrderPlaced(event)} or {@code
 * relay.publishUnpublishedEvents()} directly -- so the channel wiring itself
 * is also under test, while staying fast and independent of a live Kafka
 * broker.
 *
 * <p>Mirrors notification-service's {@code OrderPlacedConsumerTest} pattern
 * (ch.17/S5) for the "wrap each poll in a short-lived transaction via
 * QuarkusTransaction" technique -- Reactive Messaging processing happens off
 * the test's main thread with no CDI request/transaction context of its own.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class OrderPlacedConsumerTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    PaymentRepository repository;

    @Inject
    PaymentOutboxRepository outboxRepository;

    /**
     * Quarkus's CDI-managed {@code ObjectMapper} (the one {@link
     * PaymentService} serializes outbox payloads with) does not guarantee the
     * exact compact-JSON whitespace a hand-rolled {@code ObjectMapper()} in a
     * plain unit test would produce -- a literal {@code "orderId":801}
     * substring match is brittle against that. Match the field/value pair
     * with optional whitespace around the colon instead.
     */
    private static boolean payloadHasField(String payload, String field, String value) {
        return Pattern.compile("\"" + field + "\"\\s*:\\s*" + Pattern.quote(value)).matcher(payload).find();
    }

    @Test
    void consumingOrderPlacedEvent_normalMethod_capturesPayment_andEmitsPaymentCaptured() {
        InMemorySource<OrderPlacedEvent> source = connector.source("order-placed");
        InMemorySink<String> capturedSink = connector.sink("payment-captured");
        Long orderId = 801L;
        OrderPlacedEvent event = new OrderPlacedEvent(
                orderId, 801L, "capture-test@example.com", 3998L, "CARD-VISA",
                "Order #801 confirmed, total $39.98", Instant.now());

        source.send(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(orderId)))
                        .isNotNull());

        Payment payment = QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(orderId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getAmountCents()).isEqualTo(3998L);
        assertThat(payment.getMethod()).isEqualTo("CARD-VISA");

        // The outbox relay's @Scheduled poll publishes asynchronously, on its
        // own cadence (payment.outbox.relay.poll-interval), onto a SHARED
        // in-memory sink other @Test methods in this class also publish to
        // (the same @QuarkusTest instance is reused across methods) -- so
        // bounded-wait for a message matching THIS test's orderId specifically,
        // not merely "the sink is non-empty" / "the last received message".
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(capturedSink.received())
                        .anyMatch(message -> payloadHasField(message.getPayload(), "orderId", orderId.toString())));

        Message<String> published = capturedSink.received().stream()
                .filter(message -> payloadHasField(message.getPayload(), "orderId", orderId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(payloadHasField(published.getPayload(), "orderId", orderId.toString())).isTrue();
        assertThat(payloadHasField(published.getPayload(), "status", "\"CAPTURED\"")).isTrue();

        PaymentOutboxEvent outboxEvent = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(orderId)).firstResult());
        assertThat(outboxEvent.getEventType()).isEqualTo("payment.captured");
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    void consumingOrderPlacedEvent_declineMethod_declinesPayment_andEmitsPaymentDeclined() {
        InMemorySource<OrderPlacedEvent> source = connector.source("order-placed");
        InMemorySink<String> declinedSink = connector.sink("payment-declined");
        Long orderId = 802L;
        OrderPlacedEvent event = new OrderPlacedEvent(
                orderId, 802L, "decline-test@example.com", 1999L, "CARD-DECLINE",
                "Order #802 confirmed, total $19.99", Instant.now());

        source.send(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(orderId)))
                        .isNotNull());

        Payment payment = QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(orderId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DECLINED);

        // Same shared-sink caveat as the captured test above -- match by
        // orderId, not by "last received" (another test's publish can land
        // after this test's own publish within the same shared sink).
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(declinedSink.received())
                        .anyMatch(message -> payloadHasField(message.getPayload(), "orderId", orderId.toString())));

        Message<String> published = declinedSink.received().stream()
                .filter(message -> payloadHasField(message.getPayload(), "orderId", orderId.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(payloadHasField(published.getPayload(), "orderId", orderId.toString())).isTrue();
        assertThat(payloadHasField(published.getPayload(), "status", "\"DECLINED\"")).isTrue();

        PaymentOutboxEvent outboxEvent = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(orderId)).firstResult());
        assertThat(outboxEvent.getEventType()).isEqualTo("payment.declined");
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    void redeliveryOfSameOrderIsIdempotent_doesNotDoubleChargeOrDoubleEmit() {
        InMemorySource<OrderPlacedEvent> source = connector.source("order-placed");
        Long orderId = 803L;
        OrderPlacedEvent event = new OrderPlacedEvent(
                orderId, 803L, "idempotency-test@example.com", 500L, "CARD-VISA",
                "Order #803 confirmed, total $5.00", Instant.now());

        source.send(event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(orderId)))
                        .isNotNull());

        // Redeliver the IDENTICAL event -- exactly the at-least-once scenario
        // the future monolith-side order.placed publisher (S6) would produce.
        // Assert the count holds at exactly one continuously for a window
        // (not just a single point-in-time check right after sending), so a
        // slow duplicate insert racing this assertion would still be caught.
        source.send(event);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(8))
                .until(() -> QuarkusTransaction.requiringNew()
                        .call(() -> repository.find("orderId", orderId).count()) == 1);

        long outboxRowsForOrder = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(orderId)).count());
        assertThat(outboxRowsForOrder).isEqualTo(1L);
    }
}
