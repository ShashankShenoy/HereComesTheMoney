package com.moneybags.account.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Converts validation, domain, and database errors to RFC 7807 responses. */
@RestControllerAdvice(basePackages="com.moneybags.account")
@org.springframework.core.annotation.Order(-10)
public class Errors {
    /** Returns a stable code and description for expected business failures. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> business(ApiException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        p.setTitle(ex.code()); p.setProperty("code", ex.code());
        return ResponseEntity.status(ex.status()).body(p);
    }
    /** Reports malformed or missing request fields without leaking internals. */
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request data");
        p.setTitle("INVALID_REQUEST"); p.setProperty("code", "INVALID_REQUEST");
        return ResponseEntity.badRequest().body(p);
    }
    /** Maps a race with a database uniqueness or check constraint to conflict. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A conflicting account change was recorded");
        p.setTitle("CONFLICT"); p.setProperty("code", "CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
    }
}
