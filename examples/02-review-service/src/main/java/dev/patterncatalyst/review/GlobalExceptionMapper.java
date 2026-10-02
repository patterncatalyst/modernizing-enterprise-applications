package dev.patterncatalyst.review;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REFACTORED to idiomatic Quarkus (ch.15 Phase B, DRQ-029) — renamed from
 * Phase A's {@code GlobalExceptionHandler}. The Spring
 * {@code @RestControllerAdvice}/{@code @ExceptionHandler} pair is replaced by
 * Quarkus REST's {@code @ServerExceptionMapper}: a plain class (no CDI scope
 * or {@code @Provider} needed) whose methods are discovered at build time and
 * applied APPLICATION-WIDE because the class sits outside any
 * {@code @Path}-annotated resource (per the Quarkus REST docs: exception
 * mappers declared inside a resource class only apply to that class; declare
 * them in a separate class for a global mapper).
 *
 * <p>Same two exception types, same {@link ApiError} body shape, same status
 * codes as Phase A — the external contract the behavior-equivalence suite
 * checks is byte-for-byte unchanged: {@code ResourceNotFoundException} → 404,
 * {@code ConstraintViolationException} (thrown by Hibernate Validator's
 * JAX-RS {@code @Valid} integration, not Spring MVC's
 * {@code MethodArgumentNotValidException}, which never applied here even in
 * Phase A) → 400 with {@code { "error": "VALIDATION_FAILED", ... } }.
 */
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException ex) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("NOT_FOUND", ex.getMessage()))
                .build();
    }

    @ServerExceptionMapper
    public Response validationFailed(ConstraintViolationException ex) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiError.of("VALIDATION_FAILED", ex.getMessage()))
                .build();
    }
}
