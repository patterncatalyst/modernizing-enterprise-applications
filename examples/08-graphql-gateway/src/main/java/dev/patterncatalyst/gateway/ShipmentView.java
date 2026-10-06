package dev.patterncatalyst.gateway;

import org.eclipse.microprofile.graphql.Description;

/** GraphQL view of a shipment, resolved over REST from shipping-service. */
@Description("A shipment for an order, as reported by shipping-service.")
public record ShipmentView(Long id, ShipmentStatus status, String address, String createdAt) {

    static ShipmentView from(ShipmentDto dto) {
        return new ShipmentView(dto.id(), dto.status(), dto.address(), dto.createdAt());
    }
}
