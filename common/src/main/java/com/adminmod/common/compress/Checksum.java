package com.adminmod.common.compress;

import java.util.zip.CRC32C;

/**
 * CRC32C checksums for archive integrity.
 *
 * <p>CRC32C (Castagnoli) is used rather than {@link java.util.zip.CRC32} because it is the
 * hardware-accelerated variant on modern CPUs and it detects the common burst errors a truncated
 * write produces. It is not a cryptographic hash and is not used as one: its job is to catch a
 * half-written or bit-rotted archive, not a deliberate forgery.
 */
public final class Checksum {

    private Checksum() {
    }

    /** @return the CRC32C of {@code data}, as an unsigned 32-bit value in a long. */
    public static long of(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    /** @return the CRC32C of a slice, so a caller can checksum a region without copying it. */
    public static long of(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return crc.getValue();
    }
}
