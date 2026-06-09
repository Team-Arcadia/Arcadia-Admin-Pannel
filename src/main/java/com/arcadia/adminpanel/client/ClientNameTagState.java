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

    private static volatile boolean hideEnabled = true;       // matches server default
    private static volatile boolean occludeTransparent = false;

    private ClientNameTagState() {}

    // ── Packet entry points ───────────────────────────────────────────────────

    public static void applyFullSync(boolean hide, boolean transparent,
                                     Map<UUID, NameTagStyle> styles, Set<UUID> exempt) {
        hideEnabled = hide;
        occludeTransparent = transparent;
        STYLES.clear();
        if (styles != null) styles.forEach((k, v) -> { if (k != null && v != null) STYLES.put(k, v); });
        HIDE_EXEMPT.clear();
        if (exempt != null) HIDE_EXEMPT.addAll(exempt);
    }

    public static void applyUpdate(UUID uuid, boolean hasStyle, @Nullable NameTagStyle style, boolean exempt) {
        if (uuid == null) return;
        if (hasStyle && style != null) STYLES.put(uuid, style);
        else STYLES.remove(uuid);
        if (exempt) HIDE_EXEMPT.add(uuid);
        else HIDE_EXEMPT.remove(uuid);
    }

    /** Called on disconnect so a relog / server-switch starts clean. */
    public static void clear() {
        STYLES.clear();
        HIDE_EXEMPT.clear();
        hideEnabled = true;
        occludeTransparent = false;
    }

    // ── Lookups (render thread) ─────────────────────────────────────────────

    @Nullable
    public static NameTagStyle styleFor(UUID uuid) { return uuid == null ? null : STYLES.get(uuid); }

    public static boolean isHideEnabled() { return hideEnabled; }

    public static boolean occludeTransparent() { return occludeTransparent; }

    public static boolean isHideExempt(UUID uuid) { return uuid != null && HIDE_EXEMPT.contains(uuid); }
}
