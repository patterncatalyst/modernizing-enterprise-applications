package dev.patterncatalyst.monolith.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Checkout request payload (reuse-map.md section 6: {@code OrderCreate}).
 *
 * <p>{@code paymentMethod} is a deliberately simple demo seam: any value
 * containing {@code DECLINE} (case-insensitive) makes the payment service
 * (ch.23, {@code examples/05-payment-service}) simulate a decline when it
 * reacts to this checkout's {@code order.placed} event, so the
 * behavior-equivalence suite can exercise the payment-decline path
 * deterministically without a real payment gateway. r06/ch.23 S9
 * (DECOMMISSION): the monolith no longer captures payment in-process at
 * all — {@code paymentMethod} is carried on the {@code order.placed} outbox
 * payload purely as a handoff value for the payment service to read.
 */
public record OrderCreate(
        @NotNull Long customerId,
        @NotEmpty @Valid List<Line> items,
        @NotBlank String paymentMethod,
        @NotBlank String shippingAddress) {

    public record Line(@NotBlank String sku, @Positive int quantity) {
    }
}
