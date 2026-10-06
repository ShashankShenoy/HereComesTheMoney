package com.moneybags.payments.api;

import org.springframework.http.HttpStatus;

/** A stable error code and HTTP status for API clients. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    /** Constructs an API error without leaking SQL or account details. */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Returns the status that represents this failure. */
    public HttpStatus status() { return status; }

    /** Returns the machine readable failure code. */
    public String code() { return code; }
}

