package com.moneybags.treasury.web;

import com.moneybags.treasury.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Produces one predictable problem format for business and validation failures. */
@RestControllerAdvice(basePackages="com.moneybags.treasury")
@org.springframework.core.annotation.Order(-10)
public class ApiExceptionHandler {
    public record ApiProblem(OffsetDateTime timestamp, int status, String code, String message,
                             String path, String correlationId, List<String> violations) {}

    /** Maps deliberate domain failures without leaking implementation details. */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiProblem> domain(DomainException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage(), request, List.of());
    }

    /** Reports all bean-validation failures in one response. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList();
        return problem(400, "VALIDATION_FAILED", "Request validation failed", request, errors);
    }

    /** Converts Oracle uniqueness/check errors to a conflict while retaining details only in server logs. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiProblem> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return problem(409, "DATA_CONFLICT", "The command conflicts with current Treasury state", request, List.of());
    }

    private ResponseEntity<ApiProblem> problem(int status, String code, String message,
                                                HttpServletRequest request, List<String> violations) {
        return ResponseEntity.status(status).body(new ApiProblem(OffsetDateTime.now(), status, code, message,
            request.getRequestURI(), MDC.get("correlationId"), violations));
    }
}
