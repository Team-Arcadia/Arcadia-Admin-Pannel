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
import java.util.concurrent.RejectedExecutionException;
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

    /**
     * One player's connection history.
     *
     * <p>1.3.0 added {@code totalPlayMs}, {@code sessions} and {@code ipHash}. Gson leaves missing
     * fields at their zero value, so a {@code logins.json} written by 1.2.x loads unchanged and
     * simply starts counting from this session.</p>
     *
     * <p>{@code lastIp} is the plain address and is only populated when the operator explicitly
     * opts in ({@code storePlainIp}). Everything the panel does with an address, alt detection
     * included, uses {@code ipHash}: a salted digest that answers "same origin?" and nothing
     * else.</p>
     */
    public record LoginRecord(long firstSeenMs, long lastLoginMs, long lastLogoutMs,
                              @Nullable String lastIp, long totalPlayMs, int sessions,
                              @Nullable String ipHash) {

        public LoginRecord withLogin(long now, @Nullable String ip, @Nullable String hash) {
            return new LoginRecord(firstSeenMs == 0 ? now : firstSeenMs, now, lastLogoutMs,
                    ip != null ? ip : lastIp, totalPlayMs, sessions + 1,
                    hash != null ? hash : ipHash);
        }

        public LoginRecord withLogout(long now) {
            long session = lastLoginMs > 0 && now > lastLoginMs ? now - lastLoginMs : 0L;
            return new LoginRecord(firstSeenMs, lastLoginMs, now, lastIp,
                    totalPlayMs + session, sessions, ipHash);
        }

        public LoginRecord withFirstSeen(long firstMs) {
            return new LoginRecord(firstMs, lastLoginMs, lastLogoutMs, lastIp,
                    totalPlayMs, sessions, ipHash);
        }

        /** Playtime including the session currently in progress, when there is one. */
        public long playtimeMs(boolean online) {
            if (!online || lastLoginMs <= 0) return totalPlayMs;
            return totalPlayMs + Math.max(0L, System.currentTimeMillis() - lastLoginMs);
        }

        /** Mean session length, or 0 when the player has never completed one. */
        public long averageSessionMs() {
            return sessions <= 0 ? 0L : totalPlayMs / sessions;
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
    /**
     * Coalescing IO executor. <strong>Not</strong> final: this is a static singleton that outlives a
     * single-player integrated server, so {@link #shutdown()} terminates the pool on {@code ServerStopping}
     * and {@link #init()} must spin up a fresh one on the next {@code ServerStarted}. Loading a second world
     * in the same client session previously reused the dead pool, and {@code io.schedule(...)} threw
     * {@link java.util.concurrent.RejectedExecutionException} during login — surfacing as the vanilla
     * "Couldn't place player in world" disconnect. {@code volatile} so the cross-thread read in
     * {@link #markDirty()} sees the latest reference.
     */
    private volatile ScheduledExecutorService io;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private static ScheduledExecutorService newIoExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Arcadia-LoginTracker-IO");
            t.setDaemon(true);
            return t;
        });
    }

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
        // (Re)create the IO pool every server start. On an integrated (single-player) server the static
        // INSTANCE survives world unload, so a prior shutdown() may have left a terminated executor here.
        ScheduledExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = newIoExecutor();
        }
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
        String rawIp = extractIp(player);
        // The hash is what the panel uses; the plain address is only kept when the operator asked
        // for it, because it is personal data with no feature depending on it.
        String hash = AltDetector.fingerprint(rawIp);
        String storedIp = AdminConfig.get().storePlainIp ? rawIp : null;
        cache.merge(player.getUUID(),
                new LoginRecord(now, now, 0L, storedIp, 0L, 1, hash),
                (existing, fresh) -> existing.withLogin(now, storedIp, hash));
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
            ScheduledExecutorService current = io;
            if (current == null || current.isShutdown()) {
                // Pool not ready (or already torn down between ServerStopping and the next init).
                // Persist inline rather than dropping the write or throwing RejectedExecutionException.
                dirty.set(false);
                save();
                return;
            }
            try {
                current.schedule(() -> { dirty.set(false); save(); }, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                // Lost a race with shutdown() — fall back to a synchronous write so nothing is lost.
                dirty.set(false);
                save();
            }
        }
    }

    /** Final synchronous flush + executor shutdown. Call on ServerStopping so no write is lost. */
    public void shutdown() {
        dirty.set(false);
        save();
        ScheduledExecutorService current = io;
        if (current != null) current.shutdown();
        // Drop the reference so a stale terminated pool can never be reused; init() builds a fresh one.
        io = null;
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
        cache.putIfAbsent(uuid, new LoginRecord(firstSeen, 0L, 0L, null, 0L, 0, null));
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
