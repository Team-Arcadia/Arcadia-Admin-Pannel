package com.arcadia.adminpanel.util;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

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

    private static final Logger LOGGER = LogUtils.getLogger();
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
            LOGGER.warn("[AdminPanel] Cannot reload: Root path not stored. Run init first.");
            return;
        }
        offlineCache.clear();
        SkullCache.clear(); // Also clear skulls
        startScan(server, cachedRootPath);
    }

    private void startScan(MinecraftServer server, Path rootPath) {
        // Start async scan — daemon so it never blocks server shutdown.
        Thread scanThread = new Thread(() -> {
            try {
                Path ftbPath = findFTBDataDirectory(server, rootPath);
                if (ftbPath != null) {
                    FTBDataReader.setExactPath(ftbPath);
                    scanDirectory(server, ftbPath);
                    // Locate team data once player scan succeeds (sibling of ftbessentials).
                    Path worldDir = ftbPath.getParent() != null ? ftbPath.getParent().getParent() : null;
                    if (worldDir != null) {
                        Path teamsDir = worldDir.resolve("ftbteams");
                        if (Files.isDirectory(teamsDir)) {
                            FTBTeamsReader.setBasePath(teamsDir);
                            LOGGER.info("[AdminPanel] Found FTB Teams data at: {}", teamsDir);
                        } else {
                            LOGGER.info("[AdminPanel] No FTB Teams data dir at {} (mod not installed?)", teamsDir);
                        }
                    }
                } else {
                    LOGGER.warn("[AdminPanel] Could not find FTB Essentials data directory under {} — checked server root, ./world, server.properties level-name, and getWorldPath. Are FTB Essentials installed and the world loaded?", rootPath);
                }
            } catch (Exception e) {
                LOGGER.error("[AdminPanel] Offline scan failed", e);
            }
        }, "Arcadia-OfflineScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /**
     * Locate {@code <world>/ftbessentials/playerdata/}. FTB Essentials hardcodes this path under
     * the live world dir via {@code server.getWorldPath(LevelResource("ftbessentials"))}, so we
     * prefer that authoritative lookup first, then fall back to a candidate list (handles older
     * worlds, manual installs, weird launchers), and finally a bounded walk that uses path-segment
     * matching (not the buggy {@code Path.endsWith("a/b")} we had — that compares against the OS
     * separator and silently fails on Windows when the world is somewhere other than {@code ./world}).
     */
    private Path findFTBDataDirectory(MinecraftServer server, Path root) {
        // 1. Authoritative: ask the server for the world dir and resolve ftbessentials/playerdata.
        try {
            Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            if (worldDir != null) {
                Path expected = worldDir.resolve("ftbessentials").resolve("playerdata");
                if (Files.isDirectory(expected)) {
                    LOGGER.info("[AdminPanel] Found FTB data at: {} (server.getWorldPath)", expected);
                    return expected;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] getWorldPath lookup failed, falling back to candidates", e);
        }

        // 2. Static candidates — covers integrated server saves dir, common launcher layouts,
        //    and the legacy Arcadia_World path explicitly so existing installs keep working.
        List<Path> candidates = new ArrayList<>();
        candidates.add(root.resolve("ftbessentials").resolve("playerdata"));
        candidates.add(root.resolve("world").resolve("ftbessentials").resolve("playerdata"));
        candidates.add(root.resolve("Arcadia_World").resolve("ftbessentials").resolve("playerdata"));

        // 3. Read server.properties for the configured level-name.
        try {
            Path propsPath = root.resolve("server.properties");
            if (Files.exists(propsPath)) {
                Properties props = new Properties();
                try (var reader = Files.newBufferedReader(propsPath)) {
                    props.load(reader);
                }
                String levelName = props.getProperty("level-name");
                if (levelName != null && !levelName.isBlank()) {
                    candidates.add(root.resolve(levelName).resolve("ftbessentials").resolve("playerdata"));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AdminPanel] Failed to read server.properties: {}", e.getMessage());
        }

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                LOGGER.info("[AdminPanel] Found FTB data at: {} (candidate)", candidate);
                return candidate;
            }
        }

        // 4. Bounded recursive walk — uses path-segment comparison (correct on all OSes).
        //    Depth 4 to handle ./saves/<world>/ftbessentials/playerdata and similar nestings,
        //    skipping noisy mod folders to avoid AccessDeniedException + wasted IO.
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        int n = p.getNameCount();
                        if (n < 2) return false;
                        return p.getName(n - 1).toString().equals("playerdata")
                                && p.getName(n - 2).toString().equals("ftbessentials");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.warn("[AdminPanel] Recursive scan for FTB data dir failed: {}", e.getMessage());
            return null;
        }
    }

    private void scanDirectory(MinecraftServer server, Path dataDir) {
        try (Stream<Path> stream = Files.list(dataDir)) {
            stream
                    .filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .forEach(path -> {
                        try {
                            String filename = path.getFileName().toString();
                            // Strip ".snbt" only at the end (not via .replace, which is a substring replace).
                            filename = filename.substring(0, filename.length() - 5);
                            UUID uuid = UUID.fromString(filename);

                            // Don't overwrite if already cached
                            if (!offlineCache.containsKey(uuid)) {
                                Optional<GameProfile> profile = server.getProfileCache().get(uuid);
                                String name = profile.map(GameProfile::getName)
                                        .orElse("Unknown-" + uuid.toString().substring(0, 5));

                                offlineCache.put(uuid, new CachedPlayerSummary(uuid, name));

                                // Track first-time observation for "first seen" display.
                                LoginTracker.getInstance().observeFromScan(uuid, path);

                                if (profile.isPresent()) {
                                    SkullCache.createSkull(profile.get());
                                }
                            }
                        } catch (IllegalArgumentException ignored) {
                            // Non-UUID filename — ignore.
                        } catch (Exception e) {
                            LOGGER.debug("[AdminPanel] Skipped malformed playerdata file {}: {}", path, e.getMessage());
                        }
                    });
            LOGGER.info("[AdminPanel] Cached {} offline profiles from {}", offlineCache.size(), dataDir);
            // Persist any new firstSeen timestamps observed by LoginTracker during the scan.
            LoginTracker.getInstance().flush();
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to list player data directory {}", dataDir, e);
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
