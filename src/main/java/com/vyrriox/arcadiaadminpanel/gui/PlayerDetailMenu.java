package com.vyrriox.arcadiaadminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.staff.StaffActions;
import com.arcadia.lib.staff.StaffRole;
import com.arcadia.lib.staff.StaffService;
import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.vyrriox.arcadiaadminpanel.event.ChatListener;
import com.vyrriox.arcadiaadminpanel.util.FTBDataReader;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.SkullCache;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Player detail menu — stats, homes, actions.
 *
 * @author vyrriox
 */
public class PlayerDetailMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final UUID targetUUID;
    private final String targetName;
    private final boolean isOnline;
    private int homePage = 0;
    private boolean confirmClear = false;
    private static final int HOMES_PER_PAGE = 27;

    public static void open(ServerPlayer admin, UUID targetUUID, String targetName, boolean isOnline) {
        admin.openMenu(new SimpleMenuProvider(
                (id, playerInv, player) -> new PlayerDetailMenu(id, playerInv, (ServerPlayer) player,
                        targetUUID, targetName, isOnline),
                Component.literal(String.format(LanguageHelper.getText("detail.title", admin), targetName))
        ));
    }

    /** Server constructor. */
    public PlayerDetailMenu(int id, Inventory playerInv, ServerPlayer admin,
                            UUID targetUUID, String targetName, boolean isOnline) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.isOnline = isOnline;
        buildMenu();
    }

    /** Client constructor (minimal, items sync from server). */
    public PlayerDetailMenu(int id, Inventory playerInv, UUID targetUUID, String targetName, boolean isOnline) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        this.isOnline = isOnline;
    }

    /** Client fallback constructor. */
    public PlayerDetailMenu(int id, Inventory playerInv) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.targetUUID = UUID.randomUUID();
        this.targetName = "Unknown";
        this.isOnline = false;
    }

    private void buildMenu() {
        if (admin == null) return;

        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler.copy());
        }

        // Header — player skull (slot 4)
        var skull = SkullCache.createSkull(targetUUID, targetName);
        skull.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("§e" + targetName));
        skull.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(List.of(
                        Component.literal(isOnline
                                ? "§a" + LanguageHelper.getText("player.online", admin)
                                : "§c" + LanguageHelper.getText("player.offline", admin)),
                        Component.literal("§7UUID: " + targetUUID),
                        Component.literal("§7Warns: §e" + WarnManager.getInstance().getWarnCount(targetUUID))
                )));
        this.getContainer().setItem(4, skull);

        // Reset progress (slot 2)
        if (canUseCommand("advancement")) {
            this.getContainer().setItem(2, ItemBuilder.of(Items.EXPERIENCE_BOTTLE)
                    .name(Component.literal("§e" + LanguageHelper.getText("action.resetprog", admin))).build());
        }

        // InvSee (slot 6)
        if (canUseCommand("invsee")) {
            this.getContainer().setItem(6, ItemBuilder.of(Items.CHEST)
                    .name(Component.literal("§6" + LanguageHelper.getText("action.invsee", admin))).build());
        }

        // Info book (slot 8)
        this.getContainer().setItem(8, ItemBuilder.of(Items.BOOK)
                .name(Component.literal("§b" + LanguageHelper.getText("info.full", admin))).build());

        // Homes (slots 9-35)
        FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);

        if (ftbData != null && !ftbData.homes.isEmpty()) {
            List<Map.Entry<String, FTBDataReader.HomeLocation>> homes = new ArrayList<>(ftbData.homes.entrySet());
            homes.sort(Map.Entry.comparingByKey());
            int start = homePage * HOMES_PER_PAGE;
            int end = Math.min(start + HOMES_PER_PAGE, homes.size());
            for (int i = start; i < end; i++) {
                int slot = 9 + (i - start);
                var entry = homes.get(i);
                this.getContainer().setItem(slot, ItemBuilder.of(getDimensionIcon(entry.getValue().dimension))
                        .name(Component.literal("§e" + entry.getKey()))
                        .addLore(Component.literal("§7Dim: §f" + entry.getValue().getShortDimension()))
                        .addLore(Component.literal("§7Pos: §f" + entry.getValue().getFormattedCoords()))
                        .addLore(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)))
                        .build());
            }
        } else if (homePage == 0) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("homes.none", admin))).build());
        }

        // Teleport history (slots 36-44)
        if (ftbData != null && ftbData.teleportHistory != null) {
            for (int i = 0; i < Math.min(ftbData.teleportHistory.size(), 9); i++) {
                FTBDataReader.TeleportRecord record = ftbData.teleportHistory.get(i);
                this.getContainer().setItem(36 + i, ItemBuilder.of(Items.CHORUS_FRUIT)
                        .name(Component.literal("§d" + LanguageHelper.getText("detail.tp_history", admin) + " #" + (i + 1)))
                        .addLore(Component.literal("§7Dim: §f" + record.getShortDimension()))
                        .addLore(Component.literal("§7Pos: §f" + record.getFormattedCoords()))
                        .addLore(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)))
                        .build());
            }
        }

        // ── Action bar (row 6) ──────────────────────────────────────────────

        // Mute/Unmute (slot 45) — requires MOD staff role
        if (isOnline && StaffService.getRole(admin).atLeast(StaffRole.MOD)) {
            boolean isMuted = StaffActions.isMuted(targetUUID);
            if (isMuted) {
                long remaining = StaffActions.getMuteRemaining(targetUUID);
                String reason = StaffActions.getMuteReason(targetUUID);
                this.getContainer().setItem(45, ItemBuilder.of(Items.GREEN_DYE)
                        .name(Component.literal("§a" + LanguageHelper.getText("action.unmute", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("mute.remaining", admin)
                                + " §e" + TextFormatter.formatMs(remaining)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("mute.reason", admin)
                                + " §c" + (reason != null ? reason : "N/A")))
                        .build());
            } else {
                this.getContainer().setItem(45, ItemBuilder.of(Items.SCULK_SHRIEKER)
                        .name(Component.literal("§6" + LanguageHelper.getText("action.mute", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("mute.hint", admin)))
                        .build());
            }
        }

        // Clear inventory (slot 46)
        if (canUseCommand("clear")) {
            this.getContainer().setItem(46, ItemBuilder.of(confirmClear ? Items.REDSTONE_BLOCK : Items.LAVA_BUCKET)
                    .name(Component.literal((confirmClear ? "§c§l" : "§c") +
                            (confirmClear ? LanguageHelper.getText("misc.confirm", admin)
                                    : LanguageHelper.getText("action.clearinv", admin))))
                    .build());
        }

        // TP here (slot 47)
        if (isOnline && canUseCommand("tp")) {
            this.getContainer().setItem(47, ItemBuilder.of(Items.ENDER_EYE)
                    .name(Component.literal("§d" + LanguageHelper.getText("action.tp_here", admin))).build());
        }

        // TP to / last location (slot 48)
        if (canUseCommand("tp")) {
            if (isOnline) {
                this.getContainer().setItem(48, ItemBuilder.of(Items.ENDER_PEARL)
                        .name(Component.literal("§a" + LanguageHelper.getText("action.tp", admin))).build());
            } else if (ftbData != null && ftbData.lastSeen != null) {
                this.getContainer().setItem(48, ItemBuilder.of(Items.COMPASS)
                        .name(Component.literal("§6" + LanguageHelper.getText("action.tp_last", admin)))
                        .addLore(Component.literal("§7Dim: §f" + ftbData.lastSeen.getShortDimension()))
                        .addLore(Component.literal("§7Pos: §f" + ftbData.lastSeen.getFormattedCoords()))
                        .build());
            }
        }

        // Kick (slot 49)
        if (isOnline && canUseCommand("kick")) {
            this.getContainer().setItem(49, ItemBuilder.of(Items.IRON_BOOTS)
                    .name(Component.literal("§c" + LanguageHelper.getText("action.kick", admin))).build());
        }

        // Ban/Unban (slot 50)
        if (canUseCommand("ban") || canUseCommand("pardon")) {
            var profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
            boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
            this.getContainer().setItem(50, ItemBuilder.of(isBanned ? Items.LIME_DYE : Items.RED_DYE)
                    .name(Component.literal(isBanned
                            ? "§a" + LanguageHelper.getText("action.unban", admin)
                            : "§c" + LanguageHelper.getText("action.ban", admin)))
                    .build());
        }

        // Warn (slot 51)
        this.getContainer().setItem(51, ItemBuilder.of(Items.TNT)
                .name(Component.literal("§c" + LanguageHelper.getText("action.warn", admin))).build());

        // View warns (slot 52)
        this.getContainer().setItem(52, ItemBuilder.of(Items.WRITABLE_BOOK)
                .name(Component.literal("§e" + LanguageHelper.getText("action.warn_list", admin)))
                .addLore(Component.literal("§7" + WarnManager.getInstance().getWarnCount(targetUUID) + " warn(s)"))
                .build());

        // Back (slot 53)
        this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                .name(Component.literal("§e" + LanguageHelper.getText("action.back", admin))).build());
    }

    private boolean canUseCommand(String commandLiteral) {
        if (admin == null) return false;
        try {
            var node = admin.getServer().getCommands().getDispatcher().getRoot().getChild(commandLiteral);
            return node != null && node.canUse(admin.createCommandSourceStack());
        } catch (Exception e) {
            return false;
        }
    }

    private void executeTeleport(String dimensionId, double x, double y, double z) {
        ServerLevel level = null;
        for (ServerLevel w : admin.getServer().getAllLevels()) {
            if (w.dimension().location().toString().equals(dimensionId)) {
                level = w;
                break;
            }
        }
        if (level == null) level = admin.serverLevel();
        admin.teleportTo(level, x, y, z, admin.getYRot(), admin.getXRot());
        admin.sendSystemMessage(ArcadiaMessages.success(
                String.format("Teleported to %.0f, %.0f, %.0f", x, y, z)));
        SoundHelper.playAt(admin, SoundHelper.TELEPORT);
    }

    private net.minecraft.world.item.Item getDimensionIcon(String dim) {
        if (dim.contains("nether")) return Items.NETHERRACK;
        if (dim.contains("end")) return Items.END_STONE;
        if (dim.contains("mining")) return Items.IRON_PICKAXE;
        return Items.GRASS_BLOCK;
    }

    private void showDetailedInfo() {
        admin.sendSystemMessage(Component.literal("§8§m" + "─".repeat(40)));
        admin.sendSystemMessage(Component.literal(
                String.format("§6§l%s §e%s", LanguageHelper.getText("detail.title", admin).replace("%s", ""), targetName)));
        admin.sendSystemMessage(Component.literal("§7UUID: §f" + targetUUID));
        admin.sendSystemMessage(Component.literal("§7" + (isOnline
                ? "§a" + LanguageHelper.getText("player.online", admin)
                : "§c" + LanguageHelper.getText("player.offline", admin))));

        var profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
        boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
        boolean isWhitelisted = admin.getServer().getPlayerList().isWhiteListed(profile);

        admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.banned", admin) + ": "
                + (isBanned ? "§c" + LanguageHelper.getText("misc.yes", admin) : "§a" + LanguageHelper.getText("misc.no", admin))));
        admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.whitelisted", admin) + ": "
                + (isWhitelisted ? "§a" + LanguageHelper.getText("misc.yes", admin) : "§c" + LanguageHelper.getText("misc.no", admin))));

        int warnCount = WarnManager.getInstance().getWarnCount(targetUUID);
        admin.sendSystemMessage(Component.literal("§7Warns: §e" + warnCount));

        if (!isOnline) {
            FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
            if (ftbData != null && ftbData.lastSeen != null) {
                admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.last_seen", admin)
                        + ": §e" + ftbData.lastSeen.getFormattedCoords()
                        + " §7in §e" + ftbData.lastSeen.getShortDimension()));
            }
        }
        admin.sendSystemMessage(Component.literal("§8§m" + "─".repeat(40)));
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        var clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE)) return;

        // Reset confirmation state on other click
        if (slotId != 46 && confirmClear) {
            confirmClear = false;
            buildMenu();
            return;
        }

        switch (slotId) {
            case 53 -> { // Back
                sp.closeContainer();
                AdminPanelMenu.open(sp);
            }
            case 8 -> { // Info
                showDetailedInfo();
                sp.closeContainer();
            }
            case 2 -> { // Reset progress
                if (canUseCommand("advancement")) {
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "advancement revoke " + targetName + " everything");
                    admin.closeContainer();
                }
            }
            case 6 -> { // InvSee
                if (canUseCommand("invsee")) {
                    admin.closeContainer();
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "invsee " + targetName);
                }
            }
            case 46 -> { // Clear inventory
                if (canUseCommand("clear")) {
                    if (!confirmClear) {
                        confirmClear = true;
                        SoundHelper.playAt(admin, SoundHelper.CLICK);
                        buildMenu();
                    } else {
                        admin.getServer().getCommands().performPrefixedCommand(
                                admin.createCommandSourceStack(), "clear " + targetName);
                        admin.sendSystemMessage(ArcadiaMessages.success(
                                String.format(LanguageHelper.getText("msg.inv_cleared", admin), targetName)));
                        admin.closeContainer();
                    }
                }
            }
            case 47 -> { // TP here
                if (isOnline && canUseCommand("tp")) {
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "tp " + targetName + " " + admin.getName().getString());
                    admin.closeContainer();
                }
            }
            case 48 -> { // TP to / last loc
                if (isOnline) {
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "tp " + targetName);
                } else {
                    FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
                    if (ftbData != null && ftbData.lastSeen != null) {
                        executeTeleport(ftbData.lastSeen.dimension,
                                ftbData.lastSeen.x, ftbData.lastSeen.y, ftbData.lastSeen.z);
                    }
                }
                admin.closeContainer();
            }
            case 45 -> { // Mute/Unmute
                if (isOnline && StaffService.getRole(admin).atLeast(StaffRole.MOD)) {
                    boolean isMuted = StaffActions.isMuted(targetUUID);
                    if (isMuted) {
                        StaffActions.unmute(targetUUID, admin);
                        SoundHelper.playAt(admin, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    } else {
                        // Default mute: 10 minutes
                        StaffActions.mute(targetUUID, admin, "Admin Panel", 10 * 60_000L);
                        SoundHelper.playAt(admin, SoundHelper.CLICK);
                    }
                    admin.closeContainer();
                    admin.getServer().execute(() -> open(admin, targetUUID, targetName, isOnline));
                }
            }
            case 49 -> { // Kick
                if (isOnline && canUseCommand("kick")) {
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "kick " + targetName + " Admin Action");
                    admin.closeContainer();
                }
            }
            case 50 -> { // Ban/Unban
                var profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
                boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
                admin.getServer().getCommands().performPrefixedCommand(
                        admin.createCommandSourceStack(),
                        isBanned ? "pardon " + targetName : "ban " + targetName + " Admin Action");
                admin.closeContainer();
                open(admin, targetUUID, targetName, isOnline);
            }
            case 51 -> { // Warn (chat mode)
                admin.closeContainer();
                ChatListener.startWarnSession(admin, targetUUID, targetName);
            }
            case 52 -> { // View warns
                admin.closeContainer();
                WarnListMenu.open(admin, targetUUID, targetName);
            }
            default -> {
                // Homes (9-35)
                if (slotId >= 9 && slotId <= 35) {
                    FTBDataReader.PlayerFTBData ftbData = FTBDataReader.readPlayerData(targetUUID);
                    if (ftbData != null) {
                        var homes = new ArrayList<>(ftbData.homes.entrySet());
                        homes.sort(Map.Entry.comparingByKey());
                        int index = (homePage * HOMES_PER_PAGE) + (slotId - 9);
                        if (index < homes.size()) {
                            var home = homes.get(index).getValue();
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
                            var record = ftbData.teleportHistory.get(index);
                            executeTeleport(record.dimension, record.x, record.y, record.z);
                            admin.closeContainer();
                        }
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
