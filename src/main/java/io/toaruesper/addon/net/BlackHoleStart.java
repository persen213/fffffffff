package io.toaruesper.addon.net;

import net.minecraft.network.FriendlyByteBuf;

/** Server to client: a black hole opened. Only what is needed to draw it. */
public record BlackHoleStart(int id, double x, double y, double z, float radius, int duration) {
    public static void encode(BlackHoleStart m, FriendlyByteBuf b) {
        b.writeVarInt(m.id);
        b.writeDouble(m.x);
        b.writeDouble(m.y);
        b.writeDouble(m.z);
        b.writeFloat(m.radius);
        b.writeVarInt(m.duration);
    }

    public static BlackHoleStart decode(FriendlyByteBuf b) {
        return new BlackHoleStart(b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readVarInt());
    }
}
