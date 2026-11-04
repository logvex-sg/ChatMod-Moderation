package com.adminmod.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class JsonTest {

    @Test
    void readsTypedValuesWithFallbacks() {
        JsonObject object = Json.parseObject("{\"name\":\"Alex\",\"online\":true,\"count\":3,\"big\":99}");
        assertEquals("Alex", Json.str(object, "name", ""));
        assertEquals("fallback", Json.str(object, "missing", "fallback"));
        assertTrue(Json.bool(object, "online", false));
        assertFalse(Json.bool(object, "missing", false));
        assertEquals(3, Json.integer(object, "count", 0));
        assertEquals(99L, Json.lng(object, "big", 0L));
    }

    @Test
    void blankOrInvalidJsonYieldsEmptyObject() {
        assertTrue(Json.parseObject("").isEmpty());
        assertTrue(Json.parseObject("not json").isEmpty());
    }

    @Test
    void detectsUuids() {
        assertTrue(Json.isUuid("11111111-2222-3333-4444-555555555555"));
        assertFalse(Json.isUuid("not-a-uuid"));
        assertFalse(Json.isUuid(""));
        assertFalse(Json.isUuid(null));
    }

    @Test
    void serialisesRecordsWithStableFieldNames() {
        PlayerSummary summary = new PlayerSummary("uuid", "Alex", true, false, false, false, 2, 30, "127.0.0.1");
        String json = Json.write(summary);
        assertTrue(json.contains("\"name\":\"Alex\""));
        assertTrue(json.contains("\"warnings\":2"));
        assertTrue(json.contains("\"ping\":30"));
    }
}
