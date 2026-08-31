package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.InventoryAccess;
import com.arcadia.adminpanel.util.InventoryBackupManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A player's daily inventory backups, and the two ways to use one.
 *
 * <p>The list view is one row per backup: when it was taken, why, where the player was, how many
 * stacks it holds. Opening a row shows the stacks themselves, laid out the way an inventory screen is
 * laid out, with the ender chest one click away.</p>
 *
 * <p>Two actions, deliberately different in weight. <b>Clicking a single stack</b> hands that one item
 * back to a connected player, which is what the overwhelming majority of "I lost my drill" tickets
 * actually need. <b>Restore</b> replaces the whole inventory with the backup: it cannot duplicate
 * anything, but it discards whatever was collected since, so it asks twice and says so.</p>
 *
 * @author vyrriox
 */
public class InventoryBackupMenu extends PagedMenu {

    private static final int SLOT_SUMMARY = 46;
    private static final int SLOT_RESTORE = 47;
    private static final int SLOT_ENDER = 48;
    private static final int SLOT_CAPTURE = 50;

    /** Detail view renders into a fixed 45-slot grid so a click maps back to the stack it shows. */
    private static final int GRID = 45;

    private final UUID target;
    private final String targetName;
    private final @Nullable Long viewingId;

    private List<InventoryBackupManager.Header> headers = new ArrayList<>();
    private @Nullable InventoryBackupManager.Snapshot snapshot;
    private boolean loading = true;
    private boolean confirmRestore = false;
    private boolean enderView = false;

    /** Slot to stack for the detail grid, plus the same for the click side. */
    private final ItemStack[] display = new ItemStack[GRID];

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        openBackup(admin, target, targetName, null);
    }

    public static void openBackup(ServerPlayer admin, UUID target, String targetName,
                                  @Nullable Long backupId) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new InventoryBackupMenu(id, inv, (ServerPlayer) p,
                        target, targetName, backupId),
                PanelTitles.of(LanguageHelper.getText("backups.title", admin) + ": " + targetName)));
    }

    public InventoryBackupMenu(int id, Inventory playerInv, ServerPlayer admin,
                               UUID target, String targetName, @Nullable Long backupId) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        this.viewingId = backupId;
        Arrays.fill(display, ItemStack.EMPTY);
        load();
    }

    // -- Loading -------------------------------------------------------------

    private void load() {
        MinecraftServer server = admin.getServer();
        if (server == null) {
            loading = false;
            rebuild();
            return;
        }
        rebuild();
        InventoryBackupManager.headersAsync(server, target, list -> {
            headers = list;
            if (viewingId == null) {
                loading = false;
                // Rebuilt unconditionally: when the headers are already cached this callback runs
                // inside the constructor, before openMenu() has assigned containerMenu, and a
                // "containerMenu == this" guard would throw away the only render the screen gets.
                rebuild();
                return;
            }
            InventoryBackupManager.loadAsync(server, target, viewingId, snap -> {
                snapshot = snap;
                loading = false;
                buildDisplay();
                rebuild();
            });
        });
    }

    /** Lays the stacks out for the detail grid: bag, hotbar, then armour and off-hand. */
    private void buildDisplay() {
        Arrays.fill(display, ItemStack.EMPTY);
        if (snapshot == null) return;
        if (enderView) {
            ItemStack[] ender = snapshot.ender();
            for (int i = 0; i < InventoryAccess.ENDER_SIZE; i++) {
                if (ender == null || i >= ender.length) break;
                ItemStack stack = ender[i];
                if (stack != null && !stack.isEmpty()) display[i] = stack.copy();
            }
            return;
        }
        ItemStack[] items = snapshot.items();
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = items[i];
            if (stack == null || stack.isEmpty()) continue;
            display[layoutSlot(i)] = stack.copy();
        }
    }

    private boolean detail() {
        return viewingId != null;
    }

    private boolean hasEnder() {
        return snapshot != null && snapshot.ender() != null && snapshot.ender().length > 0;
    }

    // -- Content -------------------------------------------------------------

    @Override
    protected int contentSize() {
        return detail() ? GRID : headers.size();
    }

    @Override
    protected String emptyMessageKey() {
        return loading ? "backups.loading" : "backups.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        if (detail()) {
            ItemStack stack = display[index];
            if (stack == null || stack.isEmpty()) return;
            ItemStack shown = stack.copy();
            this.getContainer().setItem(slot, shown);
            return;
        }

        InventoryBackupManager.Header row = headers.get(index);
        var builder = ItemBuilder.of(icon(row.reason()))
                .name(Component.literal("§b" + LanguageHelper.getText("backups.entry", admin)
                        + " #" + (index + 1)))
                .addLore(Component.literal("§8" + date(row.time())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.reason", admin)
                        + " §f" + LanguageHelper.getText("backups.reason." + row.reason(), admin)))
                .addLore(Component.literal("§7" + shortDim(row.dimension()) + " §8"
                        + LanguageHelper.getText("backups.stacks", admin) + " §f" + row.itemCount()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.xp", admin)
                        + " §f" + row.xpLevel()));
        if (row.isForeign()) {
            builder.addLore(Component.literal("§6" + LanguageHelper.getText("backups.foreign", admin)
                    + " §f" + row.serverId()));
        }
        builder.addLore(Component.literal("§a" + LanguageHelper.getText("backups.click_open", admin)));
        this.getContainer().setItem(slot, builder.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (!detail()) {
            if (index >= headers.size()) return;
            admin.closeContainer();
            openBackup(admin, target, targetName, headers.get(index).id());
            return;
        }

        // Detail view: a click on a stack hands that one item back. This is the everyday repair, and
        // it is the one that cannot touch anything the player is currently carrying.
        if (index < 0 || index >= GRID) return;
        ItemStack stack = display[index];
        if (stack == null || stack.isEmpty()) return;
        if (!AdminPermissions.INV_BACKUP.check(admin)) return;

        MinecraftServer server = admin.getServer();
        if (server == null) return;
        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        if (targetPlayer == null) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("backups.give_offline", admin)));
            SoundHelper.error(admin);
            return;
        }
        InventoryBackupManager.giveStack(admin, targetPlayer, stack);
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("backups.gave", admin)
                        .replace("%count%", String.valueOf(stack.getCount()))
                        .replace("%item%", stack.getHoverName().getString())
                        .replace("%player%", targetName)));
        SoundHelper.success(admin);
    }

    // -- Controls ------------------------------------------------------------

    @Override
    protected void renderExtraControls() {
        if (!detail()) {
            if (AdminPermissions.INV_BACKUP.check(admin)
                    && AdminConfig.get().inventoryBackupEnabled) {
                boolean online = admin.getServer() != null
                        && admin.getServer().getPlayerList().getPlayer(target) != null;
                this.getContainer().setItem(SLOT_CAPTURE, ItemBuilder
                        .of(online ? Items.BUNDLE : Items.GRAY_DYE)
                        .name(Component.literal((online ? "§a" : "§8")
                                + LanguageHelper.getText("backups.capture", admin)))
                        .addLore(Component.literal(online
                                ? "§8" + LanguageHelper.getText("backups.capture.hint", admin)
                                : "§c" + LanguageHelper.getText("tools.requires_online", admin)))
                        .build());
            }
            return;
        }

        InventoryBackupManager.Snapshot snap = snapshot;
        if (snap == null) {
            // A backup can disappear between the list and the click: retention dropped it, or
            // another server on the same database did. An empty grid with no explanation reads as a
            // bug, so the reason is drawn where the summary would have been.
            this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText(
                            loading ? "backups.loading" : "backups.gone", admin)))
                    .build());
            return;
        }

        this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(icon(snap.header().reason()))
                .name(Component.literal("§b" + LanguageHelper.getText("backups.entry", admin)
                        + " §8" + date(snap.header().time())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.reason", admin)
                        + " §f" + LanguageHelper.getText("backups.reason." + snap.header().reason(), admin)))
                .addLore(Component.literal("§7" + shortDim(snap.header().dimension())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.stacks", admin)
                        + " §f" + snap.header().itemCount()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.xp", admin)
                        + " §f" + snap.header().xpLevel()))
                .addLore(Component.literal("§8" + LanguageHelper.getText(
                        enderView ? "backups.layout.ender" : "backups.layout", admin)))
                .addLore(Component.literal("§e" + LanguageHelper.getText("backups.give_hint", admin)))
                .build());

        if (hasEnder()) {
            this.getContainer().setItem(SLOT_ENDER, ItemBuilder
                    .of(enderView ? Items.CHEST : Items.ENDER_CHEST)
                    .name(Component.literal("§d" + LanguageHelper.getText(
                            enderView ? "backups.view.inventory" : "backups.view.ender", admin)))
                    .addLore(Component.literal("§7" + snap.enderCount() + " "
                            + LanguageHelper.getText("backups.stacks", admin)))
                    .build());
        }

        if (!AdminPermissions.INV_BACKUP.check(admin)) return;

        this.getContainer().setItem(SLOT_RESTORE, ItemBuilder
                .of(confirmRestore ? Items.REDSTONE_BLOCK : Items.SHULKER_BOX)
                .name(Component.literal((confirmRestore ? "§c§l" : "§a")
                        + LanguageHelper.getText("backups.restore", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("backups.restore.replaces", admin)))
                .addLore(Component.literal(confirmRestore
                        ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                        : "§8" + LanguageHelper.getText("backups.restore.hint", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        if (slot == SLOT_CAPTURE && !detail()) {
            if (!AdminPermissions.INV_BACKUP.check(admin)) return;
            ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
            if (targetPlayer == null) {
                admin.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("error.player_offline", admin)));
                SoundHelper.error(admin);
                return;
            }
            if (!InventoryBackupManager.capture(targetPlayer, InventoryBackupManager.REASON_MANUAL)) {
                admin.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("backups.capture_failed", admin)
                                .replace("%player%", targetName)));
                SoundHelper.error(admin);
                return;
            }
            AuditManager.record(admin, AdminAction.BACKUP_CAPTURE, target, targetName, "manual");
            admin.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("backups.captured", admin)
                            .replace("%player%", targetName)));
            SoundHelper.success(admin);
            // The write lands on the backup IO thread, so the list is re-read rather than guessed at.
            admin.closeContainer();
            open(admin, target, targetName);
            return;
        }

        if (slot == SLOT_ENDER && detail() && hasEnder()) {
            enderView = !enderView;
            buildDisplay();
            rebuild();
            SoundHelper.playAt(admin, SoundHelper.CLICK);
            return;
        }

        if (slot != SLOT_RESTORE || !detail()) return;
        if (!AdminPermissions.INV_BACKUP.check(admin)) return;
        InventoryBackupManager.Snapshot snap = snapshot;
        if (snap == null) return;

        if (!confirmRestore) {
            confirmRestore = true;
            rebuild();
            return;
        }
        confirmRestore = false;

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        if (targetPlayer != null) {
            int count = InventoryBackupManager.restoreOnline(admin, targetPlayer, snap, true);
            admin.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("backups.restored", admin)
                            .replace("%count%", String.valueOf(count))
                            .replace("%player%", targetName)));
            SoundHelper.success(admin);
            rebuild();
            return;
        }

        InventoryBackupManager.restoreOffline(admin, server, target, targetName, snap, true, ok -> {
            if (!ok) {
                admin.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("backups.restore_failed", admin)
                                .replace("%player%", targetName)));
                SoundHelper.error(admin);
                return;
            }
            admin.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("backups.restored", admin)
                            .replace("%count%", String.valueOf(snap.header().itemCount()))
                            .replace("%player%", targetName)));
            SoundHelper.success(admin);
        });
    }

    @Override
    protected void goBack() {
        if (detail()) {
            open(admin, target, targetName);
            return;
        }
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerToolsMenu.open(admin, target, targetName, online);
    }

    // -- Helpers -------------------------------------------------------------

    /** The icon says at a glance whether a backup is the daily one, a logout, or a staff capture. */
    private static Item icon(String reason) {
        if (InventoryBackupManager.REASON_LOGOUT.equals(reason)) return Items.IRON_DOOR;
        if (InventoryBackupManager.REASON_MANUAL.equals(reason)) return Items.BUNDLE;
        return Items.CHEST;
    }

    /**
     * Maps a dense inventory index onto the slot it occupies in the 45-slot grid: three rows of bag,
     * one row of hotbar, then armour (helmet to boots) and the off-hand. Same layout as the death
     * snapshots, so the two screens read alike.
     */
    private static int layoutSlot(int index) {
        if (index < 9) return 27 + index;            // hotbar, its own row under the bag
        if (index < 36) return index - 9;            // main inventory, rows one to three
        if (index < 40) return 38 + (39 - index);    // armour: helmet, chest, legs, boots
        return 43;                                    // off-hand, set apart from the armour
    }

    private static String shortDim(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
