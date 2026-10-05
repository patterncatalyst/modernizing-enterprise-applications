package dev.patterncatalyst.order;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lifted from the monolith's {@code common.web.GlobalExceptionHandler} (ch.26
 * S4, DRQ-068/073, Phase A) via quarkus-spring-web's {@code
 * @RestControllerAdvice}/{@code @ExceptionHandler} support — one error-mapping
 * surface for this service's {@code /api/orders} controller.
 *
 * <p><b>Divergence note (compat-layer behavior gap, same spirit as payment-
 * service's/shipping-service's documented Phase A gaps):</b> the monolith's
 * handler also mapped Spring's {@code MethodArgumentNotValidException} to 400
 * — that class does not even exist on quarkus-spring-web's classpath (the
 * compat extension ships the annotation-processing shim, not Spring MVC's
 * full exception hierarchy). quarkus-spring-web's {@code @Valid} processing
 * on a {@code @RequestBody} parameter is backed directly by Hibernate
 * Validator instead, so a failed {@link OrderCreate} constraint (e.g. an
 * empty {@code items} list) throws {@link ConstraintViolationException},
 * mapped to 400 by {@link #validationFailed} below — the handler that
 * actually fires under this compat layer.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> outOfStock(InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("OUT_OF_STOCK", ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> validationFailed(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", ex.getMessage()));
    }
}
