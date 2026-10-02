package dev.patterncatalyst.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * LIFTED UNCHANGED from {@code dev.patterncatalyst.monolith.review.ReviewCreate}
 * (ch.15 Phase A). Jakarta Bean Validation annotations work identically under
 * Quarkus's {@code quarkus-hibernate-validator} extension — no code change.
 */
public record ReviewCreate(
        @NotNull Long customerId,
        @NotBlank String sku,
        @Min(1) @Max(5) int rating,
        String comment) {
}
