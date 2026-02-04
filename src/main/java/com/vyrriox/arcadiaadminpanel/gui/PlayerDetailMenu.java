package com.vyrriox.arcadiaadminpanel.gui;

import com.vyrriox.arcadiaadminpanel.event.ChatListener;
import com.vyrriox.arcadiaadminpanel.util.FTBDataReader;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.SkullCache;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

import java.util.*;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;

/**
 * Player Detail Menu - V3 (Stats + History + Language)
 * 
 * @author vyrriox
 */
public class PlayerDetailMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final UUID targetUUID;
    private final String targetName;
    private final boolean isOnline;
    private int homePage = 0;
    private static final int HOMES_PER_PAGE = 27; // Slots 9-35
    private boolean confirmClear = false; // State for confirmation

    public static void open(ServerPlayer admin, UUID targetUUID, String targetName, boolean isOnline) {
        MenuProvider provider = new SimpleMenuProvider(
                (id, playerInv, player) -> new PlayerDetailMenu(id, playerInv, (ServerPlayer) player, targetUUID,
                        targetName, isOnline),
                Component.literal(String.format(LanguageHelper.getText("detail.title", admin), targetName)));
        admin.openMenu(provider);
    }

    public PlayerDetailMenu(int id, Inventory playerInv, ServerPlayer admin, UUID targetUUID, String targetName,
            boolean isOnline) {
        super(MenuType.GENERIC_9x6, id, playerInv, new net.minecraft.world.SimpleContainer(54), 6);
        this.admin = admin;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.isOnline = isOnline;
        buildMenu();
    }

    private void buildMenu() {
        // Fill background
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler);
        }

        // Header (Row 1)
        ItemStack skull = SkullCache.createSkull(targetUUID, targetName);
        skull.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + targetName));
        skull.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal(isOnline ? "§a" + LanguageHelper.getText("player.online", admin)
                        : "§c" + LanguageHelper.getText("player.offline", admin)),
                Component.literal("§7UUID: " + targetUUID))));
        this.getContainer().setItem(4, skull);

        // Reset Progress (Slot 2) - Exp Bottle
        if (canUseCommand("advancement")) {
            ItemStack reset = new ItemStack(Items.EXPERIENCE_BOTTLE);
            reset.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§e" + LanguageHelper.getText("action.resetprog", admin)));
            this.getContainer().setItem(2, reset);
        }

        // InvSee (Slot 6) - Chest
        if (canUseCommand("invsee")) { // Requires a mod/plugin providing /invsee
            ItemStack invsee = new ItemStack(Items.CHEST);
            invsee.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§6" + LanguageHelper.getText("action.invsee", admin)));
            this.getContainer().setItem(6, invsee);
        }

        // Info Book (Slot 8)
        ItemStack infoBook = new ItemStack(Items.BOOK);
        infoBook.set(DataComponents.CUSTOM_NAME, Component.literal("§b" + LanguageHelper.getText("info.full", admin)));
        this.getContainer().setItem(8, infoBook);

        FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);

        // Homes (Rows 2-4: Slots 9-35)
        if (ftbData != null && !ftbData.homes.isEmpty()) {
            List<Map.Entry<String, FTBDataReader.HomeLocation>> homes = new ArrayList<>(ftbData.homes.entrySet());
            // Sort homes?
            homes.sort(Map.Entry.comparingByKey());

            int start = homePage * HOMES_PER_PAGE;
            int end = Math.min(start + HOMES_PER_PAGE, homes.size());

            for (int i = start; i < end; i++) {
                int slot = 9 + (i - start);
                Map.Entry<String, FTBDataReader.HomeLocation> entry = homes.get(i);

                ItemStack bed = new ItemStack(getDimensionIcon(entry.getValue().dimension));
                bed.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + entry.getKey()));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7Dim: §f" + entry.getValue().getShortDimension()));
                lore.add(Component.literal("§7Pos: §f" + entry.getValue().getFormattedCoords()));
                lore.add(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)));
                bed.set(DataComponents.LORE, new ItemLore(lore));

                this.getContainer().setItem(slot, bed);
            }
        } else {
            // Maybe show "No Homes" barrier?
            if (homePage == 0) {
                ItemStack noHomes = new ItemStack(Items.BARRIER);
                noHomes.set(DataComponents.CUSTOM_NAME,
                        Component.literal("§c" + LanguageHelper.getText("homes.none", admin)));
                this.getContainer().setItem(22, noHomes);
            }
        }

        // Teleport History (Row 5: Slots 36-44)
        if (ftbData != null && ftbData.teleportHistory != null && !ftbData.teleportHistory.isEmpty()) {
            List<FTBDataReader.TeleportRecord> history = ftbData.teleportHistory;
            for (int i = 0; i < Math.min(history.size(), 9); i++) {
                FTBDataReader.TeleportRecord record = history.get(i);
                ItemStack icon = new ItemStack(Items.CHORUS_FRUIT);
                icon.set(DataComponents.CUSTOM_NAME,
                        Component.literal("§d" + LanguageHelper.getText("detail.tp_history", admin) + " #" + (i + 1)));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7Dim: §f" + record.getShortDimension()));
                lore.add(Component.literal("§7Pos: §f" + record.getFormattedCoords()));
                // Time formatting could be complex, omitting for now or just raw?
                // lore.add(Component.literal("§8" + new Date(record.time).toString()));
                lore.add(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)));

                icon.set(DataComponents.LORE, new ItemLore(lore));
                this.getContainer().setItem(36 + i, icon);
            }
        }

        // Action Bar (Row 6)

        // TP Here (Slot 47) - Only if online AND has permission
        if (isOnline && canUseCommand("tp")) {
            ItemStack tpHere = new ItemStack(Items.ENDER_EYE);
            tpHere.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§d" + LanguageHelper.getText("action.tp_here", admin)));
            this.getContainer().setItem(47, tpHere);
        }

        // Clear Inventory (Slot 46) - Lava Bucket
        if (canUseCommand("clear")) {
            ItemStack clear = new ItemStack(confirmClear ? Items.REDSTONE_BLOCK : Items.LAVA_BUCKET);
            clear.set(DataComponents.CUSTOM_NAME, Component.literal((confirmClear ? "§c§l" : "§c") +
                    (confirmClear ? LanguageHelper.getText("misc.confirm", admin)
                            : LanguageHelper.getText("action.clearinv", admin))));
            this.getContainer().setItem(46, clear);
        }

        // TP / Last Loc (Slot 48)
        if (canUseCommand("tp")) {
            if (isOnline) {
                ItemStack tp = new ItemStack(Items.ENDER_PEARL);
                tp.set(DataComponents.CUSTOM_NAME,
                        Component.literal("§a" + LanguageHelper.getText("action.tp", admin)));
                this.getContainer().setItem(48, tp);
            } else if (ftbData != null && ftbData.lastSeen != null) {
                ItemStack lastLoc = new ItemStack(Items.COMPASS);
                lastLoc.set(DataComponents.CUSTOM_NAME,
                        Component.literal("§6" + LanguageHelper.getText("action.tp_last", admin)));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.literal("§7Dim: §f" + ftbData.lastSeen.getShortDimension()));
                lore.add(Component.literal("§7Pos: §f" + ftbData.lastSeen.getFormattedCoords()));
                lastLoc.set(DataComponents.LORE, new ItemLore(lore));
                this.getContainer().setItem(48, lastLoc);
            }
        }

        // Kick (Slot 49)
        if (canUseCommand("kick")) {
            ItemStack kick = new ItemStack(Items.IRON_BOOTS);
            kick.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§c" + LanguageHelper.getText("action.kick", admin)));
            this.getContainer().setItem(49, kick);
        }

        // Ban/Unban (Slot 50)
        if (canUseCommand("ban") || canUseCommand("pardon")) {
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
            boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);

            ItemStack ban = new ItemStack(isBanned ? Items.LIME_DYE : Items.RED_DYE); // Lime to Unban, Red to Ban
            ban.set(DataComponents.CUSTOM_NAME,
                    Component.literal(isBanned ? "§a" + LanguageHelper.getText("action.unban", admin)
                            : "§c" + LanguageHelper.getText("action.ban", admin)));
            this.getContainer().setItem(50, ban);
        }

        // Warn (Slot 51)
        if (canUseCommand("arcadiaadmin")) {
            ItemStack warn = new ItemStack(Items.TNT);
            warn.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§c" + LanguageHelper.getText("action.warn", admin)));
            this.getContainer().setItem(51, warn);

            // View Warns (Slot 52)
            ItemStack viewWarns = new ItemStack(Items.WRITABLE_BOOK);
            viewWarns.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§e" + LanguageHelper.getText("action.warn_list", admin)));
            this.getContainer().setItem(52, viewWarns);
        }

        // Back (Slot 53)
        ItemStack back = new ItemStack(Items.ARROW);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("§e" + LanguageHelper.getText("action.back", admin)));
        this.getContainer().setItem(53, back);
    }

    /**
     * Checks if the admin has permission to use a specific command.
     * Compatible with LuckPerms and permission levels.
     */
    private boolean canUseCommand(String commandLiteral) {
        try {
            CommandNode<CommandSourceStack> node = admin.getServer().getCommands().getDispatcher().getRoot()
                    .getChild(commandLiteral);
            if (node == null)
                return false;
            return node.canUse(admin.createCommandSourceStack());
        } catch (Exception e) {
            return false; // Fail safe
        }
    }

    private void showDetailedInfo() {
        admin.sendSystemMessage(Component.literal("§8§m--------------------------------"));
        admin.sendSystemMessage(Component.literal(
                String.format("§6§l%s: §e%s", LanguageHelper.getText("detail.title", admin).replace("%s", ""),
                        targetName)));
        admin.sendSystemMessage(Component.literal("§7UUID: §f" + targetUUID));
        admin.sendSystemMessage(
                Component.literal("§7" + (isOnline ? "§a" + LanguageHelper.getText("player.online", admin)
                        : "§c" + LanguageHelper.getText("player.offline", admin))));

        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
        boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
        boolean isWhitelisted = admin.getServer().getPlayerList().isWhiteListed(profile);

        admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.banned", admin) + ": "
                + (isBanned ? "§c" + LanguageHelper.getText("misc.yes", admin)
                        : "§a" + LanguageHelper.getText("misc.no", admin))));
        admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.whitelisted", admin) + ": "
                + (isWhitelisted ? "§a" + LanguageHelper.getText("misc.yes", admin)
                        : "§c" + LanguageHelper.getText("misc.no", admin))));

        if (!isOnline) {
            FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
            if (ftbData != null && ftbData.lastSeen != null) {
                admin.sendSystemMessage(Component.literal(
                        "§7" + LanguageHelper.getText("info.last_seen", admin) + ": §e"
                                + ftbData.lastSeen.getFormattedCoords()
                                + " §7in §e" + ftbData.lastSeen.getShortDimension()));
            }
        }
        admin.sendSystemMessage(Component.literal("§8§m--------------------------------"));
    }

    // Execute Teleport
    private void executeTeleport(String dimensionId, double x, double y, double z) {
        ServerLevel level = null;
        for (ServerLevel w : admin.getServer().getAllLevels()) {
            if (w.dimension().location().toString().equals(dimensionId)) {
                level = w;
                break;
            }
        }

        if (level == null) {
            // Try to match short id or default
            // Fallback to current level if unknown
            admin.sendSystemMessage(
                    Component.literal("§cUnknown dimension: " + dimensionId + ". Teleporting in current world."));
            level = admin.serverLevel();
        }

        admin.teleportTo(level, x, y, z, admin.getYRot(), admin.getXRot());
        admin.sendSystemMessage(Component.literal("§aTeleported to " + String.format("%.0f, %.0f, %.0f", x, y, z)));
    }

    private net.minecraft.world.item.Item getDimensionIcon(String dim) {
        if (dim.contains("nether"))
            return Items.NETHERRACK;
        if (dim.contains("end"))
            return Items.END_STONE;
        if (dim.contains("mining"))
            return Items.IRON_PICKAXE;
        return Items.GRASS_BLOCK;
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer serverPlayer))
            return;
        ItemStack clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE))
            return;

        // Back (53)
        if (slotId == 53) {
            serverPlayer.closeContainer();
            AdminPanelMenu.open(serverPlayer);
            return;
        }

        // Info (8)
        if (slotId == 8) {
            showDetailedInfo();
            serverPlayer.closeContainer();
            return;
        }

        // Reset Progress (Slot 2)
        if (slotId == 2 && canUseCommand("advancement")) {
            admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                    "advancement revoke " + targetName + " everything");
            admin.closeContainer();
            return;
        }

        // InvSee (Slot 6)
        if (slotId == 6 && canUseCommand("invsee")) {
            admin.closeContainer(); // Close FIRST, then open new one
            admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                    "invsee " + targetName);
            return;
        }

        // Reset Confirmation if clicking anything else
        if (slotId != 46 && confirmClear) {
            confirmClear = false;
            buildMenu(); // Rebuild to reset icon
            return;
        }

        // Clear Inventory (Slot 46)
        if (slotId == 46 && canUseCommand("clear")) {
            if (!confirmClear) {
                confirmClear = true;
                admin.playNotifySound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(),
                        net.minecraft.sounds.SoundSource.MASTER, 1.0f, 1.0f);
                buildMenu(); // Update icon
            } else {
                admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                        "clear " + targetName);
                admin.sendSystemMessage(Component
                        .literal("§a" + String.format(LanguageHelper.getText("msg.inv_cleared", admin), targetName)));
                admin.closeContainer();
            }
            return;
        }

        // Actions
        if (slotId == 47 && isOnline) { // TP Here
            admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                    "tp " + targetName + " " + admin.getName().getString());
            admin.closeContainer();
        }

        if (slotId == 48) { // TP / Last Loc
            if (isOnline) {
                admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                        "tp " + targetName);
            } else {
                FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
                if (ftbData != null && ftbData.lastSeen != null) {
                    executeTeleport(ftbData.lastSeen.dimension, ftbData.lastSeen.x, ftbData.lastSeen.y,
                            ftbData.lastSeen.z);
                }
            }
            admin.closeContainer();
        } else if (slotId == 49) { // Kick
            admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                    "kick " + targetName + " Admin Action");
            admin.closeContainer();
        } else if (slotId == 50) { // Ban/Unban
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
            boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
            if (isBanned) {
                admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                        "pardon " + targetName);
            } else {
                admin.getServer().getCommands().performPrefixedCommand(admin.createCommandSourceStack(),
                        "ban " + targetName + " Admin Action");
            }
            admin.closeContainer();
            open(admin, targetUUID, targetName, isOnline);
        } else if (slotId == 51) { // Warn
            admin.closeContainer();
            ChatListener.startWarnSession(admin, targetUUID, targetName);
        } else if (slotId == 52) { // View Warns
            admin.closeContainer();
            WarnListMenu.open(admin, targetUUID, targetName);
        }

        // Homes (9-35)
        if (slotId >= 9 && slotId <= 35) {
            FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
            if (ftbData != null) {
                List<Map.Entry<String, FTBDataReader.HomeLocation>> homes = new ArrayList<>(ftbData.homes.entrySet());
                homes.sort(Map.Entry.comparingByKey());
                int index = (homePage * HOMES_PER_PAGE) + (slotId - 9);
                if (index < homes.size()) {
                    FTBDataReader.HomeLocation home = homes.get(index).getValue();
                    executeTeleport(home.dimension, home.x, home.y, home.z);
                    admin.closeContainer();
                }
            }
        }

        // History (36-44)
        if (slotId >= 36 && slotId <= 44) {
            FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
            if (ftbData != null && ftbData.teleportHistory != null) {
                int index = slotId - 36;
                if (index < ftbData.teleportHistory.size()) {
                    FTBDataReader.TeleportRecord record = ftbData.teleportHistory.get(index);
                    executeTeleport(record.dimension, record.x, record.y, record.z);
                    admin.closeContainer();
                }
            }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
