package com.adminmod.common.compress;

/**
 * A compression algorithm ADMIN can use for its persistent data.
 *
 * <p>Implementations must be safe to call from a background thread and must be stateless, so a
 * single instance can serve several worker threads. A codec never throws on malformed input:
 * {@link #decompress(byte[], int)} returns {@code null} when the bytes are not a valid stream,
 * which is what lets the archive reader report corruption instead of crashing.
 */
public interface CompressionCodec {

    /** Stable identifier stored in an archive header. Never renumber or rename an existing id. */
    CodecId id();

    /** Whether this codec is usable in the current environment (for example, its native library loaded). */
    boolean available();

    /**
     * Compresses {@code data}.
     *
     * @param data  the bytes to compress; not modified
     * @param level the requested level, clamped into {@link #minLevel()}..{@link #maxLevel()}
     * @return the compressed bytes
     * @throws CompressionException when compression fails for an environmental reason
     */
    byte[] compress(byte[] data, int level);

    /**
     * Decompresses {@code data} into a buffer of at most {@code maxOutputBytes}.
     *
     * @param data           the compressed bytes
     * @param maxOutputBytes a hard cap, so a corrupt header cannot request a huge allocation
     * @return the decompressed bytes, or {@code null} when the input is corrupt or exceeds the cap
     */
    byte[] decompress(byte[] data, int maxOutputBytes);

    /** Lowest accepted level. */
    default int minLevel() {
        return 1;
    }

    /** Highest accepted level. */
    default int maxLevel() {
        return 9;
    }

    /** Clamps {@code level} into this codec's supported range. */
    default int clampLevel(int level) {
        return Math.max(minLevel(), Math.min(maxLevel(), level));
    }

    /** Non-throwing description for the storage dashboard. */
    default String describe() {
        return id().name().toLowerCase(java.util.Locale.ROOT);
    }
}
