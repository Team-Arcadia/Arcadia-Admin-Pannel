package com.arcadia.adminpanel.util;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each connected client says it is running.
 *
 * <p><b>What this is.</b> A visibility tool. Clients that have this mod installed report their own
 * mod list on login; staff can then see, from the panel, that the player asking about a strange
 * duplication bug is running a mod that adds one. It also raises an alert when a name on the
 * operator's blacklist shows up.</p>
 *
 * <p><b>What this is not.</b> An anti-cheat. The report is self-declared: a client that has been
 * modified to lie will lie, and a client without this mod reports nothing at all. It catches the
 * common case, which is somebody who installed something they should not have and never thought
 * about hiding it, and it is presented that way in the UI rather than as proof.</p>
 *
 * @author vyrriox
 */
public final class ClientModsRegistry {

    /** One client's declaration. {@code flagged} lists the blacklist hits found at report time. */
    public record Report(List<String> mods, List<String> flagged, long receivedAt) {}

    private static final Map<UUID, Report> REPORTS = new ConcurrentHashMap<>();
    /** How many mod ids a single client may declare. Guards against a hostile payload. */
    private static final int MAX_MODS = 2000;

    private ClientModsRegistry() {}

    // -- Ingest --------------------------------------------------------------

    /**
     * Stores a client's declaration and alerts staff about blacklist hits.
     *
     * @param mods raw mod ids as reported; trimmed, lowercased and capped here
     */
    public static void accept(ServerPlayer player, List<String> mods) {
        if (!AdminConfig.get().clientModsEnabled) return;

        List<String> clean = new ArrayList<>(Math.min(mods.size(), MAX_MODS));
        for (String raw : mods) {
            if (clean.size() >= MAX_MODS) break;
            if (raw == null) continue;
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || id.length() > 128) continue;
            if (!clean.contains(id)) clean.add(id);
        }
        clean.sort(String::compareTo);

        List<String> blacklist = AdminConfig.get().clientModBlacklist;
        List<String> flagged = new ArrayList<>();
        if (blacklist != null) {
            for (String banned : blacklist) {
                if (banned == null || banned.isBlank()) continue;
                String needle = banned.trim().toLowerCase(Locale.ROOT);
                for (String id : clean) {
                    if (id.equals(needle) || id.contains(needle)) {
                        if (!flagged.contains(id)) flagged.add(id);
                    }
                }
            }
        }

        REPORTS.put(player.getUUID(), new Report(clean, flagged, System.currentTimeMillis()));

        if (!flagged.isEmpty() && AdminConfig.get().clientModAlertStaff) {
            String joined = String.join(", ", flagged);
            StaffFeed.alertStaffKey("clientmods.flagged", staff ->
                    LanguageHelper.getText("clientmods.flagged", staff)
                            .replace("%player%", player.getName().getString())
                            .replace("%mods%", joined));
        }
    }

    // -- Queries -------------------------------------------------------------

    @Nullable
    public static Report get(UUID uuid) { return REPORTS.get(uuid); }

    public static boolean hasReported(UUID uuid) { return REPORTS.containsKey(uuid); }

    public static int modCount(UUID uuid) {
        Report r = REPORTS.get(uuid);
        return r == null ? -1 : r.mods().size();
    }

    public static boolean isFlagged(UUID uuid) {
        Report r = REPORTS.get(uuid);
        return r != null && !r.flagged().isEmpty();
    }

    /** Every player currently flagged by the blacklist. */
    public static List<UUID> flaggedPlayers() {
        List<UUID> out = new ArrayList<>();
        for (var e : REPORTS.entrySet()) {
            if (!e.getValue().flagged().isEmpty()) out.add(e.getKey());
        }
        return out;
    }

    public static void onQuit(UUID uuid) {
        // The declaration describes a session, not a player: keeping it after they leave would show
        // stale information the next time somebody opens their sheet.
        REPORTS.remove(uuid);
    }

    public static void reset() { REPORTS.clear(); }

    public static int reportCount() { return REPORTS.size(); }
}
