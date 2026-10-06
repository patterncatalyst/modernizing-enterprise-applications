package dev.patterncatalyst.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Lifted byte-for-byte from the monolith's {@code common.OrderCreate} (ch.26
 * S4, DRQ-068/073, Phase A) — the checkout request payload. {@code
 * paymentMethod} is carried on the command exactly as before, even though
 * this service's Phase A never hands it off anywhere yet (the {@code
 * order.placed} outbox write that would carry it is S5 — see {@link
 * OrderService#placeOrder}'s javadoc).
 */
public record OrderCreate(
        @NotNull Long customerId,
        @NotEmpty @Valid List<Line> items,
        @NotBlank String paymentMethod,
        @NotBlank String shippingAddress) {

    public record Line(@NotBlank String sku, @Positive int quantity) {
    }
}
