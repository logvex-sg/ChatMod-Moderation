package com.adminmod.common.security;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Removes credential material from text before it reaches a log, a chat message or the
 * dashboard.
 *
 * <p>Webhook URLs embed their token in the path, and bearer tokens appear in headers, so a
 * raw exception message is enough to leak a secret. Everything that can end up in a log is
 * passed through here first.
 */
public final class SecretRedactor {

    private static final String MASK = "<redacted>";

    private static final Pattern DISCORD_WEBHOOK = Pattern.compile(
            "(https://(?:[a-z]+\\.)?discord(?:app)?\\.com/api/webhooks/)\\d+/([A-Za-z0-9_\\-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLACK_WEBHOOK = Pattern.compile(
            "(https://hooks\\.slack\\.com/services/)[A-Za-z0-9/\\-_]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile(
            "(Bearer\\s+)[A-Za-z0-9._\\-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCESS_TOKEN = Pattern.compile(
            "([?&](?:access_token|token|key|apikey|api_key)=)[^&\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "((?:Authorization|X-Auth-Token|X-Api-Key)\\s*[:=]\\s*)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MATRIX_TOKEN = Pattern.compile(
            "(matrix[_\\-]?token\\s*[:=]\\s*)\\S+", Pattern.CASE_INSENSITIVE);

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        result = DISCORD_WEBHOOK.matcher(result).replaceAll("$1" + MASK);
        result = SLACK_WEBHOOK.matcher(result).replaceAll("$1" + MASK);
        result = BEARER.matcher(result).replaceAll("$1" + MASK);
        result = ACCESS_TOKEN.matcher(result).replaceAll("$1" + MASK);
        result = AUTH_HEADER.matcher(result).replaceAll("$1" + MASK);
        result = MATRIX_TOKEN.matcher(result).replaceAll("$1" + MASK);
        return result;
    }

    /**
     * Renders a URL with its path and query stripped, so a host can be named in diagnostics
     * without disclosing the token that usually lives in the path.
     */
    public static String safeHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                return MASK;
            }
            return scheme + "://" + host;
        } catch (IllegalArgumentException e) {
            return MASK;
        }
    }

    /** Reports whether a string appears to hold a usable credential. */
    public static boolean looksLikeSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return DISCORD_WEBHOOK.matcher(trimmed).find()
                || trimmed.startsWith("Bearer ")
                || trimmed.length() > 40;
    }
}
