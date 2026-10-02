package dev.patterncatalyst.review;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
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
 * RENAMED from {@code ReviewControllerTest} for ch.15 Phase B (DRQ-029), to
 * match the resource class's new name ({@link ReviewResource}). The test
 * mechanics are unchanged from Phase A — still {@code @QuarkusTest} booting
 * the full app (Quarkus has no slice-test equivalent) with REST Assured and
 * {@code @InjectMock} — because {@link ReviewService} is already an
 * {@code @ApplicationScoped} (normal-scoped, proxyable) CDI bean regardless of
 * which REST layer sits in front of it.
 *
 * <p>The Location header assertion is relaxed from an exact-match to
 * {@code endsWith(...)}: Jakarta REST's {@code Response.created(URI)}
 * resolves a relative location URI against the request's base URI (unlike
 * Spring's {@code ResponseEntity.created(URI)}, which echoed the literal
 * string), so the header value is now an absolute URL ending in the same
 * path. The behavior-equivalence suite only asserts the header is
 * <em>present</em> (see {@code tooling/newman/mea.postman_collection.json},
 * "4d. Authenticated write succeeds"), so this is a test-assertion-strictness
 * adjustment, not a contract change.
 */
@QuarkusTest
class ReviewResourceTest {

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
                .header("Location", endsWith("/api/reviews/9"))
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
