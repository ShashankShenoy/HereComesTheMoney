package com.moneybags.txn.api;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataIntegrityViolationException;

/** Converts expected validation failures into a consistent public envelope. */
@RestControllerAdvice(basePackages="com.moneybags.txn")
@org.springframework.core.annotation.Order(-10)
public class ApiErrors {
    /** Handles a domain error. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> domain(ApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.getMessage(), "at", OffsetDateTime.now()));
    }

    /** Handles bean validation without exposing a Java stack trace. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "INVALID_REQUEST", "message", "Request validation failed", "at", OffsetDateTime.now()));
    }

    /** Converts Oracle unique and check violations without exposing SQL or constraint text. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "DATA_CONFLICT", "message", "Database constraint rejected the request", "at", OffsetDateTime.now()));
    }
}
