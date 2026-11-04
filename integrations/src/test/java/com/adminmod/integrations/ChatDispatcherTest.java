package com.adminmod.integrations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.http.RetryPolicy;
import com.adminmod.integrations.template.Template;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDispatcherTest {

    /** Transport that fails a fixed number of times, then succeeds. */
    private static final class FlakyTransport implements HttpTransport {
        private final int failuresBeforeSuccess;
        private int attempts;
        final List<HttpRequest> requests = new ArrayList<>();

        FlakyTransport(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public HttpResult send(HttpRequest request, Duration timeout) {
            requests.add(request);
            attempts++;
            if (attempts <= failuresBeforeSuccess) {
                return new HttpResult(429, "slow down", 1L);
            }
            return new HttpResult(204, "");
        }
    }

    private static final class CountingProvider implements ChatProvider {
        final List<ChatEvent> delivered = new ArrayList<>();
        private final HttpTransport transport;
        private final boolean configured;

        CountingProvider(HttpTransport transport, boolean configured) {
            this.transport = transport;
            this.configured = configured;
        }

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public HttpResult deliver(ChatEvent event, Duration timeout) {
            delivered.add(event);
            try {
                return transport.send(null, timeout);
            } catch (Exception e) {
                return HttpResult.failure("transport failed");
            }
        }

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public String lastError() {
            return "";
        }
    }

    private static ChatEvent event(String message) {
        return new ChatEvent("Alex", "uuid", message, System.currentTimeMillis(), "Test Server");
    }

    @Test
    void retriesUntilSuccessAndCountsOneDelivery() throws Exception {
        FlakyTransport transport = new FlakyTransport(2);
        CountingProvider provider = new CountingProvider(transport, true);
        try (ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 5, Duration.ofSeconds(1),
                new RetryPolicy(1L, 5L))) {
            dispatcher.enqueue(event("hello"));
            assertTrue(dispatcher.awaitIdle(3000L));
            // Give the worker a moment to finish the final attempt.
            long deadline = System.currentTimeMillis() + 3000L;
            while (dispatcher.deliveredCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            assertEquals(1L, dispatcher.deliveredCount());
            assertEquals(0L, dispatcher.failedCount());
            assertEquals(3, transport.attempts);
        }
    }

    @Test
    void givesUpAfterMaxAttemptsAndRecordsAnError() throws Exception {
        FlakyTransport transport = new FlakyTransport(100);
        CountingProvider provider = new CountingProvider(transport, true);
        try (ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 3, Duration.ofSeconds(1),
                new RetryPolicy(1L, 2L))) {
            dispatcher.enqueue(event("hello"));
            long deadline = System.currentTimeMillis() + 15_000L;
            while (dispatcher.failedCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            assertEquals(3, transport.attempts);
            assertEquals(1L, dispatcher.failedCount());
            assertEquals(1, dispatcher.recentErrors().size());
        }
    }

    @Test
    void nonRetryableFailureIsNotRetried() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport transport = (request, timeout) -> {
            attempts.incrementAndGet();
            return new HttpResult(400, "bad request");
        };
        CountingProvider provider = new CountingProvider(transport, true);
        try (ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 5, Duration.ofSeconds(1),
                new RetryPolicy(1L, 2L))) {
            dispatcher.enqueue(event("hello"));
            long deadline = System.currentTimeMillis() + 3000L;
            while (dispatcher.failedCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            assertEquals(1, attempts.get(), "a 400 response must not be retried");
            assertEquals(1, provider.delivered.size());
            assertEquals(1L, dispatcher.failedCount());
        }
    }

    @Test
    void fullQueueDropsOldestInsteadOfBlocking() {
        FlakyTransport transport = new FlakyTransport(0);
        CountingProvider provider = new CountingProvider(transport, true);
        // Capacity floor is 16; enqueue well beyond it without draining.
        try (ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 1, Duration.ofMillis(50),
                new RetryPolicy(1L, 2L))) {
            for (int i = 0; i < 500; i++) {
                dispatcher.enqueue(event("message " + i));
            }
            assertTrue(dispatcher.droppedCount() > 0L, "queue should have dropped entries");
        }
    }

    @Test
    void closedDispatcherStopsAccepting() {
        CountingProvider provider = new CountingProvider(new FlakyTransport(0), true);
        ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 1, Duration.ofMillis(50), new RetryPolicy(1L, 2L));
        dispatcher.close();
        dispatcher.enqueue(event("after close"));
        assertEquals(0, dispatcher.queuedCount());
    }

    @Test
    void errorMessagesNeverContainSecrets() {
        HttpTransport transport = (request, timeout) -> HttpResult.failure(
                "connect to https://discord.com/api/webhooks/123/super-secret-token failed");
        CountingProvider provider = new CountingProvider(transport, true);
        try (ChatDispatcher dispatcher = new ChatDispatcher(provider, 16, 1, Duration.ofMillis(50),
                new RetryPolicy(1L, 2L))) {
            dispatcher.enqueue(event("hi"));
            long deadline = System.currentTimeMillis() + 3000L;
            while (dispatcher.recentErrors().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(dispatcher.recentErrors().isEmpty());
            for (String error : dispatcher.recentErrors()) {
                assertFalse(error.contains("super-secret-token"), "secret leaked: " + error);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void templateEscapesJsonBreakingCharacters() {
        assertEquals("a\\\"b", Template.escapeJson("a\"b"));
        assertEquals("line1\\nline2", Template.escapeJson("line1\nline2"));
        assertEquals("back\\\\slash", Template.escapeJson("back\\slash"));
    }

    @Test
    void templateSubstitutesPlaceholders() {
        assertEquals("Alex: hi", Template.render("%player%: %message%",
                java.util.Map.of("player", "Alex", "message", "hi")));
        assertEquals("%unknown%", Template.render("%unknown%", java.util.Map.of()));
    }

    @Test
    void discordMentionsAreNeutralised() {
        String escaped = Template.escapeDiscordMentions("@everyone hello @Alex");
        assertFalse(escaped.contains("@everyone"));
        assertFalse(escaped.contains("@Alex"));
        assertTrue(escaped.contains("everyone"));
    }
}
