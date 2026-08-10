package com.arcadia.adminpanel.network;

import com.arcadia.adminpanel.util.DisguiseManager;
import com.arcadia.adminpanel.util.NameTagManager;
import com.arcadia.adminpanel.util.NameTagStyle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * Network registration + send helpers for the admin panel's name-tag sync. The admin panel had no
 * networking before this feature; this mirrors the arcadia-lib {@code ArcadiaLibNet} pattern
 * (versioned registrar, {@code playToClient} payloads, {@code PacketDistributor} senders).
 *
 * <p>All payloads here are server → client only — the client is a pure renderer of server-owned
 * state. There is no client → server name-tag packet: every mutation goes through the admin
 * command path (permission-checked server-side), never a client request, so a crafted packet can't
 * change anyone's name. (Matches the hardening lessons from the dashboard-action audit.)</p>
 *
 * @author vyrriox
 */
public final class AdminPanelNet {

    private AdminPanelNet() {}

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                S2CNameTagSync.TYPE,
                S2CNameTagSync.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );

        registrar.playToClient(
                S2CNameTagUpdate.TYPE,
                S2CNameTagUpdate.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );

        registrar.playToClient(
                S2CDisguiseSync.TYPE,
                S2CDisguiseSync.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );

        registrar.playToClient(
                S2CDisguiseUpdate.TYPE,
                S2CDisguiseUpdate.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );

        registrar.playToClient(
                S2CStaffChatState.TYPE,
                S2CStaffChatState.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );
    }

    /** Sends the complete name-tag state to one player (on login or after /reload). */
    public static void sendFullSync(ServerPlayer player) {
        NameTagManager mgr = NameTagManager.getInstance();
        PacketDistributor.sendToPlayer(player, new S2CNameTagSync(
                mgr.isHideEnabled(),
                mgr.occludeThroughTransparent(),
                mgr.isHideAll(),
                mgr.hideMaxDistance(),
                mgr.getAllStyles(),
                mgr.getHideExempt(),
                mgr.getForceHidden()
        ));
    }

    /**
     * Broadcasts one player's current style + exemption + force-hidden flag to every online client.
     * {@code style} may be {@code null} (meaning "cleared back to vanilla").
     */
    public static void broadcastNameTagUpdate(MinecraftServer server, UUID uuid, NameTagStyle style) {
        boolean exempt = NameTagManager.getInstance().isHideExempt(uuid);
        boolean forceHidden = NameTagManager.getInstance().isForceHidden(uuid);
        S2CNameTagUpdate pkt = new S2CNameTagUpdate(uuid, style != null, style, exempt, forceHidden);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, pkt);
        }
    }

    // ── Disguise senders ──────────────────────────────────────────────────────

    /** Sends the complete disguise map to one player (on login or after /reload). */
    public static void sendDisguiseFullSync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new S2CDisguiseSync(DisguiseManager.getInstance().getAll()));
    }

    /**
     * Broadcasts one player's disguise to every online client. {@code entityType} may be {@code null}
     * (meaning "disguise cleared, back to the normal player model").
     */
    public static void broadcastDisguiseUpdate(MinecraftServer server, UUID uuid,
                                               net.minecraft.resources.ResourceLocation entityType) {
        S2CDisguiseUpdate pkt = new S2CDisguiseUpdate(uuid, entityType != null, entityType);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(p, pkt);
        }
    }

    // ── Staff chat ────────────────────────────────────────────────────────────

    /**
     * Tells one player whether staff-chat mode is on for them, so their client can route chat lines
     * through the staff-chat command instead of the public chat pipeline (see
     * {@code StaffChatClientHandler}). Sent on login and on every toggle.
     */
    public static void sendStaffChatState(ServerPlayer player, boolean enabled) {
        PacketDistributor.sendToPlayer(player, new S2CStaffChatState(enabled));
    }
}
