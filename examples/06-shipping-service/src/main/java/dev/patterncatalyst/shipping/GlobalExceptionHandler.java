package dev.patterncatalyst.shipping;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * r07/ch.24 S4 (DRQ-063, Phase A). Lifted from the monolith's
 * {@code common.web.GlobalExceptionHandler}, scoped down to the single
 * mapping this service's lifted read surface needs ({@link
 * ResourceNotFoundException} -&gt; 404) -- the monolith's other mappings
 * (out-of-stock, validation) belong to other contexts. Spring's
 * {@code @RestControllerAdvice}/{@code @ExceptionHandler} runs via the
 * {@code quarkus-spring-web} compatibility extension, same lift mechanism
 * payment-service's Phase A {@code GlobalExceptionHandler} used (replaced in
 * Phase B, S5, by a plain {@code @ServerExceptionMapper} class). A missing
 * required {@code orderId} query parameter on {@code GET /api/shipments} is
 * NOT custom-handled here -- same as the monolith, it is a framework-level
 * 400 (Spring's default handling of a missing non-optional
 * {@code @RequestParam}), reproduced by {@code quarkus-spring-web}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }
}
