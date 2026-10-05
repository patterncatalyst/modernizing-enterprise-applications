package dev.patterncatalyst.inventory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * r05/ch.19 S5 (Phase A, DRQ-044). Real, end-to-end {@code @QuarkusTest} for
 * the lifted read surface -- no mocking of {@link InventoryService}, mirrors
 * notification-service's {@code NotificationResourceTest}: the thing worth
 * proving is that Quarkus Dev Services' isolated Testcontainers Postgres,
 * this service's OWN Flyway migration, and the Spring-compat
 * controller/service/repository stack all work together end-to-end,
 * preserving the monolith's exact {@link StockDto} JSON shape and 404
 * contract.
 *
 * <p>Seeds via {@link InventoryCdcWriter#upsert} directly -- the same
 * idempotent write path {@link InventoryCdcConsumer} uses in production --
 * rather than a Flyway seed migration, since this service owns no demo data
 * of its own (its data always originates from the monolith via CDC).
 */
@QuarkusTest
class InventoryControllerTest {

    @Inject
    InventoryCdcWriter writer;

    @BeforeEach
    void seed() {
        writer.upsert(9001L, "SKU-TEST-WIDGET", "Test Widget", 1999L, 100, Instant.now());
        writer.upsert(9002L, "SKU-TEST-GADGET", "Test Gadget", 4999L, 50, Instant.now());
    }

    @Test
    void listAll_returns200WithStockDtoArray() {
        given()
                .when().get("/api/inventory")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.name", is("Test Widget"))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.priceCents", is(1999))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.quantityOnHand", is(100));
    }

    @Test
    void getBySku_existingSku_returns200WithStockDtoShape() {
        given()
                .when().get("/api/inventory/SKU-TEST-GADGET")
                .then()
                .statusCode(200)
                .body("sku", is("SKU-TEST-GADGET"))
                .body("name", is("Test Gadget"))
                .body("priceCents", is(4999))
                .body("quantityOnHand", is(50));
    }

    @Test
    void getBySku_unknownSku_returns404WithApiErrorShape() {
        given()
                .when().get("/api/inventory/SKU-DOES-NOT-EXIST")
                .then()
                .statusCode(404)
                .body("error", is("NOT_FOUND"))
                .body("message", is("No inventory item with sku SKU-DOES-NOT-EXIST"));
    }
}
