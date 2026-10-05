package dev.patterncatalyst.notification;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REFACTORED to idiomatic Quarkus (ch.17 Phase B, DRQ-029/DRQ-035) — renamed
 * from Phase A's {@code NotificationController} to {@code NotificationResource}
 * to match JAX-RS naming convention, exactly mirroring review-service's
 * {@code ReviewController} -&gt; {@code ReviewResource} rename. The Spring MVC
 * annotations ({@code @RestController}/{@code @RequestMapping}/
 * {@code @GetMapping}/{@code @RequestParam}) are gone, replaced by Quarkus
 * REST (RESTEasy Reactive) Jakarta REST annotations (`@Path`/`@GET`) running
 * directly on {@code quarkus-rest-jackson} — no compatibility shim
 * underneath.
 *
 * <p>Spring's {@code @RequestParam Long customerId} (no default, not
 * optional) threw a framework-level 400 when the parameter was missing.
 * JAX-RS's {@code @QueryParam} has no equivalent "required" concept — a
 * missing param is simply injected as {@code null} — so the explicit null
 * check + {@link BadRequestException} below is what reproduces the exact
 * same 400 contract (mapped to a JSON body by {@link GlobalExceptionMapper},
 * same as review-service's {@code GlobalExceptionMapper}).
 */
@Path("/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    private final NotificationService service;

    public NotificationResource(NotificationService service) {
        this.service = service;
    }

    @GET
    public List<NotificationDto> listByCustomerId(@QueryParam("customerId") Long customerId) {
        if (customerId == null) {
            throw new BadRequestException("customerId query parameter is required");
        }
        return service.listByCustomerId(customerId);
    }
}
