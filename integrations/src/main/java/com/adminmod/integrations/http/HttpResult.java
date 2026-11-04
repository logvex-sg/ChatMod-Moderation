package com.adminmod.integrations.http;

/**
 * Result of one HTTP attempt.
 *
 * @param status          HTTP status code, or 0 when the request never completed
 * @param body            response body, truncated to a safe length
 * @param retryAfterMillis delay requested by the server, or 0 when none was advertised
 * @param failure         transport-level failure description, or {@code null} on a response
 */
public record HttpResult(int status, String body, long retryAfterMillis, String failure) {

    public static final int MAX_BODY = 2048;

    public HttpResult(int status, String body, long retryAfterMillis) {
        this(status, body, retryAfterMillis, null);
    }

    public HttpResult(int status, String body) {
        this(status, body, 0L, null);
    }

    public static HttpResult failure(String description) {
        return new HttpResult(0, "", 0L, description);
    }

    public boolean isSuccess() {
        return failure == null && status >= 200 && status < 300;
    }

    /** Retryable statuses: rate limiting and server-side faults. */
    public boolean isRetryable() {
        if (failure != null) {
            return true;
        }
        return status == 429 || status == 408 || (status >= 500 && status <= 599);
    }

    public static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_BODY ? value : value.substring(0, MAX_BODY);
    }
}
