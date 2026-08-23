package com.arcadia.adminpanel.util;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flags players worth a second look and pings staff when one of them connects.
 *
 * <p>The persistent record is the source of truth; a small in-memory set mirrors it so the login
 * hook is a hash lookup rather than a scan. Removing a flag appends a removal record and clears the
 * mirror, which keeps the append-only store honest about who un-flagged whom and when.</p>
 *
 * @author vyrriox
 */
public final class WatchlistManager {

    /** One watchlist mutation. {@code active} false marks a removal. */
    public record WatchEntry(String reason, String byName, String byUuid, String targetName, boolean active) {}

    private static final RecordStore<WatchEntry> STORE = new RecordStore<>("watch", WatchEntry.class, 2000);
    /** UUID to the currently-active entry. Rebuilt from the store on first use after load. */
    private static final Map<UUID, WatchEntry> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean mirrorBuilt = false;

    private WatchlistManager() {}

    private static void ensureMirror() {
        if (mirrorBuilt || !STORE.isLoaded()) return;
        synchronized (ACTIVE) {
            if (mirrorBuilt) return;
            ACTIVE.clear();
            // forSubject returns newest-first, so the first row per subject is the current state.
            for (var e : STORE.recent(Integer.MAX_VALUE)) {
                ACTIVE.putIfAbsent(e.subject(), e.payload());
            }
            ACTIVE.values().removeIf(w -> !w.active());
            mirrorBuilt = true;
        }
    }

    /** Forces the mirror to rebuild, e.g. after a reload or a cross-server pull. */
    public static void invalidate() {
        mirrorBuilt = false;
    }

    // -- Mutations -----------------------------------------------------------

    public static void add(ServerPlayer actor, UUID target, String targetName, String reason) {
        ensureMirror();
        WatchEntry entry = new WatchEntry(reason, actor.getName().getString(),
                actor.getUUID().toString(), targetName, true);
        STORE.append(target, entry);
        ACTIVE.put(target, entry);
        AuditManager.record(actor, AdminAction.WATCH_ADD, target, targetName, reason);
    }

    public static boolean remove(ServerPlayer actor, UUID target, String targetName) {
        ensureMirror();
        if (ACTIVE.remove(target) == null) return false;
        STORE.append(target, new WatchEntry("", actor.getName().getString(),
                actor.getUUID().toString(), targetName, false));
        AuditManager.record(actor, AdminAction.WATCH_REMOVE, target, targetName, "");
        return true;
    }

    /** Adds or removes in one call. Returns the new state. */
    public static boolean toggle(ServerPlayer actor, UUID target, String targetName, String reason) {
        ensureMirror();
        if (isWatched(target)) {
            remove(actor, target, targetName);
            return false;
        }
        add(actor, target, targetName, reason);
        return true;
    }

    // -- Reads ---------------------------------------------------------------

    public static boolean isWatched(UUID target) {
        ensureMirror();
        return ACTIVE.containsKey(target);
    }

    @Nullable
    public static WatchEntry get(UUID target) {
        ensureMirror();
        return ACTIVE.get(target);
    }

    /** Every currently-flagged player. */
    public static List<Map.Entry<UUID, WatchEntry>> all() {
        ensureMirror();
        return new ArrayList<>(ACTIVE.entrySet());
    }

    public static int size() {
        ensureMirror();
        return ACTIVE.size();
    }

    /** Full history for one player, including past removals. */
    public static List<RecordStore.Entry<WatchEntry>> history(UUID target) {
        return STORE.forSubject(target);
    }

    // -- Login hook ----------------------------------------------------------

    /** Pings staff when a flagged player connects. Cheap: one hash lookup per login. */
    public static void onJoin(ServerPlayer player) {
        if (!AdminConfig.get().watchlistAlertOnJoin) return;
        WatchEntry entry = get(player.getUUID());
        if (entry == null) return;
        String reason = entry.reason() == null || entry.reason().isBlank() ? "" : " - " + entry.reason();
        StaffFeed.alertStaffKey("watchlist.join", staff ->
                LanguageHelper.getText("watchlist.join", staff)
                        .replace("%player%", player.getName().getString())
                        .replace("%by%", entry.byName()) + reason);
    }
}
