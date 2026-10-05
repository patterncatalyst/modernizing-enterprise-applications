package dev.patterncatalyst.notification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * NET-NEW for ch.17 S5 (DRQ-035: idiomatic-from-the-start, no Spring
 * original to lift). Exercises {@link OrderPlacedConsumer} through the REAL
 * {@code @Incoming("order-placed")} Reactive Messaging pipeline by feeding
 * events into SmallRye's in-memory connector (see
 * {@link InMemoryMessagingTestProfile}) — not by calling
 * {@code consumer.consume(event)} directly — so the channel wiring itself
 * (the thing a live Kafka broker would otherwise provide) is also under
 * test, while staying fast and independent of Kafka/Dev Services.
 *
 * <p>The happy-path test also asserts the row is visible via
 * {@code GET /api/notifications}, proving the full
 * outbox-shape-&gt;consumer-&gt;own-store-&gt;read-surface chain this
 * service is responsible for (the monolith side of that chain — outbox write
 * + relay publish — is exercised separately in the end-to-end verification
 * against a live Kafka broker and the monolith).
 *
 * <p>The idempotency test is DRQ-034/DRQ-037's requirement made concrete:
 * the monolith's {@code OutboxRelay} is AT LEAST ONCE, so redelivery of the
 * identical event must yield exactly one {@link Notification} row, not two.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class OrderPlacedConsumerTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    NotificationRepository repository;

    @Test
    void consumingOrderPlacedEvent_persistsNotification_andIsServedByReadSurface() {
        InMemorySource<OrderPlacedEvent> source = connector.source("order-placed");
        OrderPlacedEvent event = new OrderPlacedEvent(
                701L, 701L, "consumer-test@example.com", 3998, "Order #701 confirmed, total $39.98", Instant.now());

        source.send(event);

        // Reactive Messaging processing happens off the test's main thread
        // with no CDI request/transaction context of its own -- Panache
        // queries need one, so wrap each poll in a short-lived transaction
        // via QuarkusTransaction rather than requiring @ActivateRequestContext
        // on the whole test.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(701L)))
                        .isNotNull());

        Notification saved = QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(701L));
        assertThat(saved.getCustomerId()).isEqualTo(701L);
        assertThat(saved.getChannel()).isEqualTo("EMAIL");
        assertThat(saved.getMessage()).isEqualTo("Order #701 confirmed, total $39.98");

        given()
                .queryParam("customerId", "701")
                .when().get("/api/notifications")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].orderId", is(701))
                .body("[0].channel", is("EMAIL"))
                .body("[0].message", is("Order #701 confirmed, total $39.98"));
    }

    @Test
    void redeliveryOfSameOrderIsIdempotent() {
        InMemorySource<OrderPlacedEvent> source = connector.source("order-placed");
        OrderPlacedEvent event = new OrderPlacedEvent(
                702L, 702L, "idempotency-test@example.com", 500, "Order #702 confirmed, total $5.00", Instant.now());

        source.send(event);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                        QuarkusTransaction.requiringNew().call(() -> repository.findByOrderId(702L)))
                        .isNotNull());

        // Redeliver the IDENTICAL event -- exactly the at-least-once scenario
        // DRQ-034/DRQ-037 call out. Assert the count holds at exactly one
        // continuously for a window (not just a single point-in-time check
        // right after sending), so a slow duplicate insert racing this
        // assertion would still be caught.
        source.send(event);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(8))
                .until(() -> QuarkusTransaction.requiringNew()
                        .call(() -> repository.find("orderId", 702L).count()) == 1);
    }
}
