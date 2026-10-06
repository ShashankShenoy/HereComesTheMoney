package com.moneybags.statements;

import org.springframework.http.HttpStatus;

/** Carries a stable error code and an HTTP status across layers. */
public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
