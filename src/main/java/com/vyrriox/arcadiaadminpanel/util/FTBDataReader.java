package com.vyrriox.arcadiaadminpanel.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads FTB Essentials player data from .snbt files
 * Optimized with Caching (30s TTL) and Manual Parsing for Robustness
 * 
 * @author vyrriox
 */
public class FTBDataReader {

    private static Path ftbDataPath = null;

    // Cache System
    private static final Map<UUID, CachedData> dataCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 30_000L; // 30 Seconds

    private record CachedData(PlayerFTBData data, long timestamp) {
    }

    public static void clearCache() {
        System.out.println("[ArcadiaAdmin] Clearing FTB Data Cache...");
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

    @Nullable
    public static PlayerFTBData readPlayerData(UUID uuid) {
        if (ftbDataPath == null)
            return null;

        // check cache
        CachedData cached = dataCache.get(uuid);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < CACHE_TTL)) {
            return cached.data;
        }

        Path dataFile = ftbDataPath.resolve(uuid.toString() + ".snbt");
        if (!Files.exists(dataFile))
            return null;

        try {
            List<String> lines = Files.readAllLines(dataFile);
            Map<String, HomeLocation> homes = new HashMap<>();
            LastSeenLocation lastSeen = null;
            List<TeleportRecord> history = new ArrayList<>();

            boolean inHomes = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty())
                    continue;
                // Detect start of homes block
                if (line.startsWith("homes: {") || (line.startsWith("homes:") && line.endsWith("{"))) {
                    // Fix: Check if it is an empty inline block "homes: {}"
                    if (line.contains("}")) {
                        inHomes = false; // It opens and closes on same line, do not enter block mode
                    } else {
                        inHomes = true;
                    }
                    continue;
                }

                if (inHomes && line.equals("}")) {
                    inHomes = false;
                    continue;
                }

                if (inHomes) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String homeName = line.substring(0, colonIndex).trim();
                        String nbtPart = line.substring(colonIndex + 1).trim();
                        try {
                            CompoundTag homeTag = TagParser.parseTag(nbtPart);
                            homes.put(homeName, new HomeLocation(
                                    homeTag.getString("dim"),
                                    homeTag.getDouble("x"),
                                    homeTag.getDouble("y"),
                                    homeTag.getDouble("z"),
                                    homeTag.getFloat("xRot"),
                                    homeTag.getFloat("yRot"),
                                    homeTag.getLong("time")));
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (line.startsWith("last_seen:")) {
                    try {
                        String nbtPart = line.substring(line.indexOf(':') + 1).trim();
                        CompoundTag lastSeenTag = TagParser.parseTag(nbtPart);
                        lastSeen = new LastSeenLocation(
                                lastSeenTag.getString("dim"),
                                lastSeenTag.getDouble("x"),
                                lastSeenTag.getDouble("y"),
                                lastSeenTag.getDouble("z"),
                                lastSeenTag.getFloat("xRot"),
                                lastSeenTag.getFloat("yRot"),
                                lastSeenTag.getLong("time"));
                    } catch (Exception ignored) {
                    }
                }

                if (line.startsWith("teleport_history:")) {
                    try {
                        int start = line.indexOf('[');
                        int end = line.lastIndexOf(']');
                        if (start >= 0 && end > start) {
                            String listContent = line.substring(start + 1, end).trim();
                            if (!listContent.isEmpty()) {
                                int braceDepth = 0;
                                StringBuilder currentObj = new StringBuilder();
                                for (char c : listContent.toCharArray()) {
                                    if (c == '{')
                                        braceDepth++;
                                    if (c == '}')
                                        braceDepth--;
                                    currentObj.append(c);
                                    if (braceDepth == 0 && currentObj.length() > 0
                                            && currentObj.toString().trim().endsWith("}")) {
                                        String objStr = currentObj.toString().trim();
                                        if (objStr.startsWith(","))
                                            objStr = objStr.substring(1).trim();
                                        try {
                                            CompoundTag recordTag = TagParser.parseTag(objStr);
                                            history.add(new TeleportRecord(
                                                    recordTag.getString("dim"),
                                                    recordTag.getDouble("x"),
                                                    recordTag.getDouble("y"),
                                                    recordTag.getDouble("z"),
                                                    recordTag.getLong("time")));
                                        } catch (Exception ignored) {
                                        }
                                        currentObj = new StringBuilder();
                                    }
                                }
                                Collections.reverse(history);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            PlayerFTBData data = new PlayerFTBData(homes, lastSeen, history);
            // Update Cache
            dataCache.put(uuid, new CachedData(data, System.currentTimeMillis()));
            return data;

        } catch (Exception e) {
            // Log only real errors, to console, properly
            // e.printStackTrace(); // Keep stacktrace for critical IO errors
            return null;
        }
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
            String[] parts = dimension.split(":");
            return parts.length > 1 ? parts[1].substring(0, 1).toUpperCase() + parts[1].substring(1) : dimension;
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
            String[] parts = dimension.split(":");
            return parts.length > 1 ? parts[1].substring(0, 1).toUpperCase() + parts[1].substring(1) : dimension;
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
            String[] parts = dimension.split(":");
            return parts.length > 1 ? parts[1].substring(0, 1).toUpperCase() + parts[1].substring(1) : dimension;
        }
    }
}
