package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Proves the Payment seam's content-based routing (StranglerProxyRoute,
 * payment-plan.md S7) with {@code strangler.payment.enabled} at its
 * committed default ({@code false}): requests under the FULL
 * {@code /api/payments} path prefix still reach the monolith, not the
 * payment service — including the path-variable form
 * ({@code /api/payments/{id}}), which is the exact shape of path the
 * CUTOVER.md paragraph 2 bug (a route-relative prefix silently never
 * matching) would otherwise have let slip through undetected.
 *
 * <p>There is no pre-existing Camel route test in this module to extend —
 * the Review/Notification/Inventory seams were proven solely through the
 * Newman behavior-equivalence suite against the real stack (see
 * CUTOVER.md). This is the first route-level test added to this module,
 * for payment-plan S7; it complements, rather than replaces, S8's
 * full-stack equivalence proof.
 */
@QuarkusTest
@TestProfile(PaymentFlagOffProfile.class)
class PaymentRouteFlagOffTest {

    @Test
    void listPayments_flagOff_routesToMonolith() {
        given()
            .queryParam("orderId", 7)
            .when().get("/api/payments")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/payments"));
    }

    @Test
    void getPaymentById_flagOff_routesToMonolith() {
        given()
            .when().get("/api/payments/42")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/payments/42"));
    }
}
