package dev.patterncatalyst.monolith.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReviewCreate(
        @NotNull Long customerId,
        @NotBlank String sku,
        @Min(1) @Max(5) int rating,
        String comment) {
}
