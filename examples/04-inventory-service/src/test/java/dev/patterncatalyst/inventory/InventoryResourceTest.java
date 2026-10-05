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
 * r05/ch.19 S5 (Phase A) -&gt; S6 (Phase B, DRQ-029/DRQ-044) -- renamed from
 * {@code InventoryControllerTest} to match the {@code InventoryResource}
 * rename, mirroring review-service's {@code ReviewControllerTest} -&gt;
 * {@code ReviewResourceTest}. Real, end-to-end {@code @QuarkusTest} for the
 * read surface -- no mocking of {@link InventoryService}: the thing worth
 * proving is that Quarkus Dev Services' isolated Testcontainers Postgres,
 * this service's OWN Flyway migration, and the idiomatic Quarkus REST +
 * Panache stack all work together end-to-end, preserving the monolith's
 * exact {@link StockDto} JSON shape and 404 contract -- unchanged across the
 * Phase A -&gt; Phase B refactor.
 *
 * <p>Seeds its OWN test-specific rows via {@link InventoryCdcWriter#upsert}
 * directly -- the same idempotent write path {@link InventoryCdcConsumer}
 * uses in production -- on top of the canonical 3-SKU catalog this service's
 * own {@code V2__seed_inventory.sql} Flyway migration now seeds on every
 * fresh Dev Services container (r05/ch.19 S12, DRQ-044 follow-up: CDC was
 * retired at S11, so this service owns its baseline demo data instead of
 * relying on a backfill from an upstream that no longer writes it). {@code
 * listAll_returns200WithStockDtoArray} therefore asserts against the known
 * TOTAL of 3 migration-seeded rows + 2 test-seeded rows, not just the rows
 * this class adds itself.
 */
@QuarkusTest
class InventoryResourceTest {

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
                // 3 rows from V2__seed_inventory.sql (this service's own
                // Flyway seed, post-CDC-retirement) + 2 rows this test seeds
                // itself. The gRPC test class cleans up every row it adds
                // (see InventoryGrpcServiceTest#cleanup), so this total is
                // deterministic regardless of test execution order.
                .body("$", hasSize(5))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.name", is("Test Widget"))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.priceCents", is(1999))
                .body("find { it.sku == 'SKU-TEST-WIDGET' }.quantityOnHand", is(100))
                // Proves the service's own seed migration actually ran and
                // is visible through the same read surface -- not just that
                // the table isn't empty.
                .body("find { it.sku == 'SKU-WIDGET-001' }.name", is("Standard Widget"))
                .body("find { it.sku == 'SKU-WIDGET-001' }.priceCents", is(1999))
                .body("find { it.sku == 'SKU-WIDGET-001' }.quantityOnHand", is(100));
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
