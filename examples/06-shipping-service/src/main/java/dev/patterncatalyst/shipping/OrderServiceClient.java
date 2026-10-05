package dev.patterncatalyst.shipping;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * r07/ch.24 S5 (DRQ-059 "enrich") -- a MicroProfile REST Client against the
 * order read surface's intended contract: {@code GET /api/orders/{id}}
 * returning a body that includes {@code shippingAddress}. Base URL is
 * {@code quarkus.rest-client."order-service".url}, defaulted from
 * {@code order.service.base-url} (the monolith's real port, :8080) --
 * {@code application.properties}.
 *
 * <p>See {@link OrderReadClient}'s javadoc for why this is coded against the
 * INTENDED contract rather than today's real one, and what is flagged for
 * S6.
 */
@RegisterRestClient(configKey = "order-service")
public interface OrderServiceClient {

    @GET
    @Path("/api/orders/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    OrderSummary getOrder(@PathParam("id") Long id);
}
