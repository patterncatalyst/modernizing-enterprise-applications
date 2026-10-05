package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Proves the Payment seam's content-based routing (StranglerProxyRoute,
 * payment-plan.md S7) with {@code strangler.payment.enabled} overridden to
 * {@code true} (the payment-plan S8 cutover state): requests under the FULL
 * {@code /api/payments} path prefix — both the list form and the
 * path-variable form — are routed to the payment service, not the monolith.
 * See {@link PaymentRouteFlagOffTest} for the flag-off counterpart and why
 * this is the first route-level test in this module.
 */
@QuarkusTest
@TestProfile(PaymentFlagOnProfile.class)
class PaymentRouteFlagOnTest {

    @Test
    void listPayments_flagOn_routesToPaymentService() {
        given()
            .queryParam("orderId", 7)
            .when().get("/api/payments")
            .then()
            .statusCode(200)
            .body("backend", is("payment"))
            .body("path", is("/api/payments"));
    }

    @Test
    void getPaymentById_flagOn_routesToPaymentService() {
        given()
            .when().get("/api/payments/42")
            .then()
            .statusCode(200)
            .body("backend", is("payment"))
            .body("path", is("/api/payments/42"));
    }
}
