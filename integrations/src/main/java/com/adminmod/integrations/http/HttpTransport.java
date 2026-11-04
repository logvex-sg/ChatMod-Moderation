package com.adminmod.integrations.http;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * Minimal seam over the JDK HTTP client so delivery logic can be tested without a network and
 * so the transport can be swapped without touching provider code.
 */
public interface HttpTransport {

    HttpResult send(HttpRequest request, Duration timeout) throws IOException, InterruptedException;
}
