package com.adminmod.common.compress;

/**
 * Identifies the algorithm an archive was written with.
 *
 * <p>The ordinal is written into the archive header, so the values must never be renumbered. New
 * algorithms are appended.
 */
public enum CodecId {
    /** Compatibility fallback; always available because it is part of the JDK. */
    GZIP(0),
    /** Zstandard, fast with a configurable level. Used when the native library loads. */
    ZSTD(1),
    /** Stored without compression, used when compression would grow the data. */
    NONE(2);

    private final int id;

    CodecId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static CodecId byId(int id) {
        for (CodecId codec : values()) {
            if (codec.id == id) {
                return codec;
            }
        }
        throw new CompressionException("unknown codec id " + id);
    }
}
