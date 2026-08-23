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

        // 1.3.0 payloads are optional so a client or server that predates them still connects. The
        // two features they carry (the freeze overlay and the client mod report) degrade to "no
        // overlay" and "no report" rather than to a refused handshake.
        PayloadRegistrar optional = registrar.optional();

        optional.playToClient(
                S2CFreezeState.TYPE,
                S2CFreezeState.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );

        optional.playToServer(
                C2SClientMods.TYPE,
                C2SClientMods.STREAM_CODEC,
                (p, ctx) -> p.handle(ctx)
        );
    }

    // ── Freeze ────────────────────────────────────────────────────────────────

    /**
     * Tells one player whether they are frozen, so their client can draw the screenshare overlay.
     * Skipped silently for a client that did not register the optional channel.
     */
    public static void sendFreezeState(ServerPlayer player, boolean frozen) {
        if (!net.neoforged.neoforge.network.registration.NetworkRegistry
                .hasChannel(player.connection, S2CFreezeState.TYPE.id())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new S2CFreezeState(frozen));
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
     * Broadcasts one player's disguise to every online client. {@code data} may be {@code null}
     * (meaning "disguise cleared, back to the normal player model").
     */
    public static void broadcastDisguiseUpdate(MinecraftServer server, UUID uuid,
                                               DisguiseManager.DisguiseData data) {
        S2CDisguiseUpdate pkt = data == null
                ? S2CDisguiseUpdate.cleared(uuid)
                : S2CDisguiseUpdate.of(uuid, data);
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
