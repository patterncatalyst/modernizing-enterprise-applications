package dev.patterncatalyst.review;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * LIFTED from {@code dev.patterncatalyst.monolith.common.web.GlobalExceptionHandler}
 * (ch.15 Phase A) — scoped to the two exception types Review actually throws.
 * {@code @RestControllerAdvice}/{@code @ExceptionHandler} returning
 * {@code ResponseEntity<T>} is supported by {@code quarkus-spring-web}
 * (limited to one {@code @RestControllerAdvice} per app, which is fine — this
 * is the only one in this service).
 *
 * <p>ONE deliberate, documented adaptation: the monolith's validation handler
 * is keyed on Spring MVC's {@code MethodArgumentNotValidException}, which
 * only exists in Spring's own request-binding pipeline. Under
 * {@code quarkus-spring-web}, a failed {@code @Valid} on a REST method
 * parameter is reported by Hibernate Validator's JAX-RS integration as
 * {@code jakarta.validation.ConstraintViolationException} instead — there is
 * no Spring-compat translation for this (the migration skill's annotation
 * map does not list {@code MethodArgumentNotValidException} under
 * {@code quarkus-spring-web}'s supported exception types). Handling
 * {@code ConstraintViolationException} here instead is the minimal swap that
 * preserves the exact same {@code ApiError} contract
 * ({@code { "error": "VALIDATION_FAILED", ... } }) the behavior-equivalence
 * suite asserts on.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> badRequest(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", ex.getMessage()));
    }
}
