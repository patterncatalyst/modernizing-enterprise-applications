package dev.patterncatalyst.strangler;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Proves the Shipping seam's content-based routing (StranglerProxyRoute,
 * shipping-plan.md S7) with {@code strangler.shipping.enabled} at its
 * committed default ({@code false}): requests under the FULL
 * {@code /api/shipments} path prefix still reach the monolith, not the
 * shipping service — including the path-variable form
 * ({@code /api/shipments/{id}}), which is the exact shape of path the
 * CUTOVER.md paragraph 2 bug (a route-relative prefix silently never
 * matching) would otherwise have let slip through undetected.
 *
 * <p>This is the route-level proof for shipping-plan S7; it complements,
 * rather than replaces, S8's full-stack equivalence proof through the real
 * podman stack.
 */
@QuarkusTest
@TestProfile(ShippingFlagOffProfile.class)
class ShippingRouteFlagOffTest {

    @Test
    void listShipments_flagOff_routesToMonolith() {
        given()
            .queryParam("orderId", 7)
            .when().get("/api/shipments")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/shipments"));
    }

    @Test
    void getShipmentById_flagOff_routesToMonolith() {
        given()
            .when().get("/api/shipments/42")
            .then()
            .statusCode(200)
            .body("backend", is("monolith"))
            .body("path", is("/api/shipments/42"));
    }
}
