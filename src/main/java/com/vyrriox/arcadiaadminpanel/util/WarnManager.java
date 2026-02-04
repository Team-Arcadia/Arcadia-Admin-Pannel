package com.vyrriox.arcadiaadminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player warnings.
 * Stores data in config/arcadia/arcadiaadminpanel/warns.json
 * Optimized for v1.1.1: Thread Safety & Anti-Corruption
 * 
 * @author vyrriox
 */
public class WarnManager {

    private static final WarnManager INSTANCE = new WarnManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Map<UUID, List<WarnEntry>> warnCache = new ConcurrentHashMap<>();
    private final File warnFile;
    private final File tempFile; // For atomic writes

    private WarnManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        warnFile = configDir.resolve("warns.json").toFile();
        tempFile = configDir.resolve("warns.json.tmp").toFile(); // Temp file for atomic write logic
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            loadAllWarns();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static WarnManager getInstance() {
        return INSTANCE;
    }

    public record WarnEntry(String reason, String by, long timestamp) {
    }

    public void addWarn(UUID targetUUID, String reason, String by) {
        // Use synchronized list to ensure thread safety
        List<WarnEntry> warnsByPlayer = warnCache.computeIfAbsent(targetUUID,
                k -> Collections.synchronizedList(new ArrayList<>()));
        warnsByPlayer.add(new WarnEntry(reason, by, System.currentTimeMillis()));
        saveAllWarns();
    }

    public boolean removeWarn(UUID targetUUID, int index) {
        List<WarnEntry> warnsByPlayer = warnCache.get(targetUUID);
        if (warnsByPlayer == null || warnsByPlayer.isEmpty())
            return false;

        // Thread-safe copy for sorting and modification logic
        List<WarnEntry> sortedWarns;
        synchronized (warnsByPlayer) {
            sortedWarns = new ArrayList<>(warnsByPlayer);
        }

        // Sort newest first to match GUI
        sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

        if (index < 1 || index > sortedWarns.size())
            return false;

        WarnEntry toRemove = sortedWarns.get(index - 1);

        // Safe remove
        warnsByPlayer.remove(toRemove);

        if (warnsByPlayer.isEmpty()) {
            warnCache.remove(targetUUID);
        }

        saveAllWarns();
        return true;
    }

    public List<WarnEntry> getWarns(UUID targetUUID) {
        List<WarnEntry> list = warnCache.get(targetUUID);
        if (list == null)
            return Collections.emptyList();
        // Return a thread-safe snapshot to avoid CME during iteration by consumers
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    private void loadAllWarns() {
        if (!warnFile.exists())
            return;

        try (FileReader reader = new FileReader(warnFile)) {
            Map<UUID, List<WarnEntry>> loaded = GSON.fromJson(reader, new TypeToken<Map<UUID, List<WarnEntry>>>() {
            }.getType());
            if (loaded != null) {
                // Ensure loaded lists are synchronized
                for (Map.Entry<UUID, List<WarnEntry>> entry : loaded.entrySet()) {
                    warnCache.put(entry.getKey(), Collections.synchronizedList(new ArrayList<>(entry.getValue())));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveAllWarns() {
        // Atomic Save: Write to .tmp -> Move to .json
        try (FileWriter writer = new FileWriter(tempFile)) {
            GSON.toJson(warnCache, writer);
        } catch (IOException e) {
            e.printStackTrace();
            return; // Don't attempt swap if write failed
        }

        try {
            Files.move(tempFile.toPath(), warnFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("[ArcadiaAdmin] Failed to save/move warns.json atomically.");
        }
    }
}
