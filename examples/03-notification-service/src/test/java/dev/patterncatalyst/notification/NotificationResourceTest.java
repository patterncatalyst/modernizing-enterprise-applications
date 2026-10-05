package dev.patterncatalyst.notification;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * UPDATED for ch.17 Phase B. Real, end-to-end {@code @QuarkusTest} for the
 * read surface — no mocking of {@link NotificationService}, same as Phase A,
 * because this service OWNS ITS OWN SCHEMA (DRQ-035) and the thing worth
 * proving is that Quarkus Dev Services' isolated Testcontainers Postgres,
 * this service's OWN Flyway migrations, and the idiomatic Quarkus REST +
 * Panache read surface all work together end-to-end.
 *
 * <p>Phase A seeded its happy-path row via a Flyway migration
 * (V2__seed_demo_notification.sql at customer/order id 1). Phase B's
 * V3__idempotent_order_id.sql DELETES that row — it would otherwise collide
 * with a real checkout against the repo's demo customer/order id 1 during
 * the end-to-end verification and defeat the consumer's dedupe-by-order-id
 * idempotency check (see that migration's comment). This test seeds its own
 * row instead, committed via {@link QuarkusTransaction#requiringNew()} in a
 * NEW, immediately-committed transaction — not {@code @TestTransaction},
 * which rolls back at the end of the test method and would never be visible
 * to the separate HTTP round-trip REST Assured makes against this same Dev
 * Services database.
 */
@QuarkusTest
class NotificationResourceTest {

    @Inject
    NotificationRepository repository;

    @Test
    void listByCustomerId_existingCustomer_returns200WithNotificationDtoShape() {
        QuarkusTransaction.requiringNew().run(() -> repository.persist(
                new Notification(501L, 501L, "EMAIL", "Order #501 confirmed, total $39.98")));

        given()
                .queryParam("customerId", "501")
                .when().get("/api/notifications")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].customerId", is(501))
                .body("[0].orderId", is(501))
                .body("[0].channel", is("EMAIL"))
                .body("[0].message", is("Order #501 confirmed, total $39.98"));
    }

    @Test
    void listByCustomerId_unknownCustomer_returns200WithEmptyArray() {
        // Proves the own-schema contract, not just the HTTP shape: customer
        // 999999 may well exist in the monolith's SHARED `public.customers`
        // table, but this service never joins into it — it only ever answers
        // from its own `notification.notifications` table, so an unseeded
        // customer id comes back empty rather than erroring or cross-reading.
        given()
                .queryParam("customerId", "999999")
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
