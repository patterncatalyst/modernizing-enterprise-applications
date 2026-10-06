package dev.patterncatalyst.gateway;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client to shipping-service's {@code GET /api/shipments?orderId=}
 * (unchanged read contract -- see {@code examples/06-shipping-service}'s
 * {@code ShippingResource}). Fixed operator-configured target (see
 * {@code quarkus.rest-client.shipping-service.url}).
 */
@RegisterRestClient(configKey = "shipping-service")
@Path("/api/shipments")
@Produces(MediaType.APPLICATION_JSON)
public interface ShipmentRestClient {

    @GET
    List<ShipmentDto> listByOrderId(@QueryParam("orderId") Long orderId);
}
