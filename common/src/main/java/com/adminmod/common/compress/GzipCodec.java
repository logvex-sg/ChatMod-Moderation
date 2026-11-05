package com.adminmod.common.compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP codec, always available because it ships with the JDK.
 *
 * <p>It is the compatibility fallback: an archive written on a host where Zstandard could not load
 * is still readable everywhere, and an archive written by an older ADMIN build stays readable after
 * an upgrade.
 */
public final class GzipCodec implements CompressionCodec {

    /** Smallest decompression buffer; also the read chunk for streaming input. */
    private static final int CHUNK = 8_192;

    @Override
    public CodecId id() {
        return CodecId.GZIP;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public int maxLevel() {
        return 9;
    }

    @Override
    public byte[] compress(byte[] data, int level) {
        int effective = clampLevel(level);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, CHUNK) {
            {
                // Set the level on the underlying deflater; the constructor leaves it at default.
                def.setLevel(effective);
            }
        }) {
            gzip.write(data);
        } catch (IOException e) {
            throw new CompressionException("gzip compression failed", e);
        }
        return out.toByteArray();
    }

    @Override
    public byte[] decompress(byte[] data, int maxOutputBytes) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        int cap = Math.max(1, maxOutputBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, CHUNK * 4));
        byte[] buffer = new byte[CHUNK];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                if (out.size() + read > cap) {
                    // A corrupt or hostile header claims more than the caller allows.
                    return null;
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            return null;
        }
        return out.toByteArray();
    }
}
