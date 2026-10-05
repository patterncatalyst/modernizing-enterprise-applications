package dev.patterncatalyst.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.InjectMock;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tier 2, real end-to-end {@code @QuarkusTest} (Dev Services' isolated
 * Testcontainers Postgres, migrated by this service's OWN Flyway history
 * incl. S5's {@code uq_shipments_order_id} unique constraint and the
 * {@code outbox} table). r07/ch.24 S5 (DRQ-056/057/059/062/063/064) -- the
 * orchestrated saga's happy path, deterministic abort+compensation, and
 * idempotent-redelivery acceptance criteria, exercised through the REAL
 * {@code @Incoming("payment-captured")} consumer -&gt; Camel Saga EIP route
 * -&gt; {@code @Channel("shipment-dispatched"|"shipment-failed")} outbox-relay
 * pipeline (SmallRye's in-memory connector swaps out Kafka -- see
 * {@link InMemoryMessagingTestProfile} -- the Camel {@code direct:} saga
 * route itself is entirely in-process and unaffected by that swap).
 *
 * <p>{@link OrderReadClient} is mocked ({@code @InjectMock}) rather than
 * exercised against a live monolith -- per this step's ENRICHMENT CONTRACT
 * decision (see that class's javadoc), the real {@code GET /api/orders/{id}}
 * does not expose {@code shippingAddress} yet (flagged for S6); mocking it
 * here is the documented way this step tests both a normal address and the
 * deterministic {@code SHIP-FAIL} sentinel without depending on that gap
 * being closed.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class PaymentCapturedConsumerTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    ShipmentRepository repository;

    @Inject
    ShipmentOutboxRepository outboxRepository;

    @InjectMock
    OrderReadClient orderReadClient;

    /**
     * Quarkus's CDI-managed {@code ObjectMapper} (the one {@link
     * ShipmentSagaSteps} serializes outbox payloads with) does not
     * guarantee the exact compact-JSON whitespace a hand-rolled {@code
     * ObjectMapper()} would produce -- match the field/value pair with
     * optional whitespace around the colon instead (same technique
     * payment-service's {@code OrderPlacedConsumerTest} uses).
     */
    private static boolean payloadHasField(String payload, String field, String value) {
        return Pattern.compile("\"" + field + "\"\\s*:\\s*" + Pattern.quote(value)).matcher(payload).find();
    }

    private static Shipment findByOrderId(ShipmentRepository repository, Long orderId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.findByOrderId(orderId).orElse(null));
    }

    @Test
    void normalPaymentCaptured_sagaCompletes_dispatchesShipment_emitsShipmentDispatchedExactlyOnce() {
        Long orderId = 901L;
        when(orderReadClient.fetchShippingAddress(eq(orderId))).thenReturn("1 Analytical Engine Way, London");

        InMemorySource<PaymentCaptured> source = connector.source("payment-captured");
        InMemorySink<String> dispatchedSink = connector.sink("shipment-dispatched");
        InMemorySink<String> failedSink = connector.sink("shipment-failed");

        source.send(new PaymentCaptured(orderId, 1L, 3998L, "CARD-VISA", "CAPTURED", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(findByOrderId(repository, orderId)).isNotNull());

        Shipment shipment = findByOrderId(repository, orderId);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DISPATCHED);
        assertThat(shipment.getAddress()).isEqualTo("1 Analytical Engine Way, London");

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(dispatchedSink.received())
                        .anyMatch(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString())));

        long dispatchedCount = dispatchedSink.received().stream()
                .filter(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString()))
                .count();
        assertThat(dispatchedCount).isEqualTo(1L);
        assertThat(failedSink.received())
                .noneMatch(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString()));
    }

    @Test
    void shipFailAddress_sagaAborts_compensates_cancelsShipment_emitsShipmentFailedExactlyOnce_noDispatched() {
        Long orderId = 902L;
        when(orderReadClient.fetchShippingAddress(eq(orderId))).thenReturn("SHIP-FAIL");

        InMemorySource<PaymentCaptured> source = connector.source("payment-captured");
        InMemorySink<String> dispatchedSink = connector.sink("shipment-dispatched");
        InMemorySink<String> failedSink = connector.sink("shipment-failed");

        source.send(new PaymentCaptured(orderId, 2L, 1500L, "CARD-VISA", "CAPTURED", Instant.now()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(findByOrderId(repository, orderId)).isNotNull());

        Shipment shipment = findByOrderId(repository, orderId);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(failedSink.received())
                        .anyMatch(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString())));

        long failedCount = failedSink.received().stream()
                .filter(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString()))
                .count();
        assertThat(failedCount).isEqualTo(1L);
        assertThat(dispatchedSink.received())
                .noneMatch(m -> payloadHasField(m.getPayload(), "orderId", orderId.toString()));

        long outboxRowsForOrder = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(orderId)).count());
        assertThat(outboxRowsForOrder).isEqualTo(1L);
    }

    @Test
    void redeliveryOfSameOrderIsIdempotent_doesNotCreateSecondShipmentOrRerunSaga() {
        Long orderId = 903L;
        when(orderReadClient.fetchShippingAddress(eq(orderId))).thenReturn("221B Baker Street, London");

        InMemorySource<PaymentCaptured> source = connector.source("payment-captured");
        PaymentCaptured event = new PaymentCaptured(orderId, 3L, 500L, "CARD-VISA", "CAPTURED", Instant.now());

        source.send(event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(findByOrderId(repository, orderId)).isNotNull());

        // Redeliver the IDENTICAL event -- exactly the at-least-once scenario
        // the real shipping-service Kafka consumer group would see. Assert
        // the count holds at exactly one continuously for a window (not just
        // a single point-in-time check), so a slow duplicate insert racing
        // this assertion would still be caught.
        source.send(event);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(8))
                .until(() -> QuarkusTransaction.requiringNew()
                        .call(() -> repository.find("orderId", orderId).count()) == 1);

        long outboxRowsForOrder = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(orderId)).count());
        assertThat(outboxRowsForOrder).isEqualTo(1L);

        // The second delivery's idempotency guard runs BEFORE the saga is
        // even triggered (ShippingService#processPaymentCaptured) -- the
        // enrich step (and therefore OrderReadClient) is never invoked a
        // second time for this order.
        org.mockito.Mockito.verify(orderReadClient, org.mockito.Mockito.times(1)).fetchShippingAddress(eq(orderId));
    }
}
