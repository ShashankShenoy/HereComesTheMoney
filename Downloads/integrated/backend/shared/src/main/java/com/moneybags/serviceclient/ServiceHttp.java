package com.moneybags.serviceclient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Blocking, bounded HTTP transport also works on Windows hosts without Unix-domain sockets. */
final class ServiceHttp {
    record Response(int status, String body) {}
    private ServiceHttp() {}
    static Response call(URI endpoint, String method, Map<String,String> headers, String body, int readTimeoutMillis) throws IOException {
        var connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        headers.forEach(connection::setRequestProperty);
        try {
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (var stream = connection.getOutputStream()) { stream.write(bytes); }
            }
            int status = connection.getResponseCode();
            var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return new Response(status, "");
            try (input) { return new Response(status, new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
}
