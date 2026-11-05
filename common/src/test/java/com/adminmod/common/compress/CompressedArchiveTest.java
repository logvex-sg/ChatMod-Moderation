package com.adminmod.common.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The archive container: header layout, round-trip validation and corruption handling.
 *
 * <p>These are the guarantees the store depends on: an encoded archive decodes back to exactly its
 * input, a corrupted one is refused rather than returned, and a header that claims a huge size is
 * rejected before any allocation is sized from it.
 */
class CompressedArchiveTest {

    private static final byte[] SAMPLE = "audit event line, repeated to give the codec something to do\n"
            .repeat(200).getBytes(StandardCharsets.UTF_8);

    @Test
    void encodeThenDecodeReproducesTheInput() {
        for (CodecId id : new CodecId[] {CodecId.GZIP, CodecId.ZSTD, CodecId.NONE}) {
            CompressionCodec codec = CompressionRegistry.resolve(id);
            byte[] archive = CompressedArchive.encode(codec, 3, SAMPLE);
            CompressedArchive.Decoded decoded = CompressedArchive.decode(archive, SAMPLE.length + 16);
            assertNotNull(decoded, "codec " + id + " must decode");
            assertArrayEquals(SAMPLE, decoded.data(), "codec " + id + " must round trip exactly");
        }
    }

    @Test
    void headerCarriesTheCodecAndUncompressedLength() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 6, SAMPLE);
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(CompressedArchive.MAGIC, magic);
        assertEquals(CompressedArchive.FORMAT_VERSION, buffer.get() & 0xFF);
        assertEquals(CodecId.GZIP.id(), buffer.get() & 0xFF);
        buffer.get(); // flags
        buffer.get(); // level
        assertEquals(SAMPLE.length, buffer.getInt());
        assertEquals(Checksum.of(SAMPLE), buffer.getInt() & 0xFFFF_FFFFL);
    }

    @Test
    void peekCodecReadsTheAlgorithmWithoutDecompressing() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        assertEquals(CodecId.GZIP, CompressedArchive.peekCodec(archive));
    }

    @Test
    void aFlippedByteInThePayloadIsDetected() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        archive[archive.length - 1] ^= 0x01;
        // GZIP has its own trailer check, so either the codec or the checksum refuses it.
        assertNull(CompressedArchive.decode(archive, SAMPLE.length + 16));
    }

    @Test
    void aTruncatedArchiveIsRefused() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        byte[] truncated = java.util.Arrays.copyOf(archive, archive.length - 8);
        assertNull(CompressedArchive.decode(truncated, SAMPLE.length + 16));
    }

    @Test
    void aHeaderClaimingTooMuchIsRefusedWithoutAllocating() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        // Rewrite the uncompressed length to something enormous.
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(8, Integer.MAX_VALUE - 8);
        assertNull(CompressedArchive.decode(archive, 1_024));
    }

    @Test
    void aFileWrittenByANewerFormatIsRefused() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        archive[4] = (byte) (CompressedArchive.FORMAT_VERSION + 1);
        assertNull(CompressedArchive.decode(archive, SAMPLE.length + 16));
    }

    @Test
    void randomBytesAreNotMistakenForAnArchive() {
        assertNull(CompressedArchive.decode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, 1_024));
        assertNull(CompressedArchive.decode(new byte[0], 1_024));
        assertNull(CompressedArchive.decode(null, 1_024));
    }

    @Test
    void verifyMatchesDecode() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, SAMPLE);
        assertTrue(CompressedArchive.verify(archive, SAMPLE.length + 16));
        archive[archive.length - 1] ^= 0x02;
        assertFalse(CompressedArchive.verify(archive, SAMPLE.length + 16));
    }

    @Test
    void encodeRefusesToProduceAnArchiveThatDoesNotRoundTrip() {
        // A codec that mangles its input must be caught before anything is written.
        CompressionCodec broken = new CompressionCodec() {
            @Override
            public CodecId id() {
                return CodecId.NONE;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public byte[] compress(byte[] data, int level) {
                return data.clone();
            }

            @Override
            public byte[] decompress(byte[] data, int maxOutputBytes) {
                return new byte[data.length]; // wrong content
            }
        };
        assertThrows(CompressionException.class, () -> CompressedArchive.encode(broken, 0, SAMPLE));
    }

    @Test
    void emptyInputRoundTrips() {
        byte[] archive = CompressedArchive.encode(new GzipCodec(), 3, new byte[0]);
        CompressedArchive.Decoded decoded = CompressedArchive.decode(archive, 1_024);
        assertNotNull(decoded);
        assertEquals(0, decoded.data().length);
    }
}
