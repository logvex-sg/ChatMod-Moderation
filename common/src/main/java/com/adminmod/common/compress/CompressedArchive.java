package com.adminmod.common.compress;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The on-disk container for one compressed blob.
 *
 * <p>Layout (little-endian):
 *
 * <pre>
 *   offset  size  field
 *   0       4     magic "ADAR"
 *   4       1     format version
 *   5       1     codec id
 *   6       1     flags (bit 0: a checksum is present)
 *   7       1     compression level actually used
 *   8       4     uncompressed length
 *   12      4     CRC32C of the uncompressed bytes
 *   16      ..    the compressed payload
 * </pre>
 *
 * <p>The uncompressed length and the checksum are stored uncompressed so corruption is detected
 * before any allocation sized by the header is trusted. {@link #encode} round-trips the data once
 * and refuses to produce an archive that does not decode back to the input, which is the guarantee
 * that lets a caller delete an original only after its replacement is written.
 */
public final class CompressedArchive {

    /** Magic bytes at the start of every archive. */
    static final byte[] MAGIC = {'A', 'D', 'A', 'R'};

    /** Current container version. A reader accepts this and every earlier version it knows. */
    public static final int FORMAT_VERSION = 1;

    /** Fixed header length in bytes. */
    public static final int HEADER_LENGTH = 16;

    private static final int FLAG_CHECKSUM = 0x01;

    private CompressedArchive() {
    }

    /**
     * Compresses {@code data} into an archive and verifies it decodes back exactly.
     *
     * @param codec the algorithm to use
     * @param level the requested level
     * @param data  the uncompressed bytes
     * @return the archive bytes
     * @throws CompressionException when the round-trip does not reproduce the input
     */
    public static byte[] encode(CompressionCodec codec, int level, byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        int effectiveLevel = codec.clampLevel(level);
        byte[] payload = codec.compress(data, effectiveLevel);
        // Validate before writing: a codec bug must not be persisted as a "successful" archive.
        byte[] check = codec.decompress(payload, Math.max(1, data.length + 1));
        if (check == null || !java.util.Arrays.equals(check, data)) {
            throw new CompressionException("round-trip validation failed for " + codec.id());
        }
        long crc = Checksum.of(data);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC);
        buffer.put((byte) FORMAT_VERSION);
        buffer.put((byte) codec.id().id());
        buffer.put((byte) FLAG_CHECKSUM);
        buffer.put((byte) effectiveLevel);
        buffer.putInt(data.length);
        buffer.putInt((int) crc);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * Parses and verifies an archive.
     *
     * @param archive      the archive bytes
     * @param maxOutputBytes a hard cap on the decompressed size
     * @return the decoded result, or {@code null} when the archive is corrupt, truncated, written
     *         by a newer format or larger than the cap
     */
    public static Decoded decode(byte[] archive, int maxOutputBytes) {
        if (archive == null || archive.length < HEADER_LENGTH) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            return null;
        }
        int version = buffer.get() & 0xFF;
        if (version > FORMAT_VERSION) {
            // A newer build wrote this; refuse rather than misread it.
            return null;
        }
        CodecId codecId;
        try {
            codecId = CodecId.byId(buffer.get() & 0xFF);
        } catch (CompressionException e) {
            return null;
        }
        int flags = buffer.get() & 0xFF;
        int level = buffer.get() & 0xFF;
        int uncompressedLength = buffer.getInt();
        long storedChecksum = buffer.getInt() & 0xFFFF_FFFFL;
        if (uncompressedLength < 0 || uncompressedLength > Math.max(1, maxOutputBytes)) {
            return null;
        }
        byte[] payload = new byte[archive.length - HEADER_LENGTH];
        buffer.get(payload);

        byte[] data = CompressionRegistry.codec(codecId).decompress(payload, Math.max(1, maxOutputBytes));
        if (data == null) {
            return null;
        }
        if (data.length != uncompressedLength) {
            return null;
        }
        if ((flags & FLAG_CHECKSUM) != 0 && Checksum.of(data) != storedChecksum) {
            return null;
        }
        return new Decoded(codecId, level, data);
    }

    /** Whether {@code archive} decodes and passes its checksum. */
    public static boolean verify(byte[] archive, int maxOutputBytes) {
        return decode(archive, maxOutputBytes) != null;
    }

    /** Reads the codec id from an archive header without decompressing, for statistics. */
    public static CodecId peekCodec(byte[] archive) {
        if (archive == null || archive.length < HEADER_LENGTH) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            return null;
        }
        buffer.get();
        try {
            return CodecId.byId(buffer.get() & 0xFF);
        } catch (CompressionException e) {
            return null;
        }
    }

    /** A successfully decoded archive. */
    public record Decoded(CodecId codec, int level, byte[] data) {
    }
}
