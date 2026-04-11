package com.arcadia.adminpanel.util;

import com.mojang.authlib.GameProfile;
import com.arcadia.adminpanel.AdminPanelMod;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages offline player data caching and retrieval
 * Singleton pattern for global access
 * 
 * @author vyrriox
 */
public class OfflinePlayerManager {
    private static OfflinePlayerManager instance;
    private final Map<UUID, CachedPlayerSummary> offlineCache = new ConcurrentHashMap<>();
    private boolean isInitialized = false;

    private OfflinePlayerManager() {
    }

    public static synchronized OfflinePlayerManager getInstance() {
        if (instance == null) {
            instance = new OfflinePlayerManager();
        }
        return instance;
    }

    private Path cachedRootPath;

    /**
     * Start async scanning of player data
     */
    public void init(MinecraftServer server, Path rootPath) {
        if (isInitialized)
            return;
        isInitialized = true;
        this.cachedRootPath = rootPath;
        startScan(server, rootPath);
    }

    public void reload(MinecraftServer server) {
        if (cachedRootPath == null) {
            System.out.println("[ArcadiaAdmin] Cannot reload: Root path not stored. Run init first.");
            return;
        }
        offlineCache.clear();
        SkullCache.clear(); // Also clear skulls
        startScan(server, cachedRootPath);
    }

    private void startScan(MinecraftServer server, Path rootPath) {
        // Start async scan
        new Thread(() -> {
            try {
                // Find FTB directory more robustly...
                Path ftbPath = findFTBDataDirectory(rootPath); // ...rest of logic
                if (ftbPath != null) {
                    FTBDataReader.setExactPath(ftbPath);
                    scanDirectory(server, ftbPath);
                } else {
                    System.out.println("[ArcadiaAdmin] Could not find FTB Essentials data directory!");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Arcadia-OfflineScan").start();
    }

    private Path findFTBDataDirectory(Path root) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(root.resolve("ftbessentials/playerdata"));
        candidates.add(root.resolve("world/ftbessentials/playerdata"));
        candidates.add(root.resolve("Arcadia_World/ftbessentials/playerdata"));

        // Read server.properties for dynamic level-name
        try {
            Path propsPath = root.resolve("server.properties");
            if (Files.exists(propsPath)) {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(propsPath)) {
                    props.load(reader);
                    String levelName = props.getProperty("level-name");
                    if (levelName != null && !levelName.isBlank()) {
                        candidates.add(root.resolve(levelName + "/ftbessentials/playerdata"));
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ArcadiaAdmin] Failed to read server.properties: " + e.getMessage());
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                System.out.println("[ArcadiaAdmin] Found FTB data at: " + candidate);
                return candidate;
            }
        }

        // 2. Scan explicitly for ftbessentials/playerdata folder if not found
        // Look up to 3 levels deep
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk
                    .filter(p -> p.endsWith("ftbessentials/playerdata"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void scanDirectory(MinecraftServer server, Path dataDir) {
        try (Stream<Path> stream = Files.list(dataDir)) {
            stream
                    .filter(p -> p.toString().endsWith(".snbt"))
                    .forEach(path -> {
                        try {
                            String filename = path.getFileName().toString().replace(".snbt", "");
                            UUID uuid = UUID.fromString(filename);

                            // Don't overwrite if already cached
                            if (!offlineCache.containsKey(uuid)) {
                                // Try to get name from server cache
                                Optional<GameProfile> profile = server.getProfileCache().get(uuid);
                                String name = profile.map(GameProfile::getName)
                                        .orElse("Unknown-" + uuid.toString().substring(0, 5));

                                offlineCache.put(uuid, new CachedPlayerSummary(uuid, name));

                                // Pre-load skull to force texture lookup safely
                                if (profile.isPresent()) {
                                    SkullCache.createSkull(profile.get());
                                }
                            }
                        } catch (Exception e) {
                            // Ignore malformed files
                        }
                    });
            System.out.println("[ArcadiaAdmin] Cached " + offlineCache.size() + " offline profiles from " + dataDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<CachedPlayerSummary> getAllOfflinePlayers() {
        return new ArrayList<>(offlineCache.values());
    }

    public Map<UUID, CachedPlayerSummary> getCache() {
        return offlineCache;
    }

    public record CachedPlayerSummary(UUID uuid, String name) {
    }
}
