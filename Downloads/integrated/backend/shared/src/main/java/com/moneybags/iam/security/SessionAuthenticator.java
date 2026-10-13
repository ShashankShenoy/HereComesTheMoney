package com.moneybags.iam.security;

/** Identity owns session validation. Other services use its authenticated HTTP API. */
public interface SessionAuthenticator {
    UserPrincipal authenticate(String token);
}
