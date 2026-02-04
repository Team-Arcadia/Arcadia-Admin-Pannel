package com.vyrriox.arcadiaadminpanel.gui;

import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.OfflinePlayerManager;
import com.vyrriox.arcadiaadminpanel.util.SkullCache;
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
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Main Admin Panel Menu - V2 (Language Support)
 * 
 * @author vyrriox
 */
public class AdminPanelMenu extends ChestMenu {

    private final ServerPlayer admin;
    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 45;
    private boolean showOffline = true; // Default to showing all

    public static void open(ServerPlayer admin) {
        MenuProvider provider = new SimpleMenuProvider(
                (id, playerInv, player) -> new AdminPanelMenu(id, playerInv, (ServerPlayer) player),
                LanguageHelper.getComponent("menu.title", admin));
        admin.openMenu(provider);
    }

    public AdminPanelMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(MenuType.GENERIC_9x6, id, playerInv, new net.minecraft.world.SimpleContainer(54), 6);
        this.admin = admin;
        buildMenu();
    }

    private void buildMenu() {
        // 1. Build Player List
        List<PlayerInfo> allPlayers = new ArrayList<>();

        // Add online players
        for (ServerPlayer player : admin.getServer().getPlayerList().getPlayers()) {
            allPlayers.add(new PlayerInfo(player.getUUID(), player.getName().getString(), true));
        }

        // Add offline players if enabled
        if (showOffline) {
            Map<UUID, OfflinePlayerManager.CachedPlayerSummary> cache = OfflinePlayerManager.getInstance().getCache();
            for (OfflinePlayerManager.CachedPlayerSummary summary : cache.values()) {
                boolean isOnline = admin.getServer().getPlayerList().getPlayer(summary.uuid()) != null;
                if (!isOnline) {
                    allPlayers.add(new PlayerInfo(summary.uuid(), summary.name(), false));
                }
            }
        }

        // Sort: Online first, then Alphabetical
        allPlayers.sort((p1, p2) -> {
            if (p1.online != p2.online)
                return p1.online ? -1 : 1;
            return p1.name.compareToIgnoreCase(p2.name);
        });

        // 2. Pagination
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, allPlayers.size());

        // Fill background
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler);
        }

        // Place Heads
        for (int i = start; i < end; i++) {
            PlayerInfo info = allPlayers.get(i);
            int slot = i - start;

            ItemStack skull = SkullCache.createSkull(info.uuid, info.name);
            skull.set(DataComponents.CUSTOM_NAME, Component.literal((info.online ? "§a" : "§c") + info.name));
            this.getContainer().setItem(slot, skull);
        }

        // 3. Controls (Row 6)

        // Previous Page (Slot 45)
        if (currentPage > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("§e<< Previous"));
            this.getContainer().setItem(45, prev);
        }

        // Filter Button (Slot 49)
        ItemStack filter = new ItemStack(showOffline ? Items.LIME_DYE : Items.GRAY_DYE);
        filter.set(DataComponents.CUSTOM_NAME,
                Component.literal("§6" + (showOffline ? LanguageHelper.getText("menu.filter.all", admin)
                        : LanguageHelper.getText("menu.filter.online", admin))));
        this.getContainer().setItem(49, filter);

        // Language Button (Slot 52) - REMOVED (Auto-detection)

        // Next Page (Slot 53)
        if (end < allPlayers.size()) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("§eNext >>"));
            this.getContainer().setItem(53, next);
        }
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer)) // Variable not needed if only type checking
            return;
        ItemStack clicked = this.getContainer().getItem(slotId);

        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE))
            return;

        // Player Click (Slots 0-44)
        if (slotId < 45) {
            // Need to find which player was clicked
            // Re-build list logic effectively or store cache maps?
            // Since list is dynamic, simplest is to check item Name/NBT
            // But we have OfflinePlayerManager, let's just reverse lookup from name on item
            String displayName = clicked.getHoverName().getString();
            // Name has color code prefix usually
            String cleanName = displayName.replaceAll("§[0-9a-fk-or]", "");

            // Try to find UUID
            UUID targetUUID = null;
            boolean isOnline = false;

            ServerPlayer target = admin.getServer().getPlayerList().getPlayerByName(cleanName);
            if (target != null) {
                targetUUID = target.getUUID();
                isOnline = true;
            } else {
                // Search in offline cache
                for (var entry : OfflinePlayerManager.getInstance().getCache().entrySet()) {
                    if (entry.getValue().name().equalsIgnoreCase(cleanName)) {
                        targetUUID = entry.getKey();
                        break;
                    }
                }
            }

            if (targetUUID != null) {
                PlayerDetailMenu.open(admin, targetUUID, cleanName, isOnline);
            }
            return;
        }

        // Filter (49)
        if (slotId == 49) {
            showOffline = !showOffline;
            currentPage = 0; // Reset page
            buildMenu();
            return;
        }

        // Language (52) - Removed logic

        // Paging
        if (slotId == 45 && currentPage > 0) {
            currentPage--;
            buildMenu();
        } else if (slotId == 53) {
            currentPage++;
            buildMenu();
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    private record PlayerInfo(UUID uuid, String name, boolean online) {
    }
}
