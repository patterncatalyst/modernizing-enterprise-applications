package dev.patterncatalyst.order;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

/**
 * Lifted byte-for-byte from the monolith's {@code common.web.ApiError} (ch.26
 * S4, DRQ-068/073, Phase A). {@code @RegisterForReflection} is applied from
 * the start, same discipline every other extracted service's Phase A/B
 * {@code ApiError} uses (review-service's Phase B MIGRATION.md documents the
 * native-image gotcha this avoids).
 */
@RegisterForReflection
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
