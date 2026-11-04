package com.adminmod.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.adminmod.integrations.http.JdkHttpTransport;
import com.adminmod.integrations.http.RetryPolicy;
import com.adminmod.integrations.webhook.GenericWebhookProvider;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the dispatcher against a real loopback HTTP server rather than a stub transport, so the
 * whole path — template rendering, the JDK client, retry/backoff and rate-limit handling — is
 * exercised with actual sockets.
 */
class EndToEndDeliveryTest {

    private HttpServer server;
    private String baseUrl;

    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuth = new CopyOnWriteArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile int failFirstN;
    private volatile int rateLimitFirstN;
    private volatile int successStatus = 204;

    @BeforeEach
    void startServer() throws Exception {
        receivedBodies.clear();
        receivedAuth.clear();
        attempts.set(0);
        failFirstN = 0;
        rateLimitFirstN = 0;
        successStatus = 204;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            int attempt = attempts.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                receivedAuth.add(auth);
            }
            int status;
            if (attempt <= failFirstN) {
                status = 500;
            } else if (attempt <= rateLimitFirstN) {
                status = 429;
                exchange.getResponseHeaders().add("Retry-After", "0.01");
            } else {
                status = successStatus;
            }
            byte[] out = ("{\"attempt\":" + attempt + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ChatEvent event(String message) {
        return new ChatEvent("Alex", "uuid-alex", message, 1_700_000_000_000L, "Test Server");
    }

    private GenericWebhookProvider provider(String template, Map<String, String> headers, String authHeader,
            String authToken) {
        return new GenericWebhookProvider(new JdkHttpTransport(), baseUrl, "POST", template,
                authHeader, authToken, headers, "application/json");
    }

    @Test
    void aPublicChatMessageReachesTheEndpoint() {
        GenericWebhookProvider provider = provider("{\"player\":\"%player%\",\"message\":\"%message%\"}",
                Map.of(), "", "");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 3,
                Duration.ofSeconds(5), RetryPolicy.defaults())) {
            dispatcher.enqueue(event("hello world"));
            waitUntil(() -> dispatcher.deliveredCount() == 1);
            assertEquals(1, dispatcher.deliveredCount());
            assertEquals(0, dispatcher.failedCount());
        }
        assertEquals(1, receivedBodies.size());
        assertTrue(receivedBodies.get(0).contains("\"player\":\"Alex\""));
        assertTrue(receivedBodies.get(0).contains("\"message\":\"hello world\""));
    }

    @Test
    void configuredHeadersAndAuthTokenAreSent() {
        GenericWebhookProvider provider = provider("{\"message\":\"%message%\"}",
                Map.of("X-Server", "survival-1"), "Authorization", "Bearer secret-token");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 3,
                Duration.ofSeconds(5), RetryPolicy.defaults())) {
            dispatcher.enqueue(event("hi"));
            waitUntil(() -> dispatcher.deliveredCount() == 1);
        }
        assertTrue(receivedAuth.contains("Bearer secret-token"));
    }

    @Test
    void aServerErrorIsRetriedAndEventuallySucceeds() {
        failFirstN = 2;
        GenericWebhookProvider provider = provider("{\"message\":\"%message%\"}", Map.of(), "", "");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 5,
                Duration.ofSeconds(5), new RetryPolicy(5L, 50L))) {
            dispatcher.enqueue(event("retry me"));
            waitUntil(() -> dispatcher.deliveredCount() == 1);
            assertEquals(1, dispatcher.deliveredCount());
            assertEquals(3, attempts.get(), "two 500s then a success");
            assertTrue(dispatcher.retryCount() >= 2);
        }
    }

    @Test
    void aRateLimitIsHonouredBeforeRetrying() {
        rateLimitFirstN = 1;
        GenericWebhookProvider provider = provider("{\"message\":\"%message%\"}", Map.of(), "", "");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 5,
                Duration.ofSeconds(5), new RetryPolicy(5L, 50L))) {
            dispatcher.enqueue(event("slow down"));
            waitUntil(() -> dispatcher.deliveredCount() == 1);
            assertEquals(1, dispatcher.deliveredCount());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void aDeliveryThatKeepsFailingIsCountedAsFailedNotRetriedForever() {
        failFirstN = 1_000;
        GenericWebhookProvider provider = provider("{\"message\":\"%message%\"}", Map.of(), "", "");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 3,
                Duration.ofSeconds(5), new RetryPolicy(2L, 10L))) {
            dispatcher.enqueue(event("doomed"));
            waitUntil(() -> dispatcher.failedCount() == 1);
            assertEquals(1, dispatcher.failedCount());
            assertEquals(0, dispatcher.deliveredCount());
            assertFalse(dispatcher.recentErrors().isEmpty());
            assertEquals(3, attempts.get(), "exactly maxAttempts tries");
        }
    }

    @Test
    void aNonRetryableClientErrorFailsImmediately() {
        successStatus = 400;
        GenericWebhookProvider provider = provider("{\"message\":\"%message%\"}", Map.of(), "", "");
        try (ChatDispatcher dispatcher = new ChatDispatcher(List.of(provider), 64, 5,
                Duration.ofSeconds(5), new RetryPolicy(2L, 10L))) {
            dispatcher.enqueue(event("bad request"));
            waitUntil(() -> dispatcher.failedCount() == 1);
            assertEquals(1, attempts.get(), "a 400 is not retried");
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
