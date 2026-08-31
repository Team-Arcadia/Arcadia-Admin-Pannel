package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.InventoryAccess;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Edits a player's inventory, whether they are connected or not.
 *
 * <p><b>Layout.</b> The first four rows mirror the vanilla layout the admin already knows: main
 * inventory, then hotbar, then armour and off-hand on the fifth row. The last row is controls, and
 * the gap between the two is barriers, so nothing can be dropped into the control strip by accident.</p>
 *
 * <p><b>Explicit save.</b> Nothing is written until the save button is pressed. Closing the window
 * discards, and says so. The alternative, applying every click live, means one misclick silently
 * deletes an item from someone's inventory with no undo.</p>
 *
 * <p><b>Offline safety.</b> An offline edit reads and writes the player's data file on a background
 * thread, and refuses to write if they reconnected while the window was open: vanilla would
 * overwrite the file from memory on their next save, so the edit would be lost without anyone
 * noticing.</p>
 *
 * @author vyrriox
 */
public class InventoryEditMenu extends ChestMenu {

    /** Where the editable area ends. 0-40 map onto the dense inventory layout. */
    private static final int EDIT_SLOTS = InventoryAccess.SIZE;
    private static final int SLOT_SAVE = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_DISCARD = 50;

    private final ServerPlayer admin;
    private final UUID target;
    private final String targetName;
    private final boolean online;
    private boolean saved = false;
    private boolean loading = true;

    public static void open(ServerPlayer admin, UUID target, String targetName, boolean online) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new InventoryEditMenu(id, inv, (ServerPlayer) p, target, targetName, online),
                PanelTitles.of(LanguageHelper.getText("invedit.title", admin) + ": " + targetName)));
    }

    public InventoryEditMenu(int id, Inventory playerInv, ServerPlayer admin,
                             UUID target, String targetName, boolean online) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.target = target;
        this.targetName = targetName;
        this.online = online;
        load();
    }

    // -- Loading -------------------------------------------------------------

    private void load() {
        MinecraftServer server = admin.getServer();
        if (server == null) return;
        renderFrame();

        if (online) {
            ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
            if (targetPlayer == null) {
                loading = false;
                return;
            }
            fill(InventoryAccess.readOnline(targetPlayer));
            loading = false;
            return;
        }

        // Offline: the file read happens off-thread, so the window opens immediately and fills in.
        InventoryAccess.readOfflineAsync(server, target, slots -> {
            loading = false;
            if (admin.containerMenu != this) return;
            if (slots == null) {
                admin.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("invedit.no_data", admin)
                                .replace("%player%", targetName)));
                renderFrame();
                this.broadcastChanges();
                return;
            }
            fill(slots);
            this.broadcastChanges();
        });
    }

    private void fill(ItemStack[] slots) {
        for (int i = 0; i < EDIT_SLOTS; i++) {
            this.getContainer().setItem(i, slots[i] == null ? ItemStack.EMPTY : slots[i].copy());
        }
        renderFrame();
    }

    private void renderFrame() {
        ItemStack barrier = ItemBuilder.of(Items.BLACK_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = EDIT_SLOTS; i < 45; i++) this.getContainer().setItem(i, barrier.copy());

        ItemStack filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = 45; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        this.getContainer().setItem(SLOT_SAVE, ItemBuilder.of(Items.LIME_DYE)
                .name(Component.literal("§a" + t("invedit.save")))
                .addLore(Component.literal("§7" + t("invedit.save.hint")))
                .build());

        this.getContainer().setItem(SLOT_INFO, ItemBuilder.of(Items.BOOK)
                .name(Component.literal("§6" + targetName))
                .addLore(Component.literal("§7" + t(online ? "invedit.mode.online" : "invedit.mode.offline")))
                .addLore(Component.literal("§8" + t("invedit.layout1")))
                .addLore(Component.literal("§8" + t("invedit.layout2")))
                .addLore(Component.literal("§8" + t("invedit.layout3")))
                .addLore(Component.literal(loading ? "§e" + t("invedit.loading") : "§8"))
                .build());

        this.getContainer().setItem(SLOT_DISCARD, ItemBuilder.of(Items.RED_DYE)
                .name(Component.literal("§c" + t("invedit.discard")))
                .addLore(Component.literal("§7" + t("invedit.discard.hint")))
                .build());
    }

    private String t(String key) {
        return LanguageHelper.getText(key, admin);
    }

    // -- Saving --------------------------------------------------------------

    private void save() {
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        ItemStack[] slots = new ItemStack[EDIT_SLOTS];
        for (int i = 0; i < EDIT_SLOTS; i++) {
            ItemStack stack = this.getContainer().getItem(i);
            slots[i] = stack == null ? ItemStack.EMPTY : stack.copy();
        }

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);
        if (targetPlayer != null) {
            // A backup of what was there before the edit, taken automatically since 1.3.2. An
            // inventory editor with an explicit save still has no undo, and the state most worth
            // keeping is the one that is about to be overwritten.
            com.arcadia.adminpanel.util.InventoryBackupManager.capture(targetPlayer,
                    com.arcadia.adminpanel.util.InventoryBackupManager.REASON_MANUAL);
            InventoryAccess.writeOnline(targetPlayer, slots);
            finishSave(true);
            return;
        }

        if (!AdminConfig.get().offlineInventoryEditEnabled) {
            admin.sendSystemMessage(ArcadiaMessages.error(t("invedit.offline_disabled")));
            return;
        }
        InventoryAccess.writeOfflineAsync(server, target, slots, this::finishSave);
    }

    private void finishSave(boolean ok) {
        if (!ok) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("invedit.failed", admin).replace("%player%", targetName)));
            SoundHelper.error(admin);
            return;
        }
        saved = true;
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("invedit.saved", admin).replace("%player%", targetName)));
        SoundHelper.success(admin);
        AuditManager.record(admin, AdminAction.INV_EDIT, target, targetName,
                online ? "online" : "offline");
    }

    // -- Interaction ---------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;
        if (!AdminPermissions.INV_EDIT.check(sp)) return;

        // Drags span several slots through a multi-stage protocol; blocking one stage mid-sequence
        // leaves the menu in an inconsistent state, so the whole gesture is refused instead.
        if (clickType == ClickType.QUICK_CRAFT) return;

        if (slotId == SLOT_SAVE) {
            save();
            return;
        }
        if (slotId == SLOT_DISCARD) {
            saved = true; // suppress the "nothing was saved" notice: discarding was the intent
            sp.closeContainer();
            PlayerDetailMenu.open(sp, target, targetName, online);
            return;
        }

        // Only the editable area is interactive.
        //
        // The control strip is obvious. Blocking the admin's own inventory (54+) and the
        // drop-outside slot (-999) is the part that matters: the container holds a *copy* of the
        // target's inventory that is only written back on save. If the admin could pull a stack out
        // of that copy into their own inventory, or drop it on the floor, and then close without
        // saving, the target would keep the item and a second one would exist. Confining every
        // click to the copy means the cursor can only ever hold something that came from it, which
        // is why clearing the cursor on close (below) is safe.
        //
        // Adding items to a player therefore goes through the Give Item tool, which is the audited
        // path for that anyway.
        if (slotId >= EDIT_SLOTS) return;
        if (slotId < 0) return;

        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public void removed(@NotNull Player player) {
        // Drop whatever is on the cursor before vanilla can hand it to the admin. It is a copy taken
        // from the target's snapshot (see clicked), so returning it to anyone would duplicate it.
        this.setCarried(ItemStack.EMPTY);
        super.removed(player);
        if (!saved && player instanceof ServerPlayer sp && !sp.hasDisconnected()) {
            sp.sendSystemMessage(ArcadiaMessages.warning(
                    LanguageHelper.getText("invedit.discarded", sp)));
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        // Shift-click would happily land a stack in the control strip. The editor is deliberate work;
        // one click per item is the right trade for not corrupting the layout.
        return ItemStack.EMPTY;
    }
}
