package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Import and export: versioned, secret-free, and tolerant of settings this build no longer has. */
class ProfileCodecTest {

    private final SettingRegistry registry = SettingRegistry.createDefault();

    @Test
    void anExportRoundTripsExactly() {
        ConfigProfile profile = new ConfigProfile("roundtrip", "a description", false,
                Map.of("chatBridge.provider", "matrix", "chatBridge.queueCapacity", "512"));
        String json = ProfileCodec.toJson(profile);
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(json, registry);
        assertTrue(result.ok(), result.error());
        assertEquals("roundtrip", result.profile().name());
        assertEquals("a description", result.profile().description());
        assertEquals("matrix", result.profile().value("chatBridge.provider"));
        assertEquals("512", result.profile().value("chatBridge.queueCapacity"));
    }

    @Test
    void aSecretInAnImportedFileIsRefusedWithAWarning() {
        String json = """
                {"formatVersion":1,"kind":"adminmod-profile","name":"evil","values":{
                  "chatBridge.discord.webhookUrl":"https://attacker.example/token",
                  "chatBridge.provider":"webhook"}}
                """;
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(json, registry);
        assertTrue(result.ok());
        assertFalse(result.profile().values().containsKey("chatBridge.discord.webhookUrl"));
        assertEquals("webhook", result.profile().value("chatBridge.provider"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("secret")),
                "a refused secret must be reported: " + result.warnings());
    }

    @Test
    void anUnknownSettingIsDroppedWithAWarningRatherThanFailingTheImport() {
        String json = """
                {"formatVersion":1,"kind":"adminmod-profile","name":"future","values":{
                  "some.future.setting":"1",
                  "chatBridge.enabled":"false"}}
                """;
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(json, registry);
        assertTrue(result.ok());
        assertEquals("false", result.profile().value("chatBridge.enabled"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("unknown setting")));
    }

    @Test
    void anOutOfRangeValueIsDroppedWithAWarning() {
        String json = """
                {"formatVersion":1,"kind":"adminmod-profile","name":"bad","values":{
                  "chatBridge.queueCapacity":"999999999"}}
                """;
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(json, registry);
        assertTrue(result.ok());
        assertFalse(result.profile().values().containsKey("chatBridge.queueCapacity"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("chatBridge.queueCapacity")));
    }

    @Test
    void aNewerFormatVersionIsRefusedOutright() {
        String json = """
                {"formatVersion":99,"kind":"adminmod-profile","name":"future","values":{}}
                """;
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(json, registry);
        assertFalse(result.ok());
        assertTrue(result.error().contains("newer ADMIN"));
    }

    @Test
    void aFileThatIsNotAProfileIsRefused() {
        ProfileCodec.ImportResult result = ProfileCodec.fromJson("{\"kind\":\"something-else\"}", registry);
        assertFalse(result.ok());
        assertNotNull(result.error());
    }

    @Test
    void invalidJsonIsRefusedWithAReadableMessage() {
        ProfileCodec.ImportResult result = ProfileCodec.fromJson("this is not json", registry);
        assertFalse(result.ok());
        assertTrue(result.error().contains("valid JSON"));
    }

    @Test
    void aListValueRoundTripsAsNewlineSeparatedText() {
        ConfigProfile profile = new ConfigProfile("lists", "", false,
                Map.of("chatBridge.blockedKeywords", "alpha\nbeta"));
        ProfileCodec.ImportResult result = ProfileCodec.fromJson(ProfileCodec.toJson(profile), registry);
        assertTrue(result.ok());
        assertEquals("alpha\nbeta", result.profile().value("chatBridge.blockedKeywords"));
    }
}
