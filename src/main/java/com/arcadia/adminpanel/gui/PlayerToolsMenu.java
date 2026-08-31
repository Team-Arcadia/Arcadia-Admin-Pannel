package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AltDetector;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.ClientModsRegistry;
import com.arcadia.adminpanel.util.DeathSnapshotManager;
import com.arcadia.adminpanel.util.FreezeManager;
import com.arcadia.adminpanel.util.InventoryAccess;
import com.arcadia.adminpanel.util.InventoryBackupManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.MailManager;
import com.arcadia.adminpanel.util.NotesManager;
import com.arcadia.adminpanel.util.SelectionManager;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.SpectateManager;
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

import java.util.UUID;

/**
 * The 1.3.0 half of a player's sheet.
 *
 * <p>The original detail screen was full: forty-five slots of homes and teleport history, nine of
 * actions, no room left. Rather than paginate a screen people already know by muscle memory, the new
 * per-player tools live here, one click away.</p>
 *
 * <p>Grouped by what a moderator is doing: investigating (history, notes, audit, alts, client mods),
 * intervening (freeze, spectate, watchlist, temp-ban, templates), or helping (inventory editor, death
 * snapshots, give item, mail).</p>
 *
 * @author vyrriox
 */
public class PlayerToolsMenu extends ChestMenu {

    private static final int SLOT_HEAD = 4;

    /** Column 0 of each row labels the row. A wall of twenty icons is not a menu, it is a search. */
    private static final int SLOT_GROUP_INVESTIGATE = 9;
    private static final int SLOT_GROUP_INTERVENE = 18;
    private static final int SLOT_GROUP_ASSIST = 27;

    private static final int SLOT_HISTORY = 10;
    private static final int SLOT_NOTES = 11;
    private static final int SLOT_AUDIT = 12;
    private static final int SLOT_ALTS = 13;
    private static final int SLOT_CLIENT_MODS = 14;
    private static final int SLOT_SESSIONS = 15;
    private static final int SLOT_WATCH = 16;

    private static final int SLOT_FREEZE = 19;
    private static final int SLOT_SPECTATE = 20;
    private static final int SLOT_TEMPBAN = 21;
    private static final int SLOT_TEMPLATES = 22;
    private static final int SLOT_SELECT = 23;

    private static final int SLOT_INV_EDIT = 28;
    private static final int SLOT_DEATHS = 29;
    private static final int SLOT_GIVE = 30;
    private static final int SLOT_MAIL = 31;
    private static final int SLOT_DISGUISE = 32;
    private static final int SLOT_BACKUPS = 33;

    private static final int SLOT_BACK = 49;
    private static final int SLOT_CLOSE = 50;

    private final ServerPlayer admin;
    private final UUID target;
    private final String targetName;
    private final boolean online;

    public static void open(ServerPlayer admin, UUID target, String targetName, boolean online) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new PlayerToolsMenu(id, inv, (ServerPlayer) p, target, targetName, online),
                PanelTitles.of(LanguageHelper.getText("tools.player", admin) + ": " + targetName)));
    }

    public PlayerToolsMenu(int id, Inventory playerInv, ServerPlayer admin,
                           UUID target, String targetName, boolean online) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.target = target;
        this.targetName = targetName;
        this.online = online;
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

        this.getContainer().setItem(SLOT_HEAD, ItemBuilder.of(SkullCache.createSkull(target, targetName))
                .name(Component.literal("§6§l" + targetName))
                .addLore(Component.literal((online ? "§a" : "§c")
                        + t(online ? "player.online" : "player.offline")))
                .addLore(Component.literal("§8" + t("tools.player.rows")))
                .build());

        group(SLOT_GROUP_INVESTIGATE, Items.SPYGLASS, "§e", "tools.group.investigate");
        group(SLOT_GROUP_INTERVENE, Items.IRON_BARS, "§c", "tools.group.intervene");
        group(SLOT_GROUP_ASSIST, Items.GOLDEN_APPLE, "§a", "tools.group.assist");

        if (AdminPermissions.HISTORY.check(admin)) {
            put(SLOT_HISTORY, Items.BOOK, "§e", "tools.history",
                    "§7" + AuditManager.sanctionCount(target) + " " + t("tools.history.count"));
        }
        if (AdminPermissions.NOTES.check(admin)) {
            put(SLOT_NOTES, Items.WRITABLE_BOOK, "§6", "tools.notes",
                    "§7" + NotesManager.count(target) + " " + t("notes.count"));
        }
        if (AdminPermissions.AUDIT.check(admin)) {
            put(SLOT_AUDIT, Items.PAPER, "§b", "tools.audit",
                    "§7" + AuditManager.forTarget(target).size() + " " + t("tools.audit.entries"));
        }
        if (AdminPermissions.ALTS.check(admin) && AdminConfig.get().altDetectionEnabled) {
            int alts = AltDetector.altCount(server, target);
            put(SLOT_ALTS, Items.SKELETON_SKULL, alts > 0 ? "§6" : "§7", "tools.alts",
                    "§7" + alts + " " + t("tools.alts.shared"), "§8" + t("tools.alts.privacy"));
        }
        if (AdminPermissions.CLIENT_MODS.check(admin) && AdminConfig.get().clientModsEnabled) {
            int mods = ClientModsRegistry.modCount(target);
            put(SLOT_CLIENT_MODS, Items.COMMAND_BLOCK,
                    ClientModsRegistry.isFlagged(target) ? "§c" : "§9", "tools.clientmods",
                    mods < 0 ? "§8" + t("clientmods.no_report")
                             : "§7" + mods + " " + t("clientmods.mods"));
        }
        if (AdminPermissions.SESSIONS.check(admin)) {
            var rec = com.arcadia.adminpanel.util.LoginTracker.getInstance().get(target);
            put(SLOT_SESSIONS, Items.CLOCK, "§e", "tools.sessions",
                    rec == null ? "§8-" : "§7" + TextFormatter.formatMs(rec.playtimeMs(online)),
                    rec == null ? "§8" : "§8" + rec.sessions() + " " + t("sessions.count"));
        }
        if (AdminPermissions.WATCHLIST.check(admin)) {
            boolean watched = WatchlistManager.isWatched(target);
            put(SLOT_WATCH, Items.ENDER_EYE, watched ? "§a" : "§7",
                    watched ? "tools.watch.remove" : "tools.watch.add",
                    "§7" + t(watched ? "misc.on" : "misc.off"));
        }

        if (AdminPermissions.FREEZE.check(admin)) {
            // Shown offline too: the freeze now survives a disconnect, so "release" has to stay
            // reachable for a suspect who logged off in the middle of a screenshare.
            boolean frozen = FreezeManager.isFrozen(target);
            if (frozen) {
                put(SLOT_FREEZE, Items.BLUE_ICE, "§b", "tools.unfreeze",
                        "§8" + t("tools.unfreeze.hint"),
                        online ? "" : "§8" + t("tools.offline_release"));
            } else if (online) {
                put(SLOT_FREEZE, Items.PACKED_ICE, "§f", "tools.freeze",
                        "§8" + t("tools.freeze.hint"));
            } else {
                disabled(SLOT_FREEZE, "tools.freeze");
            }
        }
        if (AdminPermissions.SPECTATE.check(admin)) {
            boolean active = SpectateManager.isSpectating(admin.getUUID());
            if (online || active) {
                // While a session is running the button stops THAT session, which is not necessarily
                // the player whose sheet this is. Naming the target stops it reading as "stop
                // watching this person" when it means something else.
                var session = SpectateManager.get(admin.getUUID());
                put(SLOT_SPECTATE, Items.ENDER_EYE, active ? "§a" : "§d",
                        active ? "tools.spectate.stop" : "tools.spectate",
                        active && session != null
                                ? "§7" + t("tools.spectate.watching") + " §f" + session.targetName()
                                : "",
                        "§8" + t("tools.spectate.hint"));
            } else {
                disabled(SLOT_SPECTATE, "tools.spectate");
            }
        }
        if (AdminPermissions.BAN.check(admin)) {
            put(SLOT_TEMPBAN, Items.RED_DYE, "§c", "tools.tempban",
                    "§7" + AdminConfig.get().defaultTempbanMinutes + " min",
                    "§8" + t("tools.tempban.hint"));
        }
        if (AdminPermissions.TEMPLATES.check(admin)) {
            put(SLOT_TEMPLATES, Items.NAME_TAG, "§e", "tools.templates",
                    "§8" + t("tools.templates.hint"));
        }
        if (AdminPermissions.BULK.check(admin)) {
            boolean selected = SelectionManager.isSelected(admin.getUUID(), target);
            put(SLOT_SELECT, Items.BEACON, selected ? "§a" : "§7",
                    selected ? "tools.select.remove" : "tools.select.add",
                    "§7" + SelectionManager.size(admin.getUUID()) + " " + t("tools.selection.count"));
        }

        if (AdminPermissions.INV_EDIT.check(admin) && AdminConfig.get().inventoryEditEnabled) {
            boolean available = online
                    || (AdminConfig.get().offlineInventoryEditEnabled
                        && InventoryAccess.hasOfflineData(server, target));
            put(SLOT_INV_EDIT, Items.SHULKER_BOX, available ? "§d" : "§8", "tools.invedit",
                    "§7" + t(online ? "invedit.mode.online" : "invedit.mode.offline"),
                    available ? "§8" + t("tools.invedit.hint") : "§c" + t("invedit.no_data_short"));
        }
        if (AdminPermissions.DEATH_RESTORE.check(admin) && AdminConfig.get().deathSnapshotsEnabled) {
            int cached = DeathSnapshotManager.cachedCount(target);
            put(SLOT_DEATHS, Items.TOTEM_OF_UNDYING, "§a", "tools.deaths",
                    cached < 0 ? "§8" + t("deaths.loading") : "§7" + cached + " " + t("deaths.count"),
                    "§8" + t("tools.deaths.hint"));
            if (cached < 0) {
                // Warm the count off the tick thread and redraw once it lands, so the button says
                // how many deaths there are instead of asking the moderator to click and find out.
                DeathSnapshotManager.loadAsync(server, target, list -> {
                    if (admin.containerMenu == this) build();
                });
            }
        }
        if (AdminPermissions.GIVE_ITEM.check(admin)) {
            if (online) {
                put(SLOT_GIVE, Items.DROPPER, "§a", "tools.give", "§8" + t("tools.give.hint"));
            } else {
                disabled(SLOT_GIVE, "tools.give");
            }
        }
        if (AdminPermissions.MAIL.check(admin)) {
            put(SLOT_MAIL, Items.PAPER, "§b", "tools.mail",
                    "§7" + MailManager.pendingCount(target) + " " + t("mail.pending"));
        }
        if (AdminPermissions.INV_BACKUP.check(admin) && AdminConfig.get().inventoryBackupEnabled) {
            int backups = InventoryBackupManager.cachedCount(target);
            long last = InventoryBackupManager.lastCapture(target);
            put(SLOT_BACKUPS, Items.CHEST, "§b", "tools.backups",
                    backups < 0 ? "§8" + t("backups.loading")
                                : "§7" + backups + " " + t("backups.count"),
                    last > 0 ? "§8" + t("backups.last") + " "
                             + new java.text.SimpleDateFormat("dd/MM HH:mm")
                                       .format(new java.util.Date(last))
                             : "§8" + t("tools.backups.hint"));
            if (backups < 0) {
                // Warm the count off the tick thread and redraw when it lands, the same way the
                // death-snapshot tile does: a button that says "click to find out" is not an answer.
                InventoryBackupManager.headersAsync(server, target, list -> {
                    if (admin.containerMenu == this) build();
                });
            }
        }
        if (AdminPermissions.DISGUISE.check(admin)) {
            var disguise = com.arcadia.adminpanel.util.DisguiseManager.getInstance().getData(target);
            put(SLOT_DISGUISE, Items.CARVED_PUMPKIN, disguise != null ? "§a" : "§7", "tools.disguise",
                    disguise == null ? "§8" + t("misc.off") : "§7" + disguise.type().getPath(),
                    "§8" + t("tools.disguise.hint"));
        }

        this.getContainer().setItem(SLOT_BACK, ItemBuilder.of(Items.ARROW)
                .name(Component.literal("§e" + t("action.back")))
                .addLore(Component.literal("§8" + t("tools.back.sheet"))).build());
        this.getContainer().setItem(SLOT_CLOSE, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + t("action.close"))).build());
        this.broadcastChanges();
    }

    private void put(int slot, net.minecraft.world.item.Item item, String colour, String key,
                     String... lore) {
        ItemBuilder b = ItemBuilder.of(item).name(Component.literal(colour + t(key)));
        for (String line : lore) {
            if (line != null && !line.isBlank()) b.addLore(Component.literal(line));
        }
        this.getContainer().setItem(slot, b.build());
    }

    /** A row label. Inert: it names the row and nothing else, and clicking it does nothing. */
    private void group(int slot, net.minecraft.world.item.Item item, String colour, String key) {
        this.getContainer().setItem(slot, ItemBuilder.of(item)
                .name(Component.literal(colour + "§l" + t(key)))
                .addLore(Component.literal("§8" + t(key + ".hint")))
                .build());
    }

    /**
     * A tool that exists but cannot run right now, drawn greyed with the reason. Hiding it instead
     * made the screen change shape depending on whether the target happened to be connected, which
     * reads as a missing feature rather than as an unavailable one.
     */
    private void disabled(int slot, String key) {
        this.getContainer().setItem(slot, ItemBuilder.of(Items.GRAY_DYE)
                .name(Component.literal("§8" + t(key)))
                .addLore(Component.literal("§c" + t("tools.requires_online")))
                .build());
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
        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);

        switch (slotId) {
            case SLOT_BACK -> {
                sp.closeContainer();
                PlayerDetailMenu.open(sp, target, targetName, targetPlayer != null);
            }
            case SLOT_CLOSE -> sp.closeContainer();
            case SLOT_HISTORY -> {
                if (!AdminPermissions.HISTORY.check(sp)) return;
                sp.closeContainer();
                HistoryMenu.open(sp, target, targetName);
            }
            case SLOT_NOTES -> {
                if (!AdminPermissions.NOTES.check(sp)) return;
                sp.closeContainer();
                NotesMenu.open(sp, target, targetName);
            }
            case SLOT_AUDIT -> {
                if (!AdminPermissions.AUDIT.check(sp)) return;
                sp.closeContainer();
                AuditLogMenu.open(sp, target, null, targetName);
            }
            case SLOT_ALTS -> {
                if (!AdminPermissions.ALTS.check(sp)) return;
                sp.closeContainer();
                AltGroupsMenu.open(sp);
            }
            case SLOT_CLIENT_MODS -> {
                if (!AdminPermissions.CLIENT_MODS.check(sp)) return;
                if (!ClientModsRegistry.hasReported(target)) return;
                sp.closeContainer();
                ClientModsMenu.openPlayer(sp, target, targetName);
            }
            case SLOT_SESSIONS -> {
                if (!AdminPermissions.SESSIONS.check(sp)) return;
                sp.closeContainer();
                SessionsMenu.open(sp);
            }
            case SLOT_WATCH -> {
                if (!AdminPermissions.WATCHLIST.check(sp)) return;
                if (WatchlistManager.isWatched(target)) {
                    WatchlistManager.remove(sp, target, targetName);
                    SoundHelper.playAt(sp, SoundHelper.CLICK);
                    build();
                } else {
                    sp.closeContainer();
                    ChatListener.startWatchReasonSession(sp, target, targetName);
                }
            }
            case SLOT_FREEZE -> {
                if (!AdminPermissions.FREEZE.check(sp)) return;
                if (FreezeManager.isFrozen(target)) {
                    if (targetPlayer != null) {
                        FreezeManager.unfreeze(sp, targetPlayer);
                    } else {
                        FreezeManager.unfreezeOffline(sp, target, targetName);
                    }
                    sp.sendSystemMessage(ArcadiaMessages.success(
                            LanguageHelper.getText("freeze.lifted", sp)
                                    .replace("%player%", targetName)));
                    SoundHelper.success(sp);
                    build();
                    return;
                }
                if (targetPlayer == null) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("error.player_offline")));
                    return;
                }
                sp.closeContainer();
                ChatListener.startFreezeReasonSession(sp, target, targetName);
            }
            case SLOT_SPECTATE -> {
                if (!AdminPermissions.SPECTATE.check(sp)) return;
                if (SpectateManager.isSpectating(sp.getUUID())) {
                    sp.closeContainer();
                    SpectateManager.stop(sp);
                    return;
                }
                if (targetPlayer == null) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("error.player_offline")));
                    return;
                }
                sp.closeContainer();
                SpectateManager.start(sp, targetPlayer);
            }
            case SLOT_TEMPBAN -> {
                if (!AdminPermissions.BAN.check(sp)) return;
                // Left click uses the configured default, right click bans permanently.
                long minutes = button == 1 ? 0L : AdminConfig.get().defaultTempbanMinutes;
                sp.closeContainer();
                ChatListener.startBanReasonSession(sp, target, targetName, minutes);
            }
            case SLOT_TEMPLATES -> {
                if (!AdminPermissions.TEMPLATES.check(sp)) return;
                sp.closeContainer();
                TemplatesMenu.open(sp, target, targetName);
            }
            case SLOT_SELECT -> {
                if (!AdminPermissions.BULK.check(sp)) return;
                SelectionManager.toggle(sp.getUUID(), target, targetName);
                SoundHelper.playAt(sp, SoundHelper.CLICK);
                build();
            }
            case SLOT_INV_EDIT -> {
                if (!AdminPermissions.INV_EDIT.check(sp)) return;
                if (!AdminConfig.get().inventoryEditEnabled) return;
                if (targetPlayer == null && !AdminConfig.get().offlineInventoryEditEnabled) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("invedit.offline_disabled")));
                    return;
                }
                sp.closeContainer();
                InventoryEditMenu.open(sp, target, targetName, targetPlayer != null);
            }
            case SLOT_DEATHS -> {
                if (!AdminPermissions.DEATH_RESTORE.check(sp)) return;
                sp.closeContainer();
                DeathSnapshotMenu.open(sp, target, targetName);
            }
            case SLOT_BACKUPS -> {
                if (!AdminPermissions.INV_BACKUP.check(sp)) return;
                if (!AdminConfig.get().inventoryBackupEnabled) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("backups.disabled")));
                    return;
                }
                sp.closeContainer();
                InventoryBackupMenu.open(sp, target, targetName);
            }
            case SLOT_GIVE -> {
                if (!AdminPermissions.GIVE_ITEM.check(sp)) return;
                if (targetPlayer == null) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("error.player_offline")));
                    return;
                }
                // Left click hands over what the admin is holding, right click prompts for an id.
                if (button == 1) {
                    sp.closeContainer();
                    ChatListener.startGiveItemSession(sp, target, targetName);
                    return;
                }
                ItemStack held = sp.getMainHandItem();
                if (held.isEmpty()) {
                    sp.sendSystemMessage(ArcadiaMessages.error(t("give.empty_hand")));
                    return;
                }
                ItemStack copy = held.copy();
                if (!targetPlayer.getInventory().add(copy)) targetPlayer.drop(copy, false);
                targetPlayer.containerMenu.broadcastChanges();
                sp.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("give.done", sp)
                                .replace("%count%", String.valueOf(held.getCount()))
                                .replace("%item%", held.getHoverName().getString())
                                .replace("%player%", targetName)));
                SoundHelper.success(sp);
                AuditManager.record(sp, AdminAction.GIVE_ITEM, target, targetName,
                        held.getCount() + "x " + held.getItem());
            }
            case SLOT_MAIL -> {
                if (!AdminPermissions.MAIL.check(sp)) return;
                sp.closeContainer();
                ChatListener.startMailSession(sp, target, targetName);
            }
            case SLOT_DISGUISE -> {
                if (!AdminPermissions.DISGUISE.check(sp)) return;
                sp.closeContainer();
                DisguiseMenu.open(sp, target, targetName);
            }
            default -> { }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
