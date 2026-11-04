package com.adminmod.common.net;

import java.util.Arrays;

/**
 * Outer frame of every remote administration message.
 *
 * <p>The body is always a single encrypted message: the nonce and timestamp are carried in
 * the clear so the server can reject replays and clock-skewed frames before doing any
 * decryption work, while the authenticated ciphertext binds them so they cannot be tampered
 * with.
 */
public record Envelope(long nonce, long timestamp, byte[] body) {

    public byte[] encode() {
        return new PayloadWriter()
                .writeVarLong(nonce)
                .writeLong(timestamp)
                .writeBytes(body)
                .toByteArray();
    }

    public static Envelope decode(byte[] raw) {
        PayloadReader reader = new PayloadReader(raw);
        long nonce = reader.readVarLong();
        long timestamp = reader.readLong();
        byte[] body = reader.readBytes();
        if (reader.hasRemaining()) {
            throw new ProtocolException("trailing bytes in envelope");
        }
        return new Envelope(nonce, timestamp, body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Envelope other)) {
            return false;
        }
        return nonce == other.nonce && timestamp == other.timestamp && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(nonce) + Long.hashCode(timestamp) + Arrays.hashCode(body);
    }
}
