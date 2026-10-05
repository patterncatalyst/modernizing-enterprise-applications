package dev.patterncatalyst.shipping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * r07/ch.24 S5 (DRQ-059 "enrich") -- the shape THIS SERVICE needs from the
 * order read surface, deliberately NOT a full mirror of the monolith's
 * {@code common.OrderDto} (which also carries {@code customerId}, {@code
 * status}, {@code totalCents}, {@code createdAt}, {@code items}) -- this
 * service only needs the shipping address. {@code @JsonIgnoreProperties
 * (ignoreUnknown = true)} tolerates those extra fields rather than failing
 * deserialization.
 *
 * <p><b>Known gap, flagged for S6 (see {@link OrderReadClient}'s javadoc):
 * </b> as of r07/S5, the monolith's real {@code common.OrderDto} /
 * {@code GET /api/orders/{id}} does NOT expose a {@code shippingAddress}
 * field at all (verified by reading {@code OrderDto.java}/
 * {@code OrderController.java} directly) -- so against the REAL monolith
 * today, {@code shippingAddress} always deserializes as {@code null}.
 * {@link OrderReadClient} is coded against the INTENDED contract (this
 * record) and substitutes a documented fallback when the field is absent;
 * see that class for the full rationale.
 *
 * @param id the order id (unused by this service -- carried for parity with
 *     the intended {@code OrderDto} contract)
 * @param shippingAddress the address to ship to -- the field this saga's
 *     enrich step actually needs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSummary(Long id, String shippingAddress) {
}
