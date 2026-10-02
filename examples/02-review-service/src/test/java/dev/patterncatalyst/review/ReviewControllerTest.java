package dev.patterncatalyst.review;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MIGRATED from {@code dev.patterncatalyst.monolith.review.ReviewControllerTest}
 * (ch.15 Phase A). The original was a Spring {@code @WebMvcTest} SLICE test
 * (controller only, security filters re-enabled via {@code @Import(SecurityConfig.class)}).
 * Quarkus has no slice-test equivalent (per the migrate-spring-to-quarkus
 * testing module: "No {@code @WebMvcTest} equivalent — use {@code @QuarkusTest}
 * for all test types"), so this boots the full app via {@code @QuarkusTest}
 * and swaps {@code MockMvc} for REST Assured and {@code @MockitoBean} for
 * {@code @InjectMock} — otherwise the same four cases, same assertions,
 * proving the exact 401/201/400 contract the behavior-equivalence suite
 * checks against the real HTTP server (not the servlet-mock layer the
 * monolith's version used).
 */
@QuarkusTest
class ReviewControllerTest {

    @InjectMock
    ReviewService reviewService;

    @Test
    void listBySku_noAuthRequired_returns200() {
        when(reviewService.listBySku("SKU-WIDGET-001")).thenReturn(List.of(
                new ReviewDto(1L, 2L, "SKU-WIDGET-001", 5, "Works great!",
                        Instant.parse("2026-01-08T15:00:00Z"))));

        given()
                .queryParam("sku", "SKU-WIDGET-001")
                .when().get("/api/reviews")
                .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    void createReview_withoutAuthentication_returns401() {
        var command = """
                {"customerId":1,"sku":"SKU-WIDGET-001","rating":3,"comment":"fine"}""";

        given()
                .contentType("application/json")
                .body(command)
                .when().post("/api/reviews")
                .then()
                .statusCode(401);
    }

    @Test
    void createReview_withValidCredentials_returns201() {
        var command = """
                {"customerId":1,"sku":"SKU-WIDGET-001","rating":3,"comment":"fine"}""";
        var dto = new ReviewDto(9L, 1L, "SKU-WIDGET-001", 3, "fine", Instant.now());
        when(reviewService.createReview(any(ReviewCreate.class))).thenReturn(dto);

        given()
                .auth().preemptive().basic("demo-customer", "demo-pass")
                .contentType("application/json")
                .body(command)
                .when().post("/api/reviews")
                .then()
                .statusCode(201)
                .header("Location", "/api/reviews/9")
                .body("sku", is("SKU-WIDGET-001"));
    }

    @Test
    void createReview_withValidCredentialsButInvalidRating_returns400() {
        var invalidCommand = """
                {"customerId":1,"sku":"SKU-WIDGET-001","rating":0,"comment":"fine"}"""; // @Min(1) violated

        given()
                .auth().preemptive().basic("demo-customer", "demo-pass")
                .contentType("application/json")
                .body(invalidCommand)
                .when().post("/api/reviews")
                .then()
                .statusCode(400)
                .body("error", is("VALIDATION_FAILED"));
    }
}
