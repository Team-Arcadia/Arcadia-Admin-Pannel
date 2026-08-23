package com.arcadia.adminpanel.network;

import com.arcadia.adminpanel.util.DisguiseManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server to client: the complete mob-disguise map. Sent on login so a client knows which players to
 * render as a mob, which type, and with which presentation options. Per-player
 * {@code S2CDisguiseUpdate} packets handle incremental changes afterwards; this establishes the
 * baseline.
 *
 * @author vyrriox
 */
public record S2CDisguiseSync(Map<UUID, DisguiseManager.DisguiseData> disguises)
        implements CustomPacketPayload {

    public static final Type<S2CDisguiseSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "disguise_sync"));

    /** Hard ceiling on entries the decoder reads: defends against a malformed packet. */
    private static final int MAX_ENTRIES = 4096;

    public static final StreamCodec<FriendlyByteBuf, S2CDisguiseSync> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.disguises.size());
                for (Map.Entry<UUID, DisguiseManager.DisguiseData> e : pkt.disguises.entrySet()) {
                    buf.writeUUID(e.getKey());
                    buf.writeResourceLocation(e.getValue().type());
                    buf.writeBoolean(e.getValue().baby());
                    buf.writeFloat(e.getValue().scale());
                    buf.writeBoolean(e.getValue().showMobName());
                }
            },
            buf -> {
                int n = Math.min(Math.max(0, buf.readVarInt()), MAX_ENTRIES);
                Map<UUID, DisguiseManager.DisguiseData> map = new HashMap<>(Math.max(4, n));
                for (int i = 0; i < n; i++) {
                    UUID u = buf.readUUID();
                    ResourceLocation type = buf.readResourceLocation();
                    boolean baby = buf.readBoolean();
                    float scale = buf.readFloat();
                    boolean showName = buf.readBoolean();
                    map.put(u, new DisguiseManager.DisguiseData(type, baby,
                            DisguiseManager.clampScale(scale), showName));
                }
                return new S2CDisguiseSync(map);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.arcadia.adminpanel.client.ClientDisguiseState.applyFullSync(disguises));
    }
}
