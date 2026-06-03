package com.arcadia.adminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Next-login spawn override" — a debugging aid. An admin pins a one-shot spawn point to a player;
 * the next time that player connects they are teleported there instead of appearing at their normal
 * (last/bed/world-spawn) position, and the override is consumed. Useful to pull a stuck/glitched
 * player to a known-safe location, or to inspect a player at a chosen spot on reconnect.
 *
 * <p>Persisted to {@code config/arcadia/arcadiaadminpanel/next_spawns.json} (atomic temp-file
 * rename) so a pending override survives a restart. Single-server / local by design — the admin
 * sets the point on the server the player will reconnect to.</p>
 *
 * @author vyrriox
 */
public final class NextSpawnManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final NextSpawnManager INSTANCE = new NextSpawnManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, SpawnPoint> pending = new ConcurrentHashMap<>();
    private final Path file;
    private final Path tempFile;

    /** A pinned spawn override. {@code setBy}/{@code timestamp} are informational (GUI lore / logs). */
    public record SpawnPoint(String dimension, double x, double y, double z,
                             float yaw, float pitch, String setBy, long timestamp) {
        public String getFormattedCoords() {
            return String.format("%.0f, %.0f, %.0f", x, y, z);
        }

        public String getShortDimension() {
            return FTBDataReader.shortDimension(dimension);
        }
    }

    private NextSpawnManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        file = configDir.resolve("next_spawns.json");
        tempFile = configDir.resolve("next_spawns.json.tmp");
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create config directory", e);
        }
    }

    public static NextSpawnManager getInstance() { return INSTANCE; }

    public void init() {
        load();
        LOGGER.info("[AdminPanel] NextSpawnManager initialized ({} pending)", pending.size());
    }

    public void reload() {
        pending.clear();
        load();
    }

    // ── Operations ────────────────────────────────────────────────────────────

    /** Pin the override from an admin's current position (the natural "spawn them here" gesture). */
    public void setFromAdmin(UUID target, ServerPlayer admin) {
        SpawnPoint point = new SpawnPoint(
                admin.serverLevel().dimension().location().toString(),
                admin.getX(), admin.getY(), admin.getZ(),
                admin.getYRot(), admin.getXRot(),
                admin.getName().getString(), System.currentTimeMillis());
        pending.put(target, point);
        save();
    }

    public boolean clear(UUID target) {
        boolean removed = pending.remove(target) != null;
        if (removed) save();
        return removed;
    }

    @Nullable
    public SpawnPoint get(UUID target) { return pending.get(target); }

    public boolean has(UUID target) { return pending.containsKey(target); }

    public Map<UUID, SpawnPoint> getAll() { return Collections.unmodifiableMap(pending); }

    /**
     * Consume the pending override (if any) and teleport the player. Called on login AFTER the jail
     * check — jail always wins, so a jailed player is never pulled out by a stale override.
     * Returns the consumed point (for messaging) or {@code null} if nothing was pending.
     */
    @Nullable
    public SpawnPoint consumeAndApply(ServerPlayer player) {
        SpawnPoint point = pending.remove(player.getUUID());
        if (point == null) return null;
        save();
        teleport(player, point, player.getServer());
        return point;
    }

    private void teleport(ServerPlayer player, SpawnPoint loc, @Nullable MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = null;
        for (ServerLevel w : server.getAllLevels()) {
            if (w.dimension().location().toString().equals(loc.dimension())) { level = w; break; }
        }
        if (level == null) level = server.overworld();
        player.teleportTo(level, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(file)) return;
        try (FileReader reader = new FileReader(file.toFile())) {
            Map<UUID, SpawnPoint> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<UUID, SpawnPoint>>() {}.getType());
            if (loaded != null) pending.putAll(loaded);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load next_spawns.json", e);
        }
    }

    private synchronized void save() {
        try (FileWriter writer = new FileWriter(tempFile.toFile())) {
            GSON.toJson(pending, writer);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to write next_spawns temp file", e);
            return;
        }
        try {
            Files.move(tempFile, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to atomically save next_spawns.json", e);
        }
    }
}
