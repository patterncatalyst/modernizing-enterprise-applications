package dev.patterncatalyst.inventory;

import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REFACTORED to idiomatic Quarkus (r05/ch.19 S6 Phase B, DRQ-029/DRQ-044) --
 * renamed from Phase A's {@code GlobalExceptionHandler}, mirroring
 * review-service's {@code GlobalExceptionHandler} -&gt; {@code
 * GlobalExceptionMapper} rename. The Spring {@code
 * @RestControllerAdvice}/{@code @ExceptionHandler} pair is replaced by
 * Quarkus REST's {@code @ServerExceptionMapper}: a plain class (no CDI scope
 * or {@code @Provider} needed) whose method is discovered at build time and
 * applied APPLICATION-WIDE because the class sits outside any
 * {@code @Path}-annotated resource.
 *
 * <p>Same one mapping, same {@link ApiError} body shape, same status code as
 * Phase A -- the external contract the behavior-equivalence suite checks is
 * byte-for-byte unchanged: {@code ResourceNotFoundException} -&gt; 404. This
 * service's read-only REST surface throws nothing else (Reserve/Release are
 * gRPC-only, S6).
 */
public class GlobalExceptionMapper {

    @ServerExceptionMapper
    public Response notFound(ResourceNotFoundException ex) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiError.of("NOT_FOUND", ex.getMessage()))
                .build();
    }
}
