package com.arcadia.adminpanel.network;

import com.arcadia.adminpanel.util.DisguiseManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server to client: an incremental change to one player's disguise. Broadcast to every online player
 * when an admin disguises, restyles or un-disguises someone, so the model swap is live without a
 * full re-sync.
 *
 * <p>{@code disguised == false} means "clear this player's disguise back to their normal model"; the
 * remaining fields are then absent from the wire entirely rather than sent as defaults.</p>
 *
 * @author vyrriox
 */
public record S2CDisguiseUpdate(
        UUID uuid,
        boolean disguised,
        ResourceLocation entityType,  // the fields below are valid only when disguised == true
        boolean baby,
        float scale,
        boolean showMobName
) implements CustomPacketPayload {

    /** Convenience for the clear case. */
    public static S2CDisguiseUpdate cleared(UUID uuid) {
        return new S2CDisguiseUpdate(uuid, false, null, false, 1.0F, false);
    }

    /** Convenience for the set case. */
    public static S2CDisguiseUpdate of(UUID uuid, DisguiseManager.DisguiseData data) {
        return new S2CDisguiseUpdate(uuid, true, data.type(), data.baby(), data.scale(),
                data.showMobName());
    }

    public static final Type<S2CDisguiseUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "disguise_update"));

    public static final StreamCodec<FriendlyByteBuf, S2CDisguiseUpdate> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.uuid);
                buf.writeBoolean(pkt.disguised);
                if (pkt.disguised) {
                    buf.writeResourceLocation(pkt.entityType);
                    buf.writeBoolean(pkt.baby);
                    buf.writeFloat(pkt.scale);
                    buf.writeBoolean(pkt.showMobName);
                }
            },
            buf -> {
                UUID u = buf.readUUID();
                boolean disguised = buf.readBoolean();
                if (!disguised) return cleared(u);
                ResourceLocation type = buf.readResourceLocation();
                boolean baby = buf.readBoolean();
                float scale = buf.readFloat();
                boolean showName = buf.readBoolean();
                return new S2CDisguiseUpdate(u, true, type, baby, scale, showName);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    @OnlyIn(Dist.CLIENT)
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.arcadia.adminpanel.client.ClientDisguiseState
                .applyUpdate(uuid, disguised, entityType, baby, scale, showMobName));
    }
}
