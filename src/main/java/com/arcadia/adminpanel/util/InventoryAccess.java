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
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Reads and writes a player's inventory, whether they are connected or not.
 *
 * <p>The offline half is the reason this exists. "I lost my quest item and I have to go" used to end
 * with the player being told to come back later; now the same panel button opens their stored
 * inventory and writes it back. The file format is vanilla's: slots 0-35 are the main inventory
 * (0-8 being the hotbar), 100-103 the armour, 150 the off-hand. This class flattens that into a
 * dense 41-slot array so the menu does not have to know about the gaps.</p>
 *
 * <p><b>Safety.</b> An offline write refuses to run if the player has reconnected in the meantime,
 * because vanilla would overwrite the file again on their next save and silently discard the edit.
 * The write is staged through a temporary file and the previous {@code .dat} is kept as
 * {@code .dat_old}, exactly like vanilla's own save path, so a crash mid-write cannot destroy an
 * inventory.</p>
 *
 * <p><b>Threading.</b> Disk access happens on a dedicated daemon thread and the result is handed
 * back on the server thread. The tick loop never waits on a file.</p>
 *
 * @author vyrriox
 */
public final class InventoryAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 36 main + 4 armour + 1 off-hand. */
    public static final int SIZE = 41;
    public static final int ARMOR_START = 36;
    public static final int OFFHAND = 40;
    /** Ender chest slots. Stored separately: it is its own container, not part of the 41. */
    public static final int ENDER_SIZE = 27;

    private static volatile ExecutorService io;

    private InventoryAccess() {}

    public static void init() {
        ExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-InventoryIO");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public static void shutdown() {
        ExecutorService current = io;
        if (current != null) current.shutdown();
        io = null;
    }

    // -- Online --------------------------------------------------------------

    /** Snapshot of a connected player's inventory in the dense layout. */
    public static ItemStack[] readOnline(ServerPlayer player) {
        ItemStack[] out = new ItemStack[SIZE];
        Arrays.fill(out, ItemStack.EMPTY);
        var inv = player.getInventory();
        for (int i = 0; i < 36 && i < inv.items.size(); i++) out[i] = inv.items.get(i).copy();
        for (int i = 0; i < 4 && i < inv.armor.size(); i++) out[ARMOR_START + i] = inv.armor.get(i).copy();
        if (!inv.offhand.isEmpty()) out[OFFHAND] = inv.offhand.get(0).copy();
        return out;
    }

    /** Overwrites a connected player's inventory from the dense layout. */
    public static void writeOnline(ServerPlayer player, ItemStack[] slots) {
        var inv = player.getInventory();
        for (int i = 0; i < 36 && i < inv.items.size(); i++) {
            inv.items.set(i, safe(slots, i));
        }
        for (int i = 0; i < 4 && i < inv.armor.size(); i++) {
            inv.armor.set(i, safe(slots, ARMOR_START + i));
        }
        if (!inv.offhand.isEmpty()) inv.offhand.set(0, safe(slots, OFFHAND));
        inv.setChanged();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }

    /** Snapshot of a connected player's ender chest. */
    public static ItemStack[] readOnlineEnder(ServerPlayer player) {
        ItemStack[] out = new ItemStack[ENDER_SIZE];
        Arrays.fill(out, ItemStack.EMPTY);
        var chest = player.getEnderChestInventory();
        for (int i = 0; i < ENDER_SIZE && i < chest.getContainerSize(); i++) {
            out[i] = chest.getItem(i).copy();
        }
        return out;
    }

    /** Overwrites a connected player's ender chest. */
    public static void writeOnlineEnder(ServerPlayer player, ItemStack[] slots) {
        var chest = player.getEnderChestInventory();
        for (int i = 0; i < ENDER_SIZE && i < chest.getContainerSize(); i++) {
            chest.setItem(i, safe(slots, i).copy());
        }
        chest.setChanged();
    }

    // -- Offline -------------------------------------------------------------

    /** True when this server has a stored data file for the player. */
    public static boolean hasOfflineData(MinecraftServer server, UUID uuid) {
        return Files.exists(playerFile(server, uuid));
    }

    /**
     * Reads a disconnected player's inventory off disk, then hands the result to {@code callback} on
     * the server thread. The callback receives {@code null} when there is no readable data.
     */
    public static void readOfflineAsync(MinecraftServer server, UUID uuid,
                                        Consumer<ItemStack @Nullable []> callback) {
        ExecutorService exec = io;
        if (exec == null) {
            callback.accept(null);
            return;
        }
        CompletableFuture
                .supplyAsync(() -> readOfflineBlocking(server, uuid), exec)
                .thenAccept(result -> server.execute(() -> callback.accept(result)));
    }

    /**
     * Writes a disconnected player's inventory back to disk, then reports success on the server
     * thread. Refuses when the player reconnected while the editor was open.
     */
    public static void writeOfflineAsync(MinecraftServer server, UUID uuid, ItemStack[] slots,
                                         Consumer<Boolean> callback) {
        writeOfflineAsync(server, uuid, slots, null, callback);
    }

    /**
     * Same, with the ender chest written alongside the inventory. Pass {@code null} for
     * {@code ender} to leave the stored ender chest untouched.
     */
    public static void writeOfflineAsync(MinecraftServer server, UUID uuid, ItemStack[] slots,
                                         ItemStack @Nullable [] ender, Consumer<Boolean> callback) {
        if (server.getPlayerList().getPlayer(uuid) != null) {
            callback.accept(false);
            return;
        }
        ExecutorService exec = io;
        if (exec == null) {
            callback.accept(false);
            return;
        }
        // The stacks are copied here, on the server thread, so the IO thread never reads a stack the
        // game is mutating underneath it.
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) copy[i] = safe(slots, i).copy();
        ItemStack[] enderCopy = null;
        if (ender != null) {
            enderCopy = new ItemStack[ENDER_SIZE];
            for (int i = 0; i < ENDER_SIZE; i++) enderCopy[i] = safe(ender, i).copy();
        }
        HolderLookup.Provider registries = server.registryAccess();
        final ItemStack[] enderFinal = enderCopy;

        CompletableFuture
                .supplyAsync(() -> writeOfflineBlocking(server, uuid, copy, enderFinal, registries), exec)
                .thenAccept(ok -> server.execute(() -> callback.accept(ok)));
    }

    @Nullable
    private static ItemStack[] readOfflineBlocking(MinecraftServer server, UUID uuid) {
        Path file = playerFile(server, uuid);
        if (!Files.exists(file)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return fromInventoryTag(root.getList("Inventory", Tag.TAG_COMPOUND), server.registryAccess());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to read offline inventory for {}", uuid, e);
            return null;
        }
    }

    private static boolean writeOfflineBlocking(MinecraftServer server, UUID uuid,
                                                ItemStack[] slots, ItemStack @Nullable [] ender,
                                                HolderLookup.Provider registries) {
        Path file = playerFile(server, uuid);
        if (!Files.exists(file)) return false;
        Path tmp = file.resolveSibling(uuid + ".dat.arcadia-tmp");
        Path backup = file.resolveSibling(uuid + ".dat_old");
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            root.put("Inventory", toInventoryTag(slots, registries));
            if (ender != null) root.put("EnderItems", toContainerTag(ender, registries));
            NbtIo.writeCompressed(root, tmp);
            // Keep the previous file as .dat_old before swapping, the same guarantee vanilla gives.
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to write offline inventory for {}", uuid, e);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            return false;
        }
    }

    private static Path playerFile(MinecraftServer server, UUID uuid) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    // -- NBT conversion ------------------------------------------------------

    /** Vanilla slot layout to dense array. Unknown slot numbers are ignored. */
    public static ItemStack[] fromInventoryTag(ListTag list, HolderLookup.Provider registries) {
        ItemStack[] out = new ItemStack[SIZE];
        Arrays.fill(out, ItemStack.EMPTY);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            int slot = tag.getByte("Slot") & 255;
            ItemStack stack = ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) continue;
            if (slot < 36) out[slot] = stack;
            else if (slot >= 100 && slot < 104) out[ARMOR_START + (slot - 100)] = stack;
            else if (slot == 150) out[OFFHAND] = stack;
        }
        return out;
    }

    /** Dense array back to the vanilla slot layout. */
    public static ListTag toInventoryTag(ItemStack[] slots, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (int i = 0; i < 36; i++) appendSlot(list, safe(slots, i), i, registries);
        for (int i = 0; i < 4; i++) appendSlot(list, safe(slots, ARMOR_START + i), 100 + i, registries);
        appendSlot(list, safe(slots, OFFHAND), 150, registries);
        return list;
    }

    private static void appendSlot(ListTag list, ItemStack stack, int slot,
                                   HolderLookup.Provider registries) {
        if (stack.isEmpty()) return;
        CompoundTag tag = new CompoundTag();
        tag.putByte("Slot", (byte) slot);
        list.add(stack.save(registries, tag));
    }

    /**
     * Reads a plain slot-indexed container list (the ender chest layout) into a dense array. The
     * game's own {@link net.minecraft.world.SimpleContainer} does the parsing, so a modded stack
     * round-trips exactly as vanilla would store it.
     */
    public static ItemStack[] fromContainerTag(ListTag list, int size, HolderLookup.Provider registries) {
        net.minecraft.world.SimpleContainer container = new net.minecraft.world.SimpleContainer(size);
        try {
            container.fromTag(list, registries);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to parse container tag", e);
        }
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) out[i] = container.getItem(i).copy();
        return out;
    }

    /** Dense array back to the slot-indexed container list. */
    public static ListTag toContainerTag(ItemStack[] slots, HolderLookup.Provider registries) {
        int size = slots == null ? 0 : slots.length;
        net.minecraft.world.SimpleContainer container =
                new net.minecraft.world.SimpleContainer(Math.max(1, size));
        for (int i = 0; i < size; i++) container.setItem(i, safe(slots, i).copy());
        return container.createTag(registries);
    }

    private static ItemStack safe(ItemStack[] slots, int index) {
        if (slots == null || index < 0 || index >= slots.length || slots[index] == null) {
            return ItemStack.EMPTY;
        }
        return slots[index];
    }

    /** How many of the 41 slots hold something. Used for the "N items" lore line. */
    public static int occupied(ItemStack[] slots) {
        int n = 0;
        for (int i = 0; i < SIZE; i++) if (!safe(slots, i).isEmpty()) n++;
        return n;
    }
}
