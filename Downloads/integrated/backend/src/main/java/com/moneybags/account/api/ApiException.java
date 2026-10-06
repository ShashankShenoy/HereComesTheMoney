package com.moneybags.account.api;

import org.springframework.http.HttpStatus;

/** A business rejection with a stable machine-readable code. */
public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    /** Returns the HTTP status associated with the rejection. */
    public HttpStatus status() { return status; }
    /** Returns the stable error code for API consumers. */
    public String code() { return code; }
}
