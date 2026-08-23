package com.arcadia.adminpanel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: whether this player is currently frozen for a screenshare.
 *
 * <p>The freeze itself is enforced entirely server-side; this packet exists so the client can draw
 * the overlay that makes the situation unmistakable. A player who is simply stuck reports a bug; a
 * player looking at a dimmed screen that says a moderator has frozen them answers the moderator.</p>
 *
 * <p>Sent on freeze, on release, and on login for a player who reconnects while frozen. Purely
 * cosmetic: a client without this mod is still frozen, it just gets the chat and title messages
 * instead of the overlay.</p>
 *
 * @author vyrriox
 */
public record S2CFreezeState(boolean frozen) implements CustomPacketPayload {

    public static final Type<S2CFreezeState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "freeze_state"));

    public static final StreamCodec<FriendlyByteBuf, S2CFreezeState> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.frozen),
            buf -> new S2CFreezeState(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.arcadia.adminpanel.client.ClientFreezeState.set(frozen));
    }
}
