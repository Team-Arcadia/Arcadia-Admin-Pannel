package com.arcadia.adminpanel.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Return-to-where-you-were for staff teleports.
 *
 * <p>Every teleport the panel performs on behalf of a staff member pushes their origin first. The
 * history is a small bounded stack per admin, so chaining three "teleport to player" jumps and then
 * walking back out is three {@code back} calls, not one. Nothing is persisted: a position from a
 * previous session is almost never where the admin wants to land.</p>
 *
 * @author vyrriox
 */
public final class BackManager {

    /** A place to return to. Dimension is stored by id so a missing world degrades gracefully. */
    public record Waypoint(String dimension, double x, double y, double z, float yaw, float pitch) {

        public static Waypoint of(ServerPlayer player) {
            return new Waypoint(player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }

        @Nullable
        public ServerLevel resolveLevel(MinecraftServer server) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            if (id == null) return null;
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        }
    }

    private static final int MAX_HISTORY = 8;
    private static final Map<UUID, Deque<Waypoint>> HISTORY = new ConcurrentHashMap<>();

    private BackManager() {}

    /** Records where a staff member is standing, right before a teleport moves them. */
    public static void push(ServerPlayer staff) {
        Deque<Waypoint> stack = HISTORY.computeIfAbsent(staff.getUUID(), k -> new ArrayDeque<>());
        synchronized (stack) {
            stack.push(Waypoint.of(staff));
            while (stack.size() > MAX_HISTORY) stack.removeLast();
        }
    }

    /** Pops the most recent origin, or {@code null} when there is nothing to go back to. */
    @Nullable
    public static Waypoint pop(UUID staff) {
        Deque<Waypoint> stack = HISTORY.get(staff);
        if (stack == null) return null;
        synchronized (stack) {
            return stack.isEmpty() ? null : stack.pop();
        }
    }

    public static boolean hasHistory(UUID staff) {
        Deque<Waypoint> stack = HISTORY.get(staff);
        if (stack == null) return false;
        synchronized (stack) { return !stack.isEmpty(); }
    }

    public static int depth(UUID staff) {
        Deque<Waypoint> stack = HISTORY.get(staff);
        if (stack == null) return 0;
        synchronized (stack) { return stack.size(); }
    }

    public static void clear(UUID staff) {
        HISTORY.remove(staff);
    }

    /**
     * Teleports a staff member back to their previous position.
     *
     * @return false when there was no history, or the recorded dimension no longer exists
     */
    public static boolean teleportBack(ServerPlayer staff) {
        Waypoint wp = pop(staff.getUUID());
        if (wp == null) return false;
        MinecraftServer server = staff.getServer();
        if (server == null) return false;
        ServerLevel level = wp.resolveLevel(server);
        if (level == null) return false;
        staff.teleportTo(level, wp.x(), wp.y(), wp.z(), wp.yaw(), wp.pitch());
        return true;
    }

    /** Convenience for callers that already hold a {@link Level} key. */
    public static boolean isSameDimension(ServerPlayer player, Waypoint wp) {
        return player.level().dimension().location().toString().equals(wp.dimension());
    }
}
