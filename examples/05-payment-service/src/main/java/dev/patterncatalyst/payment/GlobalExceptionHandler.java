package dev.patterncatalyst.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Phase A (DRQ-052): Spring MVC's {@code @RestControllerAdvice}/
 * {@code @ExceptionHandler}, supported by {@code quarkus-spring-web},
 * mirroring the monolith's shared {@code common.web.GlobalExceptionHandler}
 * -- minimal change, Spring shape preserved. Scoped to this service's one
 * read-surface failure mode: {@link ResourceNotFoundException} -&gt; 404 (the
 * monolith's {@code PAYMENT_REQUIRED}/402 mapping for {@link
 * PaymentDeclinedException} is NOT lifted here since nothing in this
 * service calls {@code charge} yet -- see that exception's javadoc). Phase B
 * (S5) will replace this with the idiomatic {@code @ServerExceptionMapper}
 * pattern used by review-service/notification-service/inventory-service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }
}
