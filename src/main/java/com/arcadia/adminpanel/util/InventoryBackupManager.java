package com.arcadia.adminpanel.util;

import com.arcadia.lib.ServerContext;
import com.arcadia.lib.data.DatabaseManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Daily inventory backups for connected players, so a lost item is a lookup rather than a debate.
 *
 * <p>Death snapshots already answer "I died and lost everything". They do not answer the other half
 * of the support queue: an item that disappeared into a broken machine, a chunk that rolled back, a
 * mod that ate a stack on update. Nobody died, so nothing was captured, and the only evidence is the
 * player's word. This takes a full copy of every connected player's inventory once a day (and, by
 * default, when they disconnect) and keeps the last few, so staff can look at what somebody was
 * actually carrying yesterday.</p>
 *
 * <p><b>Two backends, one behaviour.</b> With a database configured, each backup is a row in
 * {@code arcadia_inventory_backups} carrying its compressed payload, which means every server on the
 * network can read a backup taken on another one. Without a database, backups live in one compressed
 * NBT file per player under {@code config/arcadia/arcadiaadminpanel/invbackups}. The panel cannot
 * tell the difference.</p>
 *
 * <p><b>Cost.</b> The tick thread copies 41 inventory slots and 27 ender slots per captured player,
 * no more often than the configured interval, and hands them to a daemon thread. Listing a player's
 * backups reads headers only, never a payload: the stacks are fetched when a staff member opens one
 * specific backup. Retention is enforced on every write, so neither the table nor the files grow
 * without bound.</p>
 *
 * @author vyrriox
 */
public final class InventoryBackupManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Why a backup was taken. Stored verbatim, so these strings must not be renamed. */
    public static final String REASON_DAILY = "daily";
    public static final String REASON_LOGOUT = "logout";
    public static final String REASON_MANUAL = "manual";

    /** How often the sweep looks for players due a backup. 1200 ticks = one minute. */
    private static final int SWEEP_TICKS = 1200;
    /** Players captured per sweep, so a full server spreads its writes instead of bursting. */
    private static final int CAPTURES_PER_SWEEP = 3;
    /** Upper bound on the rows one retention pass deletes. */
    private static final int PRUNE_BATCH = 500;

    /**
     * One backup without its stacks: everything the list screen shows. Fetching a payload is a
     * deliberate act, so browsing a player's history never deserialises a single item.
     */
    public record Header(long id, long time, String reason, String dimension,
                         int xpLevel, int itemCount, String serverId) {

        /** True when the backup was taken by another server sharing this database. */
        public boolean isForeign() {
            return serverId != null && !serverId.isBlank()
                    && !serverId.equals(ServerContext.SERVER_ID);
        }
    }

    /** A header plus its stacks. {@code items} uses the dense {@link InventoryAccess} layout. */
    public record Snapshot(Header header, ItemStack[] items, ItemStack[] ender) {

        public int enderCount() { return countNonEmpty(ender); }
    }

    /** Headers per player, newest first. Absent means "never read from the backend". */
    private static final Map<UUID, List<Header>> CACHE = new ConcurrentHashMap<>();
    /** When each player was last captured, so the sweep never has to read anything. */
    private static final Map<UUID, Long> LAST_CAPTURE = new ConcurrentHashMap<>();
    /** Players whose header read is in flight; keeps the sweep from queueing a second one. */
    private static final Map<UUID, Boolean> LOADING = new ConcurrentHashMap<>();

    private static volatile ExecutorService io;
    private static int tickCounter = 0;

    private InventoryBackupManager() {}

    // -- Lifecycle -----------------------------------------------------------

    public static void init() {
        ExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-InvBackups");
                t.setDaemon(true);
                return t;
            });
        }
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create inventory backup directory", e);
        }
    }

    public static void shutdown() {
        ExecutorService current = io;
        if (current != null) current.shutdown();
        io = null;
        CACHE.clear();
        LAST_CAPTURE.clear();
        LOADING.clear();
        tickCounter = 0;
    }

    private static boolean databaseMode() {
        return DatabaseManager.isDatabaseActive();
    }

    private static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel/invbackups");
    }

    private static Path fileFor(UUID uuid) {
        return dir().resolve(uuid + ".nbt");
    }

    // -- Hooks ---------------------------------------------------------------

    /**
     * Warms a player's header list on join. The sweep needs to know when they were last captured,
     * and finding that out later would mean a disk read or a query from the tick thread.
     */
    public static void onJoin(ServerPlayer player) {
        if (!AdminConfig.get().inventoryBackupEnabled) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        warm(server, player.getUUID());
    }

    /**
     * Captures on disconnect when the operator asked for it. This is the backup that matters most in
     * practice: it is the last known good state before whatever the player reports tomorrow.
     */
    public static void onQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (AdminConfig.get().inventoryBackupEnabled
                && AdminConfig.get().inventoryBackupOnLogout
                && dueForMinimumInterval(uuid)) {
            capture(player, REASON_LOGOUT);
        }
        // The header list is only there to answer the sweep and to draw a count; a disconnected
        // player needs neither, and keeping every visitor's list would grow without bound on a busy
        // server. The last-capture clock stays, so a relog cannot bypass the minimum interval.
        // Eviction is safe against the capture queued just above: it publishes into the cache only
        // when one is present, and the next read comes off the same IO thread, after the write.
        CACHE.remove(uuid);
        LOADING.remove(uuid);
    }

    /** Daily sweep. An integer compare per tick, a scan of the online list once a minute. */
    public static void onServerTick(MinecraftServer server) {
        if (++tickCounter < SWEEP_TICKS) return;
        tickCounter = 0;
        if (!AdminConfig.get().inventoryBackupEnabled) return;

        long intervalMs = Math.max(1, AdminConfig.get().inventoryBackupIntervalHours) * 3_600_000L;
        long now = System.currentTimeMillis();
        int done = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (done >= CAPTURES_PER_SWEEP) break;
            UUID uuid = player.getUUID();
            Long last = LAST_CAPTURE.get(uuid);
            if (last == null) {
                // Nothing known yet: read the headers now and let the next sweep decide. Capturing
                // blind would hand every player a fresh backup after every restart.
                warm(server, uuid);
                continue;
            }
            if (now - last < intervalMs) continue;
            capture(player, REASON_DAILY);
            done++;
        }
    }

    // -- Capture -------------------------------------------------------------

    /**
     * Takes a backup of a connected player. Copies on the server thread, writes off it. Returns
     * false when the capture was refused: feature off, empty inventory, or no IO thread.
     */
    public static boolean capture(ServerPlayer player, String reason) {
        if (!AdminConfig.get().inventoryBackupEnabled) return false;
        ExecutorService exec = io;
        MinecraftServer server = player.getServer();
        if (exec == null || server == null) return false;

        ItemStack[] items = InventoryAccess.readOnline(player);
        ItemStack[] ender = AdminConfig.get().inventoryBackupEnderChest
                ? InventoryAccess.readOnlineEnder(player)
                : new ItemStack[0];
        int count = countNonEmpty(items) + countNonEmpty(ender);
        if (count == 0) {
            // An empty inventory is not worth a row, but it still counts as "seen": without this the
            // sweep would come back to the same player every single minute.
            LAST_CAPTURE.put(player.getUUID(), System.currentTimeMillis());
            return false;
        }

        UUID uuid = player.getUUID();
        String name = player.getName().getString();
        long now = System.currentTimeMillis();
        Header header = new Header(0L, now, reason,
                player.level().dimension().location().toString(),
                player.experienceLevel, count, ServerContext.SERVER_ID);
        HolderLookup.Provider registries = server.registryAccess();
        int keep = Math.max(1, AdminConfig.get().inventoryBackupKeepPerPlayer);

        LAST_CAPTURE.put(uuid, now);
        exec.execute(() -> {
            Header stored = databaseMode()
                    ? insertDb(uuid, name, header, items, ender, registries, keep)
                    : appendFile(uuid, header, items, ender, registries, keep);
            if (stored == null) return;
            List<Header> list = CACHE.get(uuid);
            if (list != null) {
                synchronized (list) {
                    list.add(0, stored);
                    list.sort(Comparator.comparingLong(Header::time).reversed());
                    while (list.size() > keep) list.remove(list.size() - 1);
                }
            }
        });
        return true;
    }

    /** Captures every connected player. Used by the panel button and the manual command. */
    public static int captureAll(MinecraftServer server, String reason) {
        int n = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (capture(player, reason)) n++;
        }
        return n;
    }

    /** Guards against a relog loop writing one backup per reconnection. */
    private static boolean dueForMinimumInterval(UUID uuid) {
        Long last = LAST_CAPTURE.get(uuid);
        if (last == null) return true;
        long floorMs = Math.max(0, AdminConfig.get().inventoryBackupMinIntervalMinutes) * 60_000L;
        return System.currentTimeMillis() - last >= floorMs;
    }

    // -- Read ----------------------------------------------------------------

    /** Cached header count, or -1 when this player's backups have not been read yet. */
    public static int cachedCount(UUID uuid) {
        List<Header> list = CACHE.get(uuid);
        if (list == null) return -1;
        synchronized (list) { return list.size(); }
    }

    /** When this player was last captured, or 0 when it is not known. */
    public static long lastCapture(UUID uuid) {
        Long last = LAST_CAPTURE.get(uuid);
        return last == null ? 0L : last;
    }

    /** Hands the player's headers to {@code callback} on the server thread, newest first. */
    public static void headersAsync(MinecraftServer server, UUID uuid, Consumer<List<Header>> callback) {
        List<Header> cached = CACHE.get(uuid);
        if (cached != null) {
            callback.accept(copyOf(cached));
            return;
        }
        ExecutorService exec = io;
        if (exec == null) {
            callback.accept(List.of());
            return;
        }
        CompletableFuture
                .supplyAsync(() -> readHeadersBlocking(uuid), exec)
                .thenAccept(list -> server.execute(() ->
                        callback.accept(copyOf(cacheHeaders(uuid, list)))));
    }

    /**
     * Fetches one backup with its stacks. The callback receives {@code null} when the backup is gone:
     * retention dropped it, or another server deleted the row.
     */
    public static void loadAsync(MinecraftServer server, UUID uuid, long id,
                                 Consumer<@Nullable Snapshot> callback) {
        ExecutorService exec = io;
        if (exec == null) {
            callback.accept(null);
            return;
        }
        HolderLookup.Provider registries = server.registryAccess();
        CompletableFuture
                .supplyAsync(() -> readSnapshotBlocking(uuid, id, registries), exec)
                .thenAccept(snap -> server.execute(() -> callback.accept(snap)));
    }

    /** Pre-reads the headers so the sweep and the menus have a count without waiting for one. */
    private static void warm(MinecraftServer server, UUID uuid) {
        if (CACHE.containsKey(uuid)) return;
        if (LOADING.putIfAbsent(uuid, Boolean.TRUE) != null) return;
        ExecutorService exec = io;
        if (exec == null) {
            LOADING.remove(uuid);
            return;
        }
        CompletableFuture
                .supplyAsync(() -> readHeadersBlocking(uuid), exec)
                .thenAccept(list -> server.execute(() -> {
                    cacheHeaders(uuid, list);
                    LOADING.remove(uuid);
                }));
    }

    /**
     * Publishes a freshly-read header list and seeds the last-capture clock from it.
     *
     * <p>{@code putIfAbsent}, not {@code put}: a capture that landed while the read was in flight has
     * already inserted itself into the cached list, and replacing it here would throw that entry
     * away. This is the same trap the death snapshots fell into in 1.3.0.</p>
     */
    private static List<Header> cacheHeaders(UUID uuid, List<Header> read) {
        List<Header> fresh = Collections.synchronizedList(new ArrayList<>(read));
        List<Header> existing = CACHE.putIfAbsent(uuid, fresh);
        List<Header> list = existing != null ? existing : fresh;
        long newest = 0L;
        synchronized (list) {
            for (Header h : list) newest = Math.max(newest, h.time());
        }
        LAST_CAPTURE.merge(uuid, newest, Math::max);
        return list;
    }

    private static List<Header> copyOf(List<Header> list) {
        List<Header> copy;
        synchronized (list) { copy = new ArrayList<>(list); }
        copy.sort(Comparator.comparingLong(Header::time).reversed());
        return copy;
    }

    // -- Restore -------------------------------------------------------------

    /**
     * Replaces a connected player's inventory with the backup.
     *
     * <p>Replace, not add. Handing the stored stacks back on top of what the player is already
     * carrying is a duplication machine, which is exactly the mistake the death snapshots had to be
     * fixed for in 1.3.1. Restoring the state the backup recorded cannot duplicate anything; what it
     * can do is discard what was collected since, which is why the panel asks twice.</p>
     *
     * @return how many stacks the player ends up holding
     */
    public static int restoreOnline(ServerPlayer actor, ServerPlayer target, Snapshot snapshot,
                                    boolean withEnder) {
        InventoryAccess.writeOnline(target, snapshot.items());
        boolean ender = withEnder && snapshot.ender() != null && snapshot.ender().length > 0;
        if (ender) InventoryAccess.writeOnlineEnder(target, snapshot.ender());
        target.containerMenu.broadcastChanges();
        int count = countNonEmpty(snapshot.items()) + (ender ? snapshot.enderCount() : 0);
        AuditManager.record(actor, AdminAction.BACKUP_RESTORE, target.getUUID(),
                target.getName().getString(),
                count + " stacks (" + snapshot.header().reason() + ")");
        return count;
    }

    /**
     * The same for a disconnected player, by rewriting their stored data. Reports success through
     * {@code callback}; a refusal means they reconnected while the menu was open.
     */
    public static void restoreOffline(ServerPlayer actor, MinecraftServer server, UUID target,
                                      String targetName, Snapshot snapshot, boolean withEnder,
                                      Consumer<Boolean> callback) {
        ItemStack[] ender = withEnder && snapshot.ender() != null && snapshot.ender().length > 0
                ? snapshot.ender() : null;
        InventoryAccess.writeOfflineAsync(server, target, snapshot.items(), ender, ok -> {
            if (ok) {
                int count = countNonEmpty(snapshot.items())
                        + (ender != null ? snapshot.enderCount() : 0);
                AuditManager.record(actor, AdminAction.BACKUP_RESTORE, target, targetName,
                        count + " stacks (offline, " + snapshot.header().reason() + ")");
            }
            callback.accept(ok);
        });
    }

    /**
     * Hands one stack out of a backup back to a connected player. This is the everyday answer to "I
     * lost my drill": give back the one item and leave the rest of the inventory alone.
     */
    public static boolean giveStack(ServerPlayer actor, ServerPlayer target, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack copy = stack.copy();
        int count = copy.getCount();
        String label = copy.getHoverName().getString();
        if (!target.getInventory().add(copy)) target.drop(copy, false);
        target.containerMenu.broadcastChanges();
        AuditManager.record(actor, AdminAction.BACKUP_ITEM, target.getUUID(),
                target.getName().getString(), count + "x " + label);
        return true;
    }

    // -- File backend --------------------------------------------------------

    /**
     * One compressed file per player holding a list of backup tags. Headers are plain fields on each
     * tag, so listing never has to deserialise a stack.
     */
    @Nullable
    private static Header appendFile(UUID uuid, Header header, ItemStack[] items, ItemStack[] ender,
                                     HolderLookup.Provider registries, int keep) {
        Path file = fileFor(uuid);
        Path tmp = file.resolveSibling(uuid + ".nbt.tmp");
        try {
            Files.createDirectories(dir());
            CompoundTag root = Files.exists(file)
                    ? NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                    : new CompoundTag();
            ListTag existing = root.getList("backups", Tag.TAG_COMPOUND);

            long nextId = 0L;
            for (int i = 0; i < existing.size(); i++) {
                nextId = Math.max(nextId, existing.getCompound(i).getLong("id"));
            }
            nextId++;

            CompoundTag tag = new CompoundTag();
            tag.putLong("id", nextId);
            tag.putLong("time", header.time());
            tag.putString("reason", header.reason());
            tag.putString("dimension", header.dimension());
            tag.putInt("xp", header.xpLevel());
            tag.putInt("count", header.itemCount());
            tag.putString("server", header.serverId() == null ? "" : header.serverId());
            tag.put("items", InventoryAccess.toInventoryTag(items, registries));
            if (ender != null && ender.length > 0) {
                tag.put("ender", InventoryAccess.toContainerTag(ender, registries));
            }

            // Newest first, oldest dropped past the retention window.
            List<CompoundTag> previous = new ArrayList<>();
            for (int i = 0; i < existing.size(); i++) previous.add(existing.getCompound(i));
            previous.sort(Comparator.comparingLong((CompoundTag t) -> t.getLong("time")).reversed());

            ListTag out = new ListTag();
            out.add(tag);
            for (CompoundTag t : previous) {
                if (out.size() >= keep) break;
                out.add(t);
            }

            CompoundTag newRoot = new CompoundTag();
            newRoot.put("backups", out);
            NbtIo.writeCompressed(newRoot, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Header(nextId, header.time(), header.reason(), header.dimension(),
                    header.xpLevel(), header.itemCount(), header.serverId());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to write inventory backup for {}", uuid, e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return null;
        }
    }

    private static List<Header> readHeadersFile(UUID uuid) {
        Path file = fileFor(uuid);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList("backups", Tag.TAG_COMPOUND);
            List<Header> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                out.add(new Header(tag.getLong("id"), tag.getLong("time"), tag.getString("reason"),
                        tag.getString("dimension"), tag.getInt("xp"), tag.getInt("count"),
                        tag.getString("server")));
            }
            out.sort(Comparator.comparingLong(Header::time).reversed());
            return out;
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read inventory backups for {}", uuid, e);
            return new ArrayList<>();
        }
    }

    @Nullable
    private static Snapshot readSnapshotFile(UUID uuid, long id, HolderLookup.Provider registries) {
        Path file = fileFor(uuid);
        if (!Files.exists(file)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList("backups", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                if (tag.getLong("id") != id) continue;
                Header header = new Header(id, tag.getLong("time"), tag.getString("reason"),
                        tag.getString("dimension"), tag.getInt("xp"), tag.getInt("count"),
                        tag.getString("server"));
                return new Snapshot(header,
                        InventoryAccess.fromInventoryTag(
                                tag.getList("items", Tag.TAG_COMPOUND), registries),
                        InventoryAccess.fromContainerTag(
                                tag.getList("ender", Tag.TAG_COMPOUND),
                                InventoryAccess.ENDER_SIZE, registries));
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read inventory backup {} for {}", id, uuid, e);
            return null;
        }
    }

    // -- Database backend ----------------------------------------------------

    @Nullable
    private static Header insertDb(UUID uuid, String name, Header header, ItemStack[] items,
                                   ItemStack[] ender, HolderLookup.Provider registries, int keep) {
        String payload = encodePayload(items, ender, registries);
        if (payload == null) return null;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO arcadia_inventory_backups (player_uuid, player_name, server_id, "
                   + "created_at, capture_reason, dimension, xp_level, item_count, payload) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, ServerContext.SERVER_ID);
            ps.setLong(4, header.time());
            ps.setString(5, header.reason());
            ps.setString(6, header.dimension());
            ps.setInt(7, header.xpLevel());
            ps.setInt(8, header.itemCount());
            ps.setString(9, payload);
            ps.executeUpdate();
            long id = 0L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) id = keys.getLong(1);
            }
            pruneDb(conn, uuid, keep);
            return new Header(id, header.time(), header.reason(), header.dimension(),
                    header.xpLevel(), header.itemCount(), header.serverId());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to insert inventory backup for {}", uuid, e);
            return null;
        }
    }

    /**
     * Retention, applied on every write. Selecting the ids to drop and then deleting them by id keeps
     * the statement portable: {@code DELETE ... LIMIT} and {@code NOT IN (SELECT ... LIMIT)} are each
     * rejected by one engine or the other.
     */
    private static void pruneDb(Connection conn, UUID uuid, int keep) {
        List<Long> doomed = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM arcadia_inventory_backups WHERE player_uuid = ? "
              + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, PRUNE_BATCH);
            ps.setInt(3, keep);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) doomed.add(rs.getLong(1));
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to list expired inventory backups for {}", uuid, e);
            return;
        }
        if (doomed.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM arcadia_inventory_backups WHERE id = ?")) {
            for (long id : doomed) {
                ps.setLong(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to delete expired inventory backups for {}", uuid, e);
        }
    }

    private static List<Header> readHeadersDb(UUID uuid) {
        List<Header> out = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, created_at, capture_reason, dimension, xp_level, item_count, server_id "
                   + "FROM arcadia_inventory_backups WHERE player_uuid = ? "
                   + "ORDER BY created_at DESC LIMIT 64")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Header(rs.getLong("id"), rs.getLong("created_at"),
                            rs.getString("capture_reason"), rs.getString("dimension"),
                            rs.getInt("xp_level"), rs.getInt("item_count"),
                            rs.getString("server_id")));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read inventory backups for {} from database", uuid, e);
        }
        return out;
    }

    @Nullable
    private static Snapshot readSnapshotDb(UUID uuid, long id, HolderLookup.Provider registries) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT created_at, capture_reason, dimension, xp_level, item_count, server_id, "
                   + "payload FROM arcadia_inventory_backups WHERE id = ? AND player_uuid = ?")) {
            ps.setLong(1, id);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Header header = new Header(id, rs.getLong("created_at"),
                        rs.getString("capture_reason"), rs.getString("dimension"),
                        rs.getInt("xp_level"), rs.getInt("item_count"), rs.getString("server_id"));
                CompoundTag payload = decodePayload(rs.getString("payload"));
                if (payload == null) return null;
                return new Snapshot(header,
                        InventoryAccess.fromInventoryTag(
                                payload.getList("items", Tag.TAG_COMPOUND), registries),
                        InventoryAccess.fromContainerTag(
                                payload.getList("ender", Tag.TAG_COMPOUND),
                                InventoryAccess.ENDER_SIZE, registries));
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read inventory backup {} for {}", id, uuid, e);
            return null;
        }
    }

    /**
     * Compressed NBT, base64 encoded. A modded inventory does not survive a JSON round-trip the way a
     * record payload does, and the game's own compressed NBT writer is the only encoder guaranteed to
     * read back exactly what it wrote.
     */
    @Nullable
    private static String encodePayload(ItemStack[] items, ItemStack[] ender,
                                        HolderLookup.Provider registries) {
        try {
            CompoundTag root = new CompoundTag();
            root.put("items", InventoryAccess.toInventoryTag(items, registries));
            if (ender != null && ender.length > 0) {
                root.put("ender", InventoryAccess.toContainerTag(ender, registries));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to encode an inventory backup payload", e);
            return null;
        }
    }

    @Nullable
    private static CompoundTag decodePayload(String payload) {
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            return NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to decode an inventory backup payload", e);
            return null;
        }
    }

    // -- Backend routing -----------------------------------------------------

    private static List<Header> readHeadersBlocking(UUID uuid) {
        return databaseMode() ? readHeadersDb(uuid) : readHeadersFile(uuid);
    }

    @Nullable
    private static Snapshot readSnapshotBlocking(UUID uuid, long id, HolderLookup.Provider registries) {
        return databaseMode()
                ? readSnapshotDb(uuid, id, registries)
                : readSnapshotFile(uuid, id, registries);
    }

    private static int countNonEmpty(ItemStack @Nullable [] slots) {
        if (slots == null) return 0;
        int n = 0;
        for (ItemStack stack : slots) if (stack != null && !stack.isEmpty()) n++;
        return n;
    }

    /** Empty dense array, for callers that need a placeholder. */
    public static ItemStack[] emptyItems() {
        ItemStack[] out = new ItemStack[InventoryAccess.SIZE];
        Arrays.fill(out, ItemStack.EMPTY);
        return out;
    }
}
