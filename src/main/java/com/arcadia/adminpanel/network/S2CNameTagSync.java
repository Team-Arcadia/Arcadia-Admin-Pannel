package com.arcadia.adminpanel.network;

import com.arcadia.adminpanel.util.NameTagStyle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server → Client: the complete name-tag state. Sent to a player on login so their client knows
 * (a) the global "hide names behind walls" switch + whether transparent blocks occlude, (b) every
 * styled player's {@link NameTagStyle}, and (c) the set of players exempt from hiding.
 *
 * <p>Per-player {@code S2CNameTagUpdate} packets handle incremental changes afterwards; this packet
 * establishes the baseline. Sizes are bounded: the style list is capped at the online + styled
 * player count, each style at {@link NameTagStyle#MAX_COLORS} colours.</p>
 */
public record S2CNameTagSync(
        boolean hideEnabled,
        boolean occludeTransparent,
        Map<UUID, NameTagStyle> styles,
        Set<UUID> hideExempt
) implements CustomPacketPayload {

    public static final Type<S2CNameTagSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "nametag_sync"));

    /** Hard ceiling on entries the decoder will read — defends against a malformed/MITM packet
     *  claiming a huge count and forcing a billion-iteration read loop (buffer-exhaustion DoS). Far
     *  above any real player population. */
    private static final int MAX_ENTRIES = 4096;

    public static final StreamCodec<FriendlyByteBuf, S2CNameTagSync> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeBoolean(pkt.hideEnabled);
                buf.writeBoolean(pkt.occludeTransparent);
                buf.writeVarInt(pkt.styles.size());
                for (Map.Entry<UUID, NameTagStyle> e : pkt.styles.entrySet()) {
                    buf.writeUUID(e.getKey());
                    e.getValue().write(buf);
                }
                buf.writeVarInt(pkt.hideExempt.size());
                for (UUID u : pkt.hideExempt) buf.writeUUID(u);
            },
            buf -> {
                boolean hide = buf.readBoolean();
                boolean trans = buf.readBoolean();
                int n = Math.min(Math.max(0, buf.readVarInt()), MAX_ENTRIES);
                Map<UUID, NameTagStyle> styles = new HashMap<>(Math.max(4, n));
                for (int i = 0; i < n; i++) {
                    UUID u = buf.readUUID();
                    styles.put(u, NameTagStyle.read(buf));
                }
                int m = Math.min(Math.max(0, buf.readVarInt()), MAX_ENTRIES);
                Set<UUID> exempt = new HashSet<>(Math.max(4, m));
                for (int i = 0; i < m; i++) exempt.add(buf.readUUID());
                return new S2CNameTagSync(hide, trans, styles, exempt);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.arcadia.adminpanel.client.ClientNameTagState.applyFullSync(
                        hideEnabled, occludeTransparent, styles, hideExempt));
    }
}
