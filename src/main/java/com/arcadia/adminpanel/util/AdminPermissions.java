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
    /** Toggle the login queue. */
    LOGIN_QUEUE("arcadia.adminpanel.loginqueue");

    public final String node;
    AdminPermissions(String node) { this.node = node; }

    // ── Cache ───────────────────────────────────────────────────────────────

    private static final long CACHE_TTL_MS = 2_000L;
    private static final Map<UUID, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(int flags, long stamp) {}

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
            return (e.flags & (1 << idx)) != 0;
        }
        // Cache miss — recompute the full mask in one pass so subsequent slot checks hit cache.
        int mask = 0;
        for (AdminPermissions p : AdminPermissions.values()) {
            if (PermissionService.hasPermissionStrict(player, p.node)) {
                mask |= (1 << p.ordinal());
            }
        }
        CACHE.put(player.getUUID(), new CacheEntry(mask, now));
        return (mask & (1 << idx)) != 0;
    }

    /** Convenience: open-panel gate (combines OP check with the OPEN node). */
    public static boolean canOpen(@Nullable ServerPlayer p) { return OPEN.check(p); }
}
