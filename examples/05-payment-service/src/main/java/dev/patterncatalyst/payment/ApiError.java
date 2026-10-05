package dev.patterncatalyst.payment;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * Mirrors the monolith's {@code common.web.ApiError} / the other services'
 * {@code ApiError} byte-for-byte, including {@code @RegisterForReflection}
 * applied from the start (review-service's Phase B MIGRATION.md documents
 * the native-image gotcha this avoids -- a DTO reachable only through a
 * generic exception-mapper return type is invisible to Quarkus's build-time
 * Jackson reflection scan under GraalVM's closed-world native image).
 */
@RegisterForReflection
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
