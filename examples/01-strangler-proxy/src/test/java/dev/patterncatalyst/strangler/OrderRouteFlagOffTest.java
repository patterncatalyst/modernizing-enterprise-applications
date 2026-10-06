package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Proves the Order seam's content-based routing (StranglerProxyRoute,
 * order-plan.md S8) with {@code strangler.order.enabled} at its committed
 * default ({@code false}): requests under the FULL {@code /api/orders} path
 * prefix still reach the monolith, not the order service — including the
 * path-variable GET form and the POST checkout command, which is the exact
 * shape of path the CUTOVER.md paragraph 2 bug (a route-relative prefix
 * silently never matching) would otherwise have let slip through
 * undetected.
 *
 * <p>This is the route-level proof for order-plan S8; it complements,
 * rather than replaces, S9's full-stack equivalence proof through the real
 * podman stack (the final cutover across the seam).
 */
@QuarkusTest
@TestProfile(OrderFlagOffProfile.class)
class OrderRouteFlagOffTest {

    @Test
    void getOrderById_flagOff_routesToMonolith() {
        given()
            .when().get("/api/orders/42")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/orders/42"));
    }

    @Test
    void placeOrder_flagOff_routesToMonolith() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"customerId\":1,\"shippingAddress\":\"1 Test St\",\"items\":[]}")
            .when().post("/api/orders")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/orders"));
    }
}
