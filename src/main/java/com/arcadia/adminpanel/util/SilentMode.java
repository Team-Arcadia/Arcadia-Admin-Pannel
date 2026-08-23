package com.arcadia.adminpanel.util;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-staff "act without announcing it" switch.
 *
 * <p>Silent mode suppresses the <em>public</em> side of an action: the server-wide broadcast a
 * sanction would normally produce, and the fake join/leave line vanish would normally print. It
 * never suppresses the audit row and never hides the action from the staff feed, because an action
 * nobody can review is not a moderation tool, it is a liability. Staff simply see the entry tagged
 * as silent.</p>
 *
 * <p>The toggle is session-scoped on purpose. An admin who forgets they left silent mode on three
 * days ago is exactly how sanctions stop reaching the players they are meant to warn, so it clears
 * on disconnect.</p>
 *
 * @author vyrriox
 */
public final class SilentMode {

    private static final Set<UUID> SILENT = ConcurrentHashMap.newKeySet();

    private SilentMode() {}

    public static boolean isSilent(UUID staff) {
        return SILENT.contains(staff);
    }

    public static boolean isSilent(ServerPlayer staff) {
        return staff != null && SILENT.contains(staff.getUUID());
    }

    /** Flips the switch and returns the new state. */
    public static boolean toggle(UUID staff) {
        if (SILENT.remove(staff)) return false;
        SILENT.add(staff);
        return true;
    }

    public static void set(UUID staff, boolean silent) {
        if (silent) SILENT.add(staff); else SILENT.remove(staff);
    }

    /** Called on disconnect so a forgotten toggle cannot outlive the session. */
    public static void clear(UUID staff) {
        SILENT.remove(staff);
    }

    public static int activeCount() {
        return SILENT.size();
    }
}
