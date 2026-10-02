package dev.patterncatalyst.review;

import java.time.Instant;

/**
 * LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.common.web.ApiError}
 * (ch.15 Phase A). Uniform error body matching the behavior-equivalence suite's
 * documented error contract ({@code { "error", "message", "timestamp" }}).
 */
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
