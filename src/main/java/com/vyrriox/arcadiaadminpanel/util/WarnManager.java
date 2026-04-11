package com.vyrriox.arcadiaadminpanel.util;

import com.arcadia.lib.ServerContext;
import com.arcadia.lib.data.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
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
 * Manages player warnings with dual storage: MySQL (multi-server sync) or JSON (standalone).
 * Automatically selects backend based on DatabaseManager state.
 *
 * @author vyrriox
 */
public final class WarnManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final WarnManager INSTANCE = new WarnManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, List<WarnEntry>> warnCache = new ConcurrentHashMap<>();
    private final Path warnFile;
    private final Path tempFile;

    public record WarnEntry(String reason, String by, long timestamp, String serverId) {
        public WarnEntry(String reason, String by, long timestamp) {
            this(reason, by, timestamp, ServerContext.SERVER_ID);
        }
    }

    private WarnManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        warnFile = configDir.resolve("warns.json");
        tempFile = configDir.resolve("warns.json.tmp");
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create config directory", e);
        }
    }

    public static WarnManager getInstance() {
        return INSTANCE;
    }

    /** Initialize: load from appropriate backend. */
    public void init() {
        if (isDatabaseMode()) {
            loadFromDatabase();
        } else {
            loadFromJson();
        }
        LOGGER.info("[AdminPanel] WarnManager initialized ({} mode, {} players cached)",
                isDatabaseMode() ? "database" : "json", warnCache.size());
    }

    public boolean isDatabaseMode() {
        return DatabaseManager.isDatabaseActive();
    }

    // ── Write operations ────────────────────────────────────────────────────

    public void addWarn(UUID targetUUID, String reason, String by) {
        long timestamp = System.currentTimeMillis();
        WarnEntry entry = new WarnEntry(reason, by, timestamp, ServerContext.SERVER_ID);

        warnCache.computeIfAbsent(targetUUID,
                k -> Collections.synchronizedList(new ArrayList<>())).add(entry);

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> insertWarnDb(targetUUID, entry));
        } else {
            saveToJson();
        }
    }

    public boolean removeWarn(UUID targetUUID, int index) {
        List<WarnEntry> warns = warnCache.get(targetUUID);
        if (warns == null || warns.isEmpty()) return false;

        List<WarnEntry> sortedWarns;
        synchronized (warns) {
            sortedWarns = new ArrayList<>(warns);
        }
        sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

        if (index < 1 || index > sortedWarns.size()) return false;

        WarnEntry toRemove = sortedWarns.get(index - 1);
        warns.remove(toRemove);

        if (warns.isEmpty()) {
            warnCache.remove(targetUUID);
        }

        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> deleteWarnDb(targetUUID, toRemove));
        } else {
            saveToJson();
        }
        return true;
    }

    public int clearWarns(UUID targetUUID) {
        List<WarnEntry> removed = warnCache.remove(targetUUID);
        int count = removed != null ? removed.size() : 0;

        if (count > 0) {
            if (isDatabaseMode()) {
                DatabaseManager.executeAsync(() -> clearWarnsDb(targetUUID));
            } else {
                saveToJson();
            }
        }
        return count;
    }

    // ── Read operations ─────────────────────────────────────────────────────

    public List<WarnEntry> getWarns(UUID targetUUID) {
        List<WarnEntry> list = warnCache.get(targetUUID);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public int getWarnCount(UUID targetUUID) {
        List<WarnEntry> list = warnCache.get(targetUUID);
        return list != null ? list.size() : 0;
    }

    // ── JSON backend ────────────────────────────────────────────────────────

    private void loadFromJson() {
        if (!Files.exists(warnFile)) return;
        try (FileReader reader = new FileReader(warnFile.toFile())) {
            Map<UUID, List<WarnEntry>> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<UUID, List<WarnEntry>>>() {}.getType());
            if (loaded != null) {
                for (var entry : loaded.entrySet()) {
                    warnCache.put(entry.getKey(),
                            Collections.synchronizedList(new ArrayList<>(entry.getValue())));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load warns.json", e);
        }
    }

    private void saveToJson() {
        try (FileWriter writer = new FileWriter(tempFile.toFile())) {
            GSON.toJson(warnCache, writer);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to write warns temp file", e);
            return;
        }
        try {
            Files.move(tempFile, warnFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to atomically save warns.json", e);
        }
    }

    // ── Database backend ────────────────────────────────────────────────────

    private void loadFromDatabase() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT player_uuid, reason, warned_by, server_id, timestamp FROM arcadia_admin_warns")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                WarnEntry entry = new WarnEntry(
                        rs.getString("reason"),
                        rs.getString("warned_by"),
                        rs.getLong("timestamp"),
                        rs.getString("server_id")
                );
                warnCache.computeIfAbsent(uuid,
                        k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load warns from database", e);
        }
    }

    private void insertWarnDb(UUID targetUUID, WarnEntry entry) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO arcadia_admin_warns (player_uuid, reason, warned_by, server_id, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, targetUUID.toString());
            ps.setString(2, entry.reason());
            ps.setString(3, entry.by());
            ps.setString(4, entry.serverId());
            ps.setLong(5, entry.timestamp());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to insert warn into database", e);
        }
    }

    private void deleteWarnDb(UUID targetUUID, WarnEntry entry) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM arcadia_admin_warns WHERE player_uuid = ? AND timestamp = ? AND warned_by = ? LIMIT 1")) {
            ps.setString(1, targetUUID.toString());
            ps.setLong(2, entry.timestamp());
            ps.setString(3, entry.by());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to delete warn from database", e);
        }
    }

    private void clearWarnsDb(UUID targetUUID) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM arcadia_admin_warns WHERE player_uuid = ?")) {
            ps.setString(1, targetUUID.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to clear warns from database", e);
        }
    }

    /** Reload cache from backend. */
    public void reload() {
        warnCache.clear();
        init();
    }
}
