package com.moneybags.treasury.domain;

/** Business error with an API-safe machine code. */
public class DomainException extends RuntimeException {
    private final String code;
    private final int status;

    public DomainException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** Returns a stable code clients may branch on. */
    public String code() { return code; }

    /** Returns the intended HTTP status. */
    public int status() { return status; }

    public static DomainException notFound(String resource, Object id) {
        return new DomainException("NOT_FOUND", resource + " " + id + " was not found", 404);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(code, message, 409);
    }

    public static DomainException invalid(String message) {
        return new DomainException("INVALID_REQUEST", message, 422);
    }
}
