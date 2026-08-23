package com.arcadia.adminpanel.util;

import com.arcadia.lib.text.TextFormatter;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Bans with a duration, a reason and a list you can actually read.
 *
 * <p>Before 1.3.0 the panel had one ban: permanent, no reason prompt, no way to review who was
 * banned without reading {@code banned-players.json}. This adds the two things that were missing
 * and keeps vanilla as the enforcement layer, because vanilla already checks the ban list at login,
 * already expires temporary entries on its own, and already survives a crash. Reimplementing that
 * would have been three bugs waiting to happen.</p>
 *
 * <p><b>Cross-server.</b> Every ban is also written as a record, so the other servers on the same
 * database learn about it. They apply it at negotiation time, before a slot or a chunk is claimed,
 * and the vanilla list on each server converges to the same content.</p>
 *
 * @author vyrriox
 */
public final class BanManager {

    /** A ban as replicated to the other servers. {@code expiresAt} 0 means permanent. */
    public record BanRecord(String targetName, String reason, String byName,
                            long expiresAt, boolean active) {}

    private static final RecordStore<BanRecord> STORE = new RecordStore<>("ban", BanRecord.class, 4000);

    private BanManager() {}

    // -- Apply ---------------------------------------------------------------

    /**
     * Bans a player, optionally for a limited time.
     *
     * @param minutes 0 for a permanent ban
     * @return the expiry instant, or {@code null} for permanent
     */
    @Nullable
    public static Date ban(ServerPlayer actor, MinecraftServer server, UUID targetId,
                           String targetName, String reason, long minutes) {
        Date expires = minutes > 0
                ? new Date(System.currentTimeMillis() + minutes * 60_000L)
                : null;

        GameProfile profile = new GameProfile(targetId, targetName);
        UserBanList bans = server.getPlayerList().getBans();
        bans.add(new UserBanListEntry(profile, new Date(),
                actor != null ? actor.getName().getString() : "CONSOLE", expires,
                reason == null || reason.isBlank() ? "Banned by an operator" : reason));

        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        if (online != null) online.connection.disconnect(kickMessage(reason, expires));

        if (AdminConfig.get().banSyncEnabled) {
            STORE.append(targetId, new BanRecord(targetName, reason == null ? "" : reason,
                    actor != null ? actor.getName().getString() : "CONSOLE",
                    expires != null ? expires.getTime() : 0L, true));
        }

        AuditManager.record(actor, minutes > 0 ? AdminAction.TEMPBAN : AdminAction.BAN,
                targetId, targetName, reason, minutes > 0 ? minutes * 60_000L : 0L);
        return expires;
    }

    /** Lifts a ban. Returns false when the player was not banned here. */
    public static boolean unban(ServerPlayer actor, MinecraftServer server,
                                UUID targetId, String targetName) {
        GameProfile profile = new GameProfile(targetId, targetName);
        UserBanList bans = server.getPlayerList().getBans();
        boolean was = bans.isBanned(profile);
        bans.remove(profile);

        if (AdminConfig.get().banSyncEnabled) {
            STORE.append(targetId, new BanRecord(targetName, "",
                    actor != null ? actor.getName().getString() : "CONSOLE", 0L, false));
        }
        AuditManager.record(actor, AdminAction.UNBAN, targetId, targetName, "");
        return was;
    }

    // -- Queries -------------------------------------------------------------

    public static boolean isBanned(MinecraftServer server, UUID uuid, String name) {
        return server.getPlayerList().getBans().isBanned(new GameProfile(uuid, name));
    }

    @Nullable
    public static UserBanListEntry entry(MinecraftServer server, UUID uuid, String name) {
        return server.getPlayerList().getBans().get(new GameProfile(uuid, name));
    }

    /**
     * A readable view of the vanilla ban list, newest first.
     *
     * <p>The profile behind an entry is not reachable from outside {@code net.minecraft.server.players},
     * so the name comes from the entry's display name and the UUID is resolved from the panel's own
     * offline cache. That lookup is in-memory: asking the vanilla profile cache would fall through to
     * a blocking Mojang request on a cache miss, on the server thread, once per banned player.</p>
     */
    public static List<BanView> list(MinecraftServer server) {
        List<BanView> out = new ArrayList<>();
        UserBanList bans = server.getPlayerList().getBans();
        for (UserBanListEntry e : bans.getEntries()) {
            String name = e.getDisplayName() != null ? e.getDisplayName().getString() : "?";
            out.add(new BanView(
                    resolveUuid(server, name),
                    name,
                    e.getReason() != null ? e.getReason() : "",
                    e.getSource() != null ? e.getSource() : "",
                    e.getCreated() != null ? e.getCreated().getTime() : 0L,
                    e.getExpires() != null ? e.getExpires().getTime() : 0L,
                    e));
        }
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    /**
     * One row of the ban list menu. {@code expiresAt} 0 means permanent, {@code uuid} may be
     * {@code null} when the name has never been seen by this server.
     */
    public record BanView(@Nullable UUID uuid, String name, String reason, String source,
                          long createdAt, long expiresAt, UserBanListEntry entry) {

        public boolean isPermanent() { return expiresAt <= 0L; }

        public long remainingMs() {
            return expiresAt <= 0L ? -1L : Math.max(0L, expiresAt - System.currentTimeMillis());
        }
    }

    /** Lifts a ban straight off its list entry, for rows whose UUID could not be resolved. */
    public static void unbanEntry(ServerPlayer actor, MinecraftServer server, BanView view) {
        server.getPlayerList().getBans().remove(view.entry());
        if (AdminConfig.get().banSyncEnabled && view.uuid() != null) {
            STORE.append(view.uuid(), new BanRecord(view.name(), "",
                    actor != null ? actor.getName().getString() : "CONSOLE", 0L, false));
        }
        AuditManager.record(actor, AdminAction.UNBAN, view.uuid(), view.name(), "");
    }

    /** In-memory name to UUID lookup. Never performs a network call. */
    @Nullable
    private static UUID resolveUuid(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        for (var summary : OfflinePlayerManager.getInstance().getCache().values()) {
            if (summary.name().equalsIgnoreCase(name)) return summary.uuid();
        }
        return null;
    }

    /** Full ban history for one player, including lifted bans. */
    public static List<RecordStore.Entry<BanRecord>> history(UUID target) {
        return STORE.forSubject(target);
    }

    // -- Cross-server enforcement --------------------------------------------

    /**
     * Applies a ban issued on another server, before the connection gets a slot.
     *
     * @return the kick message when the player must be refused, {@code null} to let them in
     */
    @Nullable
    public static Component checkRemoteBan(MinecraftServer server, GameProfile profile) {
        if (!AdminConfig.get().banSyncEnabled) return null;
        UUID id = profile.getId();
        if (id == null) return null;

        // Newest record wins: an unban appended after a ban lifts it.
        var latest = STORE.latest(id);
        if (latest == null || !latest.payload().active()) return null;

        long expiresAt = latest.payload().expiresAt();
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) return null;

        // Mirror it into the local vanilla list so the next login is refused by vanilla itself and
        // the ban shows up in the ban-list menu on this server too.
        String name = profile.getName() != null ? profile.getName() : latest.payload().targetName();
        UserBanList bans = server.getPlayerList().getBans();
        GameProfile local = new GameProfile(id, name);
        if (!bans.isBanned(local)) {
            bans.add(new UserBanListEntry(local, new Date(latest.createdAt()),
                    latest.payload().byName(),
                    expiresAt > 0 ? new Date(expiresAt) : null,
                    latest.payload().reason()));
        }
        return kickMessage(latest.payload().reason(),
                expiresAt > 0 ? new Date(expiresAt) : null);
    }

    // -- Presentation --------------------------------------------------------

    public static Component kickMessage(@Nullable String reason, @Nullable Date expires) {
        StringBuilder sb = new StringBuilder("§c§lBanned\n");
        if (reason != null && !reason.isBlank()) sb.append("§7").append(reason).append('\n');
        if (expires != null) {
            long remaining = Math.max(0L, expires.getTime() - System.currentTimeMillis());
            sb.append("§eExpires in ").append(TextFormatter.formatMs(remaining));
        } else {
            sb.append("§ePermanent");
        }
        return Component.literal(sb.toString());
    }
}
