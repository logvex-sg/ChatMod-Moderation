package com.adminmod.client.net;

import com.adminmod.common.net.AdminProtocol;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Fabric payload wrapper carrying an encoded {@link com.adminmod.common.net.Envelope}.
 *
 * <p>This record exists in both the server and the client module rather than in {@code common},
 * because {@code common} is deliberately free of Minecraft classes so the whole wire format can
 * be unit tested on a plain JVM. Both copies must use the same channel id and the same codec;
 * {@link AdminProtocol#CHANNEL} is the shared source of truth.
 */
public record AdminPayload(byte[] data) implements CustomPayload {

    /** Hard ceiling on a single frame, matching the reader's blob limit. */
    public static final int MAX_FRAME = 1 << 20;

    public static final CustomPayload.Id<AdminPayload> ID =
            new CustomPayload.Id<>(Identifier.of(AdminProtocol.CHANNEL));

    public static final PacketCodec<RegistryByteBuf, AdminPayload> CODEC = new PacketCodec<>() {
        @Override
        public AdminPayload decode(RegistryByteBuf buf) {
            int length = buf.readVarInt();
            if (length < 0 || length > MAX_FRAME) {
                throw new DecoderException("adminmod frame length out of range: " + length);
            }
            byte[] data = new byte[length];
            buf.readBytes(data);
            return new AdminPayload(data);
        }

        @Override
        public void encode(RegistryByteBuf buf, AdminPayload value) {
            byte[] data = value.data();
            if (data.length > MAX_FRAME) {
                throw new DecoderException("adminmod frame too large: " + data.length);
            }
            buf.writeVarInt(data.length);
            buf.writeBytes(data);
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
