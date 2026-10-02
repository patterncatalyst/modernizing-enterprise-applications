package dev.patterncatalyst.review;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.common.web.ApiError}
 * (ch.15 Phase A). Uniform error body matching the behavior-equivalence suite's
 * documented error contract ({@code { "error", "message", "timestamp" }}).
 *
 * <p>{@code @RegisterForReflection} ADDED in ch.15 Phase B (DRQ-029) — a real
 * native-image gotcha found while measuring the before/after numbers, not a
 * speculative addition. {@link GlobalExceptionMapper}'s
 * {@code @ServerExceptionMapper} methods return the generic
 * {@code jakarta.ws.rs.core.Response}, not a type parameterized with
 * {@code ApiError}, so Quarkus's build-time Jackson reflection scan (which
 * drives serialization under GraalVM's closed-world native image — no
 * runtime reflection by default) never sees {@code ApiError} as a resource
 * method return type and skips registering it. Under the JVM build this is
 * invisible (Jackson falls back to ordinary runtime reflection), so the
 * 400/404 error-body tests pass in Phase B's {@code @QuarkusTest} suite and
 * in the equivalence gate against the JVM build — the gap only surfaces
 * running the native executable, where an unregistered class under
 * {@code FAIL_ON_EMPTY_BEANS} turns the intended 400 into a 500. Explicit
 * {@code @RegisterForReflection} is the idiomatic fix for exactly this shape
 * (a DTO reachable only through a generic {@code Response}/exception-mapper
 * path).
 */
@RegisterForReflection
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
