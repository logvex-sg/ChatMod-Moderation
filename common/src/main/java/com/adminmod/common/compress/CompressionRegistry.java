package com.adminmod.common.compress;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves the codec an archive should be written with.
 *
 * <p>The requested algorithm comes from the configuration; this class maps that name onto a codec
 * and degrades safely when the first choice is not usable. Zstandard is preferred when its native
 * library loaded, and GZIP is the fallback that is always present, so a server never fails to start
 * because a native library is missing for its platform.
 *
 * <p>The instances are stateless and shared, so resolution is a map lookup with no allocation.
 */
public final class CompressionRegistry {

    private static final Map<CodecId, CompressionCodec> CODECS = new EnumMap<>(CodecId.class);

    static {
        CODECS.put(CodecId.GZIP, new GzipCodec());
        CODECS.put(CodecId.ZSTD, new ZstdCodec());
        CODECS.put(CodecId.NONE, new StoredCodec());
    }

    private CompressionRegistry() {
    }

    /**
     * Parses a configured algorithm name.
     *
     * @return the matching id, or {@code fallback} when the name is unknown
     */
    public static CodecId parse(String name, CodecId fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String normalised = name.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalised) {
            case "zstd", "zstandard" -> CodecId.ZSTD;
            case "gzip", "gz" -> CodecId.GZIP;
            case "none", "stored", "off" -> CodecId.NONE;
            default -> fallback;
        };
    }

    /** The codec for an id, never {@code null}. */
    public static CompressionCodec codec(CodecId id) {
        CompressionCodec codec = CODECS.get(id);
        return codec == null ? CODECS.get(CodecId.GZIP) : codec;
    }

    /**
     * The codec to use for a new archive.
     *
     * @param requested the configured algorithm
     * @return {@code requested} when it is available, otherwise the always-present GZIP fallback
     */
    public static CompressionCodec resolve(CodecId requested) {
        CompressionCodec codec = codec(requested);
        if (codec.available()) {
            return codec;
        }
        return codec(CodecId.GZIP);
    }

    /** The strongest codec available on this host, used when the configuration names nothing. */
    public static CodecId bestAvailable() {
        return codec(CodecId.ZSTD).available() ? CodecId.ZSTD : CodecId.GZIP;
    }

    /** Whether a configured algorithm is actually usable here, for an honest dashboard message. */
    public static boolean isAvailable(CodecId id) {
        return codec(id).available();
    }
}
