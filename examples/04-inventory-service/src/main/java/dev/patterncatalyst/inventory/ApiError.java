package dev.patterncatalyst.inventory;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * LIFTED UNCHANGED from
 * {@code dev.patterncatalyst.monolith.common.web.ApiError} (r05/ch.19 S5,
 * Phase A). Uniform error body matching the behavior-equivalence suite's
 * documented error contract ({@code { "error", "message", "timestamp" }}).
 *
 * <p>{@code @RegisterForReflection} applied from the start in Phase B (S6)
 * -- copied forward from review-service's Phase B native-image finding
 * (MIGRATION.md): {@link GlobalExceptionMapper}'s {@code @ServerExceptionMapper}
 * method returns the generic {@code jakarta.ws.rs.core.Response}, not one
 * parameterized with {@code ApiError}, so Quarkus's build-time Jackson
 * reflection scan never sees this type as a resource-method return type and
 * would otherwise skip registering it for GraalVM's closed-world native
 * image -- invisible on the JVM (Jackson falls back to runtime reflection),
 * only surfacing on an actual native run. Not re-discovered the hard way
 * here; applied proactively.
 */
@RegisterForReflection
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
