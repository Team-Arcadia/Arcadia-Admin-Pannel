package com.arcadia.adminpanel.client;

import com.arcadia.adminpanel.util.NameTagStyle;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the server's name-tag state, populated by {@code S2CNameTagSync} /
 * {@code S2CNameTagUpdate}. Read every frame by {@link NameTagRenderer}. Holds no logic beyond
 * lookups — the server owns the truth, the client only renders it.
 *
 * <p>Lives entirely on the physical client (guarded by {@link OnlyIn}); the packet handlers that
 * write here run on the network thread but enqueue onto the client thread first, and reads happen
 * on the render thread, so the maps are concurrent for safety.</p>
 *
 * @author vyrriox
 */
@OnlyIn(Dist.CLIENT)
public final class ClientNameTagState {

    private static final Map<UUID, NameTagStyle> STYLES = new ConcurrentHashMap<>();
    private static final Set<UUID> HIDE_EXEMPT = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> FORCE_HIDDEN = ConcurrentHashMap.newKeySet();

    private static volatile boolean hideEnabled = true;       // matches server default
    private static volatile boolean occludeTransparent = false;
    private static volatile boolean hideAll = false;          // event blackout, off by default
    /** Squared max distance for the occlusion raytrace; default 128² (matches server default). */
    private static volatile double maxHideDistanceSqr = 128.0 * 128.0;

    private ClientNameTagState() {}

    // ── Packet entry points ───────────────────────────────────────────────────

    public static void applyFullSync(boolean hide, boolean transparent, boolean hideAllFlag,
                                     int maxHideDistance, Map<UUID, NameTagStyle> styles,
                                     Set<UUID> exempt, Set<UUID> forceHidden) {
        hideEnabled = hide;
        occludeTransparent = transparent;
        hideAll = hideAllFlag;
        setMaxHideDistance(maxHideDistance);
        STYLES.clear();
        if (styles != null) styles.forEach((k, v) -> { if (k != null && v != null) STYLES.put(k, v); });
        HIDE_EXEMPT.clear();
        if (exempt != null) HIDE_EXEMPT.addAll(exempt);
        FORCE_HIDDEN.clear();
        if (forceHidden != null) FORCE_HIDDEN.addAll(forceHidden);
    }

    public static void applyUpdate(UUID uuid, boolean hasStyle, @Nullable NameTagStyle style,
                                   boolean exempt, boolean forceHidden) {
        if (uuid == null) return;
        if (hasStyle && style != null) STYLES.put(uuid, style);
        else STYLES.remove(uuid);
        if (exempt) HIDE_EXEMPT.add(uuid);
        else HIDE_EXEMPT.remove(uuid);
        if (forceHidden) FORCE_HIDDEN.add(uuid);
        else FORCE_HIDDEN.remove(uuid);
    }

    /** Called on disconnect so a relog / server-switch starts clean. */
    public static void clear() {
        STYLES.clear();
        HIDE_EXEMPT.clear();
        FORCE_HIDDEN.clear();
        hideEnabled = true;
        occludeTransparent = false;
        hideAll = false;
        maxHideDistanceSqr = 128.0 * 128.0;
    }

    private static void setMaxHideDistance(int blocks) {
        double d = Math.max(16, blocks); // floor at 16 blocks so a bad config can't disable hiding
        maxHideDistanceSqr = d * d;
    }

    // ── Lookups (render thread) ─────────────────────────────────────────────

    @Nullable
    public static NameTagStyle styleFor(UUID uuid) { return uuid == null ? null : STYLES.get(uuid); }

    public static boolean isHideEnabled() { return hideEnabled; }

    public static boolean occludeTransparent() { return occludeTransparent; }

    public static boolean isHideAll() { return hideAll; }

    public static double maxHideDistanceSqr() { return maxHideDistanceSqr; }

    public static boolean isHideExempt(UUID uuid) { return uuid != null && HIDE_EXEMPT.contains(uuid); }

    public static boolean isForceHidden(UUID uuid) { return uuid != null && FORCE_HIDDEN.contains(uuid); }
}
