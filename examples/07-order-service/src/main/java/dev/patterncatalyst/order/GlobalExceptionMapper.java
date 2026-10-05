package dev.patterncatalyst.order;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REFACTORED to idiomatic Quarkus (ch.26 S5, Phase B, DRQ-073) from Phase A's
 * Spring {@code @RestControllerAdvice}-based {@code GlobalExceptionHandler}
 * -- mirrors payment-service's/shipping-service's/review-service's {@code
 * GlobalExceptionMapper}: a plain class (no CDI scope, no {@code @Provider}),
 * one {@code @ServerExceptionMapper} method per failure mode, discovered at
 * build time and applied APPLICATION-WIDE because the class sits outside any
 * {@code @Path}-annotated resource.
 *
 * <p>Same three exception types, same {@link ApiError} body shape, same
 * status codes as Phase A -- the external contract the Order Context
 * Contract suite checks is byte-for-byte unchanged: {@link
 * ResourceNotFoundException} -&gt; 404 {@code NOT_FOUND}; {@link
 * InsufficientStockException} -&gt; 409 {@code OUT_OF_STOCK}; {@link
 * ConstraintViolationException} (thrown by Hibernate Validator's JAX-RS
 * {@code @Valid} integration -- same exception Phase A's compat layer
 * already threw, see {@code GlobalExceptionHandler}'s former javadoc) -&gt;
 * 400 {@code VALIDATION_FAILED}.
 */
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException ex) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("NOT_FOUND", ex.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response outOfStock(InsufficientStockException ex) {
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiError.of("OUT_OF_STOCK", ex.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response validationFailed(ConstraintViolationException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("VALIDATION_FAILED", ex.getMessage()))
                .build();
    }
}
