package com.arcadia.adminpanel.util;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers "why is the server struggling right now" without an external profiler.
 *
 * <p>Reports tick time and TPS, memory, per-dimension entity and chunk counts, the chunks with the
 * heaviest entity concentration, and which players those chunks belong to. On a heavy modpack that
 * usually turns a vague complaint into a coordinate.</p>
 *
 * <p><b>Cost, and why it is acceptable.</b> The expensive part is walking the entity list of every
 * loaded dimension, which is proportional to entity count and cannot be done off-thread safely. So
 * it is not done on a timer: it runs only when a staff member opens the panel, and the result is
 * cached for {@code lagSampleCacheSeconds}. Paging through the menu, or three admins opening it at
 * once, all read the same sample. An idle server does exactly zero work here.</p>
 *
 * @author vyrriox
 */
public final class LagMonitor {

    /** Everything the panel needs, captured in one pass. */
    public record Sample(long takenAt, double tps, double msptMean, double msptPeak,
                         long usedMemoryMb, long maxMemoryMb, int onlinePlayers,
                         List<DimensionStat> dimensions, List<HotChunk> hotChunks,
                         List<PlayerLoad> playerLoads, int totalEntities, int totalChunks) {}

    public record DimensionStat(String id, int entities, int chunks, int forcedChunks) {}

    public record HotChunk(String dimension, int chunkX, int chunkZ, int entities,
                           @Nullable String nearestPlayer) {}

    public record PlayerLoad(UUID uuid, String name, String dimension, int nearbyEntities, boolean afk) {}

    private static volatile Sample cached;

    private LagMonitor() {}

    /** Returns a fresh sample, or the cached one when it is still within the configured window. */
    public static Sample sample(MinecraftServer server) {
        Sample current = cached;
        long ttl = Math.max(1, AdminConfig.get().lagSampleCacheSeconds) * 1000L;
        if (current != null && System.currentTimeMillis() - current.takenAt() < ttl) return current;
        Sample fresh = compute(server);
        cached = fresh;
        return fresh;
    }

    /** Forces the next {@link #sample} call to recompute. */
    public static void invalidate() { cached = null; }

    // -- Computation ---------------------------------------------------------

    private static Sample compute(MinecraftServer server) {
        long now = System.currentTimeMillis();

        long[] times = server.getTickTimesNanos();
        double meanMs = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double peakMs = 0.0D;
        if (times != null) {
            for (long t : times) peakMs = Math.max(peakMs, t / 1_000_000.0D);
        }
        // A tick that finishes early still costs a full 50 ms slot, so TPS saturates at 20.
        double tps = meanMs <= 0.0D ? 20.0D : Math.min(20.0D, 1000.0D / Math.max(50.0D, meanMs));

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);

        List<DimensionStat> dims = new ArrayList<>();
        Map<String, Integer> chunkEntityCount = new HashMap<>();
        Map<String, ChunkPos> chunkPosById = new HashMap<>();
        Map<String, String> chunkDimById = new HashMap<>();
        int totalEntities = 0;
        int totalChunks = 0;

        for (ServerLevel level : server.getAllLevels()) {
            String dimId = level.dimension().location().toString();
            int entities = 0;
            for (Entity entity : level.getEntities().getAll()) {
                entities++;
                ChunkPos cp = entity.chunkPosition();
                String key = dimId + '|' + cp.x + '|' + cp.z;
                chunkEntityCount.merge(key, 1, Integer::sum);
                chunkPosById.putIfAbsent(key, cp);
                chunkDimById.putIfAbsent(key, dimId);
            }
            int chunks = level.getChunkSource().getLoadedChunksCount();
            int forced = level.getForcedChunks().size();
            dims.add(new DimensionStat(dimId, entities, chunks, forced));
            totalEntities += entities;
            totalChunks += chunks;
        }
        dims.sort(Comparator.comparingInt(DimensionStat::entities).reversed());

        int topN = Math.max(1, AdminConfig.get().lagTopChunks);
        List<HotChunk> hot = new ArrayList<>();
        chunkEntityCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .forEach(e -> {
                    ChunkPos cp = chunkPosById.get(e.getKey());
                    String dim = chunkDimById.get(e.getKey());
                    if (cp == null || dim == null) return;
                    hot.add(new HotChunk(dim, cp.x, cp.z, e.getValue(),
                            nearestPlayerName(server, dim, cp)));
                });

        List<PlayerLoad> loads = new ArrayList<>();
        int radius = Math.max(8, AdminConfig.get().lagEntityRadius);
        // Per-player load is summed from the chunk buckets built above rather than by re-walking the
        // entity list once per player. A radius query would be players times entities: on a full
        // server with a busy world that is hundreds of thousands of distance checks on the tick
        // thread. Summing buckets is players times chunks-in-radius, a couple of hundred map lookups
        // each, and chunk granularity is precise enough to point at whose build is the problem.
        int chunkRadius = Math.max(1, radius / 16);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p.level() instanceof ServerLevel level)) continue;
            String dimId = level.dimension().location().toString();
            ChunkPos centre = p.chunkPosition();
            int nearby = 0;
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    Integer n = chunkEntityCount.get(dimId + '|' + (centre.x + dx) + '|' + (centre.z + dz));
                    if (n != null) nearby += n;
                }
            }
            // The player themselves is in one of those buckets; do not count them as their own load.
            loads.add(new PlayerLoad(p.getUUID(), p.getName().getString(), dimId,
                    Math.max(0, nearby - 1), AfkTracker.isAfk(p.getUUID())));
        }
        loads.sort(Comparator.comparingInt(PlayerLoad::nearbyEntities).reversed());

        return new Sample(now, tps, meanMs, peakMs, usedMb, maxMb,
                server.getPlayerList().getPlayerCount(), dims, hot, loads,
                totalEntities, totalChunks);
    }

    @Nullable
    private static String nearestPlayerName(MinecraftServer server, String dimension, ChunkPos pos) {
        ServerLevel level = levelOf(server, dimension);
        if (level == null) return null;
        double bestSq = Double.MAX_VALUE;
        String best = null;
        double cx = pos.getMiddleBlockX();
        double cz = pos.getMiddleBlockZ();
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - cx;
            double dz = p.getZ() - cz;
            double d = dx * dx + dz * dz;
            if (d < bestSq) {
                bestSq = d;
                best = p.getName().getString();
            }
        }
        return best;
    }

    /** Resolves a dimension id string back to its level, or {@code null} when it is gone. */
    @Nullable
    public static ServerLevel levelOf(MinecraftServer server, String dimension) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(dimension);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    /** Short colour-coded TPS label for menu lore. */
    public static String tpsColor(double tps) {
        if (tps >= 19.0D) return "§a";
        if (tps >= 15.0D) return "§e";
        return "§c";
    }
}
