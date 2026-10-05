package dev.patterncatalyst.payment;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REFACTORED to idiomatic Quarkus (r06/ch.23 S5, Phase B) from Phase A's
 * Spring {@code @RestControllerAdvice}-based {@code GlobalExceptionHandler}
 * -- mirrors notification-service's/review-service's {@code
 * GlobalExceptionMapper}: a plain class (no CDI scope, no {@code @Provider}),
 * one {@code @ServerExceptionMapper} method per failure mode, discovered at
 * build time and applied APPLICATION-WIDE because the class sits outside any
 * {@code @Path}-annotated resource.
 *
 * <p>Two failure modes: {@link ResourceNotFoundException} -&gt; 404 (same
 * mapping Phase A had), and the net-new {@link BadRequestException} -&gt; 400
 * (replaces Spring MVC's framework-level 400 on a missing
 * {@code @RequestParam}, which JAX-RS's {@code @QueryParam} has no equivalent
 * for -- see {@link PaymentResource#listByOrderId}).
 */
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException ex) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("NOT_FOUND", ex.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response missingRequiredParameter(BadRequestException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("VALIDATION_FAILED", ex.getMessage()))
                .build();
    }
}
