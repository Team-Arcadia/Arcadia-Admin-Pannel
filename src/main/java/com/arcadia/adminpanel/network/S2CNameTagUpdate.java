package com.arcadia.adminpanel.network;

import com.arcadia.adminpanel.util.NameTagStyle;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: an incremental change to one player's name-tag state. Broadcast to all online
 * players when an admin changes a style or flips a player's hide-exemption, so floating tags update
 * live without a full re-sync.
 *
 * <p>{@code hasStyle == false} means "clear this player's style back to vanilla". {@code exempt}
 * carries the player's current hide-exemption so a single packet keeps both pieces of per-player
 * state in lock-step on the client.</p>
 */
public record S2CNameTagUpdate(
        UUID uuid,
        boolean hasStyle,
        NameTagStyle style,   // valid only when hasStyle == true
        boolean exempt,
        boolean forceHidden
) implements CustomPacketPayload {

    public static final Type<S2CNameTagUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "nametag_update"));

    public static final StreamCodec<FriendlyByteBuf, S2CNameTagUpdate> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.uuid);
                buf.writeBoolean(pkt.hasStyle);
                if (pkt.hasStyle) pkt.style.write(buf);
                buf.writeBoolean(pkt.exempt);
                buf.writeBoolean(pkt.forceHidden);
            },
            buf -> {
                UUID u = buf.readUUID();
                boolean has = buf.readBoolean();
                NameTagStyle st = has ? NameTagStyle.read(buf) : null;
                boolean exempt = buf.readBoolean();
                boolean forceHidden = buf.readBoolean();
                return new S2CNameTagUpdate(u, has, st, exempt, forceHidden);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.arcadia.adminpanel.client.ClientNameTagState.applyUpdate(uuid, hasStyle, style, exempt, forceHidden));
    }
}
