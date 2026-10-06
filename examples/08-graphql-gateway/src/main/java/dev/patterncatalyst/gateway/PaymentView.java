package dev.patterncatalyst.gateway;

import org.eclipse.microprofile.graphql.Description;

/** GraphQL view of a payment, resolved over REST from payment-service. */
@Description("A payment against an order, as reported by payment-service.")
public record PaymentView(Long id, PaymentStatus status, long amountCents, String method, String createdAt) {

    static PaymentView from(PaymentDto dto) {
        return new PaymentView(dto.id(), dto.status(), dto.amountCents(), dto.method(), dto.createdAt());
    }
}
