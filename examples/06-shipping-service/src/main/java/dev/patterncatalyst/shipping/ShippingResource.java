package dev.patterncatalyst.shipping;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (r07/ch.24 S5, Phase B, DRQ-063) -- renamed
 * from Phase A's {@code ShippingController} to {@code ShippingResource} to
 * match JAX-RS naming convention, exactly mirroring payment-service's
 * {@code PaymentController} -&gt; {@code PaymentResource} rename. The Spring
 * MVC annotations ({@code @RestController}/{@code @RequestMapping}/
 * {@code @GetMapping}/{@code @PathVariable}/{@code @RequestParam}) are gone,
 * replaced by Quarkus REST (RESTEasy Reactive) Jakarta REST annotations
 * ({@code @Path}/{@code @GET}) running directly on {@code quarkus-rest-jackson}
 * -- no compatibility shim underneath. The {@code /api/shipments} read
 * contract is UNCHANGED, BYTE-FOR-BYTE: same paths, same {@link ShipmentDto}
 * JSON shape, same 404/400 status codes -- the behavior-equivalence suite's
 * Shipping Context Contract (and the future transparent strangler-proxy
 * route, S7) depend on this (DRQ-065, ACL honesty: no translator needed).
 *
 * <p>Spring's {@code @RequestParam Long orderId} (no default, not optional)
 * threw a framework-level 400 when the parameter was missing. JAX-RS's
 * {@code @QueryParam} has no equivalent "required" concept -- a missing
 * param is simply injected as {@code null} -- so the explicit null check +
 * {@link BadRequestException} below is what reproduces the exact same 400
 * contract (mapped to a JSON body by {@link GlobalExceptionMapper}, same
 * pattern payment-service's Phase B used).
 */
@Path("/api/shipments")
@Produces(MediaType.APPLICATION_JSON)
public class ShippingResource {

    private final ShippingService service;

    public ShippingResource(ShippingService service) {
        this.service = service;
    }

    @GET
    @Path("/{id}")
    public ShipmentDto getById(@PathParam("id") Long id) {
        return service.getById(id);
    }

    @GET
    public List<ShipmentDto> listByOrderId(@QueryParam("orderId") Long orderId) {
        if (orderId == null) {
            throw new BadRequestException("orderId query parameter is required");
        }
        return service.listByOrderId(orderId);
    }
}
