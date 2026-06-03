package com.arcadia.adminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks per-player connection metadata that FTB Essentials does not cover natively:
 * <ul>
 *   <li>{@code firstSeenMs} — epoch ms of the first time we ever observed the player
 *       (either on login, or via {@link #observeFromScan(UUID, Path)} when scanning an
 *       existing {@code .snbt} on startup).</li>
 *   <li>{@code lastLoginMs} — epoch ms of the most recent login.</li>
 *   <li>{@code lastLogoutMs} — epoch ms of the most recent logout.</li>
 *   <li>{@code lastIp} — best-effort: only set when we can read it from the {@code ServerPlayer}.</li>
 * </ul>
 *
 * <p>Stored in {@code config/arcadia/arcadiaadminpanel/logins.json} so the data survives restarts
 * without needing a database. Writes are debounced via atomic temp-file rename to avoid corruption.
 * Reads are O(1) from an in-memory map.</p>
 *
 * <p>Why not just use FTB Essentials' {@code last_seen.time}? Because that field updates on every
 * teleport/move event (it's their {@code TeleportPos}), not specifically on login/logout — so it
 * cannot answer "when did this player last connect?". This tracker answers that question.</p>
 *
 * @author vyrriox
 */
public final class LoginTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LoginTracker INSTANCE = new LoginTracker();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LoginTracker getInstance() { return INSTANCE; }

    public record LoginRecord(long firstSeenMs, long lastLoginMs, long lastLogoutMs, @Nullable String lastIp) {
        public LoginRecord withLogin(long now, @Nullable String ip) {
            return new LoginRecord(firstSeenMs == 0 ? now : firstSeenMs, now, lastLogoutMs, ip != null ? ip : lastIp);
        }
        public LoginRecord withLogout(long now) {
            return new LoginRecord(firstSeenMs, lastLoginMs, now, lastIp);
        }
        public LoginRecord withFirstSeen(long firstMs) {
            return new LoginRecord(firstMs, lastLoginMs, lastLogoutMs, lastIp);
        }
    }

    private final Map<UUID, LoginRecord> cache = new ConcurrentHashMap<>();
    private final Path storeFile;
    private final Path storeTempFile;
    private volatile boolean loaded = false;

    /**
     * Coalesced off-thread persistence. recordLogin/recordLogout are called on the main server
     * thread (PlayerLoggedIn/Out events); writing the whole map with Gson + an atomic rename inline
     * spikes tick time during login storms. Instead we mark the cache dirty and flush at most once
     * every {@link #FLUSH_DELAY_SECONDS} seconds on a daemon IO thread.
     */
    private static final long FLUSH_DELAY_SECONDS = 5L;
    private final ScheduledExecutorService io =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-LoginTracker-IO");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private LoginTracker() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        storeFile = configDir.resolve("logins.json");
        storeTempFile = configDir.resolve("logins.json.tmp");
        try {
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create logins config dir", e);
        }
    }

    public void init() {
        load();
        loaded = true;
        LOGGER.info("[AdminPanel] LoginTracker initialized ({} records)", cache.size());
    }

    private void load() {
        if (!Files.exists(storeFile)) return;
        try (FileReader reader = new FileReader(storeFile.toFile())) {
            Map<UUID, LoginRecord> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<UUID, LoginRecord>>() {}.getType());
            if (loaded != null) cache.putAll(loaded);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load logins.json", e);
        }
    }

    private synchronized void save() {
        try (FileWriter writer = new FileWriter(storeTempFile.toFile())) {
            GSON.toJson(new HashMap<>(cache), writer);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to write logins temp file", e);
            return;
        }
        try {
            Files.move(storeTempFile, storeFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to atomically save logins.json", e);
        }
    }

    // ── Hooks ───────────────────────────────────────────────────────────────

    public void recordLogin(ServerPlayer player) {
        if (!loaded) return;
        long now = System.currentTimeMillis();
        String ip = extractIp(player);
        cache.merge(player.getUUID(),
                new LoginRecord(now, now, 0L, ip),
                (existing, fresh) -> existing.withLogin(now, ip));
        markDirty();
    }

    public void recordLogout(ServerPlayer player) {
        if (!loaded) return;
        long now = System.currentTimeMillis();
        LoginRecord existing = cache.get(player.getUUID());
        if (existing == null) return; // Login never recorded — ignore the stray logout.
        cache.put(player.getUUID(), existing.withLogout(now));
        markDirty();
    }

    /** Schedule a single coalesced flush; subsequent dirties within the window are folded in. */
    private void markDirty() {
        if (dirty.compareAndSet(false, true)) {
            io.schedule(() -> { dirty.set(false); save(); }, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Final synchronous flush + executor shutdown. Call on ServerStopping so no write is lost. */
    public void shutdown() {
        dirty.set(false);
        save();
        io.shutdown();
    }

    /**
     * Called from the offline scan when a .snbt file exists but we have no record. Uses the
     * filesystem creation time of the player file as a {@code firstSeenMs} approximation —
     * better than nothing, and stable across restarts.
     */
    public void observeFromScan(UUID uuid, Path playerDataFile) {
        if (cache.containsKey(uuid)) return;
        long firstSeen = 0L;
        try {
            BasicFileAttributes attrs = Files.readAttributes(playerDataFile, BasicFileAttributes.class);
            firstSeen = attrs.creationTime().toMillis();
            if (firstSeen <= 0) firstSeen = attrs.lastModifiedTime().toMillis();
        } catch (IOException ignored) {
            firstSeen = System.currentTimeMillis();
        }
        cache.putIfAbsent(uuid, new LoginRecord(firstSeen, 0L, 0L, null));
        // Caller (offline scan) is expected to invoke flush() once when done — saving per UUID
        // would hammer disk on first-time servers with thousands of player files.
    }

    /** Flush the in-memory cache to disk. Caller after a batch of {@link #observeFromScan} calls. */
    public void flush() {
        save();
    }

    // ── Read ────────────────────────────────────────────────────────────────

    @Nullable
    public LoginRecord get(UUID uuid) { return cache.get(uuid); }

    public Map<UUID, LoginRecord> snapshot() { return new HashMap<>(cache); }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Pulls just the IP (no port) from the address string. Best-effort; if anything weird
     * happens we return null rather than storing garbage.
     */
    @Nullable
    private static String extractIp(ServerPlayer player) {
        try {
            String addr = player.connection.getRemoteAddress().toString();
            // Format is typically "/1.2.3.4:54321" — strip leading slash and port.
            if (addr.startsWith("/")) addr = addr.substring(1);
            int colon = addr.lastIndexOf(':');
            if (colon > 0) addr = addr.substring(0, colon);
            return addr.isBlank() ? null : addr;
        } catch (Exception e) {
            return null;
        }
    }
}
