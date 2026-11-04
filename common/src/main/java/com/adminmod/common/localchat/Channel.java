package com.adminmod.common.localchat;

/**
 * The channel a chat message belongs to.
 *
 * <p>The channel is part of the forwarded payload, so an external platform can label a message
 * with the same {@code [Local]} or {@code [Global]} tag a player sees in game.
 */
public enum Channel {
    /** Delivered only to nearby players in the same dimension. */
    LOCAL("Local"),
    /** Broadcast to every eligible online player. */
    GLOBAL("Global"),
    /** Ordinary public chat when proximity chat is disabled. */
    PUBLIC(""),
    /** A server notice such as a join, leave or announcement. */
    SYSTEM("");

    private final String label;

    Channel(String label) {
        this.label = label;
    }

    /** Short label used in a rendered message, e.g. {@code Local}. Empty for non-proximity channels. */
    public String label() {
        return label;
    }

    /** Whether this channel is one of the proximity channels rather than ordinary public chat. */
    public boolean isProximity() {
        return this == LOCAL || this == GLOBAL;
    }

    public static Channel byName(String name, Channel fallback) {
        if (name == null) {
            return fallback;
        }
        for (Channel channel : values()) {
            if (channel.name().equalsIgnoreCase(name.trim())) {
                return channel;
            }
        }
        return fallback;
    }
}
