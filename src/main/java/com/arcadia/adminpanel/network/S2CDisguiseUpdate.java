package com.arcadia.adminpanel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: an incremental change to one player's disguise. Broadcast to all online players
 * when an admin disguises or un-disguises someone, so the model swap is live without a re-sync.
 *
 * <p>{@code disguised == false} means "clear this player's disguise back to their normal model".</p>
 *
 * @author vyrriox
 */
public record S2CDisguiseUpdate(
        UUID uuid,
        boolean disguised,
        ResourceLocation entityType   // valid only when disguised == true
) implements CustomPacketPayload {

    public static final Type<S2CDisguiseUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "disguise_update"));

    public static final StreamCodec<FriendlyByteBuf, S2CDisguiseUpdate> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.uuid);
                buf.writeBoolean(pkt.disguised);
                if (pkt.disguised) buf.writeResourceLocation(pkt.entityType);
            },
            buf -> {
                UUID u = buf.readUUID();
                boolean disguised = buf.readBoolean();
                ResourceLocation type = disguised ? buf.readResourceLocation() : null;
                return new S2CDisguiseUpdate(u, disguised, type);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                com.arcadia.adminpanel.client.ClientDisguiseState.applyUpdate(uuid, disguised, entityType));
    }
}
