package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads FTB Chunks claim/force-load data from {@code <world>/ftbchunks/<team-uuid>.snbt}.
 *
 * <p>One file per team UUID (the same UUID FTB Teams uses — party uuid for parties, player uuid for
 * personal teams). Schema (excerpt):</p>
 * <pre>
 * {
 *   max_claim_chunks: 500,
 *   max_force_load_chunks: 25,
 *   chunks: {
 *     "minecraft:overworld": [
 *       { x: 12, z: -7, time: 1700000000000L, force_loaded: 0L, expiry_time: 0L },
 *       ...
 *     ],
 *     "minecraft:the_nether": [ ... ]
 *   }
 * }
 * </pre>
 *
 * <p>Parsed once and cached with a 30 s TTL — same strategy as {@link FTBDataReader} and
 * {@link FTBTeamsReader} — so opening a team detail menu doesn't hit disk on every redraw.</p>
 *
 * @author vyrriox
 */
public final class FTBChunksReader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CACHE_TTL_MS = 30_000L;
    private static volatile Path basePath = null;

    private static final Map<UUID, CachedClaims> CACHE = new ConcurrentHashMap<>();

    private record CachedClaims(@Nullable ClaimStats stats, long stamp) {}

    /** Result of parsing one team's claim file. {@code totalClaims} sums every dimension. */
    public record ClaimStats(int totalClaims, int forceLoaded, int maxClaims, int maxForceLoaded,
                             Map<String, Integer> perDimension) {}

    /** Wired up by {@link OfflinePlayerManager} once the world's {@code ftbchunks} dir is located. */
    public static void setBasePath(Path chunksDir) {
        basePath = chunksDir;
        CACHE.clear();
    }

    public static boolean isAvailable() {
        Path p = basePath;
        return p != null && Files.isDirectory(p);
    }

    @Nullable
    public static Path getBasePath() { return basePath; }

    public static void clearCache() { CACHE.clear(); }

    /**
     * Returns the team's claim statistics, or {@code null} if no file exists. Cached for 30 s.
     * Safe to call from the tick thread — file reads are bounded (one small SNBT per team).
     */
    @Nullable
    public static ClaimStats getStatsFor(UUID teamId) {
        if (basePath == null) return null;
        CachedClaims cached = CACHE.get(teamId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.stamp < CACHE_TTL_MS) return cached.stats;

        Path file = basePath.resolve(teamId + ".snbt");
        ClaimStats stats = null;
        if (Files.isRegularFile(file)) {
            stats = parse(file);
        }
        CACHE.put(teamId, new CachedClaims(stats, now));
        return stats;
    }

    @Nullable
    private static ClaimStats parse(Path file) {
        try {
            CompoundTag root = SnbtCompat.parse(Files.readString(file));
            return parseRoot(root);
        } catch (IOException | RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOGGER.warn("[AdminPanel] Failed to parse FTB Chunks file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static ClaimStats parseRoot(CompoundTag root) {
        int maxClaims = root.contains("max_claim_chunks") ? root.getInt("max_claim_chunks") : 0;
        int maxForce = root.contains("max_force_load_chunks") ? root.getInt("max_force_load_chunks") : 0;

        int total = 0;
        int force = 0;
        Map<String, Integer> perDim = new HashMap<>();

        if (root.contains("chunks", Tag.TAG_COMPOUND)) {
            CompoundTag chunks = root.getCompound("chunks");
            long now = System.currentTimeMillis();
            for (String dim : chunks.getAllKeys()) {
                ListTag list = chunks.getList(dim, Tag.TAG_COMPOUND);
                int dimCount = list.size();
                total += dimCount;
                perDim.put(dim, dimCount);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag entry = list.getCompound(i);
                    long fl = entry.contains("force_loaded") ? entry.getLong("force_loaded") : 0L;
                    if (fl <= 0) continue;
                    long expiry = entry.contains("expiry_time") ? entry.getLong("expiry_time") : 0L;
                    if (expiry == 0 || expiry > now) force++;
                }
            }
        }
        return new ClaimStats(total, force, maxClaims, maxForce, perDim);
    }

    private FTBChunksReader() {}
}
