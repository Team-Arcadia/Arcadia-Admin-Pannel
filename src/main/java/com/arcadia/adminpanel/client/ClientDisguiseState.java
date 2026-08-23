package com.arcadia.adminpanel.client;

import com.arcadia.adminpanel.util.DisguiseManager;
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
 * Client-side mirror of the server's mob-disguise map, populated by {@code S2CDisguiseSync} and
 * {@code S2CDisguiseUpdate}. Read every frame by {@link DisguiseRenderer} and by
 * {@link NameTagRenderer}. The server owns the truth; the client only renders it.
 *
 * <p>The synced {@link ResourceLocation} is resolved to a concrete {@link EntityType} once, at sync
 * time, so the render path never touches the registry. An unknown id (a mob from a mod the client
 * lacks) resolves to nothing and is ignored, leaving the vanilla player model rather than an empty
 * space.</p>
 *
 * @author vyrriox
 */
@OnlyIn(Dist.CLIENT)
public final class ClientDisguiseState {

    /** A resolved disguise, ready for the render path. */
    public record Entry(EntityType<?> type, boolean baby, float scale, boolean showMobName) {}

    private static final Map<UUID, Entry> DISGUISES = new ConcurrentHashMap<>();

    private ClientDisguiseState() {}

    // ── Packet entry points ───────────────────────────────────────────────────

    public static void applyFullSync(Map<UUID, DisguiseManager.DisguiseData> disguises) {
        DISGUISES.clear();
        if (disguises == null) return;
        disguises.forEach((uuid, data) -> {
            if (uuid == null || data == null) return;
            EntityType<?> type = resolve(data.type());
            if (type != null) {
                DISGUISES.put(uuid, new Entry(type, data.baby(),
                        DisguiseManager.clampScale(data.scale()), data.showMobName()));
            }
        });
    }

    public static void applyUpdate(UUID uuid, boolean disguised,
                                   @Nullable ResourceLocation entityType,
                                   boolean baby, float scale, boolean showMobName) {
        if (uuid == null) return;
        if (!disguised || entityType == null) {
            DISGUISES.remove(uuid);
            return;
        }
        EntityType<?> type = resolve(entityType);
        if (type == null) DISGUISES.remove(uuid);
        else DISGUISES.put(uuid, new Entry(type, baby, DisguiseManager.clampScale(scale), showMobName));
    }

    /** Called on disconnect so a relog or server switch starts clean. */
    public static void clear() { DISGUISES.clear(); }

    // ── Lookups (render thread) ─────────────────────────────────────────────

    @Nullable
    public static Entry entryFor(UUID uuid) { return uuid == null ? null : DISGUISES.get(uuid); }

    @Nullable
    public static EntityType<?> disguiseFor(UUID uuid) {
        Entry e = entryFor(uuid);
        return e == null ? null : e.type();
    }

    public static boolean isDisguised(UUID uuid) { return uuid != null && DISGUISES.containsKey(uuid); }

    /** True when the disguise asks for the mob's own name to be drawn instead of nothing. */
    public static boolean showsMobName(UUID uuid) {
        Entry e = entryFor(uuid);
        return e != null && e.showMobName();
    }

    public static boolean isEmpty() { return DISGUISES.isEmpty(); }

    /** Resolves an entity-type id to its registered {@link EntityType}, or {@code null} if unknown. */
    @Nullable
    private static EntityType<?> resolve(ResourceLocation rl) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(rl) ? BuiltInRegistries.ENTITY_TYPE.get(rl) : null;
    }
}
