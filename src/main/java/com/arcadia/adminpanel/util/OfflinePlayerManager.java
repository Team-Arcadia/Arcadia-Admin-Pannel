package com.arcadia.adminpanel.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages offline player data caching and retrieval.
 * Singleton pattern for global access.
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

                // ── FTB Teams + FTB Chunks discovery — INDEPENDENT of FTB Essentials ──────────
                // Previously this was nested inside the ftbessentials lookup, so a server running
                // FTB Teams without FTB Essentials never located the ftbteams dir and the Teams
                // browser stayed hidden. Resolve the world dir on its own and probe both dirs.
                // Done BEFORE the player scan so offline-name resolution can read FTB Teams'
                // cached player_name.
                List<Path> worldCandidates = new ArrayList<>();
                Path gw = locateWorldDir(server);
                if (gw != null) worldCandidates.add(gw);
                if (ftbPath != null && ftbPath.getParent() != null
                        && ftbPath.getParent().getParent() != null) {
                    worldCandidates.add(ftbPath.getParent().getParent());
                }
                boolean teamsFound = false, chunksFound = false;
                for (Path wd : worldCandidates) {
                    if (!teamsFound) {
                        Path teamsDir = wd.resolve("ftbteams");
                        if (Files.isDirectory(teamsDir)) {
                            FTBTeamsReader.setBasePath(teamsDir);
                            LOGGER.info("[AdminPanel] Found FTB Teams data at: {}", teamsDir);
                            teamsFound = true;
                        }
                    }
                    if (!chunksFound) {
                        Path chunksDir = wd.resolve("ftbchunks");
                        if (Files.isDirectory(chunksDir)) {
                            FTBChunksReader.setBasePath(chunksDir);
                            LOGGER.info("[AdminPanel] Found FTB Chunks data at: {}", chunksDir);
                            chunksFound = true;
                        }
                    }
                }
                if (!teamsFound) LOGGER.info("[AdminPanel] No FTB Teams data dir found (mod not installed?)");
                if (!chunksFound) LOGGER.info("[AdminPanel] No FTB Chunks data dir found (mod not installed?)");

                // ── FTB Essentials player scan (homes / last-seen / login tracking) ──────────
                if (ftbPath != null) {
                    FTBDataReader.setExactPath(ftbPath);
                    scanDirectory(server, ftbPath);
                } else {
                    LOGGER.warn("[AdminPanel] Could not find FTB Essentials data directory under {} — checked server root, ./world, server.properties level-name, and getWorldPath. Offline homes/last-seen will be unavailable (FTB Teams browser still works if FTB Teams is installed).", rootPath);
                }
            } catch (Exception e) {
                LOGGER.error("[AdminPanel] Offline scan failed", e);
            }
        }, "Arcadia-OfflineScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    /** Best-effort world-dir lookup, independent of FTB Essentials. */
    @Nullable
    private Path locateWorldDir(MinecraftServer server) {
        try {
            Path w = server.getWorldPath(LevelResource.ROOT);
            if (w != null && Files.isDirectory(w)) return w;
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] getWorldPath failed for teams/chunks discovery", e);
        }
        return null;
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
            Path worldDir = server.getWorldPath(LevelResource.ROOT);
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
        // Build the usercache.json map once (uuid -> name) so we don't reparse it per player.
        Map<UUID, String> userCache = loadUserCacheMap();

        try (Stream<Path> stream = Files.list(dataDir)) {
            stream
                    .filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .forEach(path -> {
                        try {
                            String filename = path.getFileName().toString();
                            // Strip ".snbt" only at the end (not via .replace, which is a substring replace).
                            filename = filename.substring(0, filename.length() - 5);
                            UUID uuid = UUID.fromString(filename);

                            CachedPlayerSummary existing = offlineCache.get(uuid);
                            boolean placeholder = existing != null && existing.name().startsWith("Unknown-");
                            // Resolve a real name on first sight OR if we only have a placeholder so far
                            // (re-scan / reload can upgrade a stale "Unknown-xxxx" once a source appears).
                            if (existing == null || placeholder) {
                                String name = resolveName(server, uuid, userCache);
                                if (name == null) {
                                    name = existing != null ? existing.name()
                                            : "Unknown-" + uuid.toString().substring(0, 8);
                                }
                                offlineCache.put(uuid, new CachedPlayerSummary(uuid, name));

                                // Warm the textured profile so the GUI can show the real skin (async).
                                SkullCache.warmTextures(server, uuid);

                                if (existing == null) {
                                    // Track first-time observation for "first seen" display.
                                    LoginTracker.getInstance().observeFromScan(uuid, path);
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

    /**
     * Multi-source offline-name resolution. Tries, in order: (1) the in-memory profile cache
     * (usercache.json, no network), (2) FTB Teams' cached {@code player_name} (covers every player
     * who ever got a personal team — far beyond usercache's ~1000 MRU cap), (3) usercache.json
     * parsed directly (catches entries the in-memory MRU dropped). Returns {@code null} only when
     * every source is empty — the caller then keeps any existing value or a short-UUID placeholder.
     */
    @Nullable
    private String resolveName(MinecraftServer server, UUID uuid, Map<UUID, String> userCache) {
        Optional<GameProfile> profile = server.getProfileCache().get(uuid);
        if (profile.isPresent()) {
            String n = profile.get().getName();
            if (n != null && !n.isBlank()) return n;
        }
        String ftbName = FTBTeamsReader.getPlayerName(uuid);
        if (ftbName != null && !ftbName.isBlank()) return ftbName;
        String diskName = userCache.get(uuid);
        if (diskName != null && !diskName.isBlank()) return diskName;
        return null;
    }

    /** Parse {@code <root>/usercache.json} into a uuid -> name map. Best-effort; never throws. */
    private Map<UUID, String> loadUserCacheMap() {
        Map<UUID, String> map = new HashMap<>();
        if (cachedRootPath == null) return map;
        Path file = cachedRootPath.resolve("usercache.json");
        if (!Files.isRegularFile(file)) return map;
        try (Reader r = Files.newBufferedReader(file)) {
            JsonElement parsed = JsonParser.parseReader(r);
            if (!parsed.isJsonArray()) return map;
            JsonArray arr = parsed.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("uuid") || !o.has("name")) continue;
                try {
                    UUID id = UUID.fromString(o.get("uuid").getAsString());
                    String name = o.get("name").getAsString();
                    if (name != null && !name.isBlank()) map.put(id, name);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] Failed to read usercache.json: {}", e.getMessage());
        }
        return map;
    }

    /**
     * Upsert a definitive name into the offline cache. Called on player login (authoritative source)
     * so an entry that was scanned as "Unknown-xxxx" is repaired immediately, with no reload and no
     * network dependency.
     */
    public void upsertName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        offlineCache.put(uuid, new CachedPlayerSummary(uuid, name));
    }

    public List<CachedPlayerSummary> getAllOfflinePlayers() {
        return new ArrayList<>(offlineCache.values());
    }

    /**
     * Best-known display name for a UUID, or {@code null} when this server has never seen it.
     * In-memory only: callers on the tick thread can use it freely.
     */
    @org.jetbrains.annotations.Nullable
    public String getName(UUID uuid) {
        CachedPlayerSummary summary = getCache().get(uuid);
        return summary != null ? summary.name() : null;
    }

    public Map<UUID, CachedPlayerSummary> getCache() {
        return offlineCache;
    }

    public record CachedPlayerSummary(UUID uuid, String name) {
    }
}
