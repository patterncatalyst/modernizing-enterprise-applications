package dev.patterncatalyst.monolith.common.web;

import java.time.Instant;

/** Uniform error body for every REST context; shape the future Newman collection asserts on. */
public record ApiError(String error, String message, Instant timestamp) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, Instant.now());
    }
}
