package com.adminmod.common.localchat;

import java.util.Objects;

/**
 * A recipient considered for a proximity message.
 *
 * <p>Carries only what the delivery rule needs: who the player is, where they are, and whether the
 * staff bypass applies. The server builds these from its online player list; a test builds them by
 * hand.
 */
public record Recipient(String id, String name, String dimension, Position position, boolean staff) {

    public Recipient {
        Objects.requireNonNull(id, "id");
        dimension = dimension == null ? "" : dimension;
        position = position == null ? new Position(0, 0, 0) : position;
    }
}
