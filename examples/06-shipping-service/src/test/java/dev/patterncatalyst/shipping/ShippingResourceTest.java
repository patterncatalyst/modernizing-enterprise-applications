package dev.patterncatalyst.shipping;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RENAMED from Phase A's {@code ShippingControllerTest} to {@code
 * ShippingResourceTest} (r07/ch.24 S5, Phase B) to match the {@code
 * ShippingController} -&gt; {@link ShippingResource} rename -- same real,
 * end-to-end {@code @QuarkusTest} (Dev Services' isolated Testcontainers
 * Postgres, migrated by this service's OWN Flyway history incl. the S5
 * {@code uq_shipments_order_id} unique constraint), now proving the
 * idiomatic Quarkus REST + Panache stack preserves the EXACT same {@link
 * ShipmentDto} JSON shape and 404/400 contract Phase A's Spring-compat
 * stack served -- this IS the behavior-equivalence suite's "Shipping
 * Context Contract" shape, proven unchanged across the Phase A -&gt; B
 * refactor (DRQ-065, ACL honesty).
 *
 * <p>Seeds via {@link QuarkusTransaction#requiringNew()} in a NEW,
 * immediately-committed transaction -- Panache's {@code persist()} needs an
 * active transaction, unlike Phase A's Spring Data {@code save()} -- same
 * technique payment-service's Phase B {@code PaymentResourceTest} uses.
 */
@QuarkusTest
class ShippingResourceTest {

    // The Dev Services Postgres container (and its uq_shipments_order_id
    // unique index) persists across all @Test methods in this class -- only
    // one @QuarkusTest-managed instance is started for the whole class, not
    // reset per method -- so each test seeds a distinct orderId rather than
    // a fixed constant.
    private static final AtomicLong NEXT_ORDER_ID = new AtomicLong(424_242_000L);

    @Inject
    ShipmentRepository repository;

    private Long testOrderId;
    private Long testShipmentId;

    @BeforeEach
    void seed() {
        testOrderId = NEXT_ORDER_ID.incrementAndGet();
        Shipment saved = QuarkusTransaction.requiringNew().call(() -> {
            Shipment shipment =
                    new Shipment(testOrderId, "1 Analytical Engine Way, London", ShipmentStatus.DISPATCHED);
            repository.persist(shipment);
            return shipment;
        });
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
