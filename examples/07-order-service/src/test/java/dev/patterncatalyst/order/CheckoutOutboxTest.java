package dev.patterncatalyst.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tier 2, real end-to-end {@code @QuarkusTest} (Dev Services' isolated
 * Testcontainers Postgres, migrated by this service's OWN Flyway history,
 * incl. S4's {@code outbox} table). ch.26 S5 (DRQ-073) -- the CQRS write
 * model's checkout -&gt; outbox round trip, exercised through the REAL
 * {@link OrderService#placeOrder} -&gt; {@link OrderOutboxRelay} -&gt;
 * {@code @Channel("order-placed")} pipeline (SmallRye's in-memory connector
 * swaps out Kafka -- see {@link InMemoryMessagingTestProfile}).
 *
 * <p>{@link RemoteInventoryClient} is mocked ({@code @InjectMock}) rather
 * than exercised against a live inventory-service process, same as {@link
 * OrderServiceTest} and the monolith's own precedent -- gRPC wiring itself is
 * proven once (S4's MIGRATION.md "Verified live" section); this test's job
 * is the outbox atomicity and compensation guarantees.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class CheckoutOutboxTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    OrderService orderService;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderOutboxRepository outboxRepository;

    @Inject
    CustomerRepository customerRepository;

    @InjectMock
    RemoteInventoryClient remoteInventoryClient;

    /**
     * Quarkus's CDI-managed {@code ObjectMapper} does not guarantee the exact
     * compact-JSON whitespace a hand-rolled {@code ObjectMapper()} would
     * produce -- match the field/value pair with optional whitespace around
     * the colon instead (same technique payment-service's/shipping-service's
     * consumer tests use).
     */
    private static boolean payloadHasField(String payload, String field, String value) {
        return Pattern.compile("\"" + field + "\"\\s*:\\s*" + Pattern.quote(value)).matcher(payload).find();
    }

    private static Long persistCustomer(CustomerRepository customerRepository, String name, String email) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Customer customer = new Customer(name, email);
            customerRepository.persist(customer);
            return customer.getId();
        });
    }

    private static long outboxRowCount(OrderOutboxRepository outboxRepository) {
        return QuarkusTransaction.requiringNew().call(outboxRepository::count);
    }

    @Test
    void placeOrder_persistsPendingOrder_emitsExactlyOneOrderPlacedEvent_atomicWithTheRow() {
        Long customerId = persistCustomer(customerRepository, "Ada Lovelace", "checkout-outbox@example.com");
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));

        InMemorySink<String> orderPlacedSink = connector.sink("order-placed");

        var command = new OrderCreate(
                customerId, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");
        OrderDto dto = orderService.placeOrder(command);

        assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(dto.id()).isNotNull();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(orderPlacedSink.received())
                        .anyMatch(m -> payloadHasField(m.getPayload(), "orderId", dto.id().toString())));

        long matchingPublished = orderPlacedSink.received().stream()
                .filter(m -> payloadHasField(m.getPayload(), "orderId", dto.id().toString()))
                .count();
        assertThat(matchingPublished).isEqualTo(1L);

        long outboxRowsForOrder = QuarkusTransaction.requiringNew()
                .call(() -> outboxRepository.find("aggregateId", String.valueOf(dto.id())).count());
        assertThat(outboxRowsForOrder).isEqualTo(1L);

        Order persisted = QuarkusTransaction.requiringNew()
                .call(() -> orderRepository.findByIdOptional(dto.id()).orElseThrow());
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void placeOrder_reserveFailsOnSecondLine_compensatesFirstLine_writesNoOrderPlacedEvent() {
        Long customerId = persistCustomer(customerRepository, "Charles Babbage", "checkout-compensate@example.com");
        when(remoteInventoryClient.reserve("SKU-WIDGET-001", 2))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 98));
        when(remoteInventoryClient.getStock("SKU-WIDGET-001"))
                .thenReturn(new RemoteInventoryClient.StockSnapshot("SKU-WIDGET-001", "Standard Widget", 1999L));
        when(remoteInventoryClient.reserve("SKU-GIZMO-003", 99))
                .thenReturn(new RemoteInventoryClient.ReserveResult(false, 5));

        long outboxRowsBefore = outboxRowCount(outboxRepository);
        long orderRowsBefore =
                QuarkusTransaction.requiringNew().call(() -> orderRepository.find("customerId", customerId).count());

        var command = new OrderCreate(
                customerId,
                List.of(new OrderCreate.Line("SKU-WIDGET-001", 2), new OrderCreate.Line("SKU-GIZMO-003", 99)),
                "CARD-VISA",
                "1 Test Way");

        assertThatThrownBy(() -> orderService.placeOrder(command)).isInstanceOf(InsufficientStockException.class);

        verify(remoteInventoryClient).release("SKU-WIDGET-001", 2);
        verify(remoteInventoryClient, never()).release(eq("SKU-GIZMO-003"), anyInt());

        // No order row, no outbox row -- the pre-handoff catch means
        // order.placed was never written (DRQ-042).
        long orderRowsAfter =
                QuarkusTransaction.requiringNew().call(() -> orderRepository.find("customerId", customerId).count());
        assertThat(orderRowsAfter).isEqualTo(orderRowsBefore);

        long outboxRowsAfter = outboxRowCount(outboxRepository);
        assertThat(outboxRowsAfter).isEqualTo(outboxRowsBefore);
    }
}
