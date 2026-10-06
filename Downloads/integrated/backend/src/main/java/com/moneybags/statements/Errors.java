package com.moneybags.statements;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts expected failures into machine-readable responses without leaking SQL or PII. */
@RestControllerAdvice(basePackages="com.moneybags.statements")
@org.springframework.core.annotation.Order(-10)
public class Errors {
    /** Returns the stable application error associated with a domain failure. */
    @ExceptionHandler(ApiException.class) ResponseEntity<?> domain(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
    }
    /** Reports request validation errors without reflecting submitted values. */
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Invalid request fields"));
    }
    /** Hides Oracle constraint details and asks callers to resolve the conflict. */
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> constraint() {
        return ResponseEntity.status(409).body(Map.of("code", "CONSTRAINT_CONFLICT", "message", "Conflicting record"));
    }
}
