package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads FTB Essentials player data from {@code <world>/ftbessentials/playerdata/<uuid>.snbt}.
 *
 * <p>FTB Essentials serialises this file as pretty-printed, indented, <b>multi-line</b> SNBT via
 * FTB Library's writer. We therefore parse the whole file as a single NBT compound with vanilla
 * {@link TagParser} (which handles both inline and multi-line SNBT) and navigate it via the NBT
 * API — never line-by-line, which silently dropped homes / last-seen / teleport history whenever
 * a value spanned more than one line.</p>
 *
 * <p>Optimised with a 30 s TTL cache that also <b>negative-caches</b> misses (missing or
 * unparseable files) so the admin GUI never re-stats the disk on every redraw.</p>
 *
 * @author vyrriox
 */
public class FTBDataReader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Path ftbDataPath = null;

    // Cache System
    private static final Map<UUID, CachedData> dataCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 30_000L; // 30 Seconds

    private record CachedData(@Nullable PlayerFTBData data, long timestamp) {
    }

    public static void clearCache() {
        LOGGER.info("[AdminPanel] Clearing FTB Data Cache ({} entries)", dataCache.size());
        dataCache.clear();
    }

    public static void init(Path serverPath) {
        ftbDataPath = serverPath.resolve("ftbessentials").resolve("playerdata");
    }

    public static void setExactPath(Path path) {
        ftbDataPath = path;
    }

    public static Path getFTBDataPath() {
        return ftbDataPath;
    }

    /**
     * Lazily (re)locate {@code <world>/ftbessentials/playerdata} from the running server, in case the
     * boot-time scan ran before FTB Essentials created the directory — it is written on the first
     * player save, so on a fresh world the startup scan finds nothing and homes would stay invisible
     * until the next restart. Mirrors {@link FTBTeamsReader#ensureLocated}. No-op once located.
     */
    public static void ensureLocated(@Nullable net.minecraft.server.MinecraftServer server) {
        if (server == null || (ftbDataPath != null && Files.isDirectory(ftbDataPath))) return;
        try {
            Path world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            if (world == null) return;
            Path dir = world.resolve("ftbessentials").resolve("playerdata");
            if (Files.isDirectory(dir)) {
                ftbDataPath = dir;
                dataCache.clear();
                LOGGER.info("[AdminPanel] Located FTB Essentials player data on demand at: {}", dir);
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] ensureLocated failed: {}", e.getMessage());
        }
    }

    @Nullable
    public static PlayerFTBData readPlayerData(UUID uuid) {
        if (ftbDataPath == null)
            return null;

        // Check cache (positive AND negative — a cached null is a valid "no data" answer).
        CachedData cached = dataCache.get(uuid);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < CACHE_TTL)) {
            return cached.data;
        }

        Path dataFile = ftbDataPath.resolve(uuid.toString() + ".snbt");
        if (!Files.exists(dataFile)) {
            // Negative-cache: a player who never set a home / teleported has no file. Without this,
            // every member skull in the team menu would Files.exists() on the server thread per redraw.
            dataCache.put(uuid, new CachedData(null, System.currentTimeMillis()));
            return null;
        }

        try {
            // Whole-file NBT parse. FTB Essentials writes pretty-printed multi-line SNBT; the prior
            // line-based scan only handled single-line values and thus returned empty homes / null
            // last-seen / empty history on real installs. It must go through SnbtCompat: FTB Library
            // separates entries with line breaks instead of commas, which vanilla TagParser refuses
            // outright (issue #219 — every player file failed to parse, so no homes ever showed).
            CompoundTag root = SnbtCompat.parse(Files.readString(dataFile));

            Map<String, HomeLocation> homes = new HashMap<>();
            if (root.contains("homes", Tag.TAG_COMPOUND)) {
                CompoundTag homesTag = root.getCompound("homes");
                for (String name : homesTag.getAllKeys()) {
                    CompoundTag h = homesTag.getCompound(name);
                    homes.put(name, new HomeLocation(
                            h.getString("dim"),
                            h.getDouble("x"), h.getDouble("y"), h.getDouble("z"),
                            h.getFloat("xRot"), h.getFloat("yRot"),
                            h.getLong("time")));
                }
            }

            LastSeenLocation lastSeen = null;
            if (root.contains("last_seen", Tag.TAG_COMPOUND)) {
                CompoundTag t = root.getCompound("last_seen");
                lastSeen = new LastSeenLocation(
                        t.getString("dim"),
                        t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                        t.getFloat("xRot"), t.getFloat("yRot"),
                        t.getLong("time"));
            }

            List<TeleportRecord> history = new ArrayList<>();
            if (root.contains("teleport_history", Tag.TAG_LIST)) {
                ListTag list = root.getList("teleport_history", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    history.add(new TeleportRecord(
                            t.getString("dim"),
                            t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                            t.getLong("time")));
                }
                // Newest-first for the GUI (FTB appends chronologically).
                Collections.reverse(history);
            }

            PlayerFTBData data = new PlayerFTBData(homes, lastSeen, history);
            dataCache.put(uuid, new CachedData(data, System.currentTimeMillis()));
            return data;

        } catch (IOException | RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            // WARN, not debug: this failing means the whole homes / last-seen / history block goes
            // blank in the GUI. Swallowing it at debug is what let issue #219 stay invisible.
            LOGGER.warn("[AdminPanel] Failed to parse FTB player data {}: {}", dataFile, e.getMessage());
            // Negative-cache parse failures too, so a malformed file isn't re-read every redraw.
            dataCache.put(uuid, new CachedData(null, System.currentTimeMillis()));
            return null;
        }
    }

    /**
     * Shared dimension prettifier: {@code minecraft:the_nether} -&gt; {@code The_nether}. Guards the
     * empty-path-segment case (e.g. a corrupt {@code "a::b"}) so GUI lore building never throws
     * {@link StringIndexOutOfBoundsException}.
     */
    static String shortDimension(String dimension) {
        if (dimension == null || dimension.isEmpty()) return dimension == null ? "" : dimension;
        String[] parts = dimension.split(":");
        String name = parts.length > 1 ? parts[1] : dimension;
        if (name.isEmpty()) return dimension;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public static class PlayerFTBData {
        public final Map<String, HomeLocation> homes;
        @Nullable
        public final LastSeenLocation lastSeen;
        public final List<TeleportRecord> teleportHistory;

        public PlayerFTBData(Map<String, HomeLocation> homes, @Nullable LastSeenLocation lastSeen,
                List<TeleportRecord> teleportHistory) {
            this.homes = homes;
            this.lastSeen = lastSeen;
            this.teleportHistory = teleportHistory;
        }
    }

    public static class HomeLocation {
        public final String dimension;
        public final double x, y, z;
        public final float xRot, yRot;
        public final long time;

        public HomeLocation(String dimension, double x, double y, double z, float xRot, float yRot, long time) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.xRot = xRot;
            this.yRot = yRot;
            this.time = time;
        }

        public String getFormattedCoords() {
            return String.format("%.0f, %.0f, %.0f", x, y, z);
        }

        public String getShortDimension() {
            return FTBDataReader.shortDimension(dimension);
        }
    }

    public static class LastSeenLocation {
        public final String dimension;
        public final double x, y, z;
        public final float xRot, yRot;
        public final long time;

        public LastSeenLocation(String dimension, double x, double y, double z, float xRot, float yRot, long time) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.xRot = xRot;
            this.yRot = yRot;
            this.time = time;
        }

        public String getFormattedCoords() {
            return String.format("%.0f, %.0f, %.0f", x, y, z);
        }

        public String getShortDimension() {
            return FTBDataReader.shortDimension(dimension);
        }
    }

    public static class TeleportRecord {
        public final String dimension;
        public final double x, y, z;
        public final long time;

        public TeleportRecord(String dimension, double x, double y, double z, long time) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }

        public String getFormattedCoords() {
            return String.format("%.0f, %.0f, %.0f", x, y, z);
        }

        public String getShortDimension() {
            return FTBDataReader.shortDimension(dimension);
        }
    }
}
