package com.adminmod.common.settings;

import java.util.List;

/**
 * The categories the central settings screen is organised into.
 *
 * <p>The category is a property of a {@link SettingSpec}, not of the Java class the setting lives
 * on, so a file can hold settings that belong to more than one screen category (for example the
 * chat bridge file contributes to both Chat Bridge and Integrations).
 *
 * <p>Order here is the order the sidebar shows them in, and it is the order the dashboard expects
 * when it lays out the tab list, so it must stay stable.
 */
public enum SettingCategory {
    GENERAL("General", "Server identity, master switches and logging"),
    MODERATION("Moderation", "Punishments, warnings and staff notifications"),
    ANTI_CHEAT("Anti-Cheat", "Connection and packet abuse checks"),
    ANTI_DUPE("Anti-Dupe", "Inventory transaction validation and dupe detection"),
    LOCAL_CHAT("Local Chat", "Proximity chat radius, channels and formats"),
    CHAT_BRIDGE("Chat Bridge", "Forwarding public chat to external platforms"),
    REMOTE_ADMIN("Remote Administration", "The authenticated remote dashboard endpoint"),
    SECURITY("Security", "Authentication, permissions and access control"),
    PERFORMANCE("Performance", "Monitoring intervals, limits and profiles"),
    STORAGE("Storage & Compression", "Retention, archiving and the audit store"),
    BACKUPS("Backups", "Automatic configuration and data backups"),
    AUTOMATION("Automation", "Scheduled tasks and escalation"),
    NOTIFICATIONS("Notifications", "Where ADMIN sends its alerts"),
    INTEGRATIONS("Integrations", "Discord, Matrix and generic webhook credentials");

    private final String label;
    private final String description;

    SettingCategory(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** Human-readable name for the dashboard sidebar. */
    public String label() {
        return label;
    }

    /** One-line explanation of what the category groups. */
    public String description() {
        return description;
    }

    public static List<SettingCategory> ordered() {
        return List.of(values());
    }
}
