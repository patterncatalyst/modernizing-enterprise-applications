package dev.patterncatalyst.payment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RENAMED from Phase A's {@code PaymentControllerTest} to {@code
 * PaymentResourceTest} (r06/ch.23 S5, Phase B) to match the {@code
 * PaymentController} -&gt; {@link PaymentResource} rename -- same real,
 * end-to-end {@code @QuarkusTest} (Dev Services' isolated Testcontainers
 * Postgres, self-seeded via {@link PaymentRepository}), now proving the
 * idiomatic Quarkus REST + Panache stack preserves the exact same {@link
 * PaymentDto} JSON shape and 404/400 contract Phase A's Spring-compat stack
 * served -- this IS the behavior-equivalence suite's "Payment Context
 * Contract" shape (S2/S4), proven unchanged across the Phase A -&gt; B
 * refactor.
 *
 * <p>Seeds via {@link QuarkusTransaction#requiringNew()} in a NEW,
 * immediately-committed transaction -- not Spring Data's {@code save()}
 * (Panache's {@code persist()} requires an active transaction, unlike Spring
 * Data's implicitly-transactional repository methods) -- same technique
 * notification-service's Phase B {@code NotificationResourceTest} uses.
 */
@QuarkusTest
class PaymentResourceTest {

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
        Payment saved = QuarkusTransaction.requiringNew().call(() -> {
            Payment payment = new Payment(testOrderId, 5999L, "CARD-MASTERCARD", PaymentStatus.CAPTURED);
            repository.persist(payment);
            return payment;
        });
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
