package com.arcadia.adminpanel.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's mob-disguise map, populated by {@code S2CDisguiseSync} /
 * {@code S2CDisguiseUpdate}. Read every frame by {@link DisguiseRenderer} and by
 * {@link NameTagRenderer} (to hide a disguised player's real name). The server owns the truth; the
 * client only renders it.
 *
 * <p>Resolves the synced {@link ResourceLocation} to a concrete {@link EntityType} once, at sync
 * time, and stores that — so the render path never touches the registry. Unknown ids (e.g. a mob
 * from a mod the client lacks) resolve to {@code null} and are ignored, leaving the vanilla player
 * model.</p>
 *
 * @author vyrriox
 */
@OnlyIn(Dist.CLIENT)
public final class ClientDisguiseState {

    private static final Map<UUID, EntityType<?>> DISGUISES = new ConcurrentHashMap<>();

    private ClientDisguiseState() {}

    // ── Packet entry points ───────────────────────────────────────────────────

    public static void applyFullSync(Map<UUID, ResourceLocation> disguises) {
        DISGUISES.clear();
        if (disguises != null) {
            disguises.forEach((uuid, rl) -> {
                if (uuid == null || rl == null) return;
                EntityType<?> type = resolve(rl);
                if (type != null) DISGUISES.put(uuid, type);
            });
        }
    }

    public static void applyUpdate(UUID uuid, boolean disguised, @Nullable ResourceLocation entityType) {
        if (uuid == null) return;
        if (disguised && entityType != null) {
            EntityType<?> type = resolve(entityType);
            if (type != null) DISGUISES.put(uuid, type);
            else DISGUISES.remove(uuid);
        } else {
            DISGUISES.remove(uuid);
        }
    }

    /** Called on disconnect so a relog / server-switch starts clean. */
    public static void clear() { DISGUISES.clear(); }

    // ── Lookups (render thread) ─────────────────────────────────────────────

    @Nullable
    public static EntityType<?> disguiseFor(UUID uuid) { return uuid == null ? null : DISGUISES.get(uuid); }

    public static boolean isDisguised(UUID uuid) { return uuid != null && DISGUISES.containsKey(uuid); }

    public static boolean isEmpty() { return DISGUISES.isEmpty(); }

    /** Resolves an entity-type id to its registered {@link EntityType}, or {@code null} if unknown. */
    @Nullable
    private static EntityType<?> resolve(ResourceLocation rl) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(rl) ? BuiltInRegistries.ENTITY_TYPE.get(rl) : null;
    }
}
