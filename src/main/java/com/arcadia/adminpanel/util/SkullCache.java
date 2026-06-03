package com.arcadia.adminpanel.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for player skulls (GameProfile).
 *
 * <p>To render the REAL skin inside a server-side {@code GENERIC_9x6} GUI (items synced to the
 * client), the texture property must ship inline in the {@link ResolvableProfile} — a bare
 * name+UUID profile with an empty {@code PropertyMap} renders the default Steve/Alex skin because
 * the client never resolves textures for arbitrary GUI items. We therefore resolve the full,
 * textured {@link GameProfile} server-side (async, via {@link SkullBlockEntity#fetchGameProfile})
 * and cache it; heads built afterwards carry the textures and render correctly.</p>
 *
 * <p>Resolution requires online mode + a real Mojang UUID; offline-mode UUIDs simply stay on the
 * default skin (no Mojang record to fetch). Each UUID is attempted at most once until {@link #clear()}.</p>
 *
 * @author vyrriox
 */
public class SkullCache {

    private static final Map<UUID, GameProfile> profileCache = new ConcurrentHashMap<>();
    /** UUID -> fully-resolved profile WITH the "textures" property. */
    private static final Map<UUID, GameProfile> texturedCache = new ConcurrentHashMap<>();
    /** UUIDs whose async resolution was already kicked off (dedup + negative cache). */
    private static final Set<UUID> attempted = ConcurrentHashMap.newKeySet();

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
        skull.set(DataComponents.PROFILE, new ResolvableProfile(profile));
        return skull;
    }

    /**
     * Create a player skull with UUID and name. If a textured profile has already been resolved for
     * this UUID, the real skin ships inline; otherwise a name+UUID head (default skin) is returned
     * and async texture resolution is kicked off so the real skin appears on the next GUI redraw.
     */
    public static ItemStack createSkull(UUID uuid, String name) {
        ItemStack skull = new ItemStack(Items.PLAYER_HEAD);

        GameProfile textured = uuid != null ? texturedCache.get(uuid) : null;
        if (textured != null) {
            skull.set(DataComponents.PROFILE, new ResolvableProfile(textured));
            return skull;
        }

        if (uuid != null) warmTextures(uuid);

        UUID safeUUID = uuid != null ? uuid
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        ResolvableProfile resolvable = new ResolvableProfile(
                Optional.of(name),
                Optional.of(safeUUID),
                new com.mojang.authlib.properties.PropertyMap()
        );
        skull.set(DataComponents.PROFILE, resolvable);
        return skull;
    }

    /** True once a textured profile has been resolved for this UUID (real skin available). */
    public static boolean hasTexture(UUID uuid) {
        return uuid != null && texturedCache.containsKey(uuid);
    }

    /**
     * Kick off async resolution of the textured profile for {@code uuid}. No-op if already resolved
     * or already attempted. Backed by {@link SkullBlockEntity#fetchGameProfile(UUID)} which goes
     * through the server's profile cache + session service and yields a profile WITH textures.
     */
    public static void warmTextures(UUID uuid) {
        if (uuid == null || texturedCache.containsKey(uuid)) return;
        if (!attempted.add(uuid)) return; // already in flight / done / permanently failed
        try {
            SkullBlockEntity.fetchGameProfile(uuid).thenAccept(opt ->
                    opt.ifPresent(profile -> {
                        if (hasTextures(profile)) texturedCache.put(uuid, profile);
                    }));
        } catch (Exception ignored) {
            // Resolution infra not ready (e.g. SkullBlockEntity.setup not yet called) — stay on
            // the default skin; a later call after clear() can retry.
        }
    }

    /** Server-aware overload (server arg currently unused — resolution is server-global). */
    public static void warmTextures(MinecraftServer server, UUID uuid) {
        warmTextures(uuid);
    }

    private static boolean hasTextures(GameProfile p) {
        return p != null && !p.getProperties().get("textures").isEmpty();
    }

    /**
     * Clear the cache
     */
    public static void clear() {
        profileCache.clear();
        texturedCache.clear();
        attempted.clear();
    }

    /**
     * Remove a specific profile from cache
     */
    public static void invalidate(UUID uuid) {
        profileCache.remove(uuid);
        texturedCache.remove(uuid);
        attempted.remove(uuid);
    }
}
