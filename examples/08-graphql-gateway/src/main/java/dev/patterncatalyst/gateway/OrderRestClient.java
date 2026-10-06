package dev.patterncatalyst.gateway;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client to order-service's {@code GET /api/orders/{id}} (unchanged,
 * byte-for-byte, read contract -- see {@code examples/07-order-service}'s
 * {@code OrderResource}). The base URL is supplied via the
 * {@code order-service} config key ({@code quarkus.rest-client.order-service.url}
 * in application.properties) -- a FIXED operator-configured target, never
 * re-pinned at request time (DRQ-069's secure-by-design requirement).
 *
 * <p>The modern reactive {@code quarkus-rest-client} throws a
 * {@code jakarta.ws.rs.WebApplicationException} for any response status
 * &gt;= 400 -- even with a plain {@code OrderDto} return type -- so a 404
 * from order-service (unknown order id) never reaches {@link GatewayApi} as
 * a value to inspect; it surfaces as a thrown exception, which {@link
 * GatewayApi#order} maps onto {@link OrderNotFoundException} (the GraphQL
 * Gateway Contract's GG-b error envelope).
 */
@RegisterRestClient(configKey = "order-service")
@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
public interface OrderRestClient {

    @GET
    @Path("/{id}")
    OrderDto getById(@PathParam("id") String id);
}
