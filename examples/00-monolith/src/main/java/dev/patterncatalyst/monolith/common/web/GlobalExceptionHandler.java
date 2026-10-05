package dev.patterncatalyst.monolith.common.web;

import dev.patterncatalyst.monolith.common.exception.InsufficientStockException;
import dev.patterncatalyst.monolith.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One error-mapping surface shared by all six contexts' controllers. */
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

    // r06/ch.23 S9 (DECOMMISSION): the synchronous 402 PAYMENT_DECLINED
    // mapping is gone along with PaymentDeclinedException itself — payment
    // is no longer called in-process from placeOrder, so it can no longer
    // throw synchronously into this handler. A decline is now an eventual
    // GET /api/orders/{id} -> PAYMENT_DECLINED, driven by the choreographed
    // saga's order.OrderSagaListener#onPaymentDeclined reaction (DRQ-047/
    // DRQ-049), not an HTTP error response on the POST.

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> badRequest(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_FAILED", ex.getMessage()));
    }
}
