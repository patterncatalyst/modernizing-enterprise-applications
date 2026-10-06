package dev.patterncatalyst.monolith.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-contained smoke test (DoD for S4, r02-plan): boots the full Spring
 * context against a throwaway, Testcontainers-provisioned Postgres (no
 * external podman-stack required).
 *
 * <p>Originally proved all six contexts responded from one running monolith.
 * Each bounded context has since been extracted to its own Quarkus service
 * and decommissioned from this module, one at a time: Review (r02/S10),
 * Notification (r04/S8), Inventory (r05/ch.19 S11), Payment (r06/ch.23 S9),
 * Shipping (r07/ch.24 S9), and finally Order itself — the god {@code
 * OrderService}/{@code OrderSagaListener} hub, SMELL #2, the LAST context —
 * in **order-plan.md S10 (DRQ-070), the single, deliberately irreversible
 * decommission for the whole system**.
 *
 * <p><b>This module now owns ZERO bounded contexts.</b> There are no REST
 * controllers, no JPA entities with live writers, no Kafka producers/
 * consumers, and no gRPC clients left anywhere in {@code src/main}. This test
 * is reduced accordingly: it boots the full Spring context (proving the
 * frozen shell still assembles and starts cleanly — a real regression signal
 * if anything were left dangling after six rounds of deletions) and asserts
 * every former bounded-context path now **404s**. Each extracted context's
 * real, positive behavior is proven by the extracted service's own test
 * suite plus the project's contract/acceptance suite
 * (`tooling/newman/mea.postman_collection.json`) run against it through the
 * strangler proxy, now a permanent REST edge router
 * (`examples/01-strangler-proxy`) — never against this frozen monolith again.
 *
 * <p>Kept permanently in-repo (DRQ-024/DRQ-070) as the "before" + golden-
 * baseline referent; this test is this module's own proof that "before" is
 * still a thing that builds and boots, not dead weight. The complete,
 * runnable "before" (all six contexts live) remains checked out on the
 * {@code reference/monolith-before} branch (tag {@code v0-monolith}) for a
 * genuine side-by-side comparison; {@code stage/NN-*-extracted} tags step
 * through each extraction in order.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SixContextsSmokeTest { // name kept for history; zero contexts remain, six decommission checks

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("monolith")
            .withUsername("monolith")
            .withPassword("monolith");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    // No dedicated "the context boots" test: every @Test method below
    // requires a live Spring context to even receive an HTTP response (a 404
    // from the embedded server, not a connection failure) -- Flyway migrating
    // V1-V4 unchanged against a throwaway Postgres, including the now-frozen
    // orders/order_items/customers tables (see SMELLS.md), is therefore
    // exercised by all six methods below, not a separate assertion. If any
    // deleted class's dependents were missed, the context would fail to
    // start and every method here would fail with a startup exception rather
    // than a clean 404 -- the regression signal this test exists to catch.

    @Test
    void orderIsNoLongerServedByTheMonolith() {
        // order-plan.md S10 decommission (DRQ-070), THE LAST of the six: the
        // god OrderService/OrderController/Order/OrderItem/OrderRepository/
        // OrderSagaListener hub (SMELL #2), the common/* vocabulary, the
        // common/outbox/* relay, and inventory/RemoteInventoryClient were all
        // removed from the monolith once examples/07-order-service became the
        // sole owner of the order context, reached through the strangler
        // proxy's edge-router routing (strangler.order.enabled's permanent
        // cutover). The monolith itself now 404s here -- proof the extraction
        // was clean and nothing else depended on this package. (The
        // underlying `orders`/`order_items`/`customers` tables are still
        // present in the shared schema, write-only history now; see
        // SMELLS.md #1.)
        ResponseEntity<Object> response = rest.getForEntity("/api/orders", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void inventoryIsNoLongerServedByTheMonolith() {
        // r05/ch.19 S11 decommission: Inventory's controller/service/
        // repository/entity were removed from the monolith once checkout's
        // reserve/release/read path became exclusively gRPC against
        // examples/04-inventory-service. The monolith itself now 404s here.
        // (The underlying `inventory_items` table is still present in the
        // shared schema, write-only history now; see SMELLS.md #5.)
        ResponseEntity<Object> response = rest.getForEntity("/api/inventory", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void paymentIsNoLongerServedByTheMonolith() {
        // r06/ch.23 S9 decommission: Payment's controller/service/
        // repository/entity were removed from the monolith once the
        // choreographed saga against examples/05-payment-service became the
        // only path. The monolith itself now 404s here. (The underlying
        // `payments` table is still present in the shared schema, write-only
        // history now; see SMELLS.md #1/#3.)
        ResponseEntity<Object> response = rest.getForEntity("/api/payments?orderId=1", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void shippingIsNoLongerServedByTheMonolith() {
        // r07/ch.24 S9 decommission: Shipping's controller/service/
        // repository/entity were removed from the monolith once the
        // orchestrated saga against examples/06-shipping-service became the
        // only path. The monolith itself now 404s here. (The underlying
        // `shipments` table is still present in the shared schema, write-only
        // history now; see SMELLS.md #1/#3.)
        ResponseEntity<Object> response = rest.getForEntity("/api/shipments?orderId=1", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void notificationIsNoLongerServedByTheMonolith() {
        // r04/S8 decommission: Notification's controller/service/repository
        // and its read model (common.NotificationDto) were removed from the
        // monolith once the strangler proxy's cutover to
        // examples/03-notification-service became the permanent default. The
        // monolith itself now 404s here. (The underlying `notifications`
        // table is still present in the shared schema, write-only history
        // now; see SMELLS.md #4.)
        ResponseEntity<Object> response = rest.getForEntity("/api/notifications?customerId=1", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reviewIsNoLongerServedByTheMonolith() {
        // r02/S10 decommission: Review's controller/service/repository/entity
        // were removed from the monolith once the strangler proxy's cutover
        // to examples/02-review-service became the permanent default. The
        // monolith itself now 404s here.
        ResponseEntity<Object> response = rest.getForEntity("/api/reviews?sku=SKU-WIDGET-001", Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
