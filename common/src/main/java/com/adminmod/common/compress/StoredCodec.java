package com.adminmod.common.compress;

/**
 * Stored codec: the payload is written unchanged.
 *
 * <p>It exists so a record that does not compress (already compressed, or too small for the header
 * to pay for itself) can be stored without the codec registry having to special-case it. It is also
 * the last-resort fallback if every real codec is somehow unavailable.
 */
public final class StoredCodec implements CompressionCodec {

    @Override
    public CodecId id() {
        return CodecId.NONE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public int minLevel() {
        return 0;
    }

    @Override
    public int maxLevel() {
        return 0;
    }

    @Override
    public byte[] compress(byte[] data, int level) {
        return data.clone();
    }

    @Override
    public byte[] decompress(byte[] data, int maxOutputBytes) {
        if (data == null) {
            return new byte[0];
        }
        if (data.length > Math.max(1, maxOutputBytes)) {
            return null;
        }
        return data.clone();
    }
}
