package dev.patterncatalyst.order;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tier 2, real end-to-end {@code @QuarkusTest} (Dev Services' isolated
 * Testcontainers Postgres). ch.26 S6 (DRQ-067/074) — THE CQRS non-vacuity
 * proof for the order-service read model: every assertion here reads
 * through the REAL {@code GET /api/orders}/{@code GET /api/orders/{id}} HTTP
 * surface ({@link OrderResource} → {@link OrderService} → {@link
 * OrderViewRepository}), driven by the REAL {@code placeOrder} command and
 * the REAL {@link OrderSagaListener} {@code @Incoming} pipeline (SmallRye's
 * in-memory connector swaps out Kafka — see {@link
 * InMemoryMessagingTestProfile}). {@link RemoteInventoryClient} is mocked,
 * same precedent as {@link CheckoutOutboxTest}/{@link
 * OrderSagaListenerIntegrationTest} — gRPC wiring itself is proven
 * elsewhere.
 *
 * <p>See {@link OrderViewProjectionDisabledTest} for the companion NEGATIVE
 * check (projection suppressed ⇒ read goes stale), which is what proves
 * these reads genuinely come from {@code order_view} and are not secretly
 * falling back to the aggregate.
 */
@QuarkusTest
@TestProfile(InMemoryMessagingTestProfile.class)
class OrderViewProjectionTest {

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
    OrderViewProjector orderViewProjector;

    @Inject
    CustomerRepository customerRepository;

    @InjectMock
    RemoteInventoryClient remoteInventoryClient;

    private static Long persistCustomer(CustomerRepository customerRepository, String email) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Customer customer = new Customer("Ada Lovelace", email);
            customerRepository.persist(customer);
            return customer.getId();
        });
    }

    private Long placeOrderViaRealService(String email, String sku, int quantity, long unitPriceCents) {
        Long customerId = persistCustomer(customerRepository, email);
        when(remoteInventoryClient.reserve(sku, quantity))
                .thenReturn(new RemoteInventoryClient.ReserveResult(true, 100));
        when(remoteInventoryClient.getStock(sku))
                .thenReturn(new RemoteInventoryClient.StockSnapshot(sku, "Item", unitPriceCents));
        var command = new OrderCreate(customerId, List.of(new OrderCreate.Line(sku, quantity)), "CARD-VISA",
                "1 Test Way");
        return orderService.placeOrder(command).id();
    }

    // -- read-after-write ----------------------------------------------------

    @Test
    void placeOrder_getReflectsPendingImmediately_fromTheView() {
        Long orderId = placeOrderViaRealService("read-after-write-1@example.com", "SKU-RAW-1", 1, 1000L);

        // ch.26 S6 (DRQ-067): strongly consistent WITHIN this service -- no
        // await/polling needed, the projection committed in the SAME
        // transaction as placeOrder.
        given().when().get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .body("id", is(orderId.intValue()))
                .body("status", is("PENDING"));
    }

    @Test
    void happyChain_paymentCapturedThenShipmentDispatched_getReflectsEachTransition_fromTheView() {
        Long orderId = placeOrderViaRealService("read-after-write-2@example.com", "SKU-RAW-2", 1, 1000L);

        connector.source("payment-captured")
                .send(new PaymentCaptured(orderId, 1L, 1000L, "CARD-VISA", "CAPTURED", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("AWAITING_SHIPMENT")));

        connector.source("shipment-dispatched")
                .send(new ShipmentDispatched(orderId, 1L, "1 Test Way", "DISPATCHED", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("CONFIRMED")));
    }

    @Test
    void paymentDeclined_getReflectsPaymentDeclined_fromTheView() {
        Long orderId = placeOrderViaRealService("read-after-write-3@example.com", "SKU-RAW-3", 2, 1500L);

        connector.source("payment-declined")
                .send(new PaymentDeclined(orderId, 1L, 3000L, "CARD-DECLINE", "DECLINED", "insufficient funds",
                        Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("PAYMENT_DECLINED")));
    }

    @Test
    void shipmentFailed_getReflectsShippingFailed_fromTheView() {
        Long orderId = placeOrderViaRealService("read-after-write-4@example.com", "SKU-RAW-4", 1, 2000L);

        connector.source("payment-captured")
                .send(new PaymentCaptured(orderId, 1L, 2000L, "CARD-VISA", "CAPTURED", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("AWAITING_SHIPMENT")));

        connector.source("shipment-failed")
                .send(new ShipmentFailed(orderId, 1L, "1 Test Way", "FAILED", Instant.now(), "carrier unavailable"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("SHIPPING_FAILED")));
    }

    /**
     * {@link OrderDto} served off the view must be BYTE-FOR-BYTE the same
     * external shape the Order Context Contract depends on: exactly the six
     * top-level fields (id/customerId/status/totalCents/createdAt/
     * shippingAddress/items), each item exactly
     * sku/quantity/unitPriceCents — no leaked read-model-internal column
     * (payment_status/shipment_status are NOT part of this contract).
     */
    @Test
    void getById_responseShape_isByteForByteOrderDtoContract_noReadModelColumnsLeak() {
        Long orderId = placeOrderViaRealService("shape-check@example.com", "SKU-SHAPE-1", 3, 777L);

        given().when().get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .body("$", hasKey("id"))
                .body("$", hasKey("customerId"))
                .body("$", hasKey("status"))
                .body("$", hasKey("totalCents"))
                .body("$", hasKey("createdAt"))
                .body("$", hasKey("shippingAddress"))
                .body("$", hasKey("items"))
                .body("items[0]", hasKey("sku"))
                .body("items[0]", hasKey("quantity"))
                .body("items[0]", hasKey("unitPriceCents"))
                .body("totalCents", is(777 * 3));

        JsonPath response = given().when().get("/api/orders/" + orderId).then().extract().jsonPath();
        Map<String, Object> rootFields = response.getMap("$");
        assertThat(rootFields.keySet())
                .isEqualTo(Set.of("id", "customerId", "status", "totalCents", "createdAt", "shippingAddress",
                        "items"));
        List<Map<String, Object>> items = response.getList("items");
        assertThat(items.get(0).keySet()).isEqualTo(Set.of("sku", "quantity", "unitPriceCents"));
    }

    // -- idempotent projection (DRQ-074) --------------------------------------

    @Test
    void project_calledTwiceForSameOrder_upsertsSingleRow_notDuplicated() {
        Long orderId = placeOrderViaRealService("idempotent@example.com", "SKU-IDEMPOTENT-1", 1, 500L);

        // The order is now PENDING with exactly one order_view row (written
        // by placeOrder's own projection). Call the projector AGAIN
        // directly for the SAME order -- simulating a redelivered saga
        // event re-running a reaction's project() call -- with a DIFFERENT
        // payment outcome, proving this is a genuine UPDATE-in-place, not a
        // second INSERT.
        QuarkusTransaction.requiringNew().run(() -> {
            Order order = orderRepository.findByIdOptional(orderId).orElseThrow();
            orderViewProjector.project(order, "CAPTURED", null);
        });
        QuarkusTransaction.requiringNew().run(() -> {
            Order order = orderRepository.findByIdOptional(orderId).orElseThrow();
            orderViewProjector.project(order, "CAPTURED", null);
        });

        long rowCount = QuarkusTransaction.requiringNew()
                .call(() -> orderViewRepository.find("orderId", orderId).count());
        assertThat(rowCount).isEqualTo(1L);

        String paymentStatus = QuarkusTransaction.requiringNew()
                .call(() -> orderViewRepository.findByIdOptional(orderId).orElseThrow().getPaymentStatus());
        assertThat(paymentStatus).isEqualTo("CAPTURED");
    }

    // -- rebuild-from-aggregate (DRQ-074) -------------------------------------

    @Test
    void rebuildFromAggregate_afterViewRowCorrupted_matchesAggregateAgain() {
        Long orderId = placeOrderViaRealService("rebuild@example.com", "SKU-REBUILD-1", 1, 999L);

        // Drive it to AWAITING_SHIPMENT via the real pipeline first, so the
        // aggregate and the view both agree on a non-trivial state before
        // corrupting the view.
        connector.source("payment-captured")
                .send(new PaymentCaptured(orderId, 1L, 999L, "CARD-VISA", "CAPTURED", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given()
                .when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("AWAITING_SHIPMENT")));

        // Corrupt the view row directly (bypassing the projector entirely)
        // -- simulates drift: a bad manual fix, a bug in a past projection.
        QuarkusTransaction.requiringNew().run(() -> {
            OrderView corrupted = orderViewRepository.findByIdOptional(orderId).orElseThrow();
            corrupted.update(
                    corrupted.getCustomerId(), OrderStatus.PENDING.name(), 1L, "CORRUPTED ADDRESS",
                    corrupted.getItems(), null, null);
        });
        given().when().get("/api/orders/" + orderId)
                .then().statusCode(200).body("status", is("PENDING")); // confirms the corruption took.

        // Rebuild-from-aggregate (DRQ-074) -- the documented recovery path.
        given().when().post("/api/orders/_rebuild-view")
                .then().statusCode(200);

        given().when().get("/api/orders/" + orderId)
                .then()
                .statusCode(200)
                .body("status", is("AWAITING_SHIPMENT"))
                .body("totalCents", is(999))
                .body("shippingAddress", is("1 Test Way"));
    }
}
