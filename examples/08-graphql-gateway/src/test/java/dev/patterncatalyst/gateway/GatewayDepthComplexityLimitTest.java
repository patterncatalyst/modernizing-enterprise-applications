package dev.patterncatalyst.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * Proves the DRQ-069 secure-by-design bound is actually wired and active:
 * under {@link StrictGraphQLLimitsProfile}'s deliberately tiny depth/
 * complexity budget (1/1), even an ordinary, shallow aggregation query is
 * rejected at validation time -- BEFORE any resolver runs (no downstream
 * REST/gRPC call is made; nothing is mocked here). This is a distinct
 * concern from {@link GatewayApiTest}, which proves the real,
 * production-sized bounds (depth 8 / complexity 200 in
 * application.properties) do NOT reject the actual GG-a shape.
 */
@QuarkusTest
@TestProfile(StrictGraphQLLimitsProfile.class)
class GatewayDepthComplexityLimitTest {

    @Test
    void overBudgetQueryIsRejectedBeforeAnyResolverRuns() {
        String query = "{ order(id: \"42\") { id status } }";
        String body = "{ \"query\": \"" + query.replace("\"", "\\\"") + "\" }";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data", nullValue())
                .body("errors", hasSize(greaterThanOrEqualTo(1)))
                .body("errors[0].message", not(emptyOrNullString()));
    }
}
