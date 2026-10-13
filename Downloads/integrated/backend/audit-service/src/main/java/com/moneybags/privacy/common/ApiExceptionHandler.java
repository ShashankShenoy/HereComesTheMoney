package com.moneybags.privacy.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.OffsetDateTime;

/** Emits RFC 9457 problem responses without leaking database or security details. */
@RestControllerAdvice(basePackages="com.moneybags.privacy")
@org.springframework.core.annotation.Order(-10)
public class ApiExceptionHandler {

    /** Converts expected business failures into stable API responses. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setType(URI.create("urn:moneybags:privacy:error:" + exception.code()));
        problem.setTitle(exception.code());
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("path", request.getRequestURI());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    /** Reports validation errors as a safe client error. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                exception.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b).orElse("Request validation failed"));
        problem.setTitle("VALIDATION_FAILED");
        return ResponseEntity.badRequest().body(problem);
    }

    /** Converts an idempotency or unique-key race to a retryable conflict. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateKeyException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A record with this key already exists; read the existing resource before retrying.");
        problem.setTitle("DUPLICATE_KEY");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** Hides SQL and connection details while asking callers to defer sensitive actions. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDatabase(DataAccessException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Privacy persistence is temporarily unavailable; retry or defer the operation.");
        problem.setTitle("PRIVACY_STORE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
