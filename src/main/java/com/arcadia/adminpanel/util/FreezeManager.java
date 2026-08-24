package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.util.SoundHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Holds a player still for a screenshare.
 *
 * <p>A frozen player cannot move, break, place, use, attack, drop, or open anything, and by default
 * cannot take damage either: a suspect who drowns halfway through an interview turns a moderation
 * question into a compensation dispute. What they <em>can</em> do is talk, because the entire point
 * is to ask them questions, and run the short whitelist of commands needed to answer them.</p>
 *
 * <p><b>The hold is two-layered.</b> An infinite, invisible slowness stops the client from walking
 * in the first place, and an anchor sweep corrects anything that gets through it. The correction
 * goes out through the connection rather than through the entity: moving the server-side entity
 * alone leaves the client where it was, and the client's next movement packet simply wins. That is
 * what made the freeze look like it did nothing before 1.3.1.</p>
 *
 * <p><b>Disconnecting is not an escape.</b> The freeze outlives a relog and a server restart. A
 * suspect who alt-F4s during a screenshare comes back frozen, which is the only behaviour that
 * makes freezing worth doing.</p>
 *
 * <p><b>Cost.</b> The anchor sweep runs from the player tick and returns on the first line when
 * nobody is frozen, which is the normal state of a server. When someone is frozen it is one distance
 * comparison against a stored position for that player alone.</p>
 *
 * @author vyrriox
 */
public final class FreezeManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Where the player was pinned, who pinned them, and why. */
    public record FreezeEntry(String byName, String reason, long startedAt,
                              BackManager.Waypoint anchor) {}

    /** Flat shape written to disk. Kept separate so the on-disk format survives record changes. */
    private static final class StoredFreeze {
        String name = "";
        String byName = "";
        String reason = "";
        long startedAt;
        String dimension = "";
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
    }

    private static final Map<UUID, FreezeEntry> FROZEN = new ConcurrentHashMap<>();
    /** Display name per frozen UUID, so an offline suspect can still be released from the panel. */
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();
    /** Last reminder tick per frozen player, so the message paces itself. */
    private static final Map<UUID, Long> LAST_REMINDER = new ConcurrentHashMap<>();
    /** Last refusal per frozen player. Separate from the reminder: they throttle each other. */
    private static final Map<UUID, Long> LAST_DENY = new ConcurrentHashMap<>();

    /** Commands a frozen player may still run: the ones they need to answer or to be released. */
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "arcadia_adminpanel", "msg", "tell", "w", "r", "reply", "help", "checkwarn");

    /** Squared distance the player may drift before being snapped back. Absorbs rounding noise. */
    private static final double DRIFT_TOLERANCE_SQ = 0.35D * 0.35D;

    /** Slowness VII. The movement-speed multiplier lands below zero, so walking stops outright. */
    private static final int HOLD_AMPLIFIER = 6;

    private static volatile ExecutorService io;

    private FreezeManager() {}

    // -- Lifecycle -----------------------------------------------------------

    /** Boots the persistence thread and restores anyone who was frozen when the server stopped. */
    public static void init() {
        ExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-Freeze-IO");
                t.setDaemon(true);
                return t;
            });
        }
        loadBlocking();
    }

    /** Flushes the current state and stops the persistence thread. */
    public static void shutdown() {
        saveBlocking();
        ExecutorService current = io;
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        io = null;
        FROZEN.clear();
        NAMES.clear();
        LAST_REMINDER.clear();
        LAST_DENY.clear();
    }

    // -- State ---------------------------------------------------------------

    public static boolean isFrozen(UUID uuid) { return FROZEN.containsKey(uuid); }

    public static boolean isFrozen(ServerPlayer player) {
        return player != null && FROZEN.containsKey(player.getUUID());
    }

    @Nullable
    public static FreezeEntry get(UUID uuid) { return FROZEN.get(uuid); }

    /** Last known display name of a frozen player, or {@code null} when it was never recorded. */
    @Nullable
    public static String nameOf(UUID uuid) { return NAMES.get(uuid); }

    /** Finds a frozen player by the name they carried when they were frozen. Case-insensitive. */
    @Nullable
    public static UUID findByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Map.Entry<UUID, String> e : NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

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
        NAMES.put(target.getUUID(), target.getName().getString());

        // Stop whatever they were doing: close the open container, cancel the swing, kill momentum.
        target.closeContainer();
        applyHold(target);

        MessageHelper.sendTitle(target,
                Component.literal("§b§l" + LanguageHelper.getText("freeze.title", target)),
                Component.literal("§f" + LanguageHelper.getText("freeze.subtitle", target)),
                10, 80, 20);
        target.sendSystemMessage(ArcadiaMessages.error(LanguageHelper.getText("freeze.notice", target)));
        SoundHelper.error(target);

        FreezeSync.push(target, true);
        AuditManager.record(actor, AdminAction.FREEZE, target.getUUID(),
                target.getName().getString(), reason);
        saveAsync();
        return true;
    }

    /** Releases {@code target}. Returns false when they were not frozen. */
    public static boolean unfreeze(ServerPlayer actor, ServerPlayer target) {
        if (FROZEN.remove(target.getUUID()) == null) return false;
        NAMES.remove(target.getUUID());
        LAST_REMINDER.remove(target.getUUID());
        LAST_DENY.remove(target.getUUID());
        releaseHold(target);

        target.sendSystemMessage(ArcadiaMessages.success(LanguageHelper.getText("freeze.released", target)));
        MessageHelper.sendTitle(target,
                Component.literal("§a" + LanguageHelper.getText("freeze.released.title", target)),
                Component.literal(""), 5, 30, 10);
        SoundHelper.success(target);

        FreezeSync.push(target, false);
        AuditManager.record(actor, AdminAction.UNFREEZE, target.getUUID(),
                target.getName().getString(), "");
        saveAsync();
        return true;
    }

    /**
     * Releases a frozen player who is not connected. The freeze now survives a disconnect, so
     * without this a suspect who logged off mid-screenshare could only be released by catching them
     * on their next login.
     *
     * @return false when that player was not frozen
     */
    public static boolean unfreezeOffline(@Nullable ServerPlayer actor, UUID uuid, String name) {
        if (FROZEN.remove(uuid) == null) return false;
        NAMES.remove(uuid);
        LAST_REMINDER.remove(uuid);
        LAST_DENY.remove(uuid);
        AuditManager.record(actor, AdminAction.UNFREEZE, uuid, name, "offline");
        saveAsync();
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

    /** Drops a frozen player without announcing. Kept for callers that need a silent clear. */
    public static void clearSilently(UUID uuid) {
        FROZEN.remove(uuid);
        NAMES.remove(uuid);
        LAST_REMINDER.remove(uuid);
        LAST_DENY.remove(uuid);
        saveAsync();
    }

    public static void reset() {
        FROZEN.clear();
        NAMES.clear();
        LAST_REMINDER.clear();
        LAST_DENY.clear();
    }

    // -- Session hooks -------------------------------------------------------

    /**
     * Re-applies the hold to a player who reconnects while frozen. Their anchor is moved to wherever
     * they actually spawned: the stored one may be in a dimension they no longer are in, and
     * teleporting a player across the world on login is a bigger surprise than pinning them where
     * the server put them.
     */
    public static void onJoin(ServerPlayer player) {
        FreezeEntry entry = FROZEN.get(player.getUUID());
        if (entry == null) return;

        reanchor(player);
        NAMES.put(player.getUUID(), player.getName().getString());
        applyHold(player);

        MessageHelper.sendTitle(player,
                Component.literal("§b§l" + LanguageHelper.getText("freeze.title", player)),
                Component.literal("§f" + LanguageHelper.getText("freeze.subtitle", player)),
                10, 80, 20);
        player.sendSystemMessage(ArcadiaMessages.error(
                LanguageHelper.getText("freeze.relog", player)));
        String name = player.getName().getString();
        StaffFeed.alertStaffKey("freeze.alert.join", staff ->
                LanguageHelper.getText("freeze.alert.join", staff).replace("%player%", name));
        saveAsync();

        // Jail and the next-login spawn override both position the player a few ticks after the
        // login event. Re-anchoring once they have settled keeps the sweep from dragging them back
        // out of the jail it just put them in.
        com.arcadia.lib.scheduler.SchedulerService.delayed(20, () -> {
            if (!player.hasDisconnected()) reanchor(player);
        });
    }

    /** Moves the anchor to where the player is standing now, keeping the rest of the entry. */
    private static void reanchor(ServerPlayer player) {
        FreezeEntry entry = FROZEN.get(player.getUUID());
        if (entry == null) return;
        FROZEN.put(player.getUUID(), new FreezeEntry(entry.byName(), entry.reason(),
                entry.startedAt(), BackManager.Waypoint.of(player)));
    }

    /** Called when a frozen player disconnects. The freeze is kept; only the pacing state is not. */
    public static void onQuit(UUID uuid) {
        LAST_REMINDER.remove(uuid);
        LAST_DENY.remove(uuid);
        if (!FROZEN.containsKey(uuid)) return;
        // A suspect leaving mid-screenshare is the moment staff most need to be told, and the moment
        // they are least likely to notice on their own.
        String name = NAMES.getOrDefault(uuid, "?");
        StaffFeed.alertStaffKey("freeze.alert.quit", staff ->
                LanguageHelper.getText("freeze.alert.quit", staff).replace("%player%", name));
    }

    // -- Enforcement ---------------------------------------------------------

    /**
     * Snaps a drifting frozen player back to their anchor and paces the reminder line. Called from
     * the player tick; the caller must already know the map is not empty.
     */
    public static void enforce(ServerPlayer player) {
        FreezeEntry entry = FROZEN.get(player.getUUID());
        if (entry == null) return;

        // Creative flight and an elytra both outrun the slowness, so they are taken away every tick
        // rather than only at the moment of freezing.
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        if (player.isFallFlying()) player.stopFallFlying();
        if (player.isPassenger()) player.stopRiding();

        BackManager.Waypoint a = entry.anchor();
        if (!BackManager.isSameDimension(player, a)) {
            // Something moved them across worlds. Pin them where they landed: teleporting to the
            // anchor's coordinates in a dimension it does not belong to would be worse than the
            // move it is trying to undo.
            reanchor(player);
            return;
        }
        double dx = player.getX() - a.x();
        double dy = player.getY() - a.y();
        double dz = player.getZ() - a.z();
        if (dx * dx + dy * dy + dz * dz > DRIFT_TOLERANCE_SQ) {
            // Through the connection, not through the entity. Entity#teleportTo moves the
            // server-side player only: the client never hears about it and its next movement
            // packet overwrites the correction, which is why the anchor used to do nothing.
            player.connection.teleport(a.x(), a.y(), a.z(), player.getYRot(), player.getXRot());
            player.setDeltaMovement(0, 0, 0);
            player.hurtMarked = true;
        } else if (player.getDeltaMovement().lengthSqr() > 1.0E-4D) {
            player.setDeltaMovement(0, 0, 0);
        }
        player.fallDistance = 0.0F;

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
        Long last = LAST_DENY.get(player.getUUID());
        if (last != null && now - last < 1000L) return;
        LAST_DENY.put(player.getUUID(), now);
        MessageHelper.sendActionBar(player,
                Component.literal("§c" + LanguageHelper.getText("freeze.denied", player)));
    }

    // -- Hold ----------------------------------------------------------------

    /**
     * Pins the client itself. An infinite slowness with no particles, no icon and no ambient haze
     * takes the player's walking speed to zero, so the anchor sweep below only ever has to correct
     * what physics did rather than fight the player's own input every tick.
     */
    private static void applyHold(ServerPlayer target) {
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                MobEffectInstance.INFINITE_DURATION, HOLD_AMPLIFIER, false, false, false));
        if (target.getAbilities().flying) {
            target.getAbilities().flying = false;
            target.onUpdateAbilities();
        }
        if (target.isFallFlying()) target.stopFallFlying();
        // A boat or a horse would carry them out from under the anchor sweep.
        if (target.isPassenger()) target.stopRiding();
        target.setDeltaMovement(0, 0, 0);
        target.hurtMarked = true;
        target.fallDistance = 0.0F;
    }

    /**
     * Releases the hold. This also clears a slowness the player brought with them, which is the
     * price of not tracking every potion they were under when the screenshare started.
     */
    private static void releaseHold(ServerPlayer target) {
        target.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
    }

    // -- Persistence ---------------------------------------------------------

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel/freezes.json");
    }

    private static void saveAsync() {
        ExecutorService exec = io;
        if (exec == null || exec.isShutdown()) return;
        try {
            exec.execute(FreezeManager::saveBlocking);
        } catch (Exception ignored) {
            // Shutting down; the flush in shutdown() covers the final state.
        }
    }

    private static synchronized void saveBlocking() {
        Path file = file();
        Path tmp = file.resolveSibling("freezes.json.tmp");
        try {
            Files.createDirectories(file.getParent());
            Map<String, StoredFreeze> byUuid = new HashMap<>();
            for (Map.Entry<UUID, FreezeEntry> e : FROZEN.entrySet()) {
                FreezeEntry entry = e.getValue();
                StoredFreeze s = new StoredFreeze();
                s.name = NAMES.getOrDefault(e.getKey(), "");
                s.byName = entry.byName();
                s.reason = entry.reason();
                s.startedAt = entry.startedAt();
                s.dimension = entry.anchor().dimension();
                s.x = entry.anchor().x();
                s.y = entry.anchor().y();
                s.z = entry.anchor().z();
                s.yaw = entry.anchor().yaw();
                s.pitch = entry.anchor().pitch();
                byUuid.put(e.getKey().toString(), s);
            }
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(byUuid, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to write freezes.json", e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static void loadBlocking() {
        FROZEN.clear();
        NAMES.clear();
        Path file = file();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, StoredFreeze> raw = GSON.fromJson(r,
                    new TypeToken<Map<String, StoredFreeze>>() {}.getType());
            if (raw == null) return;
            for (Map.Entry<String, StoredFreeze> e : raw.entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(e.getKey());
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                StoredFreeze s = e.getValue();
                if (s == null) continue;
                FROZEN.put(uuid, new FreezeEntry(s.byName, s.reason, s.startedAt,
                        new BackManager.Waypoint(s.dimension, s.x, s.y, s.z, s.yaw, s.pitch)));
                if (s.name != null && !s.name.isBlank()) NAMES.put(uuid, s.name);
            }
            if (!FROZEN.isEmpty()) {
                LOGGER.info("[AdminPanel] Restored {} frozen player(s) from disk", FROZEN.size());
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read freezes.json", e);
        }
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
