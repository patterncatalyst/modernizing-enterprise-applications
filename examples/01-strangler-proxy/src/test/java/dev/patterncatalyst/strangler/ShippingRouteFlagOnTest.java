package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Proves the Shipping seam's content-based routing (StranglerProxyRoute,
 * shipping-plan.md S7) with {@code strangler.shipping.enabled} overridden to
 * {@code true} (the shipping-plan S8 cutover state): requests under the FULL
 * {@code /api/shipments} path prefix — both the list form and the
 * path-variable form — are routed to the shipping service, not the monolith.
 * See {@link ShippingRouteFlagOffTest} for the flag-off counterpart.
 */
@QuarkusTest
@TestProfile(ShippingFlagOnProfile.class)
class ShippingRouteFlagOnTest {

    @Test
    void listShipments_flagOn_routesToShippingService() {
        given()
            .queryParam("orderId", 7)
            .when().get("/api/shipments")
            .then()
            .statusCode(200)
            .body("backend", is("shipping"))
            .body("path", is("/api/shipments"));
    }

    @Test
    void getShipmentById_flagOn_routesToShippingService() {
        given()
            .when().get("/api/shipments/42")
            .then()
            .statusCode(200)
            .body("backend", is("shipping"))
            .body("path", is("/api/shipments/42"));
    }
}
