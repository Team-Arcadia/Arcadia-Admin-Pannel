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
 * Server-authoritative store for the mob-disguise system.
 *
 * <p>Holds, per player, the entity type they appear as plus the presentation options added in 1.3.0:
 * baby form, a render scale, and whether the mob's name is shown above them. Absent = not disguised.
 * The server is the single source of truth; clients only render what they are told
 * ({@code S2CDisguiseSync} / {@code S2CDisguiseUpdate}).</p>
 *
 * <p>The disguise stays purely <b>visual</b>. Scale in particular changes only the model: a player
 * rendered at three times the size still has a player hitbox, still fits through a two-block gap,
 * and still takes the same hits. Making the hitbox follow would turn a cosmetic event tool into a
 * physics exploit.</p>
 *
 * <p>Persisted to {@code config/arcadia/arcadiaadminpanel/disguises.json} with an atomic
 * temp-file-then-rename. The 1.2.9 file shape is still read, so upgrading keeps existing
 * disguises.</p>
 *
 * @author vyrriox
 */
public final class DisguiseManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DisguiseManager INSTANCE = new DisguiseManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final float MIN_SCALE = 0.25F;
    public static final float MAX_SCALE = 4.0F;

    /**
     * One player's disguise.
     *
     * @param type        the entity type to render instead of the player
     * @param baby        render the baby variant where the mob has one
     * @param scale       model scale multiplier, clamped to [{@value #MIN_SCALE}, {@value #MAX_SCALE}]
     * @param showMobName draw the mob's name above it instead of hiding the tag entirely
     */
    public record DisguiseData(ResourceLocation type, boolean baby, float scale, boolean showMobName) {

        public DisguiseData(ResourceLocation type) {
            this(type, false, 1.0F, false);
        }

        public DisguiseData withType(ResourceLocation newType) {
            return new DisguiseData(newType, baby, scale, showMobName);
        }

        public DisguiseData withBaby(boolean value) {
            return new DisguiseData(type, value, scale, showMobName);
        }

        public DisguiseData withScale(float value) {
            return new DisguiseData(type, baby, clampScale(value), showMobName);
        }

        public DisguiseData withShowMobName(boolean value) {
            return new DisguiseData(type, baby, scale, value);
        }
    }

    public static float clampScale(float value) {
        if (Float.isNaN(value)) return 1.0F;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /** Per-player disguise. Absent = the player's normal model. */
    private final Map<UUID, DisguiseData> disguises = new ConcurrentHashMap<>();

    private final Path file;
    private final Path tempFile;
    /** Guards only the temp-write + atomic-rename so disk IO never blocks map mutations. */
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

    /** The full disguise record, or {@code null} if the player is not disguised. */
    @Nullable
    public DisguiseData getData(UUID uuid) { return uuid == null ? null : disguises.get(uuid); }

    /** The entity-type id the player is disguised as, or {@code null} if not disguised. */
    @Nullable
    public ResourceLocation getDisguise(UUID uuid) {
        DisguiseData data = getData(uuid);
        return data == null ? null : data.type();
    }

    public boolean isDisguised(UUID uuid) { return uuid != null && disguises.containsKey(uuid); }

    public int count() { return disguises.size(); }

    /** Sets (or replaces) a player's disguise, keeping any options already applied to them. */
    public void setDisguise(UUID uuid, ResourceLocation entityType) {
        if (uuid == null || entityType == null) return;
        DisguiseData existing = disguises.get(uuid);
        disguises.put(uuid, existing == null
                ? new DisguiseData(entityType)
                : existing.withType(entityType));
        save();
    }

    /** Replaces the whole record, options included. */
    public void setData(UUID uuid, DisguiseData data) {
        if (uuid == null || data == null) return;
        disguises.put(uuid, data);
        save();
    }

    /**
     * Applies a change to one option, doing nothing when the player is not disguised.
     *
     * @return the updated record, or {@code null} when there was nothing to change
     */
    @Nullable
    public DisguiseData mutate(UUID uuid, java.util.function.UnaryOperator<DisguiseData> change) {
        DisguiseData existing = disguises.get(uuid);
        if (existing == null) return null;
        DisguiseData updated = change.apply(existing);
        disguises.put(uuid, updated);
        save();
        return updated;
    }

    /** Removes a player's disguise (back to their normal model). Returns true if one existed. */
    public boolean clearDisguise(UUID uuid) {
        boolean removed = disguises.remove(uuid) != null;
        if (removed) save();
        return removed;
    }

    /** Removes every disguise. Returns how many were cleared. Used by the event "reset all". */
    public int clearAll() {
        int n = disguises.size();
        if (n > 0) {
            disguises.clear();
            save();
        }
        return n;
    }

    public Map<UUID, DisguiseData> getAll() { return Collections.unmodifiableMap(disguises); }

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

    /** Death hook: drops the disguise when the operator asked for it. */
    public void onDeath(ServerPlayer player) {
        if (!AdminConfig.get().disguiseClearOnDeath) return;
        if (!isDisguised(player.getUUID())) return;
        clearDisguise(player.getUUID());
        MinecraftServer server = player.getServer();
        if (server != null) broadcastUpdate(server, player.getUUID());
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /** On-disk shape. {@code disguises} is the 1.2.9 layout, kept so an upgrade loses nothing. */
    private static final class Persisted {
        Map<String, String> disguises;
        Map<String, StoredDisguise> entries;
    }

    private static final class StoredDisguise {
        String type;
        boolean baby;
        float scale = 1.0F;
        boolean showMobName;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (FileReader reader = new FileReader(file.toFile())) {
            Persisted loaded = GSON.fromJson(reader, new TypeToken<Persisted>() {}.getType());
            if (loaded == null) return;

            if (loaded.disguises != null) {
                loaded.disguises.forEach((k, v) -> {
                    if (k == null || v == null) return;
                    try {
                        disguises.put(UUID.fromString(k), new DisguiseData(ResourceLocation.parse(v)));
                    } catch (Exception ignored) {
                        // Skip a malformed UUID or entity id rather than abort the whole load.
                    }
                });
            }
            if (loaded.entries != null) {
                loaded.entries.forEach((k, v) -> {
                    if (k == null || v == null || v.type == null) return;
                    try {
                        disguises.put(UUID.fromString(k), new DisguiseData(
                                ResourceLocation.parse(v.type), v.baby, clampScale(v.scale), v.showMobName));
                    } catch (Exception ignored) {
                        // Same: one bad row must not cost every other disguise.
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load disguises.json", e);
        }
    }

    private void save() {
        Persisted out = new Persisted();
        out.entries = new HashMap<>();
        disguises.forEach((k, v) -> {
            StoredDisguise s = new StoredDisguise();
            s.type = v.type().toString();
            s.baby = v.baby();
            s.scale = v.scale();
            s.showMobName = v.showMobName();
            out.entries.put(k.toString(), s);
        });
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
