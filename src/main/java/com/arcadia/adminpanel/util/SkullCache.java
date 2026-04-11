package com.arcadia.adminpanel.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for player skulls (GameProfile)
 * Prevents repeated lookups and improves performance
 * 
 * @author vyrriox
 */
public class SkullCache {

    private static final Map<UUID, GameProfile> profileCache = new ConcurrentHashMap<>();

    /**
     * Get or cache a player's GameProfile
     */
    public static GameProfile getProfile(UUID uuid, String name) {
        return profileCache.computeIfAbsent(uuid, k -> new GameProfile(uuid, name));
    }

    /**
     * Create a player skull item with profile
     */
    public static ItemStack createSkull(GameProfile profile) {
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);

        // Set the profile on the skull
        skull.set(DataComponents.PROFILE, new ResolvableProfile(profile));

        return skull;
    }

    /**
     * Create a player skull with UUID and name
     * Uses Name-only profile to ensure skin loads even on offline servers
     */
    public static ItemStack createSkull(UUID uuid, String name) {
        // Reverting to using UUID to prevent "Profile ID must not be null" crash
        // If UUID is null for some reason, generate one from name to be safe
        UUID safeUUID = uuid != null ? uuid : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        GameProfile profile = getProfile(safeUUID, name);
        return createSkull(profile);
    }

    /**
     * Clear the cache
     */
    public static void clear() {
        profileCache.clear();
    }

    /**
     * Remove a specific profile from cache
     */
    public static void invalidate(UUID uuid) {
        profileCache.remove(uuid);
    }
}
