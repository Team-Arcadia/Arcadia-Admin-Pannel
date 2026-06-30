package com.arcadia.adminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative store for the name-tag system. Holds, per player, an optional
 * {@link NameTagStyle}, plus the two global toggles for the "hide names behind walls" feature
 * (the master switch and the per-player exemption set). The server is the single source of truth;
 * clients only render what this manager syncs to them ({@code S2CNameTagSync} /
 * {@code S2CNameTagUpdate}).
 *
 * <p>Persisted to {@code config/arcadia/arcadiaadminpanel/nametags.json} with the same atomic
 * temp-file-then-rename pattern as {@link NextSpawnManager}, so styles and exemptions survive a
 * restart. Hide-behind-walls is ON by default (operator-requested) and lives in {@link AdminConfig}
 * so it shares the operator-editable config file; the exemption set lives here because it's
 * per-player runtime state, not a tunable.</p>
 *
 * @author vyrriox
 */
public final class NameTagManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final NameTagManager INSTANCE = new NameTagManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Per-player name styling. Absent = vanilla name. */
    private final Map<UUID, NameTagStyle> styles = new ConcurrentHashMap<>();
    /** Players exempt from the global hide-behind-walls rule (their name is always visible). */
    private final Set<UUID> hideExempt = ConcurrentHashMap.newKeySet();
    /** Players whose name is force-hidden at all times (1.2.9), regardless of walls/distance. */
    private final Set<UUID> forceHidden = ConcurrentHashMap.newKeySet();

    private final Path file;
    private final Path tempFile;
    /** Guards only the temp-write + atomic-rename so disk I/O never blocks map mutations. */
    private final Object ioLock = new Object();

    /** On-disk shape — the styled players plus the two per-player UUID sets. */
    private static final class Persisted {
        Map<UUID, NameTagStyle> styles;
        Set<UUID> hideExempt;
        Set<UUID> forceHidden;
    }

    private NameTagManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        file = configDir.resolve("nametags.json");
        tempFile = configDir.resolve("nametags.json.tmp");
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create config directory", e);
        }
    }

    public static NameTagManager getInstance() { return INSTANCE; }

    public void init() {
        load();
        LOGGER.info("[AdminPanel] NameTagManager initialized ({} styled, {} hide-exempt)",
                styles.size(), hideExempt.size());
    }

    public void reload() {
        styles.clear();
        hideExempt.clear();
        forceHidden.clear();
        load();
    }

    // ── Styles ────────────────────────────────────────────────────────────────

    /** Returns the player's style, or {@code null} if they use the vanilla name. */
    @Nullable
    public NameTagStyle getStyle(UUID uuid) { return styles.get(uuid); }

    /** Sets (or replaces) a player's style and persists. A no-op style is stored as a clear. */
    public void setStyle(UUID uuid, NameTagStyle style) {
        if (style == null || style.isNoOp()) {
            clearStyle(uuid);
            return;
        }
        styles.put(uuid, style.normalised());
        save();
    }

    /** Removes a player's style (back to vanilla name). Returns true if one existed. */
    public boolean clearStyle(UUID uuid) {
        boolean removed = styles.remove(uuid) != null;
        if (removed) save();
        return removed;
    }

    public Map<UUID, NameTagStyle> getAllStyles() { return Collections.unmodifiableMap(styles); }

    // ── Hide-behind-walls ───────────────────────────────────────────────────

    /** The global master switch (operator config, ON by default). */
    public boolean isHideEnabled() { return AdminConfig.get().nameTagHideBehindWalls; }

    public void setHideEnabled(boolean on) {
        AdminConfig.get().nameTagHideBehindWalls = on;
        AdminConfig.save();
    }

    /** Whether transparent blocks (glass, water…) also occlude. Default false (only opaque). */
    public boolean occludeThroughTransparent() { return AdminConfig.get().nameTagOccludeTransparent; }

    /** Max distance (blocks) the wall-occlusion raytrace runs at (operator config, default 128). */
    public int hideMaxDistance() { return AdminConfig.get().nameTagHideMaxDistance; }

    // ── Hide-all (event blackout) + force-hidden ─────────────────────────────

    /** The global "blackout" event switch (operator config, OFF by default). */
    public boolean isHideAll() { return AdminConfig.get().nameTagHideAll; }

    public void setHideAll(boolean on) {
        AdminConfig.get().nameTagHideAll = on;
        AdminConfig.save();
    }

    public boolean isForceHidden(UUID uuid) { return forceHidden.contains(uuid); }

    /** Toggle a player's permanent name-hide; returns the new state (true = now force-hidden).
     *  TOCTOU-free via the atomic return values of {@code add}/{@code remove} (see {@link #toggleExempt}). */
    public boolean toggleForceHidden(UUID uuid) {
        boolean nowHidden = forceHidden.add(uuid);
        if (!nowHidden) forceHidden.remove(uuid);
        save();
        return nowHidden;
    }

    public void setForceHidden(UUID uuid, boolean hidden) {
        boolean changed = hidden ? forceHidden.add(uuid) : forceHidden.remove(uuid);
        if (changed) save();
    }

    public Set<UUID> getForceHidden() { return Collections.unmodifiableSet(forceHidden); }

    public boolean isHideExempt(UUID uuid) { return hideExempt.contains(uuid); }

    /** Toggle a player's exemption; returns the new state (true = now exempt = always visible).
     *  Uses the atomic return values of {@code add}/{@code remove} rather than a check-then-act, so
     *  two concurrent toggles on the same UUID can't both observe the old state and step on each
     *  other (TOCTOU-free). */
    public boolean toggleExempt(UUID uuid) {
        // add() returns true only if the element was absent → it's now present (exempt).
        boolean nowExempt = hideExempt.add(uuid);
        if (!nowExempt) hideExempt.remove(uuid); // was already present → toggle off
        save();
        return nowExempt;
    }

    public void setExempt(UUID uuid, boolean exempt) {
        boolean changed = exempt ? hideExempt.add(uuid) : hideExempt.remove(uuid);
        if (changed) save();
    }

    public Set<UUID> getHideExempt() { return Collections.unmodifiableSet(hideExempt); }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    /**
     * Broadcasts a single player's style change to every online client so the floating tag updates
     * for everyone immediately (the change is also persisted by the setter). Delegates the actual
     * packet send to the network layer to keep this class free of client/networking imports beyond
     * the payload helper.
     */
    public void broadcastUpdate(MinecraftServer server, UUID uuid) {
        com.arcadia.adminpanel.network.AdminPanelNet.broadcastNameTagUpdate(server, uuid, styles.get(uuid));
        refreshTabName(server, uuid);
    }

    /**
     * Re-pushes the target's tab-list display name to every client by re-firing the NeoForge
     * {@code TabListNameFormat} event (which {@code NameTagTabList} answers) and broadcasting the
     * resulting display-name packet. No-op if the player is offline. This is what makes a custom
     * pseudo / grade toggle appear in the tab list live, without a relog.
     */
    public void refreshTabName(MinecraftServer server, UUID uuid) {
        if (server == null) return;
        ServerPlayer target = server.getPlayerList().getPlayer(uuid);
        if (target != null) target.refreshTabListName();
    }

    /** Pushes the full state (all styles + hide config + exemptions) to one player on login. */
    public void syncTo(ServerPlayer player) {
        com.arcadia.adminpanel.network.AdminPanelNet.sendFullSync(player);
    }

    /** Re-pushes the full state to everyone (used after /reload). */
    public void syncAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) syncTo(p);
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(file)) return;
        try (FileReader reader = new FileReader(file.toFile())) {
            com.google.gson.JsonObject root = GSON.fromJson(reader, com.google.gson.JsonObject.class);
            // Backward-compat: styles persisted before the grade toggle existed have no "showGrade"
            // key. A primitive boolean defaults to false on parse, which would wrongly hide every
            // legacy grade — so inject showGrade=true for any style entry missing it.
            defaultMissingShowGrade(root);
            Persisted loaded = GSON.fromJson(root, new TypeToken<Persisted>() {}.getType());
            if (loaded != null) {
                if (loaded.styles != null) {
                    loaded.styles.forEach((k, v) -> {
                        if (k != null && v != null) styles.put(k, v.normalised());
                    });
                }
                if (loaded.hideExempt != null) hideExempt.addAll(loaded.hideExempt);
                if (loaded.forceHidden != null) forceHidden.addAll(loaded.forceHidden);
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load nametags.json", e);
        }
    }

    /** Walks every persisted style object and adds {@code showGrade=true} where it's absent, so
     *  styles written before the grade toggle keep their grade instead of silently hiding it. */
    private static void defaultMissingShowGrade(com.google.gson.JsonObject root) {
        if (root == null || !root.has("styles") || !root.get("styles").isJsonObject()) return;
        com.google.gson.JsonObject styles = root.getAsJsonObject("styles");
        for (Map.Entry<String, com.google.gson.JsonElement> e : styles.entrySet()) {
            if (e.getValue().isJsonObject()) {
                com.google.gson.JsonObject style = e.getValue().getAsJsonObject();
                if (!style.has("showGrade")) style.addProperty("showGrade", true);
            }
        }
    }

    /** Serialises a consistent snapshot to disk. The snapshot is taken from the concurrent maps
     *  (each is internally consistent), the JSON is rendered to a string, and only the temp-write +
     *  atomic-rename are guarded by {@link #ioLock} — so the disk I/O never blocks the command
     *  threads doing map mutations, and two saves can't interleave their temp files. */
    private void save() {
        Persisted out = new Persisted();
        out.styles = new java.util.HashMap<>(styles);
        out.hideExempt = new java.util.HashSet<>(hideExempt);
        out.forceHidden = new java.util.HashSet<>(forceHidden);
        final String json = GSON.toJson(out);
        synchronized (ioLock) {
            try (FileWriter writer = new FileWriter(tempFile.toFile())) {
                writer.write(json);
            } catch (IOException e) {
                LOGGER.error("[AdminPanel] Failed to write nametags temp file", e);
                return;
            }
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOGGER.error("[AdminPanel] Failed to atomically save nametags.json", e);
            }
        }
    }
}
