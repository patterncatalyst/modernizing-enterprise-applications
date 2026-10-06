package dev.patterncatalyst.gateway;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client to review-service's {@code GET /api/reviews?sku=} (unchanged
 * read contract -- see {@code examples/02-review-service}'s {@code
 * ReviewResource}). Fixed operator-configured target (see
 * {@code quarkus.rest-client.review-service.url}). Reviews are resolved
 * per order ITEM (by sku), not per order -- each line item carries its own
 * nested {@code reviews} field (see {@link GatewayApi#reviews}).
 */
@RegisterRestClient(configKey = "review-service")
@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
public interface ReviewRestClient {

    @GET
    List<ReviewDto> listBySku(@QueryParam("sku") String sku);
}
