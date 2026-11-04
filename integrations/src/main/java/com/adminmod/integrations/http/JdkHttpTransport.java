package com.adminmod.integrations.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * HTTP transport backed by {@link HttpClient}. A single shared client is reused because it
 * owns the connection pool and its own executor; creating one per request would leak threads.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public HttpResult send(HttpRequest request, Duration timeout) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(
                response.statusCode(),
                HttpResult.truncate(response.body()),
                retryAfterMillis(response).orElse(0L));
    }

    private static Optional<Long> retryAfterMillis(HttpResponse<?> response) {
        // Discord returns the delay as a decimal number of seconds in Retry-After.
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                double seconds = Double.parseDouble(header.get().trim());
                return Optional.of((long) (seconds * 1000.0));
            } catch (NumberFormatException ignored) {
                // Fall through to the JSON body probe below.
            }
        }
        String body = response.body() instanceof String text ? text : "";
        String lower = body.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("\"retry_after\"");
        if (index >= 0) {
            int colon = lower.indexOf(':', index);
            if (colon > 0) {
                String tail = body.substring(colon + 1).trim();
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < tail.length(); i++) {
                    char c = tail.charAt(i);
                    if (Character.isDigit(c) || c == '.') {
                        digits.append(c);
                    } else if (digits.length() > 0) {
                        break;
                    }
                }
                if (digits.length() > 0) {
                    try {
                        return Optional.of((long) (Double.parseDouble(digits.toString()) * 1000.0));
                    } catch (NumberFormatException ignored) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }
}
