package com.arcadia.adminpanel.util;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Who is keeping chunks loaded, and where.
 *
 * <p>Two sources, because a modded server has two answers. Vanilla forced chunks come from
 * {@link ServerLevel#getForcedChunks()} and are exact. FTB Chunks claims and force-loads come from
 * the files the panel already parses for the team browser, so this view costs one pass over the
 * team list rather than a dependency on the FTB Chunks mod being present.</p>
 *
 * <p>Both are computed on demand when the browser opens and cached briefly, since the underlying
 * data changes on the scale of minutes, not ticks.</p>
 *
 * @author vyrriox
 */
public final class ChunkReport {

    /** A chunk the server is holding loaded regardless of players. */
    public record ForcedChunk(String dimension, int x, int z) {}

    /** A team's footprint, from the FTB Chunks files. */
    public record TeamFootprint(UUID teamId, String teamName, int claims, int forceLoaded, int members) {}

    private static volatile List<ForcedChunk> cachedForced;
    private static volatile List<TeamFootprint> cachedTeams;
    private static volatile long stamp;

    private static final long TTL_MS = 15_000L;

    private ChunkReport() {}

    public static void invalidate() {
        cachedForced = null;
        cachedTeams = null;
        stamp = 0L;
    }

    // -- Vanilla forced chunks -----------------------------------------------

    public static List<ForcedChunk> forcedChunks(MinecraftServer server) {
        refreshIfStale(server);
        List<ForcedChunk> out = cachedForced;
        return out == null ? List.of() : out;
    }

    // -- FTB Chunks footprints -----------------------------------------------

    /** Team footprints, largest force-load count first. Empty when FTB Chunks data is absent. */
    public static List<TeamFootprint> teamFootprints(MinecraftServer server) {
        refreshIfStale(server);
        List<TeamFootprint> out = cachedTeams;
        return out == null ? List.of() : out;
    }

    /** Total force-loaded chunks across every team, for the summary line. */
    public static int totalForceLoaded(MinecraftServer server) {
        int n = 0;
        for (TeamFootprint f : teamFootprints(server)) n += f.forceLoaded();
        return n;
    }

    public static int totalClaims(MinecraftServer server) {
        int n = 0;
        for (TeamFootprint f : teamFootprints(server)) n += f.claims();
        return n;
    }

    // -- Refresh -------------------------------------------------------------

    private static void refreshIfStale(MinecraftServer server) {
        if (System.currentTimeMillis() - stamp < TTL_MS && cachedForced != null) return;

        List<ForcedChunk> forced = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            LongSet set = level.getForcedChunks();
            if (set == null || set.isEmpty()) continue;
            String dim = level.dimension().location().toString();
            for (long packed : set) {
                forced.add(new ForcedChunk(dim, ChunkPos.getX(packed), ChunkPos.getZ(packed)));
            }
        }
        forced.sort(Comparator.comparing(ForcedChunk::dimension)
                .thenComparingInt(ForcedChunk::x)
                .thenComparingInt(ForcedChunk::z));

        List<TeamFootprint> teams = new ArrayList<>();
        if (FTBChunksReader.isAvailable() && FTBTeamsReader.isAvailable()) {
            List<FTBTeamsReader.Team> all = new ArrayList<>();
            all.addAll(FTBTeamsReader.getParties());
            all.addAll(FTBTeamsReader.getServerTeams());
            all.addAll(FTBTeamsReader.getPlayerTeams());
            for (FTBTeamsReader.Team team : all) {
                FTBChunksReader.ClaimStats stats = FTBChunksReader.getStatsFor(team.id);
                if (stats == null) continue;
                if (stats.totalClaims() == 0 && stats.forceLoaded() == 0) continue;
                teams.add(new TeamFootprint(team.id, team.displayName,
                        stats.totalClaims(), stats.forceLoaded(), team.members.size()));
            }
            teams.sort(Comparator.comparingInt(TeamFootprint::forceLoaded).reversed()
                    .thenComparing(Comparator.comparingInt(TeamFootprint::claims).reversed()));
        }

        cachedForced = forced;
        cachedTeams = teams;
        stamp = System.currentTimeMillis();
    }
}
