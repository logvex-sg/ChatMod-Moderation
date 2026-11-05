package com.adminmod.common.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Codec round-trips and the guarantees the archive layer relies on.
 *
 * <p>These tests use real data and real codecs — the GZIP one from the JDK and the Zstandard one
 * from the native library on the test runtime classpath — rather than mocks, because the whole point
 * of the layer is that the bytes survive a round trip.
 */
class CompressionCodecTest {

    private static final byte[] SAMPLE = "The quick brown fox jumps over the lazy dog. ".repeat(400)
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void gzipRoundTripsAndShrinksRepetitiveData() {
        GzipCodec codec = new GzipCodec();
        assertTrue(codec.available());
        byte[] compressed = codec.compress(SAMPLE, 6);
        assertTrue(compressed.length < SAMPLE.length, "repetitive data should compress");
        assertArrayEquals(SAMPLE, codec.decompress(compressed, SAMPLE.length + 16));
    }

    @Test
    void gzipLevelsAreClampedAndStillRoundTrip() {
        GzipCodec codec = new GzipCodec();
        for (int level : new int[] {-5, 0, 1, 9, 99}) {
            byte[] compressed = codec.compress(SAMPLE, level);
            assertArrayEquals(SAMPLE, codec.decompress(compressed, SAMPLE.length + 16),
                    "level " + level + " must round trip");
        }
    }

    @Test
    void gzipReturnsNullForCorruptInput() {
        GzipCodec codec = new GzipCodec();
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8};
        assertNull(codec.decompress(garbage, 1_024));
    }

    @Test
    void gzipRefusesOutputLargerThanTheCap() {
        GzipCodec codec = new GzipCodec();
        byte[] compressed = codec.compress(SAMPLE, 6);
        // A tiny cap stands in for a corrupt header claiming a huge size.
        assertNull(codec.decompress(compressed, 16));
    }

    @Test
    void zstdRoundTripsWhenAvailable() {
        ZstdCodec codec = new ZstdCodec();
        if (!codec.available()) {
            // A platform without the native library is a supported configuration; GZIP covers it.
            return;
        }
        byte[] compressed = codec.compress(SAMPLE, 3);
        assertTrue(compressed.length < SAMPLE.length);
        assertArrayEquals(SAMPLE, codec.decompress(compressed, SAMPLE.length + 16));
    }

    @Test
    void zstdRefusesOutputLargerThanTheCap() {
        ZstdCodec codec = new ZstdCodec();
        if (!codec.available()) {
            return;
        }
        byte[] compressed = codec.compress(SAMPLE, 3);
        assertNull(codec.decompress(compressed, 16));
    }

    @Test
    void storedCodecRoundTripsExactly() {
        StoredCodec codec = new StoredCodec();
        byte[] compressed = codec.compress(SAMPLE, 0);
        assertArrayEquals(SAMPLE, compressed);
        assertArrayEquals(SAMPLE, codec.decompress(compressed, SAMPLE.length + 1));
    }

    @Test
    void registryAlwaysResolvesToAnAvailableCodec() {
        for (CodecId id : CodecId.values()) {
            CompressionCodec resolved = CompressionRegistry.resolve(id);
            assertNotNull(resolved);
            assertTrue(resolved.available(), "the resolved codec must be usable: " + id);
        }
    }

    @Test
    void registryParsesNamesAndFallsBack() {
        assertEquals(CodecId.ZSTD, CompressionRegistry.parse("zstd", CodecId.GZIP));
        assertEquals(CodecId.ZSTD, CompressionRegistry.parse("Zstandard", CodecId.GZIP));
        assertEquals(CodecId.GZIP, CompressionRegistry.parse("gzip", CodecId.ZSTD));
        assertEquals(CodecId.NONE, CompressionRegistry.parse("off", CodecId.GZIP));
        assertEquals(CodecId.GZIP, CompressionRegistry.parse("brotli", CodecId.GZIP));
        assertEquals(CodecId.GZIP, CompressionRegistry.parse(null, CodecId.GZIP));
    }

    @Test
    void bestAvailableIsAlwaysUsable() {
        assertTrue(CompressionRegistry.codec(CompressionRegistry.bestAvailable()).available());
    }

    @Test
    void randomDataRoundTripsEvenWhenItDoesNotShrink() {
        byte[] random = new byte[4_096];
        new Random(42).nextBytes(random);
        for (CompressionCodec codec : new CompressionCodec[] {new GzipCodec(), new StoredCodec()}) {
            byte[] compressed = codec.compress(random, 3);
            assertArrayEquals(random, codec.decompress(compressed, random.length + 16),
                    codec.id() + " must round trip incompressible data");
        }
    }

    @Test
    void checksumDetectsASingleBitFlip() {
        byte[] data = SAMPLE.clone();
        long before = Checksum.of(data);
        data[data.length / 2] ^= 0x01;
        assertFalse(before == Checksum.of(data), "a flipped bit must change the checksum");
    }

    @Test
    void unknownCodecIdThrows() {
        assertThrows(CompressionException.class, () -> CodecId.byId(99));
    }
}
