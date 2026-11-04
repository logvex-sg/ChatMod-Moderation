package com.adminmod.common.model;

/**
 * Free-form staff note attached to a player.
 *
 * @param id        identifier, unique per server
 * @param playerUuid UUID of the player the note is about
 * @param authorUuid UUID of the staff member who wrote it
 * @param authorName name of the staff member who wrote it
 * @param text      the note body
 * @param createdAt epoch milliseconds when the note was written
 */
public record PlayerNote(
        long id,
        String playerUuid,
        String authorUuid,
        String authorName,
        String text,
        long createdAt) {
}
