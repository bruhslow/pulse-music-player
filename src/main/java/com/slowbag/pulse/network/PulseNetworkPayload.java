package com.slowbag.pulse.network;

import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PulseNetworkPayload(String json) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PulseNetworkPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "pulse_packet"));

    public static final StreamCodec<FriendlyByteBuf, PulseNetworkPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PulseNetworkPayload::json, PulseNetworkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
