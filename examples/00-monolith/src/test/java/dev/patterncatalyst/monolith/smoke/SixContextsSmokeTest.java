package dev.patterncatalyst.monolith.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import dev.patterncatalyst.monolith.common.OrderCreate;
import dev.patterncatalyst.monolith.common.OrderDto;
import dev.patterncatalyst.monolith.common.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-contained smoke test (DoD for S4): boots the full Spring context against a
 * throwaway, Testcontainers-provisioned Postgres (no external podman-stack
 * required) and hits at least one REST endpoint per bounded context, proving all
 * six contexts respond from one running monolith.
 *
 * <p>This is intentionally a thin smoke test, not the full suite — JUnit
 * unit/integration coverage per service and the Newman behavior-equivalence suite
 * are built in the next steps (S5, S6).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SixContextsSmokeTest {

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

    @Test
    void orderContextResponds() {
        ResponseEntity<OrderDto[]> response = rest.getForEntity("/api/orders", OrderDto[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty(); // seed order #1
    }

    @Test
    void inventoryContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/inventory", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3); // three seeded products
    }

    @Test
    void paymentContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/payments?orderId=1", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // seed order #1's payment
    }

    @Test
    void shippingContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/shipments?orderId=1", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // seed order #1's shipment
    }

    @Test
    void notificationContextResponds() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/notifications?customerId=1", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1); // seed order #1's confirmation
    }

    @Test
    void reviewContextRespondsAndEnforcesSharedSecurity() {
        ResponseEntity<Object[]> reads = rest.getForEntity("/api/reviews?sku=SKU-WIDGET-001", Object[].class);
        assertThat(reads.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reads.getBody()).hasSize(1); // Grace's seeded review

        // SMELL[ch.15]: the write path is authenticated via the monolith's ONE
        // shared SecurityConfig, even though review has no other dependency on it.
        var unauthenticatedBody = new dev.patterncatalyst.monolith.review.ReviewCreate(
                1L, "SKU-WIDGET-001", 3, "fine");
        ResponseEntity<Object> unauthenticated = rest.postForEntity("/api/reviews", unauthenticatedBody, Object.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Object> authenticated = rest
                .withBasicAuth("demo-customer", "demo-pass")
                .postForEntity("/api/reviews", unauthenticatedBody, Object.class);
        assertThat(authenticated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void checkoutFlowSpansAllFiveNonReviewContextsInOneTransaction() {
        var command = new OrderCreate(
                2L,
                List.of(new OrderCreate.Line("SKU-GADGET-002", 1)),
                "CARD-VISA",
                "42 Analytical Avenue");
        ResponseEntity<OrderDto> response = rest.postForEntity("/api/orders", command, OrderDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.getBody().totalCents()).isEqualTo(4999L);
    }
}
