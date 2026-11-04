package com.adminmod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;

/**
 * The announcement rotation and its interval. The class itself has no Minecraft dependency for
 * this path, so the timer can be driven with explicit timestamps instead of a running server.
 */
class ServerTickerTest {

    private static AdminConfig config(String... messages) {
        AdminConfig config = new AdminConfig();
        config.announcements.enabled = true;
        config.announcements.intervalSeconds = 60L;
        config.announcements.messages = List.of(messages);
        return config;
    }

    @Test
    void nothingIsAnnouncedWhileDisabled() {
        AdminConfig config = config("hello");
        config.announcements.enabled = false;
        ServerTicker ticker = new ServerTicker(config, null);
        assertTrue(ticker.nextAnnouncement(System.currentTimeMillis() + 1_000_000L).isEmpty());
    }

    @Test
    void nothingIsAnnouncedWithoutMessages() {
        AdminConfig config = config();
        ServerTicker ticker = new ServerTicker(config, null);
        assertTrue(ticker.nextAnnouncement(System.currentTimeMillis() + 1_000_000L).isEmpty());
    }

    @Test
    void theFirstAnnouncementWaitsForTheInterval() {
        AdminConfig config = config("one", "two");
        ServerTicker ticker = new ServerTicker(config, null);
        long now = System.currentTimeMillis();

        assertTrue(ticker.nextAnnouncement(now).isEmpty(), "the interval has not elapsed yet");
        assertTrue(ticker.nextAnnouncement(now + 30_000L).isEmpty(), "half the interval is not enough");
        assertEquals("one", ticker.nextAnnouncement(now + 60_000L).orElseThrow());
    }

    @Test
    void messagesRotateInOrderAndWrapAround() {
        AdminConfig config = config("one", "two", "three");
        ServerTicker ticker = new ServerTicker(config, null);
        long now = System.currentTimeMillis();

        assertEquals("one", ticker.nextAnnouncement(now + 60_000L).orElseThrow());
        assertEquals("two", ticker.nextAnnouncement(now + 120_000L).orElseThrow());
        assertEquals("three", ticker.nextAnnouncement(now + 180_000L).orElseThrow());
        assertEquals("one", ticker.nextAnnouncement(now + 240_000L).orElseThrow(), "the list should wrap");
    }

    @Test
    void aBlankEntryIsSkippedButStillConsumesItsTurn() {
        AdminConfig config = config("one", "   ", "three");
        ServerTicker ticker = new ServerTicker(config, null);
        long now = System.currentTimeMillis();

        assertEquals("one", ticker.nextAnnouncement(now + 60_000L).orElseThrow());
        assertTrue(ticker.nextAnnouncement(now + 120_000L).isEmpty(), "the blank entry is skipped");
        assertEquals("three", ticker.nextAnnouncement(now + 180_000L).orElseThrow());
    }

    @Test
    void aResetRestartsTheIntervalAndTheRotation() {
        AdminConfig config = config("one", "two");
        ServerTicker ticker = new ServerTicker(config, null);
        long now = System.currentTimeMillis();

        assertEquals("one", ticker.nextAnnouncement(now + 60_000L).orElseThrow());
        ticker.resetAnnouncements();
        // A reset sets the timer to "now", so a reload does not fire an announcement immediately.
        assertTrue(ticker.nextAnnouncement(System.currentTimeMillis()).isEmpty());
        assertEquals("one", ticker.nextAnnouncement(System.currentTimeMillis() + 61_000L).orElseThrow(),
                "the rotation should restart at the first message");
    }

    @Test
    void aVerySmallIntervalIsClampedToTenSeconds() {
        AdminConfig config = config("one", "two");
        config.announcements.intervalSeconds = 0L;
        ServerTicker ticker = new ServerTicker(config, null);
        long now = System.currentTimeMillis();

        assertTrue(ticker.nextAnnouncement(now + 9_000L).isEmpty(), "below the ten second floor");
        assertEquals("one", ticker.nextAnnouncement(now + 10_000L).orElseThrow());
    }

    @Test
    void announcementsExposeTheConfiguredMessages() {
        ServerTicker ticker = new ServerTicker(config("a", "b"), null);
        assertEquals(List.of("a", "b"), ticker.announcements());
        assertFalse(ticker.announcements().isEmpty());
    }
}
