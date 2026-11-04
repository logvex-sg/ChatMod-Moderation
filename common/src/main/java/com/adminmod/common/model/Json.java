package com.adminmod.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * JSON helpers for the dashboard payloads.
 *
 * <p>These payloads are the interface between the server and the client GUI, so the field
 * names are treated as part of the protocol and must stay stable.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException e) {
            // Payloads arrive over the network; malformed JSON must not propagate.
            return new JsonObject();
        }
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    public static String str(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    public static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    public static long lng(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    public static double dbl(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
    }

    public static List<String> stringList(JsonObject object, String key) {
        List<String> result = new ArrayList<>();
        JsonElement element = object.get(key);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                result.add(item.getAsString());
            }
        }
        return result;
    }

    public static boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Renders a UUID in the dashed form the configuration file uses. */
    public static String uuidString(UUID uuid) {
        return uuid.toString();
    }
}
