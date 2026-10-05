package dev.patterncatalyst.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * LIFTED from {@code dev.patterncatalyst.monolith.common.web.GlobalExceptionHandler}
 * (r05/ch.19 S5, Phase A, DRQ-044 -- minimal change, Spring-compat
 * {@code @RestControllerAdvice}/{@code @ExceptionHandler} unchanged, not yet
 * refactored to Quarkus REST's {@code @ServerExceptionMapper} -- that's
 * Phase B, S6, mirroring review-service's ch.15 Phase A -&gt; Phase B history).
 * Trimmed to just the one mapping this service's read-only surface can
 * actually throw: {@code ResourceNotFoundException} -&gt; 404. The
 * monolith's {@code InsufficientStockException}/{@code PaymentDeclinedException}/
 * validation mappings don't apply here -- this service exposes no mutating
 * REST endpoints (Reserve/Release are gRPC-only, S6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }
}
