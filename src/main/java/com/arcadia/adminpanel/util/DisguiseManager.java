package com.arcadia.adminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative store for the mob-disguise system (1.2.9). Holds, per player, the
 * {@link ResourceLocation} of the entity type they are disguised as (e.g. {@code minecraft:pig}).
 * Absent = not disguised. The server is the single source of truth; clients only render the mob
 * they're told to ({@code S2CDisguiseSync} / {@code S2CDisguiseUpdate}).
 *
 * <p>The disguise is purely <b>visual</b>: the player keeps their own hitbox, reach, and collision —
 * only their on-screen model is swapped, client-side, by {@code DisguiseRenderer}. This keeps the
 * feature safe for events (a "pig" can't suddenly walk through a 1-block gap) while still looking
 * the part with full mob animation.</p>
 *
 * <p>Persisted to {@code config/arcadia/arcadiaadminpanel/disguises.json} with the same atomic
 * temp-file-then-rename pattern as {@link NameTagManager}, so disguises survive a restart.</p>
 *
 * @author vyrriox
 */
public final class DisguiseManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DisguiseManager INSTANCE = new DisguiseManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Per-player disguise: UUID → entity-type id. Absent = the player's normal model. */
    private final Map<UUID, ResourceLocation> disguises = new ConcurrentHashMap<>();

    private final Path file;
    private final Path tempFile;
    /** Guards only the temp-write + atomic-rename so disk I/O never blocks map mutations. */
    private final Object ioLock = new Object();

    private DisguiseManager() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        file = configDir.resolve("disguises.json");
        tempFile = configDir.resolve("disguises.json.tmp");
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create config directory", e);
        }
    }

    public static DisguiseManager getInstance() { return INSTANCE; }

    public void init() {
        load();
        LOGGER.info("[AdminPanel] DisguiseManager initialized ({} disguised)", disguises.size());
    }

    public void reload() {
        disguises.clear();
        load();
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** The entity-type id the player is disguised as, or {@code null} if not disguised. */
    @Nullable
    public ResourceLocation getDisguise(UUID uuid) { return disguises.get(uuid); }

    public boolean isDisguised(UUID uuid) { return disguises.containsKey(uuid); }

    /** Sets (or replaces) a player's disguise and persists. */
    public void setDisguise(UUID uuid, ResourceLocation entityType) {
        if (uuid == null || entityType == null) return;
        disguises.put(uuid, entityType);
        save();
    }

    /** Removes a player's disguise (back to their normal model). Returns true if one existed. */
    public boolean clearDisguise(UUID uuid) {
        boolean removed = disguises.remove(uuid) != null;
        if (removed) save();
        return removed;
    }

    public Map<UUID, ResourceLocation> getAll() { return Collections.unmodifiableMap(disguises); }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    /** Broadcasts one player's disguise change to every online client (persisted by the setter). */
    public void broadcastUpdate(MinecraftServer server, UUID uuid) {
        com.arcadia.adminpanel.network.AdminPanelNet.broadcastDisguiseUpdate(server, uuid, disguises.get(uuid));
    }

    /** Pushes the full disguise map to one player on login. */
    public void syncTo(ServerPlayer player) {
        com.arcadia.adminpanel.network.AdminPanelNet.sendDisguiseFullSync(player);
    }

    /** Re-pushes the full map to everyone (used after /reload). */
    public void syncAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) syncTo(p);
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /** On-disk shape: UUID → entity-type id, both as strings for a clean Gson round-trip. */
    private static final class Persisted {
        Map<String, String> disguises;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (FileReader reader = new FileReader(file.toFile())) {
            Persisted loaded = GSON.fromJson(reader, new TypeToken<Persisted>() {}.getType());
            if (loaded != null && loaded.disguises != null) {
                loaded.disguises.forEach((k, v) -> {
                    if (k == null || v == null) return;
                    try {
                        ResourceLocation rl = ResourceLocation.parse(v);
                        disguises.put(UUID.fromString(k), rl);
                    } catch (Exception ignored) {
                        // Skip malformed UUID / entity-id rather than abort the whole load.
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load disguises.json", e);
        }
    }

    private void save() {
        Persisted out = new Persisted();
        out.disguises = new HashMap<>();
        disguises.forEach((k, v) -> out.disguises.put(k.toString(), v.toString()));
        final String json = GSON.toJson(out);
        synchronized (ioLock) {
            try (FileWriter writer = new FileWriter(tempFile.toFile())) {
                writer.write(json);
            } catch (IOException e) {
                LOGGER.error("[AdminPanel] Failed to write disguises temp file", e);
                return;
            }
            try {
                Files.move(tempFile, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOGGER.error("[AdminPanel] Failed to atomically save disguises.json", e);
            }
        }
    }
}
