package dev.patterncatalyst.payment;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (r06/ch.23 S5, Phase B, DRQ-052) -- renamed
 * from Phase A's {@code PaymentController} to {@code PaymentResource} to match
 * JAX-RS naming convention, exactly mirroring notification-service's/
 * review-service's {@code *Controller} -&gt; {@code *Resource} rename. The
 * Spring MVC annotations ({@code @RestController}/{@code @RequestMapping}/
 * {@code @GetMapping}/{@code @PathVariable}/{@code @RequestParam}) are gone,
 * replaced by Quarkus REST (RESTEasy Reactive) Jakarta REST annotations
 * ({@code @Path}/{@code @GET}) running directly on {@code quarkus-rest-jackson}
 * -- no compatibility shim underneath. The {@code /api/payments} read
 * contract is UNCHANGED: same paths, same {@link PaymentDto} JSON shape, same
 * 404/400 status codes (this is the strangler proxy's future
 * {@code PaymentAclRoute}, S7, route target).
 *
 * <p>Spring's {@code @RequestParam Long orderId} (no default, not optional)
 * threw a framework-level 400 when the parameter was missing. JAX-RS's
 * {@code @QueryParam} has no equivalent "required" concept -- a missing
 * param is simply injected as {@code null} -- so the explicit null check +
 * {@link BadRequestException} below is what reproduces the exact same 400
 * contract (mapped to a JSON body by {@link GlobalExceptionMapper}, same
 * pattern as notification-service's/review-service's exception mapper).
 */
@Path("/api/payments")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentResource {

    private final PaymentService service;

    public PaymentResource(PaymentService service) {
        this.service = service;
    }

    @GET
    @Path("/{id}")
    public PaymentDto getById(@PathParam("id") Long id) {
        return service.getById(id);
    }

    @GET
    public List<PaymentDto> listByOrderId(@QueryParam("orderId") Long orderId) {
        if (orderId == null) {
            throw new BadRequestException("orderId query parameter is required");
        }
        return service.listByOrderId(orderId);
    }
}
