package com.arcadia.adminpanel.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of players a staff member has ticked in the panel, for actions that apply to more than
 * one person.
 *
 * <p>Gathering an event group, messaging a raid party, kicking the six accounts that just joined
 * together: all of these were one-at-a-time before. The selection is per staff member, kept in
 * insertion order so the panel shows it the way it was built, and cleared on disconnect.</p>
 *
 * @author vyrriox
 */
public final class SelectionManager {

    /** Staff UUID to the ordered set of selected players, with the name captured at selection time. */
    private static final Map<UUID, Map<UUID, String>> SELECTIONS = new ConcurrentHashMap<>();

    /** Upper bound so a stray click cannot build a selection that no action should ever run on. */
    private static final int MAX_SELECTION = 64;

    private SelectionManager() {}

    // -- Mutation ------------------------------------------------------------

    /** Adds or removes one player. Returns the new membership state. */
    public static boolean toggle(UUID staff, UUID target, String targetName) {
        Map<UUID, String> set = SELECTIONS.computeIfAbsent(staff,
                k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (set) {
            if (set.remove(target) != null) return false;
            if (set.size() >= MAX_SELECTION) return false;
            set.put(target, targetName);
            return true;
        }
    }

    public static boolean isSelected(UUID staff, UUID target) {
        Map<UUID, String> set = SELECTIONS.get(staff);
        if (set == null) return false;
        synchronized (set) { return set.containsKey(target); }
    }

    public static int size(UUID staff) {
        Map<UUID, String> set = SELECTIONS.get(staff);
        if (set == null) return 0;
        synchronized (set) { return set.size(); }
    }

    public static void clear(UUID staff) {
        SELECTIONS.remove(staff);
    }

    /** Selects every player currently online, for the "all players" shortcut. */
    public static int selectAllOnline(ServerPlayer staff) {
        MinecraftServer server = staff.getServer();
        if (server == null) return 0;
        Map<UUID, String> set = SELECTIONS.computeIfAbsent(staff.getUUID(),
                k -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (set) {
            set.clear();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (set.size() >= MAX_SELECTION) break;
                set.put(p.getUUID(), p.getName().getString());
            }
            return set.size();
        }
    }

    // -- Reads ---------------------------------------------------------------

    /** Selected UUIDs in selection order. */
    public static Set<UUID> selected(UUID staff) {
        Map<UUID, String> set = SELECTIONS.get(staff);
        if (set == null) return Set.of();
        synchronized (set) { return new LinkedHashSet<>(set.keySet()); }
    }

    /** Selected players with their captured names, in selection order. */
    public static List<Map.Entry<UUID, String>> entries(UUID staff) {
        Map<UUID, String> set = SELECTIONS.get(staff);
        if (set == null) return List.of();
        synchronized (set) { return new ArrayList<>(set.entrySet()); }
    }

    /** The subset that is currently connected, resolved fresh. */
    public static List<ServerPlayer> onlineTargets(ServerPlayer staff) {
        MinecraftServer server = staff.getServer();
        List<ServerPlayer> out = new ArrayList<>();
        if (server == null) return out;
        for (UUID id : selected(staff.getUUID())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }
}
