package com.adminmod.common.audit;

import java.util.Locale;

/**
 * A search over the audit log.
 *
 * <p>Every field is optional; a query with only a time range returns everything in that range. The
 * free-text terms are matched case-insensitively against the actor, subject, category, severity and
 * detail together, so "diamond" finds an item and "192.168" finds an address. The dedicated fields
 * (item id, block, position, container) match against the corresponding {@code key=value} pair in
 * the detail, which is what makes an investigation by item or coordinate possible without a schema.
 */
public record AuditQuery(
        String player,
        AuditCategory category,
        AuditSeverity minSeverity,
        Long fromMillis,
        Long toMillis,
        String itemId,
        String blockId,
        String position,
        String container,
        String text,
        int limit) {

    /** A query matching everything, bounded by {@code limit}. */
    public static AuditQuery all(int limit) {
        return new AuditQuery(null, null, null, null, null, null, null, null, null, null, limit);
    }

    /** Whether {@code event} matches every set field. */
    public boolean matches(AuditEvent event) {
        if (event == null) {
            return false;
        }
        if (fromMillis != null && event.timestamp() < fromMillis) {
            return false;
        }
        if (toMillis != null && event.timestamp() > toMillis) {
            return false;
        }
        if (category != null && event.category() != category) {
            return false;
        }
        if (minSeverity != null && event.severity().ordinal() < minSeverity.ordinal()) {
            return false;
        }
        if (player != null && !player.isBlank() && !matchesPlayer(event, player)) {
            return false;
        }
        if (itemId != null && !itemId.isBlank() && !contains(event.detailValue(AuditEvent.KEY_ITEM), itemId)) {
            return false;
        }
        if (blockId != null && !blockId.isBlank() && !contains(event.detailValue(AuditEvent.KEY_BLOCK), blockId)) {
            return false;
        }
        if (position != null && !position.isBlank() && !contains(event.detailValue(AuditEvent.KEY_POSITION), position)) {
            return false;
        }
        if (container != null && !container.isBlank()
                && !contains(event.detailValue(AuditEvent.KEY_CONTAINER), container)) {
            return false;
        }
        if (text != null && !text.isBlank()) {
            for (String term : text.trim().split("\\s+")) {
                if (!matchesFreeText(event, term)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesPlayer(AuditEvent event, String player) {
        String needle = player.toLowerCase(Locale.ROOT);
        return equalsIgnoreCase(event.actor(), needle)
                || equalsIgnoreCase(event.actorUuid(), needle)
                || equalsIgnoreCase(event.subject(), needle);
    }

    private static boolean equalsIgnoreCase(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(lowerNeedle);
    }

    private static boolean matchesFreeText(AuditEvent event, String term) {
        String needle = term.toLowerCase(Locale.ROOT);
        return contains(event.actor(), needle)
                || contains(event.actorUuid(), needle)
                || contains(event.subject(), needle)
                || contains(event.detail(), needle)
                || contains(event.category().name(), needle)
                || contains(event.severity().name(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** A copy of this query with a different limit, used to clamp to the configured maximum. */
    public AuditQuery withLimit(int newLimit) {
        return new AuditQuery(player, category, minSeverity, fromMillis, toMillis, itemId, blockId,
                position, container, text, newLimit);
    }
}
