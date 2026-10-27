package com.qateralong.rollback;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PanelPayload(String json) implements CustomPacketPayload {
    public static final Type<PanelPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("rollback", "panel"));
    public static final StreamCodec<ByteBuf, PanelPayload> CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(PanelPayload::new, PanelPayload::json);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
