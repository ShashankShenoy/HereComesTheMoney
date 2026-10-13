package com.moneybags.serviceclient;

import java.net.URI;

public final class ServiceUrls {
    private ServiceUrls() {}
    public static URI base(String value) {
        URI uri = URI.create(value);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
            throw new IllegalArgumentException("Service URL must be an HTTP(S) origin");
        return uri;
    }
}
