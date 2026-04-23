package com.arcadia.adminpanel.util;

import com.arcadia.lib.ServerContext;
import com.arcadia.lib.data.DatabaseManager;
import com.arcadia.lib.scheduler.SchedulerService;
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
                            String serverId, @Nullable PreviousLocation previousLocation) {
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

    /**
     * Player's position captured at the moment they were jailed, restored on release.
     * May be {@code null} on entries created before this feature was added — in that
     * case release is silent (no teleport).
     */
    public record PreviousLocation(String dimension, double x, double y, double z,
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
        // Re-schedule releases for existing timed jails
        for (var entry : jailCache.entrySet()) {
            JailEntry jail = entry.getValue();
            if (jail.durationMs() > 0 && !jail.isExpired()) {
                long remainingMs = jail.getRemainingMs();
                int delayTicks = Math.max(1, (int) (remainingMs / 50L));
                UUID uuid = entry.getKey();
                SchedulerService.delayed(delayTicks, () -> {
                    if (jailCache.containsKey(uuid) && jailCache.get(uuid).isExpired()) {
                        jailCache.remove(uuid);
                        if (isDatabaseMode()) {
                            DatabaseManager.executeAsync(() -> deleteJailDb(uuid));
                        } else {
                            saveToJson();
                        }
                        var player = com.arcadia.lib.player.PlayerManager.getPlayer(uuid);
                        if (player != null) {
                            player.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.success(
                                    LanguageHelper.getText("jail.released", player)));
                        }
                    }
                });
            }
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

    /**
     * Jails a player and teleports them to the jail location. Captures their current
     * position so {@link #unjail} (or auto-expiry) can put them back where they were.
     *
     * <p>Call this variant whenever the target is online — it is the only code path
     * that records {@link PreviousLocation}. The legacy UUID-only overload is kept for
     * callers that have just a UUID (e.g. offline target) and silently stores a
     * {@code null} previous location.</p>
     */
    public void jail(ServerPlayer target, String reason, String jailedBy, long durationMs,
                     MinecraftServer server) {
        PreviousLocation prev = new PreviousLocation(
                target.serverLevel().dimension().location().toString(),
                target.getX(), target.getY(), target.getZ(),
                target.getYRot(), target.getXRot()
        );
        jailInternal(target.getUUID(), reason, jailedBy, durationMs, prev);
        teleportToJail(target, server);
    }

    /** Back-compat overload — no previous location is recorded (no teleport on release). */
    public void jail(UUID targetUUID, String reason, String jailedBy, long durationMs) {
        jailInternal(targetUUID, reason, jailedBy, durationMs, null);
    }

    private void jailInternal(UUID targetUUID, String reason, String jailedBy, long durationMs,
                              @Nullable PreviousLocation prev) {
        JailEntry entry = new JailEntry(reason, jailedBy, System.currentTimeMillis(),
                durationMs, ServerContext.SERVER_ID, prev);
        jailCache.put(targetUUID, entry);

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> insertJailDb(targetUUID, entry));
        } else {
            saveToJson();
        }

        // Schedule auto-release (no tick polling)
        if (durationMs > 0) {
            int delayTicks = (int) (durationMs / 50L); // ms -> ticks
            SchedulerService.delayed(delayTicks, () -> {
                JailEntry current = jailCache.get(targetUUID);
                if (current != null && current.isExpired()) {
                    jailCache.remove(targetUUID);
                    if (isDatabaseMode()) {
                        DatabaseManager.executeAsync(() -> deleteJailDb(targetUUID));
                    } else {
                        saveToJson();
                    }
                    var player = com.arcadia.lib.player.PlayerManager.getPlayer(targetUUID);
                    if (player != null) {
                        if (current.previousLocation() != null) {
                            teleportToPrevious(player, current.previousLocation(), player.getServer());
                        }
                        player.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.success(
                                LanguageHelper.getText("jail.released", player)));
                    }
                    LOGGER.info("[AdminPanel] Jail expired for {}", targetUUID);
                }
            });
        }
    }

    /**
     * Releases a jailed player. If {@code server} is provided and the player is online
     * and the entry has a stored previous location, teleports them back there.
     */
    public boolean unjail(UUID targetUUID, @Nullable MinecraftServer server) {
        JailEntry removed = jailCache.remove(targetUUID);
        if (removed == null) return false;

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> deleteJailDb(targetUUID));
        } else {
            saveToJson();
        }

        if (server != null && removed.previousLocation() != null) {
            ServerPlayer target = server.getPlayerList().getPlayer(targetUUID);
            if (target != null) teleportToPrevious(target, removed.previousLocation(), server);
        }
        return true;
    }

    /** Back-compat overload — no server context, so no teleport-back. */
    public boolean unjail(UUID targetUUID) {
        return unjail(targetUUID, null);
    }

    /** Teleports a player back to their pre-jail position. Best-effort: falls back to overworld if the dimension is gone. */
    private void teleportToPrevious(ServerPlayer player, PreviousLocation loc, MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = null;
        for (ServerLevel w : server.getAllLevels()) {
            if (w.dimension().location().toString().equals(loc.dimension())) {
                level = w;
                break;
            }
        }
        if (level == null) level = server.overworld();
        player.teleportTo(level, loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
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
        migrateJailSchema();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, reason, jailed_by, server_id, timestamp, duration_ms, "
                   + "prev_dimension, prev_x, prev_y, prev_z, prev_yaw, prev_pitch "
                   + "FROM arcadia_admin_jail")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                String prevDim = rs.getString("prev_dimension");
                PreviousLocation prev = null;
                if (prevDim != null) {
                    prev = new PreviousLocation(prevDim,
                            rs.getDouble("prev_x"), rs.getDouble("prev_y"), rs.getDouble("prev_z"),
                            rs.getFloat("prev_yaw"), rs.getFloat("prev_pitch"));
                }
                JailEntry entry = new JailEntry(
                        rs.getString("reason"),
                        rs.getString("jailed_by"),
                        rs.getLong("timestamp"),
                        rs.getLong("duration_ms"),
                        rs.getString("server_id"),
                        prev
                );
                if (!entry.isExpired()) {
                    jailCache.put(uuid, entry);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load jail from database", e);
        }
    }

    /**
     * Adds the {@code prev_*} columns to existing {@code arcadia_admin_jail} tables
     * created before 1.2.1. Safe to run on fresh installs: if the column already
     * exists (or the table was just created with them), MySQL returns error 1060
     * "Duplicate column name" which we swallow.
     */
    private void migrateJailSchema() {
        String[] alters = new String[]{
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_dimension VARCHAR(128) NULL",
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_x DOUBLE NULL",
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_y DOUBLE NULL",
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_z DOUBLE NULL",
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_yaw FLOAT NULL",
                "ALTER TABLE arcadia_admin_jail ADD COLUMN prev_pitch FLOAT NULL"
        };
        try (Connection conn = DatabaseManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            for (String sql : alters) {
                try { stmt.executeUpdate(sql); }
                catch (java.sql.SQLException e) {
                    // 1060 = "Duplicate column name" — column already present, fine.
                    if (e.getErrorCode() != 1060) {
                        LOGGER.warn("[AdminPanel] Jail schema migration step failed: {}", sql, e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Jail schema migration failed to open connection", e);
        }
    }

    private void insertJailDb(UUID targetUUID, JailEntry entry) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "REPLACE INTO arcadia_admin_jail "
                   + "(player_uuid, reason, jailed_by, server_id, timestamp, duration_ms, "
                   + "prev_dimension, prev_x, prev_y, prev_z, prev_yaw, prev_pitch) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, targetUUID.toString());
            ps.setString(2, entry.reason());
            ps.setString(3, entry.jailedBy());
            ps.setString(4, entry.serverId());
            ps.setLong(5, entry.timestamp());
            ps.setLong(6, entry.durationMs());
            PreviousLocation prev = entry.previousLocation();
            if (prev != null) {
                ps.setString(7, prev.dimension());
                ps.setDouble(8, prev.x());
                ps.setDouble(9, prev.y());
                ps.setDouble(10, prev.z());
                ps.setFloat(11, prev.yaw());
                ps.setFloat(12, prev.pitch());
            } else {
                ps.setNull(7, java.sql.Types.VARCHAR);
                ps.setNull(8, java.sql.Types.DOUBLE);
                ps.setNull(9, java.sql.Types.DOUBLE);
                ps.setNull(10, java.sql.Types.DOUBLE);
                ps.setNull(11, java.sql.Types.FLOAT);
                ps.setNull(12, java.sql.Types.FLOAT);
            }
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
