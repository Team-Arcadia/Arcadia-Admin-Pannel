package com.arcadia.adminpanel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: whether this player currently has staff-chat mode on.
 *
 * <p>The client needs to know because staff-chat mode is applied <em>before</em> the message leaves
 * the client: a toggled staff member's chat line is rewritten into
 * {@code /arcadia_adminpanel staffchat <message>} instead of being sent as a normal chat message.
 * Cancelling {@code ServerChatEvent} server-side is not enough — a Discord bridge that hooks the
 * chat pipeline ahead of us (or reads cancelled events) still relays the line. A message that was
 * never a chat message in the first place cannot leak.</p>
 *
 * <p>Sent on login (always {@code false} — the toggle is cleared on disconnect) and on every
 * {@code /arcadia_adminpanel stafftoggle}.</p>
 *
 * @author vyrriox
 */
public record S2CStaffChatState(boolean enabled) implements CustomPacketPayload {

    public static final Type<S2CStaffChatState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "staff_chat_state"));

    public static final StreamCodec<FriendlyByteBuf, S2CStaffChatState> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.enabled),
            buf -> new S2CStaffChatState(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.arcadia.adminpanel.client.ClientStaffChatState.set(enabled));
    }
}
