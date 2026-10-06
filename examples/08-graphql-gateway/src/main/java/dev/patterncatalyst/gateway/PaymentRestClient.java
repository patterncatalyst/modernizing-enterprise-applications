package dev.patterncatalyst.gateway;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client to payment-service's {@code GET /api/payments?orderId=}
 * (unchanged read contract -- see {@code examples/05-payment-service}'s
 * {@code PaymentResource}). Fixed operator-configured target (see
 * {@code quarkus.rest-client.payment-service.url}).
 */
@RegisterRestClient(configKey = "payment-service")
@Path("/api/payments")
@Produces(MediaType.APPLICATION_JSON)
public interface PaymentRestClient {

    @GET
    List<PaymentDto> listByOrderId(@QueryParam("orderId") Long orderId);
}
