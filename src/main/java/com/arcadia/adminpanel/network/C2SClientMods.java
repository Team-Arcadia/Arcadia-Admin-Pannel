package com.arcadia.adminpanel.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client to server: the mod ids this client is running.
 *
 * <p>Sent once, shortly after login, by clients that have this mod installed. The server records it
 * so staff can see what a player is running and so blacklisted ids raise an alert.</p>
 *
 * <p><b>Untrusted by construction.</b> This is a self-report from a client, so the payload is capped
 * on the wire ({@link #MAX_ENTRIES} ids, {@link #MAX_ID_LENGTH} characters each) before anything is
 * allocated from it, and the registry that consumes it treats the content as a claim rather than a
 * fact. A modified client can lie; the cap only guarantees that lying cannot cost the server
 * memory.</p>
 *
 * @author vyrriox
 */
public record C2SClientMods(List<String> mods) implements CustomPacketPayload {

    /** Hard wire limits. A vanilla-sized modpack sits an order of magnitude below both. */
    public static final int MAX_ENTRIES = 2000;
    public static final int MAX_ID_LENGTH = 128;

    public static final Type<C2SClientMods> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("arcadiaadminpanel", "client_mods"));

    public static final StreamCodec<FriendlyByteBuf, C2SClientMods> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                int count = Math.min(pkt.mods.size(), MAX_ENTRIES);
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    buf.writeUtf(pkt.mods.get(i), MAX_ID_LENGTH);
                }
            },
            buf -> {
                int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
                List<String> out = new ArrayList<>(Math.min(count, 256));
                for (int i = 0; i < count; i++) {
                    out.add(buf.readUtf(MAX_ID_LENGTH));
                }
                return new C2SClientMods(out);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                com.arcadia.adminpanel.util.ClientModsRegistry.accept(sp, mods);
            }
        });
    }
}
