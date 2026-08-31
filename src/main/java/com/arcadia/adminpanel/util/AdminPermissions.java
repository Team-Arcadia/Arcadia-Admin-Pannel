package com.arcadia.adminpanel.util;

import com.arcadia.lib.permissions.PermissionService;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Granular permission registry for the admin panel. Each interactive feature has its own node so
 * staff roles can be tuned per-action — e.g. a moderator gets {@code arcadia.adminpanel.warn} and
 * {@code arcadia.adminpanel.mute} but not {@code arcadia.adminpanel.ban}.
 *
 * <p>Two layers of gating use these checks:</p>
 * <ul>
 *   <li><b>Visibility</b> — menu builders skip buttons the viewer doesn't have permission for.</li>
 *   <li><b>Action</b> — every {@code clicked()} handler re-validates before executing the action,
 *       so a packet-crafted click cannot trigger an action that the GUI never rendered.</li>
 * </ul>
 *
 * <p>OP level &gt;= 2 grants every node implicitly so vanilla admins keep "full access" without
 * needing a perm plugin configured. The {@code OPEN} node is the only gate for opening the panel
 * itself — without it, no GUI ever appears.</p>
 *
 * <p>Results are cached per-player for 2 seconds. The panel queries the same nodes dozens of times
 * during a single menu build (one per slot decision) and we don't want to hit the perm backend on
 * every call. Cache is invalidated on logout.</p>
 *
 * @author vyrriox
 */
public enum AdminPermissions {

    /** Open the admin panel at all. Without this, no other node is checked. */
    OPEN("arcadia.adminpanel.open"),
    /** Open the FTB Teams browser sub-menu. */
    TEAMS("arcadia.adminpanel.teams"),
    /** Open the warn list sub-menu. */
    WARN_VIEW("arcadia.adminpanel.warn.view"),
    /** Add or remove warns. */
    WARN_EDIT("arcadia.adminpanel.warn.edit"),
    /** Teleport to / teleport here. */
    TELEPORT("arcadia.adminpanel.teleport"),
    /** Open invsee. */
    INVSEE("arcadia.adminpanel.invsee"),
    /** Clear a player's inventory. */
    CLEAR_INV("arcadia.adminpanel.clearinv"),
    /** Reset progress (advancement revoke). */
    RESET_PROGRESS("arcadia.adminpanel.resetprogress"),
    /** Kick a player from the server. */
    KICK("arcadia.adminpanel.kick"),
    /** Ban / unban a player. */
    BAN("arcadia.adminpanel.ban"),
    /** Mute / unmute a player. */
    MUTE("arcadia.adminpanel.mute"),
    /** Jail / unjail a player. */
    JAIL("arcadia.adminpanel.jail"),
    /** Trigger {@code /arcadia_adminpanel reload}. */
    RELOAD("arcadia.adminpanel.reload"),
    /** Set the jail location (high-impact admin action). */
    SETJAIL("arcadia.adminpanel.setjail"),
    /** Toggle the login queue at runtime ({@code /arcadia_adminpanel loginqueue [on|off]}). */
    LOGIN_QUEUE("arcadia.adminpanel.loginqueue"),
    /** View a player's detailed info sheet (ban/whitelist status, login history, last-seen). */
    INFO("arcadia.adminpanel.info"),
    /** Broadcast a server-wide title + subtitle announcement. */
    ANNOUNCE("arcadia.adminpanel.announce"),
    /** Pin / clear a player's next-login spawn override (debug teleport). */
    NEXT_SPAWN("arcadia.adminpanel.nextspawn"),
    /** Change a player's game mode from the panel. */
    GAMEMODE("arcadia.adminpanel.gamemode"),
    /** Heal / feed a player from the panel. */
    HEAL("arcadia.adminpanel.heal"),
    /** Edit a player's name-tag colour / effect / style ({@code /arcadia_adminpanel nametag …}). */
    NAMETAG_EDIT("arcadia.adminpanel.nametag"),
    /** Toggle the global hide-names-behind-walls switch and per-player exemptions. */
    NAMETAG_HIDE("arcadia.adminpanel.nametag.hide"),
    /** Disguise a player as a mob ({@code /arcadia_adminpanel disguise …}). */
    DISGUISE("arcadia.adminpanel.disguise"),

    // ── 1.3.0 ───────────────────────────────────────────────────────────────
    /** Go invisible. */
    VANISH("arcadia.adminpanel.vanish"),
    /** See other vanished staff. Separate from {@link #VANISH} so a trainee can be hidden from. */
    VANISH_SEE("arcadia.adminpanel.vanish.see"),
    /** Freeze a player for a screenshare. */
    FREEZE("arcadia.adminpanel.freeze"),
    /** One-click spectate + return. */
    SPECTATE("arcadia.adminpanel.spectate"),
    /** Command spy and social spy feeds. */
    SPY("arcadia.adminpanel.spy"),
    /** Read the staff audit log. */
    AUDIT("arcadia.adminpanel.audit"),
    /** Read and write private staff notes. */
    NOTES("arcadia.adminpanel.notes"),
    /** Flag players on the watchlist. */
    WATCHLIST("arcadia.adminpanel.watchlist"),
    /** Open a player's unified sanction history. */
    HISTORY("arcadia.adminpanel.history"),
    /** Edit an inventory, online or offline. Strictly stronger than {@link #INVSEE}. */
    INV_EDIT("arcadia.adminpanel.invedit"),
    /** Browse and restore death snapshots. */
    DEATH_RESTORE("arcadia.adminpanel.deathrestore"),
    /** Hand an item to a player from the panel. */
    GIVE_ITEM("arcadia.adminpanel.giveitem"),
    /** Send offline mail. */
    MAIL("arcadia.adminpanel.mail"),
    /** Read playtime and session statistics. */
    SESSIONS("arcadia.adminpanel.sessions"),
    /** See who is AFK. */
    AFK("arcadia.adminpanel.afk"),
    /** See shared-connection account groups. */
    ALTS("arcadia.adminpanel.alts"),
    /** See the mod list reported by clients. */
    CLIENT_MODS("arcadia.adminpanel.clientmods"),
    /** Open the performance panel. */
    PERFORMANCE("arcadia.adminpanel.performance"),
    /** Browse claimed and force-loaded chunks. */
    CHUNKS("arcadia.adminpanel.chunks"),
    /** Lock and clear the chat. */
    CHAT_CONTROL("arcadia.adminpanel.chatcontrol"),
    /** Run an action against a multi-player selection. */
    BULK("arcadia.adminpanel.bulk"),
    /** Toggle silent mode. */
    SILENT("arcadia.adminpanel.silent"),
    /** Change time, weather, difficulty and game rules. */
    WORLD("arcadia.adminpanel.world"),
    /** Schedule or cancel a restart. */
    RESTART("arcadia.adminpanel.restart"),
    /** Control the rotating auto-broadcast. */
    BROADCAST("arcadia.adminpanel.broadcast"),
    /** Open the proximity radar. */
    RADAR("arcadia.adminpanel.radar"),
    /** Return to the position held before the last panel teleport. */
    BACK("arcadia.adminpanel.back"),
    /** Apply a sanction template and its escalation ladder. */
    TEMPLATES("arcadia.adminpanel.templates"),

    // ── 1.3.2 ───────────────────────────────────────────────────────────────
    /**
     * Browse a player's daily inventory backups, hand one stack back, and restore a whole backup.
     * Deliberately separate from {@link #DEATH_RESTORE}: restoring a backup replaces an inventory,
     * which is a strictly heavier act than handing back what somebody died with.
     */
    INV_BACKUP("arcadia.adminpanel.invbackup"),
    /** Read the in-game command index. Held by every staff member in practice. */
    HELP("arcadia.adminpanel.help");

    public final String node;
    AdminPermissions(String node) { this.node = node; }

    // ── Cache ───────────────────────────────────────────────────────────────

    private static final long CACHE_TTL_MS = 2_000L;
    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    /**
     * One resolved snapshot of every node for one player.
     *
     * <p>This used to be an {@code int} bitmask indexed by ordinal. 1.3.0 pushed the node count past
     * 32, at which point {@code 1 << ordinal} silently wrapped and the high nodes started reading
     * the low ones' answers: a moderator with {@code open} would have been granted {@code vanish}.
     * A flat array has no such ceiling and costs one allocation per player per cache window.</p>
     */
    private record CacheEntry(boolean[] flags, long stamp) {}

    /** Drop any cached perm flags for a player (call on logout, on rank change, on reload). */
    public static void invalidate(UUID uuid) { CACHE.remove(uuid); }

    /** Drop the whole cache (call on /reload, on backend hot-swap). */
    public static void invalidateAll() { CACHE.clear(); }

    /**
     * Returns true if {@code player} can use the node. OP level &gt;= 2 short-circuits to true
     * (vanilla admins always pass). Otherwise queries {@link PermissionService#hasPermissionStrict}
     * so a missing/uninitialized perm backend fails closed.
     */
    public boolean check(@Nullable ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;

        CacheEntry e = CACHE.get(player.getUUID());
        long now = System.currentTimeMillis();
        int idx = this.ordinal();
        if (e != null && now - e.stamp < CACHE_TTL_MS) {
            return e.flags[idx];
        }
        // Cache miss — resolve every node in one pass so subsequent slot checks hit cache.
        AdminPermissions[] all = AdminPermissions.values();
        boolean[] flags = new boolean[all.length];
        for (AdminPermissions p : all) {
            flags[p.ordinal()] = PermissionService.hasPermissionStrict(player, p.node);
        }
        CACHE.put(player.getUUID(), new CacheEntry(flags, now));
        return flags[idx];
    }

    /** Convenience: open-panel gate (combines OP check with the OPEN node). */
    public static boolean canOpen(@Nullable ServerPlayer p) { return OPEN.check(p); }
}
