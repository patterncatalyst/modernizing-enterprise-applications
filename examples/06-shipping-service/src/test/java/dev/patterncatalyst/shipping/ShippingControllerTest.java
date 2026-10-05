package dev.patterncatalyst.shipping;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tier 2, real end-to-end {@code @QuarkusTest} (Dev Services' isolated
 * Testcontainers Postgres, migrated by this service's OWN Flyway history).
 * r07/ch.24 S4 (DRQ-063, Phase A): proves the lifted
 * {@code quarkus-spring-web}/{@code quarkus-spring-data-jpa} stack serves
 * the EXACT same {@link ShipmentDto} JSON shape and 404/400 contract as the
 * monolith's {@code ShippingControllerTest} -- this IS the behavior-
 * equivalence suite's forward-referenced "Shipping Context Contract" shape
 * (S2/S4) that S7's transparent strangler-proxy route depends on staying
 * byte-for-byte unchanged (DRQ-065, ACL honesty: no translator needed).
 *
 * <p>{@link ShipmentRepository#save} is automatically {@code @Transactional}
 * under {@code quarkus-spring-data-jpa} (unlike Panache's {@code persist()},
 * which needs an explicit active transaction) -- so this seed, unlike
 * payment-service's Phase B {@code PaymentResourceTest}, needs no
 * {@code QuarkusTransaction.requiringNew()} wrapper; this is the genuine
 * Phase A Spring-compat behavior being proven, not a simplification.
 */
@QuarkusTest
class ShippingControllerTest {

    // The Dev Services Postgres container persists across all @Test methods
    // in this class -- only one @QuarkusTest-managed instance is started for
    // the whole class, not reset per method -- so each test seeds a distinct
    // orderId rather than a fixed constant.
    private static final AtomicLong NEXT_ORDER_ID = new AtomicLong(424_242_000L);

    @Inject
    ShipmentRepository repository;

    private Long testOrderId;
    private Long testShipmentId;

    @BeforeEach
    void seed() {
        testOrderId = NEXT_ORDER_ID.incrementAndGet();
        Shipment saved = repository.save(
                new Shipment(testOrderId, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED));
        testShipmentId = saved.getId();
    }

    @Test
    void getById_found_returns200WithShipmentDtoShape() {
        given()
                .when().get("/api/shipments/" + testShipmentId)
                .then()
                .statusCode(200)
                .body("id", is(testShipmentId.intValue()))
                .body("orderId", is(testOrderId.intValue()))
                .body("address", is("1 Analytical Engine Way, London"))
                .body("status", is("DISPATCHED"))
                .body("createdAt", notNullValue());
    }

    @Test
    void getById_unknown_returns404WithApiErrorShape() {
        given()
                .when().get("/api/shipments/999999")
                .then()
                .statusCode(404)
                .body("error", is("NOT_FOUND"))
                .body("message", is("No shipment with id 999999"));
    }

    @Test
    void listByOrderId_existingOrder_returns200WithShipmentDtoArray() {
        given()
                .queryParam("orderId", testOrderId)
                .when().get("/api/shipments")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].orderId", is(testOrderId.intValue()))
                .body("[0].status", is("DISPATCHED"));
    }

    @Test
    void listByOrderId_unknownOrder_returns200WithEmptyArray() {
        given()
                .queryParam("orderId", 1_000_000L)
                .when().get("/api/shipments")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void listByOrderId_missingRequiredParam_returns400() {
        given()
                .when().get("/api/shipments")
                .then()
                .statusCode(400);
    }
}
