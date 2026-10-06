package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Proves the Order seam's content-based routing (StranglerProxyRoute,
 * order-plan.md S8) with {@code strangler.order.enabled} overridden to
 * {@code true} (the order-plan S9 cutover state): requests under the FULL
 * {@code /api/orders} path prefix are routed to the order service, not the
 * monolith — BOTH the GET read (path-variable form) and the POST checkout
 * command, since this flag (unlike the five prior ones) moves the whole
 * order context at once. See {@link OrderRouteFlagOffTest} for the
 * flag-off counterpart.
 */
@QuarkusTest
@TestProfile(OrderFlagOnProfile.class)
class OrderRouteFlagOnTest {

    @Test
    void getOrderById_flagOn_routesToOrderService() {
        given()
            .when().get("/api/orders/42")
            .then()
            .statusCode(200)
            .body("backend", is("order"))
            .body("path", is("/api/orders/42"));
    }

    @Test
    void placeOrder_flagOn_routesToOrderService() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"customerId\":1,\"shippingAddress\":\"1 Test St\",\"items\":[]}")
            .when().post("/api/orders")
            .then()
            .statusCode(200)
            .body("backend", is("order"))
            .body("path", is("/api/orders"));
    }
}
