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
 * containing {@code DECLINE} (case-insensitive) makes
 * {@link dev.patterncatalyst.monolith.payment.PaymentService} simulate a
 * decline, so the future behavior-equivalence suite can exercise the
 * payment-decline path deterministically without a real payment gateway.
 */
public record OrderCreate(
        @NotNull Long customerId,
        @NotEmpty @Valid List<Line> items,
        @NotBlank String paymentMethod,
        @NotBlank String shippingAddress) {

    public record Line(@NotBlank String sku, @Positive int quantity) {
    }
}
