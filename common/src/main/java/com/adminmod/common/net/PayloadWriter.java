package com.adminmod.common.net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Mirror of {@link PayloadReader} used to build the remote administration wire format. */
public final class PayloadWriter {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(buffer);

    public PayloadWriter writeVarInt(int value) {
        try {
            while ((value & 0xFFFFFF80) != 0) {
                out.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.writeByte(value);
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public PayloadWriter writeVarLong(long value) {
        try {
            while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
                out.writeByte((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.writeByte((int) value);
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public PayloadWriter writeInt(int value) {
        try {
            out.writeInt(value);
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public PayloadWriter writeLong(long value) {
        try {
            out.writeLong(value);
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public PayloadWriter writeBoolean(boolean value) {
        try {
            out.writeByte(value ? 1 : 0);
            return this;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public PayloadWriter writeString(String value) {
        String v = value == null ? "" : value;
        byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PayloadReader.MAX_STRING) {
            throw new ProtocolException("string too long: " + bytes.length);
        }
        writeVarInt(bytes.length);
        writeRaw(bytes);
        return this;
    }

    public PayloadWriter writeUuid(UUID value) {
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
        return this;
    }

    public PayloadWriter writeBytes(byte[] value) {
        if (value.length > PayloadReader.MAX_BLOB) {
            throw new ProtocolException("blob too long: " + value.length);
        }
        writeVarInt(value.length);
        writeRaw(value);
        return this;
    }

    private void writeRaw(byte[] bytes) {
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] toByteArray() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return buffer.toByteArray();
    }
}
