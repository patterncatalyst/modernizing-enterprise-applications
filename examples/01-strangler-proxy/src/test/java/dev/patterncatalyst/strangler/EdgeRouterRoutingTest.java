package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link StranglerProxyRoute}'s permanent, flagless edge-routing
 * (order-plan.md S10, DRQ-070, HARD PARTS H4/H5): each of the six extracted
 * bounded contexts' path prefixes routes straight to its own service,
 * unconditionally, and an unmatched path gets a direct {@code 404} rather
 * than falling through to a monolith that no longer exists.
 *
 * <p>This replaces the six per-context {@code *RouteFlagOffTest}/
 * {@code *RouteFlagOnTest} pairs this module had before S10 (Review/
 * Notification never got route-level tests of their own — they were proven
 * solely through the Newman behavior-equivalence suite, see CUTOVER.md;
 * Payment/Shipping/Order did). Those tests asserted a flag-off state that
 * literally cannot exist anymore (the {@code strangler.*.enabled} flags and
 * {@code strangler.monolith.base-url} fallback are retired — see {@link
 * StranglerProxyRoute}'s field declarations) and a flag-on state that is now
 * simply "the only state" — so one unconditional test per context replaces
 * each flag-on/flag-off pair.
 */
@QuarkusTest
@TestProfile(EdgeRouterTestProfile.class)
class EdgeRouterRoutingTest {

    @Test
    void reviewPathRoutesToReviewService() {
        given()
            .when().get("/api/reviews?sku=SKU-WIDGET-001")
            .then()
            .statusCode(200)
            .body("backend", is("review"))
            .body("path", is("/api/reviews"));
    }

    @Test
    void notificationPathRoutesToNotificationService() {
        given()
            .when().get("/api/notifications?customerId=1")
            .then()
            .statusCode(200)
            .body("backend", is("notification"))
            .body("path", is("/api/notifications"));
    }

    @Test
    void inventoryPathRoutesToInventoryService() {
        given()
            .when().get("/api/inventory")
            .then()
            .statusCode(200)
            .body("backend", is("inventory"))
            .body("path", is("/api/inventory"));
    }

    @Test
    void paymentPathRoutesToPaymentService() {
        given()
            .queryParam("orderId", 7)
            .when().get("/api/payments")
            .then()
            .statusCode(200)
            .body("backend", is("payment"))
            .body("path", is("/api/payments"));
    }

    @Test
    void shippingPathRoutesToShippingService() {
        given()
            .when().get("/api/shipments/42")
            .then()
            .statusCode(200)
            .body("backend", is("shipping"))
            .body("path", is("/api/shipments/42"));
    }

    @Test
    void orderGetPathRoutesToOrderService() {
        given()
            .when().get("/api/orders/42")
            .then()
            .statusCode(200)
            .body("backend", is("order"))
            .body("path", is("/api/orders/42"));
    }

    @Test
    void orderPostCheckoutRoutesToOrderService() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"customerId\":1,\"shippingAddress\":\"1 Test St\",\"items\":[]}")
            .when().post("/api/orders")
            .then()
            .statusCode(200)
            .body("backend", is("order"))
            .body("path", is("/api/orders"));
    }

    @Test
    void unmatchedPathReturns404DirectlyWithNoMonolithFallback() {
        // order-plan.md S10 (DRQ-070): there is no monolith left to fall
        // through to -- an unmatched /api/** path is answered 404 directly
        // by this route's otherwise() branch, never forwarded anywhere.
        given()
            .when().get("/api/does-not-exist")
            .then()
            .statusCode(404)
            .body("error", is("NOT_FOUND"));
    }
}
