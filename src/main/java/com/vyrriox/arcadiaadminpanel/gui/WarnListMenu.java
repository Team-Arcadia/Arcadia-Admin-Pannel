package com.vyrriox.arcadiaadminpanel.gui;

import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Menu matching Admin Panel style to list warnings
 * 
 * @author vyrriox
 */
public class WarnListMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final UUID targetUUID;
    private final String targetName;
    private int page = 0;
    private static final int ITEMS_PER_PAGE = 45;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public static void open(ServerPlayer admin, UUID targetUUID, String targetName) {
        MenuProvider provider = new SimpleMenuProvider(
                (id, playerInv, player) -> new WarnListMenu(id, playerInv, (ServerPlayer) player, targetUUID,
                        targetName),
                Component.literal(String.format(LanguageHelper.getText("warn.list.title", admin), targetName)));
        admin.openMenu(provider);
    }

    public WarnListMenu(int id, Inventory playerInv, ServerPlayer admin, UUID targetUUID, String targetName) {
        super(MenuType.GENERIC_9x6, id, playerInv, new net.minecraft.world.SimpleContainer(54), 6);
        this.admin = admin;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        buildMenu();
    }

    private void buildMenu() {
        // Background
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler);
        }

        List<WarnManager.WarnEntry> warns = WarnManager.getInstance().getWarns(targetUUID);

        // Reverse order (newest first)
        List<WarnManager.WarnEntry> sortedWarns = new ArrayList<>(warns);
        sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

        if (sortedWarns.isEmpty()) {
            ItemStack barrier = new ItemStack(Items.BARRIER);
            barrier.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§c" + LanguageHelper.getText("warn.list.empty", admin)));
            this.getContainer().setItem(22, barrier);
        } else {
            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, sortedWarns.size());

            for (int i = start; i < end; i++) {
                WarnManager.WarnEntry warn = sortedWarns.get(i);
                int slot = i - start;

                ItemStack item = new ItemStack(Items.RED_STAINED_GLASS_PANE); // or PAPER/BOOK
                // Make it look dangerous
                item = new ItemStack(Items.PAPER);

                String title = String.format(LanguageHelper.getText("warn.item.title", admin), sortedWarns.size() - i);
                item.set(DataComponents.CUSTOM_NAME, Component.literal("§c" + title));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal(
                        "§7" + String.format(LanguageHelper.getText("warn.item.by", admin), "§e" + warn.by())));
                lore.add(Component.literal("§7" + String.format(LanguageHelper.getText("warn.item.date", admin),
                        "§f" + DATE_FORMAT.format(new Date(warn.timestamp())))));
                lore.add(Component.literal(" "));
                lore.add(Component.literal(
                        "§7" + String.format(LanguageHelper.getText("warn.item.reason", admin), "§c" + warn.reason())));

                item.set(DataComponents.LORE, new ItemLore(lore));
                this.getContainer().setItem(slot, item);
            }
        }

        // Pagination
        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§e<< Previous"));
            this.getContainer().setItem(45, prev);
        }

        if ((page + 1) * ITEMS_PER_PAGE < sortedWarns.size()) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext >>"));
            this.getContainer().setItem(53, next);
        }

        // Back Button (Slot 49)
        // Only show back button if admin is NOT the target (i.e. admin viewing someone
        // else)
        if (!admin.getUUID().equals(targetUUID)) {
            ItemStack back = new ItemStack(Items.OAK_DOOR);
            back.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§e" + LanguageHelper.getText("action.back", admin)));
            this.getContainer().setItem(49, back);
        }
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer))
            return;

        ItemStack clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE))
            return;

        if (slotId == 49) { // Back
            // Determine online status if possible, or pass false (safer)
            // Ideally we check if player is online again
            boolean online = admin.getServer().getPlayerList().getPlayer(targetUUID) != null;
            PlayerDetailMenu.open(admin, targetUUID, targetName, online);
            return;
        }

        if (slotId == 45 && page > 0) {
            page--;
            buildMenu();
        } else if (slotId == 53) {
            page++;
            buildMenu();
        }

        // Warn Item Click (Delete) - Only for Admins viewing others
        if (!admin.getUUID().equals(targetUUID)) {
            List<WarnManager.WarnEntry> warns = WarnManager.getInstance().getWarns(targetUUID);
            // Re-sort to match display
            List<WarnManager.WarnEntry> sortedWarns = new ArrayList<>(warns);
            sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, sortedWarns.size());

            if (slotId >= 0 && slotId < (end - start)) {
                int absoluteIndex = start + slotId;
                if (absoluteIndex < sortedWarns.size()) {
                    // Logic: Get 1-based index for removal
                    // removeWarn takes 1-based index relative to the sorted list
                    // So absoluteIndex + 1 should be correct
                    boolean success = WarnManager.getInstance().removeWarn(targetUUID, absoluteIndex + 1);
                    if (success) {
                        admin.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(),
                                net.minecraft.sounds.SoundSource.MASTER, 1.0f, 1.0f);
                        admin.sendSystemMessage(Component.literal("§a" + LanguageHelper.getText("warn.deleted", admin)
                                .replace("%d", String.valueOf(absoluteIndex + 1)).replace("%s", targetName)));
                        // Rebuild menu to reflect changes
                        buildMenu();
                    }
                }
            }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
