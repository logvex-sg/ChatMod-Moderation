package com.adminmod.common.audit;

import com.adminmod.common.model.Json;
import com.google.gson.JsonObject;

/**
 * One recorded audit event.
 *
 * <p>The shape is deliberately flat and small: the store compresses thousands of them, so a record
 * is a category, a severity, an actor, an optional subject and a detail string. Anything richer
 * (an item id, a block position, a container location) is folded into {@code detail} in a
 * {@code key=value} form so it stays searchable without a schema migration.
 *
 * <p>A record never holds a password, a token or a private message. {@code actor} and
 * {@code subject} are a player name or {@code UUID}; the detail is free text written by the code
 * that raised the event, none of which includes a credential.
 */
public record AuditEvent(
        long timestamp,
        AuditCategory category,
        AuditSeverity severity,
        String actor,
        String actorUuid,
        String subject,
        String detail) {

    /** The detail keys that are treated as first-class search fields. */
    public static final String KEY_ITEM = "item";
    public static final String KEY_BLOCK = "block";
    public static final String KEY_POSITION = "pos";
    public static final String KEY_CONTAINER = "container";
    public static final String KEY_COMMAND = "command";

    /** A short builder so call sites stay readable. */
    public static Builder builder(AuditCategory category, AuditSeverity severity) {
        return new Builder(category, severity);
    }

    /** @return the value of a {@code key=value} pair inside the detail, or {@code null}. */
    public String detailValue(String key) {
        if (detail == null || detail.isBlank() || key == null) {
            return null;
        }
        String needle = key + "=";
        int index = detail.indexOf(needle);
        while (index >= 0) {
            boolean atBoundary = index == 0 || Character.isWhitespace(detail.charAt(index - 1));
            if (atBoundary) {
                int start = index + needle.length();
                int end = start;
                while (end < detail.length() && !Character.isWhitespace(detail.charAt(end))) {
                    end++;
                }
                return detail.substring(start, end);
            }
            index = detail.indexOf(needle, index + 1);
        }
        return null;
    }

    /** Whether retention must keep this event even when the ordinary window has passed. */
    public boolean isSecurityRelevant() {
        return severity.isSecurityRelevant() || category.isSecurityRelevant();
    }

    /** JSON view used by the dashboard and the export. */
    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("at", timestamp);
        object.addProperty("category", category.name().toLowerCase(java.util.Locale.ROOT));
        object.addProperty("severity", severity.name().toLowerCase(java.util.Locale.ROOT));
        object.addProperty("actor", actor == null ? "" : actor);
        object.addProperty("actorUuid", actorUuid == null ? "" : actorUuid);
        object.addProperty("subject", subject == null ? "" : subject);
        object.addProperty("detail", detail == null ? "" : detail);
        return object;
    }

    /** Serialises the event as the single JSON line stored in the log. */
    public byte[] encode() {
        return Json.write(toJson()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Parses a stored line back into an event, or {@code null} when it is malformed. */
    public static AuditEvent decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonObject object = Json.parseObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            AuditCategory category = AuditCategory.parse(Json.str(object, "category", ""));
            if (category == null) {
                return null;
            }
            AuditSeverity severity = AuditSeverity.parse(Json.str(object, "severity", "info"));
            return new AuditEvent(
                    Json.lng(object, "at", 0L),
                    category,
                    severity == null ? AuditSeverity.INFO : severity,
                    Json.str(object, "actor", ""),
                    Json.str(object, "actorUuid", ""),
                    Json.str(object, "subject", ""),
                    Json.str(object, "detail", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Fluent construction with the common defaults filled in. */
    public static final class Builder {
        private final AuditCategory category;
        private final AuditSeverity severity;
        private long timestamp = System.currentTimeMillis();
        private String actor = "";
        private String actorUuid = "";
        private String subject = "";
        private String detail = "";

        Builder(AuditCategory category, AuditSeverity severity) {
            this.category = category;
            this.severity = severity;
        }

        public Builder at(long value) {
            this.timestamp = value;
            return this;
        }

        public Builder actor(String name, String uuid) {
            this.actor = name == null ? "" : name;
            this.actorUuid = uuid == null ? "" : uuid;
            return this;
        }

        public Builder actor(String name) {
            return actor(name, "");
        }

        public Builder subject(String value) {
            this.subject = value == null ? "" : value;
            return this;
        }

        /**
         * Adds free text to the detail.
         *
         * <p>Appends rather than replaces, so a call site can put the human-readable phrase either
         * before or after its {@code key=value} fields without one wiping the other.
         */
        public Builder detail(String value) {
            if (value == null || value.isBlank()) {
                return this;
            }
            this.detail = this.detail.isBlank() ? value : this.detail + " " + value;
            return this;
        }

        /** Appends a {@code key=value} pair, space-separated, so it stays searchable. */
        public Builder field(String key, Object value) {
            if (value == null) {
                return this;
            }
            String text = String.valueOf(value);
            if (text.isBlank() || text.indexOf(' ') >= 0) {
                // Quote a value with spaces so the pair still parses back out.
                text = '"' + text.replace("\"", "'") + '"';
            }
            this.detail = this.detail.isBlank() ? key + "=" + text : this.detail + " " + key + "=" + text;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(timestamp, category, severity, actor, actorUuid, subject, detail);
        }
    }
}
