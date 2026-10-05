package dev.patterncatalyst.notification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Real, end-to-end @QuarkusTest for the ch.17 S4 read surface — no mocking of
 * {@link NotificationService}, unlike review-service's Phase A
 * {@code ReviewControllerTest} (which could mock the service because Review
 * stays in the monolith's shared schema and has nothing of its own to prove).
 * Because this service OWNS ITS OWN SCHEMA (DRQ-035), the thing actually worth
 * proving here is that Quarkus Dev Services spins up an isolated Testcontainers
 * Postgres, this service's OWN Flyway migrations
 * (V1__create_notifications_table.sql + V2__seed_demo_notification.sql) apply
 * cleanly against it, and the lifted Spring-compat read surface serves real
 * rows out of its own {@code notification.notifications} table — the full
 * own-schema contract, not just the HTTP shape.
 */
@QuarkusTest
class NotificationResourceTest {

    @Test
    void listByCustomerId_seededCustomer_returns200WithNotificationDtoShape() {
        given()
                .queryParam("customerId", "1")
                .when().get("/api/notifications")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].customerId", is(1))
                .body("[0].orderId", is(1))
                .body("[0].channel", is("EMAIL"))
                .body("[0].message", is("Order #1 confirmed, total $39.98"))
                .body("[0].sentAt", is("2026-01-07T12:00:15Z"));
    }

    @Test
    void listByCustomerId_unknownCustomer_returns200WithEmptyArray() {
        // Proves the own-schema contract, not just the HTTP shape: customer
        // 999 may well exist in the monolith's SHARED `public.customers`
        // table, but this service never joins into it — it only ever answers
        // from its own `notification.notifications` table, so an unseeded
        // customer id comes back empty rather than erroring or cross-reading.
        given()
                .queryParam("customerId", "999")
                .when().get("/api/notifications")
                .then()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    void listByCustomerId_missingRequiredParam_returns400() {
        given()
                .when().get("/api/notifications")
                .then()
                .statusCode(400);
    }
}
