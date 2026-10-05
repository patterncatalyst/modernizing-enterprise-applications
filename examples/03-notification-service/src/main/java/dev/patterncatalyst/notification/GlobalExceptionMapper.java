package dev.patterncatalyst.notification;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * NET-NEW for ch.17 Phase B — mirrors review-service's
 * {@code GlobalExceptionMapper} (ch.15 Phase B, DRQ-029): a plain class (no
 * CDI scope, no {@code @Provider}) whose {@code @ServerExceptionMapper}
 * method is discovered at build time and applied APPLICATION-WIDE because
 * the class sits outside any {@code @Path}-annotated resource.
 *
 * <p>This read-only service has exactly one validation failure to map: a
 * missing required {@code customerId} query parameter
 * ({@link NotificationResource}), which reproduces the same 400 status
 * Spring MVC returned in Phase A for a missing {@code @RequestParam}.
 */
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response missingRequiredParameter(BadRequestException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("VALIDATION_FAILED", ex.getMessage()))
                .build();
    }
}
