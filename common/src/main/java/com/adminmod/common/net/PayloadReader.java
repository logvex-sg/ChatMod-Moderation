package com.adminmod.common.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Small, dependency-free binary writer/reader used for the remote administration wire
 * format. Keeping the format independent of Minecraft classes means the whole protocol can
 * be unit tested on a plain JVM and reused by both distributions.
 */
public final class PayloadReader {
    public static final int MAX_STRING = 32767;
    public static final int MAX_BLOB = 1 << 20;

    private final DataInputStream in;

    public PayloadReader(byte[] data) {
        this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        while (true) {
            int b;
            try {
                b = in.readUnsignedByte();
            } catch (IOException e) {
                throw new ProtocolException("truncated varint");
            }
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 35) {
                throw new ProtocolException("varint too large");
            }
        }
    }

    public long readVarLong() {
        long value = 0;
        int position = 0;
        while (true) {
            int b;
            try {
                b = in.readUnsignedByte();
            } catch (IOException e) {
                throw new ProtocolException("truncated varlong");
            }
            value |= (long) (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 70) {
                throw new ProtocolException("varlong too large");
            }
        }
    }

    public int readInt() {
        try {
            return in.readInt();
        } catch (IOException e) {
            throw new ProtocolException("truncated int");
        }
    }

    public long readLong() {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new ProtocolException("truncated long");
        }
    }

    public boolean readBoolean() {
        try {
            return in.readUnsignedByte() != 0;
        } catch (IOException e) {
            throw new ProtocolException("truncated boolean");
        }
    }

    public String readString() {
        int len = readVarInt();
        if (len < 0 || len > MAX_STRING) {
            throw new ProtocolException("invalid string length " + len);
        }
        byte[] bytes = new byte[len];
        try {
            in.readFully(bytes);
        } catch (IOException e) {
            throw new ProtocolException("truncated string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public byte[] readBytes() {
        int len = readVarInt();
        if (len < 0 || len > MAX_BLOB) {
            throw new ProtocolException("invalid blob length " + len);
        }
        byte[] bytes = new byte[len];
        try {
            in.readFully(bytes);
        } catch (IOException e) {
            throw new ProtocolException("truncated blob");
        }
        return bytes;
    }

    public int remaining() {
        try {
            return in.available();
        } catch (IOException e) {
            return 0;
        }
    }

    public boolean hasRemaining() {
        return remaining() > 0;
    }
}
