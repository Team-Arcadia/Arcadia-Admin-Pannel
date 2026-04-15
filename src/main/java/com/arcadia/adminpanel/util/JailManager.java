package com.arcadia.adminpanel.util;

import com.arcadia.lib.ServerContext;
import com.arcadia.lib.data.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the jail system: jail location, jailed players, timers.
 * Supports MySQL (multi-server sync) or JSON (standalone).
 *
 * @author vyrriox
 */
public final class JailManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final JailManager INSTANCE = new JailManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, JailEntry> jailCache = new ConcurrentHashMap<>();
    private final Path jailFile;
    private final Path jailTempFile;
    private final Path locationFile;

    // Jail location (per-server, stored locally)
    private volatile JailLocation jailLocation = null;

    public record JailEntry(String reason, String jailedBy, long timestamp, long durationMs,
                            String serverId) {
        public boolean isExpired() {
            if (durationMs <= 0) return false; // Permanent
            return System.currentTimeMillis() >= timestamp + durationMs;
        }

        public long getRemainingMs() {
            if (durationMs <= 0) return -1; // Permanent
            return Math.max(0, (timestamp + durationMs) - System.currentTimeMillis());
        }
    }

    public record JailLocation(String dimension, double x, double y, double z,
                                float yaw, float pitch) {}

    private JailManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        jailFile = configDir.resolve("jail.json");
        jailTempFile = configDir.resolve("jail.json.tmp");
        locationFile = configDir.resolve("jail_location.json");
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create config directory", e);
        }
    }

    public static JailManager getInstance() { return INSTANCE; }

    // ── Init ────────────────────────────────────────────────────────────────

    public void init() {
        loadJailLocation();
        if (isDatabaseMode()) {
            loadFromDatabase();
        } else {
            loadFromJson();
        }
        LOGGER.info("[AdminPanel] JailManager initialized ({} mode, {} jailed, location {})",
                isDatabaseMode() ? "database" : "json",
                jailCache.size(),
                jailLocation != null ? "set" : "not set");
    }

    public boolean isDatabaseMode() {
        return DatabaseManager.isDatabaseActive();
    }

    // ── Jail location ───────────────────────────────────────────────────────

    public void setJailLocation(ServerPlayer admin) {
        jailLocation = new JailLocation(
                admin.serverLevel().dimension().location().toString(),
                admin.getX(), admin.getY(), admin.getZ(),
                admin.getYRot(), admin.getXRot()
        );
        saveJailLocation();
    }

    @Nullable
    public JailLocation getJailLocation() { return jailLocation; }

    public boolean hasJailLocation() { return jailLocation != null; }

    private void loadJailLocation() {
        if (!Files.exists(locationFile)) return;
        try (FileReader reader = new FileReader(locationFile.toFile())) {
            jailLocation = GSON.fromJson(reader, JailLocation.class);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load jail location", e);
        }
    }

    private void saveJailLocation() {
        try (FileWriter writer = new FileWriter(locationFile.toFile())) {
            GSON.toJson(jailLocation, writer);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to save jail location", e);
        }
    }

    // ── Jail operations ─────────────────────────────────────────────────────

    public void jail(UUID targetUUID, String reason, String jailedBy, long durationMs) {
        JailEntry entry = new JailEntry(reason, jailedBy, System.currentTimeMillis(),
                durationMs, ServerContext.SERVER_ID);
        jailCache.put(targetUUID, entry);

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> insertJailDb(targetUUID, entry));
        } else {
            saveToJson();
        }
    }

    public boolean unjail(UUID targetUUID) {
        JailEntry removed = jailCache.remove(targetUUID);
        if (removed == null) return false;

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> deleteJailDb(targetUUID));
        } else {
            saveToJson();
        }
        return true;
    }

    public boolean isJailed(UUID uuid) {
        JailEntry entry = jailCache.get(uuid);
        if (entry == null) return false;
        if (entry.isExpired()) {
            jailCache.remove(uuid);
            if (isDatabaseMode()) {
                DatabaseManager.executeAsync(() -> deleteJailDb(uuid));
            } else {
                saveToJson();
            }
            return false;
        }
        return true;
    }

    @Nullable
    public JailEntry getJailEntry(UUID uuid) {
        if (!isJailed(uuid)) return null;
        return jailCache.get(uuid);
    }

    public Map<UUID, JailEntry> getAllJailed() {
        // Clean expired entries
        jailCache.entrySet().removeIf(e -> e.getValue().isExpired());
        return Collections.unmodifiableMap(jailCache);
    }

    /** Teleport a player to the jail location. Returns true if successful. */
    public boolean teleportToJail(ServerPlayer player, MinecraftServer server) {
        if (jailLocation == null) return false;

        ServerLevel level = null;
        for (ServerLevel w : server.getAllLevels()) {
            if (w.dimension().location().toString().equals(jailLocation.dimension())) {
                level = w;
                break;
            }
        }
        if (level == null) level = server.overworld();

        player.teleportTo(level, jailLocation.x(), jailLocation.y(), jailLocation.z(),
                jailLocation.yaw(), jailLocation.pitch());
        return true;
    }

    /** Check and release expired jails. Called periodically. */
    public void tickExpiry(MinecraftServer server) {
        for (var it = jailCache.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue().isExpired()) {
                it.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    player.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.success(
                            LanguageHelper.getText("jail.released", player)));
                }
                if (isDatabaseMode()) {
                    UUID uuid = entry.getKey();
                    DatabaseManager.executeAsync(() -> deleteJailDb(uuid));
                } else {
                    saveToJson();
                }
            }
        }
    }

    // ── Allowed commands for jailed players ──────────────────────────────────

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "msg", "tell", "w", "reply", "r", "me", "help"
    );

    public boolean isCommandAllowed(String command) {
        String base = command.split(" ")[0].replace("/", "");
        return ALLOWED_COMMANDS.contains(base.toLowerCase(Locale.ROOT));
    }

    // ── JSON backend ────────────────────────────────────────────────────────

    private void loadFromJson() {
        if (!Files.exists(jailFile)) return;
        try (FileReader reader = new FileReader(jailFile.toFile())) {
            Map<UUID, JailEntry> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<UUID, JailEntry>>() {}.getType());
            if (loaded != null) jailCache.putAll(loaded);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load jail.json", e);
        }
    }

    private void saveToJson() {
        try (FileWriter writer = new FileWriter(jailTempFile.toFile())) {
            GSON.toJson(jailCache, writer);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to write jail temp file", e);
            return;
        }
        try {
            Files.move(jailTempFile, jailFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to atomically save jail.json", e);
        }
    }

    // ── Database backend ────────────────────────────────────────────────────

    private void loadFromDatabase() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, reason, jailed_by, server_id, timestamp, duration_ms FROM arcadia_admin_jail")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                JailEntry entry = new JailEntry(
                        rs.getString("reason"),
                        rs.getString("jailed_by"),
                        rs.getLong("timestamp"),
                        rs.getLong("duration_ms"),
                        rs.getString("server_id")
                );
                if (!entry.isExpired()) {
                    jailCache.put(uuid, entry);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load jail from database", e);
        }
    }

    private void insertJailDb(UUID targetUUID, JailEntry entry) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "REPLACE INTO arcadia_admin_jail (player_uuid, reason, jailed_by, server_id, timestamp, duration_ms) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, targetUUID.toString());
            ps.setString(2, entry.reason());
            ps.setString(3, entry.jailedBy());
            ps.setString(4, entry.serverId());
            ps.setLong(5, entry.timestamp());
            ps.setLong(6, entry.durationMs());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to insert jail into database", e);
        }
    }

    private void deleteJailDb(UUID targetUUID) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM arcadia_admin_jail WHERE player_uuid = ?")) {
            ps.setString(1, targetUUID.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to delete jail from database", e);
        }
    }

    public void reload() {
        jailCache.clear();
        init();
    }
}
