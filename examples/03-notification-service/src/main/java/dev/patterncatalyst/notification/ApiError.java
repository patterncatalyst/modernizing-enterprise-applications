package dev.patterncatalyst.notification;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * NET-NEW for ch.17 Phase B — mirrors review-service's {@code ApiError}
 * (ch.15 Phase B, DRQ-029) byte-for-byte, including the
 * {@code @RegisterForReflection} annotation. review-service's MIGRATION.md
 * documents the native-image gotcha that annotation fixes (a DTO reachable
 * only through a generic {@code Response}/exception-mapper return type is
 * invisible to Quarkus's build-time Jackson reflection scan under GraalVM's
 * closed-world native image). That lesson is applied here from the start
 * rather than re-discovered.
 */
@RegisterForReflection
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
