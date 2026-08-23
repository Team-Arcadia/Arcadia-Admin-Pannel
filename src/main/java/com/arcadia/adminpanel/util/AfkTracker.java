package com.arcadia.adminpanel.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells staff who is actually at their keyboard.
 *
 * <p>Useful in its own right ("is this player ignoring me or away?") and as an input to the
 * performance panel, where a row of AFK players sitting in loaded chunks is often the answer to
 * "why is the server busy with nobody playing".</p>
 *
 * <p><b>Cost.</b> One sweep every {@code afkCheckIntervalTicks} ticks, five seconds by default. The
 * sweep compares each online player's position and rotation against the last recorded pair: a
 * handful of double comparisons per player, no allocation, no entity queries. Chat, commands and
 * interactions reset the timer directly, so the sweep only has to catch the case of someone standing
 * perfectly still.</p>
 *
 * @author vyrriox
 */
public final class AfkTracker {

    /** Last observed pose and the instant the player last did something. */
    private record Activity(double x, double y, double z, float yaw, float pitch,
                            long lastActiveMs, long afkSinceMs) {}

    private static final Map<UUID, Activity> STATE = new ConcurrentHashMap<>();
    /** Squared movement below this counts as standing still. */
    private static final double MOVE_EPSILON_SQ = 0.01D;
    private static final float LOOK_EPSILON = 1.0F;

    private static int tickCounter = 0;

    private AfkTracker() {}

    // -- Queries -------------------------------------------------------------

    public static boolean isAfk(UUID uuid) {
        Activity a = STATE.get(uuid);
        return a != null && a.afkSinceMs() > 0L;
    }

    /** How long the player has been AFK, or 0 when they are active. */
    public static long afkDurationMs(UUID uuid) {
        Activity a = STATE.get(uuid);
        if (a == null || a.afkSinceMs() <= 0L) return 0L;
        return System.currentTimeMillis() - a.afkSinceMs();
    }

    public static int afkCount() {
        int n = 0;
        for (Activity a : STATE.values()) if (a.afkSinceMs() > 0L) n++;
        return n;
    }

    /** One AFK player, longest-idle first when the list is sorted. */
    public record AfkPlayer(UUID uuid, String name, long durationMs) {}

    public static List<AfkPlayer> list(MinecraftServer server) {
        List<AfkPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            long d = afkDurationMs(p.getUUID());
            if (d > 0L) out.add(new AfkPlayer(p.getUUID(), p.getName().getString(), d));
        }
        out.sort(Comparator.comparingLong(AfkPlayer::durationMs).reversed());
        return out;
    }

    // -- Updates -------------------------------------------------------------

    /** Called on chat, commands and interactions. Clears the AFK flag immediately. */
    public static void markActive(ServerPlayer player) {
        if (!AdminConfig.get().afkEnabled) return;
        long now = System.currentTimeMillis();
        STATE.put(player.getUUID(), new Activity(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), now, 0L));
    }

    public static void onJoin(ServerPlayer player) {
        markActive(player);
    }

    public static void onQuit(UUID uuid) {
        STATE.remove(uuid);
    }

    public static void reset() {
        STATE.clear();
        tickCounter = 0;
    }

    /**
     * Server tick entry point. Returns immediately except once per configured interval, so it is
     * safe to call from every tick.
     */
    public static void onServerTick(MinecraftServer server) {
        AdminConfig.Data cfg = AdminConfig.get();
        if (!cfg.afkEnabled) return;
        int interval = Math.max(20, cfg.afkCheckIntervalTicks);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        long threshold = Math.max(1, cfg.afkMinutes) * 60_000L;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            Activity prev = STATE.get(id);
            if (prev == null) {
                STATE.put(id, new Activity(p.getX(), p.getY(), p.getZ(),
                        p.getYRot(), p.getXRot(), now, 0L));
                continue;
            }

            double dx = p.getX() - prev.x();
            double dy = p.getY() - prev.y();
            double dz = p.getZ() - prev.z();
            boolean moved = dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQ
                    || Math.abs(p.getYRot() - prev.yaw()) > LOOK_EPSILON
                    || Math.abs(p.getXRot() - prev.pitch()) > LOOK_EPSILON;

            if (moved) {
                STATE.put(id, new Activity(p.getX(), p.getY(), p.getZ(),
                        p.getYRot(), p.getXRot(), now, 0L));
            } else if (prev.afkSinceMs() <= 0L && now - prev.lastActiveMs() >= threshold) {
                // Crossed the threshold: stamp the AFK start but keep the pose so a later move is
                // still detected against the position they actually stopped at.
                STATE.put(id, new Activity(prev.x(), prev.y(), prev.z(), prev.yaw(), prev.pitch(),
                        prev.lastActiveMs(), now - threshold));
            }
        }
    }
}
