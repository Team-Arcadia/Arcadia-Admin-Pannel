package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AfkTracker;
import com.arcadia.adminpanel.util.AltDetector;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.BackManager;
import com.arcadia.adminpanel.util.ChatControl;
import com.arcadia.adminpanel.util.ClientModsRegistry;
import com.arcadia.adminpanel.util.LagMonitor;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.LoginQueueAuto;
import com.arcadia.adminpanel.util.RestartScheduler;
import com.arcadia.adminpanel.util.SelectionManager;
import com.arcadia.adminpanel.util.SilentMode;
import com.arcadia.adminpanel.util.SpyManager;
import com.arcadia.adminpanel.util.VanishManager;
import com.arcadia.adminpanel.util.WatchlistManager;
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

/**
 * The server-wide half of the panel: everything that is not about one specific player.
 *
 * <p>The player grid answers "what do I do about this person". This screen answers "what is going on
 * with the server", and holds the toggles a staff member flips on themselves. Splitting them keeps
 * the player sheet from growing a second page of unrelated buttons.</p>
 *
 * <p>Every tile is hidden when the viewer lacks its node, and every click re-checks that node before
 * doing anything, so a crafted packet cannot reach a tool that was never drawn.</p>
 *
 * @author vyrriox
 */
public class StaffToolsMenu extends ChestMenu {

    // Row 0: live status. Rows 1-3: tools. Row 5: personal toggles and navigation.
    private static final int SLOT_STATUS = 4;

    /** Column 0 of each row names the row, so the screen reads as three groups, not one wall. */
    private static final int SLOT_GROUP_INVESTIGATE = 9;
    private static final int SLOT_GROUP_SERVER = 18;
    private static final int SLOT_GROUP_SELF = 27;

    private static final int SLOT_AUDIT = 10;
    private static final int SLOT_BANS = 11;
    private static final int SLOT_WATCHLIST = 12;
    private static final int SLOT_SESSIONS = 13;
    private static final int SLOT_AFK = 14;
    private static final int SLOT_ALTS = 15;
    private static final int SLOT_CLIENT_MODS = 16;

    private static final int SLOT_PERFORMANCE = 19;
    private static final int SLOT_CHUNKS = 20;
    private static final int SLOT_WORLD = 21;
    private static final int SLOT_CHAT_LOCK = 22;
    private static final int SLOT_CHAT_CLEAR = 23;
    private static final int SLOT_RESTART = 24;
    private static final int SLOT_BROADCAST = 25;

    private static final int SLOT_VANISH = 28;
    private static final int SLOT_SILENT = 29;
    private static final int SLOT_CMD_SPY = 30;
    private static final int SLOT_SOCIAL_SPY = 31;
    private static final int SLOT_LOGIN_QUEUE = 32;
    private static final int SLOT_SELECTION = 33;
    private static final int SLOT_BACK_TP = 34;

    private static final int SLOT_PLAYERS = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_RADAR = 50;

    private final ServerPlayer admin;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new StaffToolsMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("tools.title", admin))));
    }

    public StaffToolsMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        build();
    }

    // -- Rendering -----------------------------------------------------------

    private void build() {
        if (admin == null) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        ItemStack filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        renderStatus(server);

        group(SLOT_GROUP_INVESTIGATE, Items.SPYGLASS, "§e", "tools.group.investigate");
        group(SLOT_GROUP_SERVER, Items.ANVIL, "§6", "tools.group.server");
        group(SLOT_GROUP_SELF, Items.PLAYER_HEAD, "§d", "tools.group.self");

        if (AdminPermissions.AUDIT.check(admin)) {
            put(SLOT_AUDIT, Items.WRITABLE_BOOK, "§b", "tools.audit",
                    "§7" + AuditManager.totalRecorded() + " " + t("tools.audit.entries"));
        }
        if (AdminPermissions.BAN.check(admin)) {
            put(SLOT_BANS, Items.RED_DYE, "§c", "tools.bans",
                    "§7" + com.arcadia.adminpanel.util.BanManager.list(server).size() + " " + t("tools.bans.count"));
        }
        if (AdminPermissions.WATCHLIST.check(admin)) {
            put(SLOT_WATCHLIST, Items.ENDER_EYE, "§d", "tools.watchlist",
                    "§7" + WatchlistManager.size() + " " + t("tools.watchlist.count"));
        }
        if (AdminPermissions.SESSIONS.check(admin)) {
            put(SLOT_SESSIONS, Items.CLOCK, "§e", "tools.sessions", "§7" + t("tools.sessions.hint"));
        }
        if (AdminPermissions.AFK.check(admin)) {
            put(SLOT_AFK, Items.RED_BED, "§7", "tools.afk",
                    "§7" + AfkTracker.afkCount() + " " + t("tools.afk.count"));
        }
        if (AdminPermissions.ALTS.check(admin) && AdminConfig.get().altDetectionEnabled) {
            put(SLOT_ALTS, Items.SKELETON_SKULL, "§6", "tools.alts",
                    "§7" + AltDetector.groupCount() + " " + t("tools.alts.count"),
                    "§8" + t("tools.alts.privacy"));
        }
        if (AdminPermissions.CLIENT_MODS.check(admin) && AdminConfig.get().clientModsEnabled) {
            put(SLOT_CLIENT_MODS, Items.COMMAND_BLOCK, "§9", "tools.clientmods",
                    "§7" + ClientModsRegistry.reportCount() + " " + t("tools.clientmods.count"));
        }

        if (AdminPermissions.PERFORMANCE.check(admin)) {
            put(SLOT_PERFORMANCE, Items.REDSTONE_TORCH, "§c", "tools.performance",
                    "§7" + t("tools.performance.hint"));
        }
        if (AdminPermissions.CHUNKS.check(admin)) {
            put(SLOT_CHUNKS, Items.FILLED_MAP, "§a", "tools.chunks", "§7" + t("tools.chunks.hint"));
        }
        if (AdminPermissions.WORLD.check(admin)) {
            put(SLOT_WORLD, Items.DAYLIGHT_DETECTOR, "§e", "tools.world", "§7" + t("tools.world.hint"));
        }
        if (AdminPermissions.CHAT_CONTROL.check(admin)) {
            boolean locked = ChatControl.isLocked();
            put(SLOT_CHAT_LOCK, locked ? Items.BARRIER : Items.OAK_SIGN,
                    locked ? "§c" : "§a", locked ? "tools.chat.unlock" : "tools.chat.lock",
                    "§7" + t(locked ? "tools.chat.locked_by" : "tools.chat.lock.hint")
                            + (locked ? " §f" + ChatControl.lockedBy() : ""));
            put(SLOT_CHAT_CLEAR, Items.BUCKET, "§b", "tools.chat.clear", "§7" + t("tools.chat.clear.hint"));
        }
        if (AdminPermissions.RESTART.check(admin)) {
            boolean pending = RestartScheduler.isPending();
            put(SLOT_RESTART, Items.REDSTONE_BLOCK, pending ? "§c" : "§6", "tools.restart",
                    pending ? "§c" + t("tools.restart.pending") + " §f"
                            + TextFormatter.formatMs(RestartScheduler.remainingMs())
                            : "§7" + t("tools.restart.hint"),
                    "§8" + t("tools.restart.click"));
        }
        if (AdminPermissions.BROADCAST.check(admin)) {
            var messages = AdminConfig.get().autoBroadcastMessages;
            put(SLOT_BROADCAST, Items.BELL, "§e", "tools.broadcast",
                    "§7" + (messages == null ? 0 : messages.size()) + " " + t("tools.broadcast.count"),
                    "§8" + t(AdminConfig.get().autoBroadcastEnabled ? "misc.on" : "misc.off"));
        }

        if (AdminPermissions.VANISH.check(admin)) {
            boolean on = VanishManager.isVanished(admin.getUUID());
            put(SLOT_VANISH, on ? Items.GLASS : Items.GLASS_PANE, on ? "§a" : "§7", "tools.vanish",
                    "§7" + t(on ? "misc.on" : "misc.off"));
        }
        if (AdminPermissions.SILENT.check(admin)) {
            boolean on = SilentMode.isSilent(admin.getUUID());
            put(SLOT_SILENT, on ? Items.SCULK_SENSOR : Items.SCULK, on ? "§a" : "§7", "tools.silent",
                    "§7" + t(on ? "misc.on" : "misc.off"), "§8" + t("tools.silent.hint"));
        }
        if (AdminPermissions.SPY.check(admin)) {
            boolean cmd = SpyManager.hasCommandSpy(admin.getUUID());
            put(SLOT_CMD_SPY, Items.SPYGLASS, cmd ? "§a" : "§7", "tools.cmdspy",
                    "§7" + t(cmd ? "misc.on" : "misc.off"));
            boolean social = SpyManager.hasSocialSpy(admin.getUUID());
            put(SLOT_SOCIAL_SPY, Items.WRITTEN_BOOK, social ? "§a" : "§7", "tools.socialspy",
                    "§7" + t(social ? "misc.on" : "misc.off"));
        }
        if (AdminPermissions.LOGIN_QUEUE.check(admin)) {
            boolean on = AdminConfig.get().loginQueueEnabled;
            String extra = LoginQueueAuto.isArmed()
                    ? "§8" + t("tools.loginqueue.auto") + " "
                      + TextFormatter.formatMs(LoginQueueAuto.remainingMs())
                    : "§8" + t("tools.loginqueue.hint");
            put(SLOT_LOGIN_QUEUE, Items.HOPPER, on ? "§a" : "§7", "tools.loginqueue",
                    "§7" + t(on ? "misc.on" : "misc.off"), extra);
        }
        if (AdminPermissions.BULK.check(admin)) {
            int n = SelectionManager.size(admin.getUUID());
            put(SLOT_SELECTION, Items.BEACON, n > 0 ? "§a" : "§7", "tools.selection",
                    "§7" + n + " " + t("tools.selection.count"), "§8" + t("tools.selection.hint"));
        }
        if (AdminPermissions.BACK.check(admin)) {
            int depth = BackManager.depth(admin.getUUID());
            put(SLOT_BACK_TP, Items.ENDER_PEARL, depth > 0 ? "§d" : "§7", "tools.back",
                    "§7" + depth + " " + t("tools.back.count"));
        }
        if (AdminPermissions.RADAR.check(admin)) {
            put(SLOT_RADAR, Items.COMPASS, "§b", "tools.radar", "§7" + t("tools.radar.hint"));
        }

        put(SLOT_PLAYERS, Items.PLAYER_HEAD, "§f", "tools.players", "§7" + t("tools.players.hint"));
        this.getContainer().setItem(SLOT_CLOSE, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + t("action.close"))).build());

        this.broadcastChanges();
    }

    private void renderStatus(MinecraftServer server) {
        LagMonitor.Sample s = LagMonitor.sample(server);
        this.getContainer().setItem(SLOT_STATUS, ItemBuilder.of(Items.BEACON)
                .name(Component.literal("§6§l" + t("tools.status")))
                .addLore(Component.literal("§7" + t("tools.status.tps") + " "
                        + LagMonitor.tpsColor(s.tps()) + String.format("%.1f", s.tps())))
                .addLore(Component.literal("§7" + t("tools.status.mspt") + " §f"
                        + String.format("%.1f", s.msptMean()) + " ms"))
                .addLore(Component.literal("§7" + t("tools.status.players") + " §f" + s.onlinePlayers()))
                .addLore(Component.literal("§7" + t("tools.status.memory") + " §f"
                        + s.usedMemoryMb() + " / " + s.maxMemoryMb() + " MB"))
                .addLore(Component.literal("§7" + t("tools.status.entities") + " §f" + s.totalEntities()))
                .build());
    }

    /** A row label. Inert: it names the row and nothing else, and clicking it does nothing. */
    private void group(int slot, net.minecraft.world.item.Item item, String colour, String key) {
        this.getContainer().setItem(slot, ItemBuilder.of(item)
                .name(Component.literal(colour + "§l" + t(key)))
                .addLore(Component.literal("§8" + t(key + ".hint")))
                .build());
    }

    private void put(int slot, net.minecraft.world.item.Item item, String color, String key, String... lore) {
        ItemBuilder b = ItemBuilder.of(item).name(Component.literal(color + t(key)));
        for (String line : lore) {
            if (line != null && !line.isBlank()) b.addLore(Component.literal(line));
        }
        this.getContainer().setItem(slot, b.build());
    }

    private String t(String key) {
        return LanguageHelper.getText(key, admin);
    }

    // -- Clicks --------------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;
        MinecraftServer server = sp.getServer();
        if (server == null) return;

        switch (slotId) {
            case SLOT_CLOSE -> sp.closeContainer();
            case SLOT_PLAYERS -> {
                sp.closeContainer();
                AdminPanelMenu.open(sp);
            }
            case SLOT_AUDIT -> {
                if (!AdminPermissions.AUDIT.check(sp)) return;
                sp.closeContainer();
                AuditLogMenu.open(sp, null, null);
            }
            case SLOT_BANS -> {
                if (!AdminPermissions.BAN.check(sp)) return;
                sp.closeContainer();
                BanListMenu.open(sp);
            }
            case SLOT_WATCHLIST -> {
                if (!AdminPermissions.WATCHLIST.check(sp)) return;
                sp.closeContainer();
                WatchlistMenu.open(sp);
            }
            case SLOT_SESSIONS -> {
                if (!AdminPermissions.SESSIONS.check(sp)) return;
                sp.closeContainer();
                SessionsMenu.open(sp);
            }
            case SLOT_AFK -> {
                if (!AdminPermissions.AFK.check(sp)) return;
                sp.closeContainer();
                AfkListMenu.open(sp);
            }
            case SLOT_ALTS -> {
                if (!AdminPermissions.ALTS.check(sp)) return;
                sp.closeContainer();
                AltGroupsMenu.open(sp);
            }
            case SLOT_CLIENT_MODS -> {
                if (!AdminPermissions.CLIENT_MODS.check(sp)) return;
                sp.closeContainer();
                ClientModsMenu.openOverview(sp);
            }
            case SLOT_PERFORMANCE -> {
                if (!AdminPermissions.PERFORMANCE.check(sp)) return;
                sp.closeContainer();
                LagPanelMenu.open(sp);
            }
            case SLOT_CHUNKS -> {
                if (!AdminPermissions.CHUNKS.check(sp)) return;
                sp.closeContainer();
                ChunkBrowserMenu.open(sp);
            }
            case SLOT_WORLD -> {
                if (!AdminPermissions.WORLD.check(sp)) return;
                sp.closeContainer();
                WorldControlMenu.open(sp);
            }
            case SLOT_CHAT_LOCK -> {
                if (!AdminPermissions.CHAT_CONTROL.check(sp)) return;
                ChatControl.toggleLock(sp);
                SoundHelper.success(sp);
                build();
            }
            case SLOT_CHAT_CLEAR -> {
                if (!AdminPermissions.CHAT_CONTROL.check(sp)) return;
                ChatControl.clearAll(sp);
                SoundHelper.success(sp);
            }
            case SLOT_RESTART -> {
                if (!AdminPermissions.RESTART.check(sp)) return;
                if (RestartScheduler.isPending()) {
                    RestartScheduler.cancel(sp, server);
                } else {
                    // Left click schedules the default warning ladder, right click a fast five.
                    RestartScheduler.schedule(sp, server, button == 1 ? 5 : 15, null);
                }
                SoundHelper.success(sp);
                build();
            }
            case SLOT_BROADCAST -> {
                if (!AdminPermissions.BROADCAST.check(sp)) return;
                if (button == 1) {
                    AdminConfig.get().autoBroadcastEnabled = !AdminConfig.get().autoBroadcastEnabled;
                    AdminConfig.save();
                } else {
                    com.arcadia.adminpanel.util.AutoBroadcast.sendNow(server);
                }
                SoundHelper.success(sp);
                build();
            }
            case SLOT_VANISH -> {
                if (!AdminPermissions.VANISH.check(sp)) return;
                VanishManager.toggle(sp, sp);
                SoundHelper.success(sp);
                build();
            }
            case SLOT_SILENT -> {
                if (!AdminPermissions.SILENT.check(sp)) return;
                SilentMode.toggle(sp.getUUID());
                SoundHelper.playAt(sp, SoundHelper.CLICK);
                build();
            }
            case SLOT_CMD_SPY -> {
                if (!AdminPermissions.SPY.check(sp)) return;
                SpyManager.toggleCommandSpy(sp.getUUID());
                SoundHelper.playAt(sp, SoundHelper.CLICK);
                build();
            }
            case SLOT_SOCIAL_SPY -> {
                if (!AdminPermissions.SPY.check(sp)) return;
                SpyManager.toggleSocialSpy(sp.getUUID());
                SoundHelper.playAt(sp, SoundHelper.CLICK);
                build();
            }
            case SLOT_LOGIN_QUEUE -> {
                if (!AdminPermissions.LOGIN_QUEUE.check(sp)) return;
                AdminConfig.get().loginQueueEnabled = !AdminConfig.get().loginQueueEnabled;
                AdminConfig.save();
                LoginQueueAuto.disarm();
                com.arcadia.adminpanel.util.AuditManager.recordServer(sp,
                        com.arcadia.adminpanel.util.AdminAction.LOGIN_QUEUE,
                        AdminConfig.get().loginQueueEnabled ? "on" : "off");
                SoundHelper.playAt(sp, SoundHelper.CLICK);
                build();
            }
            case SLOT_SELECTION -> {
                if (!AdminPermissions.BULK.check(sp)) return;
                sp.closeContainer();
                BulkActionsMenu.open(sp);
            }
            case SLOT_BACK_TP -> {
                if (!AdminPermissions.BACK.check(sp)) return;
                sp.closeContainer();
                if (BackManager.teleportBack(sp)) SoundHelper.playAt(sp, SoundHelper.TELEPORT);
                else sp.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.error(t("back.none")));
            }
            case SLOT_RADAR -> {
                if (!AdminPermissions.RADAR.check(sp)) return;
                sp.closeContainer();
                RadarMenu.open(sp);
            }
            default -> { }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
