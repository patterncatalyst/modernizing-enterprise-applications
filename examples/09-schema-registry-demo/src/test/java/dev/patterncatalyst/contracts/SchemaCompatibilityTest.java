package dev.patterncatalyst.contracts;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ch.28 (DRQ-038 follow-up, DRQ-076) — proves Apicurio's BACKWARD
 * compatibility rule against the three evolution fixtures under
 * {@code src/test/resources/avro/}: v1 registers cleanly, v2 (adds
 * {@code giftMessage} WITH a default) is accepted, and v3 (retypes {@code
 * totalCents} {@code long -> string}) is rejected with a 409 {@code
 * RuleViolationException}.
 *
 * <p>Talks to Apicurio's v3 REST API directly via REST Assured (plain HTTP,
 * no SDK) against the SAME registry URL the application's {@code
 * order-avro-out}/{@code order-avro-in} channels use — Quarkus Dev Services
 * for Apicurio Registry (Testcontainers), auto-started for the {@code %test}
 * profile because application.properties deliberately leaves {@code
 * mp.messaging.connector.smallrye-kafka.apicurio.registry.url} unset there.
 * Every request/response shape below (artifact creation, the rules
 * endpoint, the 409 payload on violation) was verified by hand against a
 * live {@code quay.io/apicurio/apicurio-registry:3.1.7} container before
 * being encoded here — not guessed from documentation.
 */
@QuarkusTest
class SchemaCompatibilityTest {

    private static final String ARTIFACT_ID = "order-placed-compat-test";

    @ConfigProperty(name = "mp.messaging.connector.smallrye-kafka.apicurio.registry.url")
    String registryUrl;

    private String baseUrl;

    @BeforeEach
    void deriveBaseUrl() {
        // registryUrl already ends in "/apis/registry/v3" (Dev Services
        // populates the exact same property the app's channels consume —
        // see application.properties); REST Assured wants the base path
        // without a trailing slash duplicated.
        baseUrl = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
    }

    @Test
    void v1RegistersAndV2IsBackwardCompatibleButV3IsRejected() {
        String v1Schema = readFixture("/main-avro/order-placed-v1.avsc");
        String v2Schema = readFixture("/avro/order-placed-v2-backward-compatible.avsc");
        String v3Schema = readFixture("/avro/order-placed-v3-incompatible.avsc");

        // 1. Register v1 as a brand-new artifact.
        given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body("""
                        {
                          "artifactId": "%s",
                          "artifactType": "AVRO",
                          "firstVersion": {
                            "version": "1",
                            "content": { "content": %s, "contentType": "application/json" }
                          }
                        }
                        """.formatted(ARTIFACT_ID, jsonQuote(v1Schema)))
                .when()
                .post("/groups/default/artifacts")
                .then()
                .statusCode(200);

        // 2. Apply the BACKWARD compatibility rule to this artifact.
        given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body("""
                        { "ruleType": "COMPATIBILITY", "config": "BACKWARD" }
                        """)
                .when()
                .post("/groups/default/artifacts/" + ARTIFACT_ID + "/rules")
                .then()
                .statusCode(204);

        // 3. v2 (adds giftMessage WITH a default) MUST be accepted.
        given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body("""
                        { "version": "2", "content": { "content": %s, "contentType": "application/json" } }
                        """.formatted(jsonQuote(v2Schema)))
                .when()
                .post("/groups/default/artifacts/" + ARTIFACT_ID + "/versions")
                .then()
                .statusCode(200)
                .body("version", equalTo("2"));

        // 4. v3 (retypes totalCents long -> string) MUST be rejected: 409 +
        // RuleViolationException, per Apicurio's BACKWARD rule.
        given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body("""
                        { "version": "3", "content": { "content": %s, "contentType": "application/json" } }
                        """.formatted(jsonQuote(v3Schema)))
                .when()
                .post("/groups/default/artifacts/" + ARTIFACT_ID + "/versions")
                .then()
                .statusCode(409)
                .body("name", equalTo("RuleViolationException"));
    }

    /**
     * Reads a fixture either from this module's own
     * {@code src/main/avro/} (v1 -- read directly off disk, since it is a
     * main-source file, not a test resource) or from the test classpath
     * ({@code src/test/resources/avro/}, v2/v3).
     */
    private static String readFixture(String relativeResourcePath) {
        if (relativeResourcePath.startsWith("/main-avro/")) {
            Path path = Path.of("src/main/avro", relativeResourcePath.substring("/main-avro/".length()));
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read " + path, e);
            }
        }
        try (var in = SchemaCompatibilityTest.class.getResourceAsStream(relativeResourcePath)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on classpath: " + relativeResourcePath);
            }
            return new String(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read classpath fixture " + relativeResourcePath, e);
        }
    }

    /** Minimal JSON string-escaping for embedding raw schema text into a JSON request body. */
    private static String jsonQuote(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + '"';
    }
}
