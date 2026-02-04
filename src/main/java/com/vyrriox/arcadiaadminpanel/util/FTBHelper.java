package com.vyrriox.arcadiaadminpanel.util;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Helper class for FTB Essentials integration
 * Uses reflection to avoid hard dependency
 * 
 * @author vyrriox
 */
public class FTBHelper {

    private static final boolean FTB_AVAILABLE;
    private static Class<?> ftbEssentialsAPI;
    private static Method getHomesMethod;
    private static Method getLastSeenMethod;

    static {
        boolean available = false;
        try {
            // Try to load FTB Essentials API class
            ftbEssentialsAPI = Class.forName("dev.ftb.mods.ftbessentials.api.FTBEssentialsAPI");
            available = true;
        } catch (ClassNotFoundException e) {
            ftbEssentialsAPI = null;
        }
        FTB_AVAILABLE = available;
    }

    /**
     * Check if FTB Essentials is loaded
     */
    public static boolean isFTBAvailable() {
        return FTB_AVAILABLE;
    }

    /**
     * Get number of homes for a player
     * Returns -1 if FTB is not available or error occurs
     */
    public static int getHomeCount(ServerPlayer player) {
        if (!FTB_AVAILABLE)
            return -1;

        try {
            // Use reflection to call FTB API
            // This is a safe fallback approach
            Object api = ftbEssentialsAPI.getMethod("api").invoke(null);
            Object homes = api.getClass().getMethod("getHomes", ServerPlayer.class).invoke(api, player);

            if (homes instanceof java.util.Collection) {
                return ((java.util.Collection<?>) homes).size();
            }
        } catch (Exception e) {
            // Silent fail - FTB might be installed but API changed
        }

        return -1;
    }

    /**
     * Get last seen timestamp for offline player
     * Returns null if not available
     */
    @Nullable
    public static Long getLastSeen(ServerPlayer player) {
        if (!FTB_AVAILABLE)
            return null;

        try {
            Object api = ftbEssentialsAPI.getMethod("api").invoke(null);
            Object lastSeen = api.getClass().getMethod("getLastSeen", ServerPlayer.class).invoke(api, player);

            if (lastSeen instanceof Long) {
                return (Long) lastSeen;
            }
        } catch (Exception e) {
            // Silent fail
        }

        return null;
    }

    /**
     * Format last seen as human-readable string
     */
    public static String formatLastSeen(@Nullable Long timestamp) {
        if (timestamp == null)
            return "Unknown";

        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0)
            return days + "d ago";
        if (hours > 0)
            return hours + "h ago";
        if (minutes > 0)
            return minutes + "m ago";
        return "Just now";
    }
}
