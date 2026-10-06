package dev.patterncatalyst.order;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tier 2, real end-to-end {@code @QuarkusTest}. ch.26 S6 (DRQ-067) — THE
 * non-vacuity guard for the CQRS read model: {@link OrderViewProjector} is
 * {@code @InjectMock}ed so individual {@link OrderViewProjector#project}
 * calls can be suppressed, proving that {@link OrderService#getById} reads
 * EXCLUSIVELY from {@code order_view} and NEVER falls back to the {@link
 * Order} aggregate. If a future change ever introduced such a fallback,
 * this test would go RED (it would see the post-transition status instead
 * of the pre-transition one asserted below as "stale") — exactly the
 * scenario build-plan H2 calls out.
 *
 * <p><b>Why "re-enable" delegates to a hand-built {@link OrderViewProjector}
 * instead of {@code doCallRealMethod()}:</b> {@code @InjectMock} replaces
 * the CDI bean with a bare Mockito mock that never ran the real
 * constructor/field injection — its {@code orderViewRepository}/{@code
 * orderRepository}/{@code objectMapper} fields are null, so {@code
 * doCallRealMethod()} would NPE. Instead, {@link #setUp} builds a SEPARATE,
 * fully-wired {@link OrderViewProjector} from the REAL injected {@link
 * OrderViewRepository}/{@link OrderRepository}/{@link ObjectMapper} beans
 * (none of which are mocked in this test), and the mock's default answer
 * delegates every call to it — "enabled" means delegate to this real
 * instance; "disabled" ({@link #suppressNextProjection}) means {@code
 * doNothing()} for the suppressed window.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class OrderViewProjectionDisabledTest {

    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    OrderService orderService;

    @Inject
    OrderRepository orderRepository;

    @Inject
    OrderViewRepository orderViewRepository;

    @Inject
    CustomerRepository customerRepository;

    @Inject
    ObjectMapper objectMapper;

    @InjectMock
    RemoteInventoryClient remoteInventoryClient;

    @InjectMock
    OrderViewProjector orderViewProjector;

    private OrderViewProjector realDelegate;

    @BeforeEach
    void setUp() {
        realDelegate = new OrderViewProjector(orderViewRepository, orderRepository, objectMapper);
        enableProjection();
    }

    /** "Re-enable" -- every subsequent {@code project} call does real work via {@link #realDelegate}. */
    private void enableProjection() {
        doAnswer(invocation -> {
            realDelegate.project(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(orderViewProjector).project(any(), any(), any());
    }

    /** "Disable" -- the NEXT {@code project} call is a silent no-op, same as a broken/crashed projection. */
    private void suppressNextProjection() {
        doNothing().when(orderViewProjector).project(any(), any(), any());
    }

    private static Long persistCustomer(CustomerRepository customerRepository, String email) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Customer customer = new Customer("Ada Lovelace", email);
            customerRepository.persist(customer);
            return customer.getId();
        });
    }

    private Long placeOrderViaRealService(String email, String sku) {
        Long customerId = persistCustomer(customerRepository, email);
        when(remoteInventoryClient.reserve(sku, 1)).thenReturn(new RemoteInventoryClient.ReserveResult(true, 100));
        when(remoteInventoryClient.getStock(sku))
                .thenReturn(new RemoteInventoryClient.StockSnapshot(sku, "Item", 1000L));
        var command = new OrderCreate(customerId, List.of(new OrderCreate.Line(sku, 1)), "CARD-VISA", "1 Test Way");
        return orderService.placeOrder(command).id();
    }

    private static OrderStatus aggregateStatus(OrderRepository orderRepository, Long orderId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> orderRepository.findByIdOptional(orderId).orElseThrow().getStatus());
    }

    @Test
    void projectionDisabledDuringTransition_viewGoesStale_thenNextTransitionResyncsIt() {
        Long orderId = placeOrderViaRealService("projection-disabled@example.com", "SKU-STALE-1");

        given().when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("PENDING"));

        // Suppress the projection for exactly the next transition.
        suppressNextProjection();

        connector.source("payment-captured")
                .send(new PaymentCaptured(orderId, 1L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));

        // The AGGREGATE genuinely transitioned -- this is not a "the event
        // was never processed" false negative.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(aggregateStatus(orderRepository, orderId))
                        .isEqualTo(OrderStatus.AWAITING_SHIPMENT));

        // But the READ -- which must come EXCLUSIVELY from order_view, never
        // the aggregate -- is STALE: it still reports the pre-transition
        // PENDING status, because the projection that would have updated
        // the view never ran. Held over a window so a slow/eventual
        // fallback-driven read would still be caught as a failure (this is
        // the assertion that would go RED if a read ever fell back to the
        // aggregate).
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).until(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().extract().path("status").equals("PENDING"));

        // Re-enable the projection -- the NEXT transition re-derives the
        // FULL view row from the aggregate's CURRENT state (project() is a
        // full re-derive, not an incremental patch -- see
        // OrderViewProjector's javadoc), so it self-heals even though
        // AWAITING_SHIPMENT was skipped in the view entirely.
        enableProjection();

        connector.source("shipment-dispatched")
                .send(new ShipmentDispatched(orderId, 1L, "1 Test Way", "DISPATCHED", Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("CONFIRMED")));
    }
}
