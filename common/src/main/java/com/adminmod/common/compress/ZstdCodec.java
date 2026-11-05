package com.adminmod.common.compress;

import java.lang.reflect.Method;

/**
 * Zstandard codec, backed by {@code com.github.luben:zstd-jni}.
 *
 * <p>The dependency is referenced reflectively for two reasons. First, the class lives in the
 * shared module that is folded into both the server and the client jar, and the client never
 * compresses anything, so it must not carry a hard reference to a native library. Second, the
 * native library is loaded lazily on first use and can fail on an unsupported platform; probing
 * reflectively lets {@link #available()} answer honestly and lets the registry fall back to GZIP
 * instead of throwing {@link NoClassDefFoundError} into the server.
 *
 * <p>The native library is extracted to the JVM temporary directory by zstd-jni itself on first
 * load; nothing here manages that.
 */
public final class ZstdCodec implements CompressionCodec {

    private static final String ZSTD_CLASS = "com.github.luben.zstd.Zstd";

    private final boolean available;
    private final Method compress;
    private final Method decompress;
    private final Method decompressedSize;

    public ZstdCodec() {
        Method compressMethod = null;
        Method decompressMethod = null;
        Method sizeMethod = null;
        boolean ok = false;
        try {
            Class<?> zstd = Class.forName(ZSTD_CLASS);
            compressMethod = zstd.getMethod("compress", byte[].class, int.class);
            decompressMethod = zstd.getMethod("decompress", byte[].class, int.class);
            sizeMethod = zstd.getMethod("decompressedSize", byte[].class);
            // Touch the native library through a real call so a missing .so is detected here rather
            // than on the first archive write.
            compressMethod.invoke(null, new byte[] {1, 2, 3, 4}, 1);
            ok = true;
        } catch (Throwable ignored) {
            // Any failure — class absent, native load refused, security manager — means unavailable.
            ok = false;
        }
        this.available = ok;
        this.compress = compressMethod;
        this.decompress = decompressMethod;
        this.decompressedSize = sizeMethod;
    }

    @Override
    public CodecId id() {
        return CodecId.ZSTD;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public int minLevel() {
        return 1;
    }

    @Override
    public int maxLevel() {
        return 19;
    }

    @Override
    public byte[] compress(byte[] data, int level) {
        if (!available) {
            throw new CompressionException("zstd is not available");
        }
        try {
            return (byte[]) compress.invoke(null, data, clampLevel(level));
        } catch (Throwable e) {
            throw new CompressionException("zstd compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] data, int maxOutputBytes) {
        if (!available) {
            return null;
        }
        try {
            long size = (long) decompressedSize.invoke(null, data);
            if (size < 0 || size > Math.max(1, maxOutputBytes)) {
                return null;
            }
            return (byte[]) decompress.invoke(null, data, (int) size);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The level zstd-jni uses when none is given; exposed so the registry default can match it. */
    public static int defaultLevel() {
        return 3;
    }
}
