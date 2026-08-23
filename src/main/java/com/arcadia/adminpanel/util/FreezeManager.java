package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.util.SoundHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a player still for a screenshare.
 *
 * <p>A frozen player cannot move, break, place, use, attack, drop, or open anything, and by default
 * cannot take damage either: a suspect who drowns halfway through an interview turns a moderation
 * question into a compensation dispute. What they <em>can</em> do is talk, because the entire point
 * is to ask them questions, and run the short whitelist of commands needed to answer them.</p>
 *
 * <p><b>Cost.</b> The anchor sweep runs from the player tick and returns on the first line when
 * nobody is frozen, which is the normal state of a server. When someone is frozen it is one distance
 * comparison against a stored position for that player alone.</p>
 *
 * @author vyrriox
 */
public final class FreezeManager {

    /** Where the player was pinned, who pinned them, and why. */
    public record FreezeEntry(String byName, String reason, long startedAt,
                              BackManager.Waypoint anchor) {}

    private static final Map<UUID, FreezeEntry> FROZEN = new ConcurrentHashMap<>();
    /** Last reminder tick per frozen player, so the message paces itself. */
    private static final Map<UUID, Long> LAST_REMINDER = new ConcurrentHashMap<>();

    /** Commands a frozen player may still run: the ones they need to answer or to be released. */
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "arcadia_adminpanel", "msg", "tell", "w", "r", "reply", "help", "checkwarn");

    /** Squared distance the player may drift before being snapped back. Absorbs rounding noise. */
    private static final double DRIFT_TOLERANCE_SQ = 0.35D * 0.35D;

    private FreezeManager() {}

    // -- State ---------------------------------------------------------------

    public static boolean isFrozen(UUID uuid) { return FROZEN.containsKey(uuid); }

    public static boolean isFrozen(ServerPlayer player) {
        return player != null && FROZEN.containsKey(player.getUUID());
    }

    @Nullable
    public static FreezeEntry get(UUID uuid) { return FROZEN.get(uuid); }

    public static int count() { return FROZEN.size(); }

    public static Set<UUID> all() { return Set.copyOf(FROZEN.keySet()); }

    public static boolean isEmpty() { return FROZEN.isEmpty(); }

    // -- Toggle --------------------------------------------------------------

    /** Freezes {@code target} where they stand. Returns false when they were already frozen. */
    public static boolean freeze(ServerPlayer actor, ServerPlayer target, String reason) {
        if (FROZEN.containsKey(target.getUUID())) return false;
        FROZEN.put(target.getUUID(), new FreezeEntry(
                actor != null ? actor.getName().getString() : "CONSOLE",
                reason == null ? "" : reason,
                System.currentTimeMillis(),
                BackManager.Waypoint.of(target)));

        // Stop whatever they were doing: close the open container, cancel the swing, kill momentum.
        target.closeContainer();
        target.setDeltaMovement(0, 0, 0);
        target.hurtMarked = true;

        MessageHelper.sendTitle(target,
                Component.literal("§b§l" + LanguageHelper.getText("freeze.title", target)),
                Component.literal("§f" + LanguageHelper.getText("freeze.subtitle", target)),
                10, 80, 20);
        target.sendSystemMessage(ArcadiaMessages.error(LanguageHelper.getText("freeze.notice", target)));
        SoundHelper.error(target);

        FreezeSync.push(target, true);
        AuditManager.record(actor, AdminAction.FREEZE, target.getUUID(),
                target.getName().getString(), reason);
        return true;
    }

    /** Releases {@code target}. Returns false when they were not frozen. */
    public static boolean unfreeze(ServerPlayer actor, ServerPlayer target) {
        if (FROZEN.remove(target.getUUID()) == null) return false;
        LAST_REMINDER.remove(target.getUUID());

        target.sendSystemMessage(ArcadiaMessages.success(LanguageHelper.getText("freeze.released", target)));
        MessageHelper.sendTitle(target,
                Component.literal("§a" + LanguageHelper.getText("freeze.released.title", target)),
                Component.literal(""), 5, 30, 10);
        SoundHelper.success(target);

        FreezeSync.push(target, false);
        AuditManager.record(actor, AdminAction.UNFREEZE, target.getUUID(),
                target.getName().getString(), "");
        return true;
    }

    public static boolean toggle(ServerPlayer actor, ServerPlayer target, String reason) {
        if (isFrozen(target.getUUID())) {
            unfreeze(actor, target);
            return false;
        }
        freeze(actor, target, reason);
        return true;
    }

    /** Drops a frozen player without announcing, e.g. because they disconnected. */
    public static void clearSilently(UUID uuid) {
        FROZEN.remove(uuid);
        LAST_REMINDER.remove(uuid);
    }

    public static void reset() {
        FROZEN.clear();
        LAST_REMINDER.clear();
    }

    // -- Enforcement ---------------------------------------------------------

    /**
     * Snaps a drifting frozen player back to their anchor and paces the reminder line. Called from
     * the player tick; the caller must already know the map is not empty.
     */
    public static void enforce(ServerPlayer player) {
        FreezeEntry entry = FROZEN.get(player.getUUID());
        if (entry == null) return;

        BackManager.Waypoint a = entry.anchor();
        double dx = player.getX() - a.x();
        double dy = player.getY() - a.y();
        double dz = player.getZ() - a.z();
        if (dx * dx + dy * dy + dz * dz > DRIFT_TOLERANCE_SQ) {
            player.teleportTo(a.x(), a.y(), a.z());
            player.setDeltaMovement(0, 0, 0);
            player.hurtMarked = true;
        } else if (player.getDeltaMovement().lengthSqr() > 1.0E-4D) {
            player.setDeltaMovement(0, 0, 0);
        }

        int every = AdminConfig.get().freezeReminderSeconds;
        if (every <= 0) return;
        long now = System.currentTimeMillis();
        long last = LAST_REMINDER.getOrDefault(player.getUUID(), 0L);
        if (now - last >= every * 1000L) {
            LAST_REMINDER.put(player.getUUID(), now);
            MessageHelper.sendActionBar(player,
                    Component.literal("§b" + LanguageHelper.getText("freeze.actionbar", player)));
        }
    }

    /** True when a frozen player may run this command line. */
    public static boolean isCommandAllowed(String rawCommand) {
        String line = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        String root = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        return ALLOWED_COMMANDS.contains(root);
    }

    /**
     * Refuses an interaction and tells the player why, at most once per second so a held right-click
     * does not spam them.
     */
    public static void deny(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_REMINDER.get(player.getUUID());
        if (last != null && now - last < 1000L) return;
        LAST_REMINDER.put(player.getUUID(), now);
        MessageHelper.sendActionBar(player,
                Component.literal("§c" + LanguageHelper.getText("freeze.denied", player)));
    }

    /** Hook seam so the manager does not depend on the network package. Bound at mod init. */
    public interface Syncer { void push(ServerPlayer player, boolean frozen); }

    static final class FreezeSync {
        private static volatile Syncer impl = (p, f) -> {};
        static void push(ServerPlayer p, boolean frozen) { impl.push(p, frozen); }
    }

    /** Called once at startup to wire the client overlay packet in. */
    public static void bindSyncer(Syncer syncer) {
        if (syncer != null) FreezeSync.impl = syncer;
    }
}
