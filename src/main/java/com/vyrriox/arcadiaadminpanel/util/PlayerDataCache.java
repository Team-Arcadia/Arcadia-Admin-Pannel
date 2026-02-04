package com.vyrriox.arcadiaadminpanel.util;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for player data with TTL
 * Prevents expensive lookups on every GUI open
 * 
 * @author vyrriox
 */
public class PlayerDataCache {

    private static final long CACHE_TTL_MS = 30_000; // 30 seconds

    private static class CachedData {
        final int homeCount;
        final Long lastSeen;
        final long timestamp;

        CachedData(int homeCount, Long lastSeen) {
            this.homeCount = homeCount;
            this.lastSeen = lastSeen;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    private final Map<UUID, CachedData> cache = new ConcurrentHashMap<>();

    /**
     * Get player data from cache or fetch if expired
     */
    public PlayerData getData(ServerPlayer player) {
        UUID uuid = player.getUUID();
        CachedData cached = cache.get(uuid);

        // Return cached if valid
        if (cached != null && !cached.isExpired()) {
            return new PlayerData(cached.homeCount, cached.lastSeen);
        }

        // Fetch new data
        int homeCount = FTBHelper.getHomeCount(player);
        Long lastSeen = FTBHelper.getLastSeen(player);

        // Update cache
        cache.put(uuid, new CachedData(homeCount, lastSeen));

        return new PlayerData(homeCount, lastSeen);
    }

    /**
     * Clear entire cache
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Clear specific player from cache
     */
    public void invalidate(UUID playerUUID) {
        cache.remove(playerUUID);
    }

    /**
     * Immutable player data snapshot
     */
    public static class PlayerData {
        public final int homeCount;
        @Nullable
        public final Long lastSeen;

        PlayerData(int homeCount, @Nullable Long lastSeen) {
            this.homeCount = homeCount;
            this.lastSeen = lastSeen;
        }

        public String getLastSeenFormatted() {
            return FTBHelper.formatLastSeen(lastSeen);
        }
    }
}
