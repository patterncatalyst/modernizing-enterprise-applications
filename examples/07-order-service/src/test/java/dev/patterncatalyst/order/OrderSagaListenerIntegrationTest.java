package dev.patterncatalyst.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
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
 * Tier 2, real end-to-end {@code @QuarkusTest} (Dev Services' isolated
 * Testcontainers Postgres). ch.26 S5 (DRQ-074) -- the four lifted saga
 * reactions, exercised through the REAL {@code @Incoming("payment-captured"|
 * "payment-declined"|"shipment-dispatched"|"shipment-failed")} pipeline
 * (SmallRye's in-memory connector swaps out Kafka -- see {@link
 * InMemoryMessagingTestProfile}), proving the happy chain, both compensation
 * paths (with &ge;2 skus, so the "release EVERY reserved sku" loop is
 * genuinely exercised, not just a single-item special case), and idempotent
 * redelivery through the actual messaging layer -- not merely unit-level
 * method calls (see {@link OrderSagaListenerTest} for the broader
 * guard/mutual-exclusion/unknown-order/partial-failure matrix at the unit
 * level).
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class OrderSagaListenerIntegrationTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    OrderRepository orderRepository;

    @InjectMock
    RemoteInventoryClient remoteInventoryClient;

    private static Long persistPendingOrder(OrderRepository orderRepository, String... skuQtyPairs) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Order order = new Order(1L, "saga-it@example.com", "1 Test Way");
            for (int i = 0; i < skuQtyPairs.length; i += 2) {
                order.addItem(
                        new OrderItem(skuQtyPairs[i], "Item", Integer.parseInt(skuQtyPairs[i + 1]), 1000L));
            }
            orderRepository.persist(order);
            return order.getId();
        });
    }

    private static OrderStatus statusOf(OrderRepository orderRepository, Long orderId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> orderRepository.findByIdOptional(orderId).orElseThrow().getStatus());
    }

    @Test
    void happyChain_paymentCapturedThenShipmentDispatched_reachesConfirmed_neverReleases() {
        Long orderId = persistPendingOrder(orderRepository, "SKU-HAPPY-1", "1");

        InMemorySource<PaymentCaptured> capturedSource = connector.source("payment-captured");
        InMemorySource<ShipmentDispatched> dispatchedSource = connector.source("shipment-dispatched");

        capturedSource.send(new PaymentCaptured(orderId, 1L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderRepository, orderId))
                        .isEqualTo(OrderStatus.AWAITING_SHIPMENT));

        dispatchedSource.send(new ShipmentDispatched(orderId, 1L, "1 Test Way", "DISPATCHED", Instant.now()));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderRepository, orderId)).isEqualTo(OrderStatus.CONFIRMED));

        verify(remoteInventoryClient, never()).release(anyString(), anyInt());
    }

    @Test
    void paymentDeclined_fromPending_releasesEverySkuExactlyOnce_redeliveryIsNoOp() {
        Long orderId = persistPendingOrder(orderRepository, "SKU-DECLINE-A", "2", "SKU-DECLINE-B", "3");
        PaymentDeclined event =
                new PaymentDeclined(orderId, 1L, 5000L, "CARD-DECLINE", "DECLINED", "insufficient funds", Instant.now());

        InMemorySource<PaymentDeclined> declinedSource = connector.source("payment-declined");
        declinedSource.send(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderRepository, orderId))
                        .isEqualTo(OrderStatus.PAYMENT_DECLINED));
        verify(remoteInventoryClient, times(1)).release("SKU-DECLINE-A", 2);
        verify(remoteInventoryClient, times(1)).release("SKU-DECLINE-B", 3);

        // Redeliver the IDENTICAL event -- exactly the at-least-once scenario
        // the real consumer group would see. Hold the assertion over a
        // window so a slow duplicate Release racing this check would still
        // be caught.
        declinedSource.send(event);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(8))
                .until(() -> statusOf(orderRepository, orderId) == OrderStatus.PAYMENT_DECLINED);
        verify(remoteInventoryClient, times(1)).release("SKU-DECLINE-A", 2);
        verify(remoteInventoryClient, times(1)).release("SKU-DECLINE-B", 3);
    }

    @Test
    void shipmentFailed_fromAwaitingShipment_releasesEverySkuExactlyOnce_redeliveryIsNoOp() {
        Long orderId = persistPendingOrder(orderRepository, "SKU-FAIL-A", "4", "SKU-FAIL-B", "5");
        // Drive the order to AWAITING_SHIPMENT first via the real pipeline,
        // exactly like the monolith's actual lifecycle would.
        connector.source("payment-captured")
                .send(new PaymentCaptured(orderId, 1L, 9000L, "CARD-VISA", "CAPTURED", Instant.now()));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderRepository, orderId))
                        .isEqualTo(OrderStatus.AWAITING_SHIPMENT));

        ShipmentFailed event =
                new ShipmentFailed(orderId, 1L, "1 Test Way", "FAILED", Instant.now(), "carrier unavailable");
        InMemorySource<ShipmentFailed> failedSource = connector.source("shipment-failed");
        failedSource.send(event);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderRepository, orderId))
                        .isEqualTo(OrderStatus.SHIPPING_FAILED));
        verify(remoteInventoryClient, times(1)).release("SKU-FAIL-A", 4);
        verify(remoteInventoryClient, times(1)).release("SKU-FAIL-B", 5);

        failedSource.send(event);
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(8))
                .until(() -> statusOf(orderRepository, orderId) == OrderStatus.SHIPPING_FAILED);
        verify(remoteInventoryClient, times(1)).release("SKU-FAIL-A", 4);
        verify(remoteInventoryClient, times(1)).release("SKU-FAIL-B", 5);
    }
}
