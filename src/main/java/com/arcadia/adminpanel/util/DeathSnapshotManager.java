package com.arcadia.adminpanel.util;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Captures a player's full inventory the moment they die, so staff can give it back.
 *
 * <p>On a heavy modpack the single most common support ticket is "I lost my stuff and it was not my
 * fault". Without a snapshot the only answers are to believe the player, to refuse, or to roll back
 * the world. With one, the question becomes a lookup: here is exactly what they were carrying, at
 * what time, and how they died.</p>
 *
 * <p><b>Bounded by construction.</b> Each player keeps the last N deaths (five by default) in one
 * NBT file. Writes happen on a dedicated daemon thread; the capture itself is a copy of at most 41
 * stacks on the death tick, which is cheaper than the death screen it precedes. Nothing is loaded at
 * boot: a player's file is read the first time somebody opens their snapshot list.</p>
 *
 * @author vyrriox
 */
public final class DeathSnapshotManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One captured death. {@code items} uses the dense {@link InventoryAccess} layout. */
    public record Snapshot(long time, String cause, String dimension,
                           double x, double y, double z, int xpLevel, ItemStack[] items) {

        public int itemCount() { return InventoryAccess.occupied(items); }
    }

    private static final Map<UUID, List<Snapshot>> CACHE = new ConcurrentHashMap<>();
    private static volatile ExecutorService io;

    private DeathSnapshotManager() {}

    // -- Lifecycle -----------------------------------------------------------

    public static void init() {
        ExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-DeathSnapshots");
                t.setDaemon(true);
                return t;
            });
        }
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create death snapshot directory", e);
        }
    }

    public static void shutdown() {
        ExecutorService current = io;
        if (current != null) current.shutdown();
        io = null;
        CACHE.clear();
    }

    private static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel/deaths");
    }

    private static Path fileFor(UUID uuid) {
        return dir().resolve(uuid + ".nbt");
    }

    // -- Capture -------------------------------------------------------------

    /**
     * Called from the death event. Copies on the server thread, merges and persists off it.
     *
     * <p>The merge reads the player's file first when nothing is cached yet. Before 1.3.1 the cache
     * simply started empty, so the first death after a restart wrote a one-entry file over the four
     * snapshots already on disk and the history quietly reset itself every reboot.</p>
     */
    public static void capture(ServerPlayer player, String cause) {
        if (!AdminConfig.get().deathSnapshotsEnabled) return;
        ItemStack[] items = InventoryAccess.readOnline(player);
        if (InventoryAccess.occupied(items) == 0) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;
        ExecutorService exec = io;
        if (exec == null) {
            LOGGER.warn("[AdminPanel] Death snapshot for {} dropped: IO thread not running",
                    player.getName().getString());
            return;
        }

        Snapshot snap = new Snapshot(System.currentTimeMillis(), cause,
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                player.experienceLevel, items);

        UUID uuid = player.getUUID();
        HolderLookup.Provider registries = server.registryAccess();
        int max = Math.max(1, AdminConfig.get().deathSnapshotsPerPlayer);

        exec.execute(() -> {
            List<Snapshot> list = CACHE.get(uuid);
            if (list == null) {
                list = Collections.synchronizedList(new ArrayList<>(readBlocking(uuid, registries)));
                CACHE.put(uuid, list);
            }
            List<Snapshot> toWrite;
            synchronized (list) {
                list.add(0, snap);
                while (list.size() > max) list.remove(list.size() - 1);
                toWrite = new ArrayList<>(list);
            }
            writeBlocking(uuid, toWrite, registries);
        });
    }

    // -- Read ----------------------------------------------------------------

    /**
     * Hands the player's snapshots to {@code callback} on the server thread, reading the file the
     * first time and serving the cache afterwards.
     */
    public static void loadAsync(MinecraftServer server, UUID uuid, Consumer<List<Snapshot>> callback) {
        List<Snapshot> cached = CACHE.get(uuid);
        if (cached != null) {
            List<Snapshot> copy;
            synchronized (cached) { copy = new ArrayList<>(cached); }
            callback.accept(copy);
            return;
        }
        ExecutorService exec = io;
        if (exec == null) {
            callback.accept(List.of());
            return;
        }
        HolderLookup.Provider registries = server.registryAccess();
        CompletableFuture
                .supplyAsync(() -> readBlocking(uuid, registries), exec)
                .thenAccept(list -> server.execute(() -> {
                    // computeIfAbsent, not put: a death captured while this read was in flight has
                    // already merged the file with the new snapshot, and overwriting it here would
                    // throw that snapshot away.
                    List<Snapshot> cache = CACHE.computeIfAbsent(uuid,
                            k -> Collections.synchronizedList(new ArrayList<>(list)));
                    List<Snapshot> copy;
                    synchronized (cache) { copy = new ArrayList<>(cache); }
                    callback.accept(copy);
                }));
    }

    /** Cached count without touching disk. Returns -1 when the player's file has not been read. */
    public static int cachedCount(UUID uuid) {
        List<Snapshot> list = CACHE.get(uuid);
        if (list == null) return -1;
        synchronized (list) { return list.size(); }
    }

    // -- Restore -------------------------------------------------------------

    /**
     * Gives a snapshot back to a connected player. Items that do not fit are dropped at their feet
     * rather than silently discarded.
     *
     * @return how many stacks were handed back
     */
    public static int restoreOnline(ServerPlayer actor, ServerPlayer target, Snapshot snapshot) {
        int given = 0;
        for (ItemStack stack : snapshot.items()) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            if (!target.getInventory().add(copy)) target.drop(copy, false);
            given++;
        }
        target.containerMenu.broadcastChanges();
        AuditManager.record(actor, AdminAction.RESTORE_DEATH, target.getUUID(),
                target.getName().getString(), given + " stacks");
        return given;
    }

    /**
     * Merges a snapshot into a disconnected player's stored inventory, filling empty slots only.
     * Reports how many stacks did not fit through {@code callback}.
     */
    public static void restoreOffline(ServerPlayer actor, MinecraftServer server, UUID target,
                                      String targetName, Snapshot snapshot,
                                      Consumer<int[]> callback) {
        InventoryAccess.readOfflineAsync(server, target, current -> {
            if (current == null) {
                callback.accept(new int[] { 0, snapshot.itemCount() });
                return;
            }
            int restored = 0;
            int skipped = 0;
            for (ItemStack stack : snapshot.items()) {
                if (stack == null || stack.isEmpty()) continue;
                int free = firstEmpty(current);
                if (free < 0) { skipped++; continue; }
                current[free] = stack.copy();
                restored++;
            }
            final int done = restored;
            final int missed = skipped;
            InventoryAccess.writeOfflineAsync(server, target, current, ok -> {
                if (ok) {
                    AuditManager.record(actor, AdminAction.RESTORE_DEATH, target, targetName,
                            done + " stacks (offline)");
                }
                callback.accept(new int[] { ok ? done : 0, missed });
            });
        });
    }

    /** Only main-inventory slots are eligible; armour and off-hand are left alone. */
    private static int firstEmpty(ItemStack[] slots) {
        for (int i = 0; i < 36; i++) {
            if (slots[i] == null || slots[i].isEmpty()) return i;
        }
        return -1;
    }

    // -- Persistence ---------------------------------------------------------

    private static List<Snapshot> readBlocking(UUID uuid, HolderLookup.Provider registries) {
        Path file = fileFor(uuid);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList("snapshots", Tag.TAG_COMPOUND);
            List<Snapshot> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                ItemStack[] items = InventoryAccess.fromInventoryTag(
                        tag.getList("items", Tag.TAG_COMPOUND), registries);
                out.add(new Snapshot(tag.getLong("time"), tag.getString("cause"),
                        tag.getString("dimension"), tag.getDouble("x"), tag.getDouble("y"),
                        tag.getDouble("z"), tag.getInt("xp"), items));
            }
            return out;
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read death snapshots for {}", uuid, e);
            return new ArrayList<>();
        }
    }

    private static void writeBlocking(UUID uuid, List<Snapshot> snapshots,
                                      HolderLookup.Provider registries) {
        Path file = fileFor(uuid);
        Path tmp = file.resolveSibling(uuid + ".nbt.tmp");
        try {
            Files.createDirectories(dir());
            ListTag list = new ListTag();
            for (Snapshot s : snapshots) {
                CompoundTag tag = new CompoundTag();
                tag.putLong("time", s.time());
                tag.putString("cause", s.cause() == null ? "" : s.cause());
                tag.putString("dimension", s.dimension() == null ? "" : s.dimension());
                tag.putDouble("x", s.x());
                tag.putDouble("y", s.y());
                tag.putDouble("z", s.z());
                tag.putInt("xp", s.xpLevel());
                tag.put("items", InventoryAccess.toInventoryTag(s.items(), registries));
                list.add(tag);
            }
            CompoundTag root = new CompoundTag();
            root.put("snapshots", list);
            NbtIo.writeCompressed(root, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to write death snapshots for {}", uuid, e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /** Empty snapshot array helper for callers that need a placeholder. */
    public static ItemStack[] emptyItems() {
        ItemStack[] out = new ItemStack[InventoryAccess.SIZE];
        Arrays.fill(out, ItemStack.EMPTY);
        return out;
    }

    @Nullable
    public static Snapshot at(List<Snapshot> list, int index) {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }
}
