package com.finguard.bankserver.common;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleBusinessError(ApiException exception) {
        return error(exception.getStatus(), exception.getCode(), exception.getMessage(), new HttpHeaders());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request values.", new HttpHeaders());
    }

    @ExceptionHandler({TransientDataAccessException.class, TransactionTimedOutException.class})
    ResponseEntity<Object> handleContention(Exception exception) {
        log.warn("Transfer requires retry: {}", exception.getClass().getSimpleName());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return error(HttpStatus.CONFLICT, "REQUEST_RETRY",
                "Retry the same request with the same requestId.", headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpectedError(Exception exception) {
        // Do not log database exception messages: they may contain rejected row values.
        log.error("Unexpected request failure: {}", exception.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.", new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        String message = status.is5xxServerError() ? "An unexpected error occurred." : "Invalid HTTP request.";
        return error(status, code, message, headers);
    }

    private ResponseEntity<Object> error(HttpStatusCode status, String code, String message, HttpHeaders headers) {
        return new ResponseEntity<>(new ApiError(Instant.now(), status.value(), code, message), headers, status);
    }
}
