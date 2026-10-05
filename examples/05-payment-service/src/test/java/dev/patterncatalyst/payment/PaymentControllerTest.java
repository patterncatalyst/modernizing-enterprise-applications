package dev.patterncatalyst.payment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * r06/ch.23 S4 (Phase A, DRQ-052). Real, end-to-end {@code @QuarkusTest} for
 * the lifted read surface -- no mocking of {@link PaymentService}: the thing
 * worth proving is that Quarkus Dev Services' isolated Testcontainers
 * Postgres, this service's OWN Flyway migration (own `payment` schema), and
 * the Spring-compat stack (quarkus-spring-web/-di/-data-jpa) all work
 * together end-to-end, preserving the monolith's exact {@link PaymentDto}
 * JSON shape and 404 contract -- this is the behavior-equivalence suite's
 * forward-looking "Payment Context Contract" shape (S2/S4), proven here at
 * the service level before the strangler proxy's PaymentAclRoute (S7)
 * exists.
 *
 * <p>Seeds its own test-specific row directly via {@link PaymentRepository}
 * (self-seeded, like inventory-service's {@code InventoryResourceTest} seeds
 * via its writer) ON TOP of the one demo row {@code V2__seed_demo_payment.sql}
 * seeds on every fresh Dev Services container, so assertions don't depend on
 * migration-seed ordering alone.
 */
@QuarkusTest
class PaymentControllerTest {

    // The Dev Services Postgres container (and its uq_payments_order_id
    // unique index) persists across all @Test methods in this class -- only
    // one @QuarkusTest-managed instance is started for the whole class, not
    // reset per method -- so each test must seed a distinct orderId rather
    // than a fixed constant.
    private static final AtomicLong NEXT_ORDER_ID = new AtomicLong(424_242_000L);

    @Inject
    PaymentRepository repository;

    private Long testOrderId;
    private Long testPaymentId;

    @BeforeEach
    void seed() {
        testOrderId = NEXT_ORDER_ID.incrementAndGet();
        Payment saved = repository.save(new Payment(testOrderId, 5999L, "CARD-MASTERCARD", PaymentStatus.CAPTURED));
        testPaymentId = saved.getId();
    }

    @Test
    void getById_found_returns200WithPaymentDtoShape() {
        given()
                .when().get("/api/payments/" + testPaymentId)
                .then()
                .statusCode(200)
                .body("id", is(testPaymentId.intValue()))
                .body("orderId", is(testOrderId.intValue()))
                .body("amountCents", is(5999))
                .body("method", is("CARD-MASTERCARD"))
                .body("status", is("CAPTURED"))
                .body("createdAt", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void getById_unknown_returns404WithApiErrorShape() {
        given()
                .when().get("/api/payments/999999")
                .then()
                .statusCode(404)
                .body("error", is("NOT_FOUND"))
                .body("message", is("No payment with id 999999"));
    }

    @Test
    void listByOrderId_existingOrder_returns200WithPaymentDtoArray() {
        given()
                .queryParam("orderId", testOrderId)
                .when().get("/api/payments")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].orderId", is(testOrderId.intValue()))
                .body("[0].status", is("CAPTURED"));
    }

    @Test
    void listByOrderId_unknownOrder_returns200WithEmptyArray() {
        given()
                .queryParam("orderId", 1_000_000L)
                .when().get("/api/payments")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void listByOrderId_missingRequiredParam_returns400() {
        given()
                .when().get("/api/payments")
                .then()
                .statusCode(400);
    }
}
