package com.adminmod.server;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.server.remote.SessionManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Periodic server-side housekeeping that does not belong to any single subsystem.
 *
 * <p>Three jobs run here: expiring idle remote-administration sessions, rotating scheduled
 * announcements, and enforcing maintenance mode. All of them run on the server thread from the
 * end-of-tick event, so they may touch the world and the player list directly. The work per tick
 * is a few comparisons, and the announcement timer only fires on its configured interval.
 */
public final class ServerTicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("adminmod");

    private final AdminConfig config;
    private final SessionManager sessions;

    private long lastAnnouncementMillis;
    private int announcementIndex;
    private long lastExpirySweepMillis;

    public ServerTicker(AdminConfig config, SessionManager sessions) {
        this.config = config;
        this.sessions = sessions;
        this.lastAnnouncementMillis = System.currentTimeMillis();
    }

    /** Called at the end of every server tick. */
    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        // Session expiry is cheap but pointless to run every tick; once a second is plenty.
        if (sessions != null && now - lastExpirySweepMillis >= 1_000L) {
            lastExpirySweepMillis = now;
            int expired = sessions.expireIdle(now);
            if (expired > 0) {
                LOGGER.info("Expired {} idle remote administration session(s)", expired);
            }
        }

        tickAnnouncements(server, now);
    }

    private void tickAnnouncements(MinecraftServer server, long now) {
        nextAnnouncement(now).ifPresent(message ->
                // A broadcast is delivered as a game message, which the bridge forwards when
                // join/leave forwarding is on, so an announcement reaches the platform too.
                server.getPlayerManager().broadcast(Text.literal(message), false));
    }

    /**
     * Returns the announcement that is due at {@code now}, advancing the rotation when one is.
     *
     * <p>Kept separate from the broadcast so the timer and rotation can be tested without a
     * running server; the only side effect is on this instance's own counters.
     *
     * @return the message to show, or empty when none is due
     */
    public java.util.Optional<String> nextAnnouncement(long now) {
        AdminConfig.Announcements settings = config.announcements;
        if (!settings.enabled || settings.messages.isEmpty()) {
            return java.util.Optional.empty();
        }
        long interval = Math.max(10L, settings.intervalSeconds) * 1000L;
        if (now - lastAnnouncementMillis < interval) {
            return java.util.Optional.empty();
        }
        lastAnnouncementMillis = now;
        String message = settings.messages.get(announcementIndex % settings.messages.size());
        announcementIndex++;
        return message == null || message.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(message);
    }

    /**
     * Handles a player joining while maintenance mode is active.
     *
     * @return {@code true} when the player was allowed to stay
     */
    public boolean allowJoin(ServerPlayerEntity player) {
        AdminConfig.Maintenance maintenance = config.maintenance;
        if (!maintenance.enabled) {
            return true;
        }
        if (!maintenance.message.isBlank()) {
            player.sendMessage(Text.literal(maintenance.message), false);
        }
        if (!maintenance.kickNonOperators) {
            return true;
        }
        UUID uuid = player.getUuid();
        if (maintenance.allowList.contains(uuid.toString())) {
            return true;
        }
        if (player.hasPermissionLevel(config.permissions.adminLevel)) {
            return true;
        }
        player.networkHandler.disconnect(Text.literal(
                maintenance.message.isBlank() ? "The server is under maintenance." : maintenance.message));
        return false;
    }

    /** Resets the announcement timer, so a reload does not fire immediately. */
    public void resetAnnouncements() {
        lastAnnouncementMillis = System.currentTimeMillis();
        announcementIndex = 0;
    }

    /** Exposed for tests. */
    public List<String> announcements() {
        return List.copyOf(config.announcements.messages);
    }
}
