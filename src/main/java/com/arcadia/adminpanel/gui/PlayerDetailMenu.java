package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.staff.StaffActions;
import com.arcadia.lib.staff.StaffRole;
import com.arcadia.lib.staff.StaffService;
import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.lib.scheduler.SchedulerService;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.FTBChunksReader;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.LoginTracker;
import com.arcadia.adminpanel.util.NextSpawnManager;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.TimeFormat;
import com.arcadia.adminpanel.util.WarnManager;
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

    // Deferred header-skin refresh (skins resolve async via Mojang); bounded so offline-mode UUIDs
    // don't loop forever.
    private boolean headerRefreshScheduled = false;
    private int headerRefreshAttempts = 0;

    // Per-session FTB data cache. readPlayerData() reads + parses a file (NBT/JSON) on the server
    // thread; without this it was re-read once per buildMenu() AND again on every home/history/tp
    // click and the info sheet. One menu instance reads it at most once now; a fresh buildMenu()
    // (e.g. after an action re-opens the menu) clears it so stale data never sticks.
    private FTBDataReader.PlayerFTBData cachedFtbData;
    private boolean ftbDataLoaded = false;

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
        this.targetName = "unknown";
        this.isOnline = false;
    }

    private void buildMenu() {
        if (admin == null) return;

        // Fresh render — drop any FTB data cached from a prior build so the menu reflects current
        // homes / last-seen after an action re-opens it.
        ftbDataLoaded = false;
        cachedFtbData = null;

        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler.copy());
        }

        // Header — player skull (slot 4) — login info merged in lore so we don't burn an extra slot.
        var skull = SkullCache.createSkull(targetUUID, targetName);
        skull.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("§e" + targetName));

        boolean french = isFrench(admin);
        LoginTracker.LoginRecord login = LoginTracker.getInstance().get(targetUUID);
        long lastLoginMs = login != null ? login.lastLoginMs() : 0L;
        long firstSeenMs = login != null ? login.firstSeenMs() : 0L;
        long lastLogoutMs = login != null ? login.lastLogoutMs() : 0L;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal(isOnline
                ? "§a" + LanguageHelper.getText("player.online", admin)
                : "§c" + LanguageHelper.getText("player.offline", admin)));
        lore.add(Component.literal("§7UUID: §8" + targetUUID));
        lore.add(Component.literal("§7" + LanguageHelper.getText("misc.warns_label", admin)
                + " §e" + WarnManager.getInstance().getWarnCount(targetUUID)));
        if (lastLoginMs > 0) {
            lore.add(Component.literal("§7" + LanguageHelper.getText("info.last_login", admin)
                    + " §f" + TimeFormat.absolute(lastLoginMs)
                    + " §8(" + TimeFormat.relative(lastLoginMs, french) + ")"));
        }
        if (!isOnline && lastLogoutMs > 0 && lastLogoutMs >= lastLoginMs) {
            lore.add(Component.literal("§7" + LanguageHelper.getText("info.last_logout", admin)
                    + " §f" + TimeFormat.absolute(lastLogoutMs)
                    + " §8(" + TimeFormat.relative(lastLogoutMs, french) + ")"));
        }
        if (firstSeenMs > 0) {
            lore.add(Component.literal("§7" + LanguageHelper.getText("info.first_seen", admin)
                    + " §f" + TimeFormat.absolute(firstSeenMs)));
        }
        skull.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(lore));
        this.getContainer().setItem(4, skull);
        // Resolve the real skin async and re-render the header once it's ready.
        SkullCache.warmTextures(admin.getServer(), targetUUID);
        if (!SkullCache.hasTexture(targetUUID)) scheduleHeaderRefresh();

        // Gamemode switch (slot 1) — online only; cycles SURVIVAL→CREATIVE→ADVENTURE→SPECTATOR.
        if (isOnline && canUseCommand("gamemode") && AdminPermissions.GAMEMODE.check(admin)) {
            ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
            String gm = target != null
                    ? target.gameMode.getGameModeForPlayer().getName() : "?";
            this.getContainer().setItem(1, ItemBuilder.of(Items.GRASS_BLOCK)
                    .name(Component.literal("§a" + LanguageHelper.getText("action.gamemode", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("gamemode.current", admin) + " §f" + gm))
                    .addLore(Component.literal("§e" + LanguageHelper.getText("gamemode.cycle", admin)))
                    .build());
        }

        // Heal / Feed (slot 3) — online only. Left-click heals, right-click feeds.
        if (isOnline && AdminPermissions.HEAL.check(admin)) {
            this.getContainer().setItem(3, ItemBuilder.of(Items.GOLDEN_APPLE)
                    .name(Component.literal("§d" + LanguageHelper.getText("action.heal", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("heal.hint", admin)))
                    .build());
        }

        // Next-login spawn override (slot 7) — works for online AND offline targets. Left-click pins
        // the admin's current position; right-click clears a pending override.
        if (AdminPermissions.NEXT_SPAWN.check(admin)) {
            NextSpawnManager.SpawnPoint pin = NextSpawnManager.getInstance().get(targetUUID);
            var nsb = ItemBuilder.of(pin != null ? Items.RECOVERY_COMPASS : Items.ENDER_EYE)
                    .name(Component.literal("§b" + LanguageHelper.getText("action.nextspawn", admin)));
            if (pin != null) {
                nsb.addLore(Component.literal("§7" + LanguageHelper.getText("nextspawn.set_to", admin)
                        + " §f" + pin.getShortDimension() + " §7(" + pin.getFormattedCoords() + ")"));
                nsb.addLore(Component.literal("§e" + LanguageHelper.getText("nextspawn.left_update", admin)));
                nsb.addLore(Component.literal("§c" + LanguageHelper.getText("nextspawn.right_clear", admin)));
            } else {
                nsb.addLore(Component.literal("§7" + LanguageHelper.getText("nextspawn.hint", admin)));
            }
            this.getContainer().setItem(7, nsb.build());
        }

        // Team button (slot 5) — only if FTB Teams data is loaded AND the player belongs somewhere
        // AND the viewer has the TEAMS perm. Now also surfaces FTB Chunks claim count.
        if (FTBTeamsReader.isAvailable() && AdminPermissions.TEAMS.check(admin)) {
            FTBTeamsReader.Team team = FTBTeamsReader.getEffectiveTeamFor(targetUUID);
            if (team != null) {
                var builder = ItemBuilder.of(Items.WHITE_BANNER)
                        .name(Component.literal("§b" + LanguageHelper.getText("team.view", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("team.name", admin)
                                + " §f" + team.displayName))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("team.type", admin)
                                + " §f" + team.type.name().toLowerCase()))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("team.members", admin)
                                + " §e" + team.memberCount()));
                if (FTBChunksReader.isAvailable()) {
                    FTBChunksReader.ClaimStats st = FTBChunksReader.getStatsFor(team.id);
                    if (st != null) {
                        builder.addLore(Component.literal("§7" + LanguageHelper.getText("team.claims", admin)
                                + " §e" + st.totalClaims()
                                + (st.maxClaims() > 0 ? " §8/ §7" + st.maxClaims() : "")));
                        builder.addLore(Component.literal("§7" + LanguageHelper.getText("team.force_loaded", admin)
                                + " §e" + st.forceLoaded()
                                + (st.maxForceLoaded() > 0 ? " §8/ §7" + st.maxForceLoaded() : "")));
                    }
                }
                this.getContainer().setItem(5, builder.build());
            }
        }

        // Jail/Unjail (slot 0). Visibility gate on the new JAIL node — moderators without it
        // simply don't see the button.
        if (isOnline && JailManager.getInstance().hasJailLocation() && AdminPermissions.JAIL.check(admin)) {
            boolean isJailed = JailManager.getInstance().isJailed(targetUUID);
            if (isJailed) {
                JailManager.JailEntry jail = JailManager.getInstance().getJailEntry(targetUUID);
                String remaining = jail != null && jail.durationMs() > 0
                        ? TextFormatter.formatMs(jail.getRemainingMs())
                        : LanguageHelper.getText("jail.permanent", admin);
                this.getContainer().setItem(0, ItemBuilder.of(Items.IRON_DOOR)
                        .name(Component.literal("§a" + LanguageHelper.getText("action.unjail", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("jail.remaining", admin) + " §e" + remaining))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("jail.reason.label", admin) + " §c"
                                + (jail != null ? jail.reason() : "N/A")))
                        .build());
            } else {
                this.getContainer().setItem(0, ItemBuilder.of(Items.IRON_BARS)
                        .name(Component.literal("§c" + LanguageHelper.getText("action.jail", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("jail.hint", admin)))
                        .build());
            }
        }

        // Reset progress (slot 2) — granular perm AND vanilla command availability.
        if (canUseCommand("advancement") && AdminPermissions.RESET_PROGRESS.check(admin)) {
            this.getContainer().setItem(2, ItemBuilder.of(Items.EXPERIENCE_BOTTLE)
                    .name(Component.literal("§e" + LanguageHelper.getText("action.resetprog", admin))).build());
        }

        // InvSee (slot 6)
        if (canUseCommand("invsee") && AdminPermissions.INVSEE.check(admin)) {
            this.getContainer().setItem(6, ItemBuilder.of(Items.CHEST)
                    .name(Component.literal("§6" + LanguageHelper.getText("action.invsee", admin))).build());
        }

        // Info book (slot 8) — gated on the INFO node so the player's ban/whitelist/login sheet
        // isn't exposed to a viewer who only holds OPEN.
        if (AdminPermissions.INFO.check(admin)) {
            this.getContainer().setItem(8, ItemBuilder.of(Items.BOOK)
                    .name(Component.literal("§b" + LanguageHelper.getText("info.full", admin))).build());
        }

        // Homes (slots 9-35) — informational, like the teleport-history row below: shown to any
        // panel viewer so home coordinates are visible again (regression from 1.2.6, issue #208 —
        // gating render on canUseCommand("tp") hid homes from every admin driving the panel through
        // arcadia.adminpanel.* nodes without vanilla /tp op level). The teleport ACTION stays gated
        // on TELEPORT at the click layer (see clicked()), so a viewer without the node sees the
        // homes but cannot warp to them; the "click to TP" hint is therefore also conditional.
        FTBDataReader.PlayerFTBData ftbData = readFtbData();
        boolean canTeleport = AdminPermissions.TELEPORT.check(admin);

        if (ftbData != null && !ftbData.homes.isEmpty()) {
            List<Map.Entry<String, FTBDataReader.HomeLocation>> homes = new ArrayList<>(ftbData.homes.entrySet());
            homes.sort(Map.Entry.comparingByKey());
            int start = homePage * HOMES_PER_PAGE;
            int end = Math.min(start + HOMES_PER_PAGE, homes.size());
            for (int i = start; i < end; i++) {
                int slot = 9 + (i - start);
                var entry = homes.get(i);
                var builder = ItemBuilder.of(getDimensionIcon(entry.getValue().dimension))
                        .name(Component.literal("§e" + entry.getKey()))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.dim", admin) + " §f" + entry.getValue().getShortDimension()))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.pos", admin) + " §f" + entry.getValue().getFormattedCoords()));
                if (canTeleport) {
                    builder = builder.addLore(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)));
                }
                this.getContainer().setItem(slot, builder.build());
            }
        } else if (homePage == 0) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("homes.none", admin))).build());
        }

        // Teleport history (slots 36-43). The row lost its last slot in 1.3.0 to the player-tools
        // button: the sheet had no free space left, and a ninth history entry is worth less than a
        // door to the history, notes, freeze, inventory editor and death snapshots behind it.
        if (ftbData != null && ftbData.teleportHistory != null) {
            for (int i = 0; i < Math.min(ftbData.teleportHistory.size(), 8); i++) {
                FTBDataReader.TeleportRecord record = ftbData.teleportHistory.get(i);
                this.getContainer().setItem(36 + i, ItemBuilder.of(Items.CHORUS_FRUIT)
                        .name(Component.literal("§d" + LanguageHelper.getText("detail.tp_history", admin) + " #" + (i + 1)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.dim", admin) + " §f" + record.getShortDimension()))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.pos", admin) + " §f" + record.getFormattedCoords()))
                        .addLore(Component.literal("§e" + LanguageHelper.getText("misc.click_tp", admin)))
                        .build());
            }
        }

        // Player tools (slot 44) — the 1.3.0 door: history, notes, freeze, spectate, inventory
        // editor, death snapshots, mail, templates and the rest.
        int noteCount = com.arcadia.adminpanel.util.NotesManager.count(targetUUID);
        var toolsIcon = ItemBuilder.of(Items.ENDER_CHEST)
                .name(Component.literal("§d" + LanguageHelper.getText("tools.player", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("tools.player.hint", admin)));
        if (noteCount > 0) {
            toolsIcon.addLore(Component.literal("§6" + noteCount + " "
                    + LanguageHelper.getText("notes.count", admin)));
        }
        if (com.arcadia.adminpanel.util.WatchlistManager.isWatched(targetUUID)) {
            toolsIcon.addLore(Component.literal("§d" + LanguageHelper.getText("watchlist.flagged", admin)));
        }
        if (com.arcadia.adminpanel.util.FreezeManager.isFrozen(targetUUID)) {
            toolsIcon.addLore(Component.literal("§b" + LanguageHelper.getText("freeze.flagged", admin)));
        }
        this.getContainer().setItem(44, toolsIcon.build());

        // ── Action bar (row 6) ──────────────────────────────────────────────

        // Mute/Unmute (slot 45) — needs both vanilla staff role (lib gating) and granular perm.
        if (isOnline && StaffService.getRole(admin).atLeast(StaffRole.MOD) && AdminPermissions.MUTE.check(admin)) {
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
        if (canUseCommand("clear") && AdminPermissions.CLEAR_INV.check(admin)) {
            this.getContainer().setItem(46, ItemBuilder.of(confirmClear ? Items.REDSTONE_BLOCK : Items.LAVA_BUCKET)
                    .name(Component.literal((confirmClear ? "§c§l" : "§c") +
                            (confirmClear ? LanguageHelper.getText("misc.confirm", admin)
                                    : LanguageHelper.getText("action.clearinv", admin))))
                    .build());
        }

        // TP here (slot 47)
        if (isOnline && canUseCommand("tp") && AdminPermissions.TELEPORT.check(admin)) {
            this.getContainer().setItem(47, ItemBuilder.of(Items.ENDER_EYE)
                    .name(Component.literal("§d" + LanguageHelper.getText("action.tp_here", admin))).build());
        }

        // TP to / last location (slot 48)
        if (canUseCommand("tp") && AdminPermissions.TELEPORT.check(admin)) {
            if (isOnline) {
                this.getContainer().setItem(48, ItemBuilder.of(Items.ENDER_PEARL)
                        .name(Component.literal("§a" + LanguageHelper.getText("action.tp", admin))).build());
            } else if (ftbData != null && ftbData.lastSeen != null) {
                this.getContainer().setItem(48, ItemBuilder.of(Items.COMPASS)
                        .name(Component.literal("§6" + LanguageHelper.getText("action.tp_last", admin)))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.dim", admin) + " §f" + ftbData.lastSeen.getShortDimension()))
                        .addLore(Component.literal("§7" + LanguageHelper.getText("misc.pos", admin) + " §f" + ftbData.lastSeen.getFormattedCoords()))
                        .build());
            }
        }

        // Kick (slot 49)
        if (isOnline && canUseCommand("kick") && AdminPermissions.KICK.check(admin)) {
            this.getContainer().setItem(49, ItemBuilder.of(Items.IRON_BOOTS)
                    .name(Component.literal("§c" + LanguageHelper.getText("action.kick", admin))).build());
        }

        // Ban/Unban (slot 50)
        if ((canUseCommand("ban") || canUseCommand("pardon")) && AdminPermissions.BAN.check(admin)) {
            var profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
            boolean isBanned = admin.getServer().getPlayerList().getBans().isBanned(profile);
            this.getContainer().setItem(50, ItemBuilder.of(isBanned ? Items.LIME_DYE : Items.RED_DYE)
                    .name(Component.literal(isBanned
                            ? "§a" + LanguageHelper.getText("action.unban", admin)
                            : "§c" + LanguageHelper.getText("action.ban", admin)))
                    .build());
        }

        // Warn (slot 51)
        if (AdminPermissions.WARN_EDIT.check(admin)) {
            this.getContainer().setItem(51, ItemBuilder.of(Items.TNT)
                    .name(Component.literal("§c" + LanguageHelper.getText("action.warn", admin))).build());
        }

        // View warns (slot 52) — view-only perm is enough.
        if (AdminPermissions.WARN_VIEW.check(admin)) {
            this.getContainer().setItem(52, ItemBuilder.of(Items.WRITABLE_BOOK)
                    .name(Component.literal("§e" + LanguageHelper.getText("action.warn_list", admin)))
                    .addLore(Component.literal("§7" + String.format(
                            LanguageHelper.getText("misc.warn_count", admin), WarnManager.getInstance().getWarnCount(targetUUID))))
                    .build());
        }

        // Back (slot 53)
        this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                .name(Component.literal("§e" + LanguageHelper.getText("action.back", admin))).build());
    }

    /**
     * Vanilla Minecraft usernames are constrained to {@code [a-zA-Z0-9_]} with length 3–16. Any
     * value falling outside this set indicates either an offline-mode server with weird names OR a
     * crafted name designed to inject arguments into the command string. We reject everything that
     * doesn't conform — defense in depth so the {@code performPrefixedCommand} concatenations in
     * this menu cannot be weaponized as command injection.
     */
    private static boolean isSafePlayerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isFrench(ServerPlayer p) {
        if (p == null) return false;
        try {
            String lang = p.clientInformation() != null ? p.clientInformation().language() : null;
            return lang != null && lang.toLowerCase().startsWith("fr");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the target's FTB data, reading + parsing it from disk at most once per menu session.
     * {@link #buildMenu()} resets the cache so a re-opened menu sees fresh data.
     */
    private FTBDataReader.PlayerFTBData readFtbData() {
        if (!ftbDataLoaded) {
            // Self-heal the data location first: on a world where FTB Essentials created its
            // playerdata dir after our boot scan, opening the menu now finds it instead of showing
            // an empty homes grid until the next restart.
            FTBDataReader.ensureLocated(admin != null ? admin.getServer() : null);
            cachedFtbData = FTBDataReader.readPlayerData(targetUUID);
            ftbDataLoaded = true;
        }
        return cachedFtbData;
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
                String.format(LanguageHelper.getText("tp.success", admin), x, y, z)));
        SoundHelper.playAt(admin, SoundHelper.TELEPORT);
    }

    private net.minecraft.world.item.Item getDimensionIcon(String dim) {
        if (dim.contains("nether")) return Items.NETHERRACK;
        if (dim.contains("end")) return Items.END_STONE;
        if (dim.contains("mining")) return Items.IRON_PICKAXE;
        return Items.GRASS_BLOCK;
    }

    private static net.minecraft.world.level.GameType cycleGameMode(net.minecraft.world.level.GameType current) {
        return switch (current) {
            case SURVIVAL -> net.minecraft.world.level.GameType.CREATIVE;
            case CREATIVE -> net.minecraft.world.level.GameType.ADVENTURE;
            case ADVENTURE -> net.minecraft.world.level.GameType.SPECTATOR;
            case SPECTATOR -> net.minecraft.world.level.GameType.SURVIVAL;
        };
    }

    /**
     * Re-render the header skull once the real skin resolves (bounded retries so offline-mode UUIDs
     * don't loop). Only runs while this menu is still the admin's open container.
     */
    private void scheduleHeaderRefresh() {
        if (admin == null || headerRefreshScheduled || headerRefreshAttempts >= 4) return;
        headerRefreshScheduled = true;
        headerRefreshAttempts++;
        SchedulerService.delayed(25, () -> {
            headerRefreshScheduled = false;
            if (admin.containerMenu != this) return;
            if (SkullCache.hasTexture(targetUUID)) {
                buildMenu();
                this.broadcastChanges();
            } else {
                scheduleHeaderRefresh();
            }
        });
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
        admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("misc.warns_label", admin) + " §e" + warnCount));

        // Login/logout times — pulled from our own tracker rather than FTB's last_seen.time
        // (which updates on any teleport, not specifically on connect/disconnect).
        LoginTracker.LoginRecord login = LoginTracker.getInstance().get(targetUUID);
        boolean french = isFrench(admin);
        if (login != null) {
            if (login.lastLoginMs() > 0) {
                admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.last_login", admin)
                        + " §f" + TimeFormat.absolute(login.lastLoginMs())
                        + " §8(" + TimeFormat.relative(login.lastLoginMs(), french) + ")"));
            }
            if (!isOnline && login.lastLogoutMs() > 0 && login.lastLogoutMs() >= login.lastLoginMs()) {
                admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.last_logout", admin)
                        + " §f" + TimeFormat.absolute(login.lastLogoutMs())
                        + " §8(" + TimeFormat.relative(login.lastLogoutMs(), french) + ")"));
            }
            if (login.firstSeenMs() > 0) {
                admin.sendSystemMessage(Component.literal("§7" + LanguageHelper.getText("info.first_seen", admin)
                        + " §f" + TimeFormat.absolute(login.firstSeenMs())));
            }
        }

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

        // Open-panel gate first (cheap, cached). Every individual action additionally re-checks its
        // own granular node below — so a packet-crafted click on slot 50 still has to pass BAN.
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;

        var clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE)) return;

        // Refuse to build any command involving the target if the cached name is not a vanilla-shaped
        // identifier — prevents argument injection via crafted offline-mode names.
        boolean nameSafeForCommands = isSafePlayerName(targetName);

        // Reset confirmation state on other click
        if (slotId != 46 && confirmClear) {
            confirmClear = false;
            buildMenu();
            return;
        }

        switch (slotId) {
            case 44 -> { // Player tools (1.3.0)
                admin.closeContainer();
                PlayerToolsMenu.open(sp, targetUUID, targetName, isOnline);
            }
            case 0 -> { // Jail/Unjail
                if (!AdminPermissions.JAIL.check(sp)) return;
                if (isOnline && JailManager.getInstance().hasJailLocation()) {
                    boolean isJailed = JailManager.getInstance().isJailed(targetUUID);
                    if (isJailed) {
                        JailManager.getInstance().unjail(targetUUID, admin.getServer());
                        ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                        if (target != null) {
                            target.sendSystemMessage(ArcadiaMessages.success(
                                    LanguageHelper.getText("jail.released", target)));
                        }
                        admin.sendSystemMessage(ArcadiaMessages.success(
                                LanguageHelper.getText("jail.unjail.success", admin)
                                        .replace("%player%", targetName)));
                        SoundHelper.playAt(admin, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    } else {
                        // Default jail: 30 minutes
                        ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                        if (target != null) {
                            JailManager.getInstance().jail(target,
                                    LanguageHelper.getText("misc.admin_action", admin),
                                    admin.getName().getString(),
                                    30 * 60_000L,
                                    admin.getServer());
                        }
                        if (target != null) {
                            target.sendSystemMessage(ArcadiaMessages.error(
                                    LanguageHelper.getText("jail.notify", target)
                                            .replace("%time%", TextFormatter.formatMs(30 * 60_000L))
                                            .replace("%reason%", LanguageHelper.getText("misc.admin_action", admin))));
                        }
                        admin.sendSystemMessage(ArcadiaMessages.success(
                                LanguageHelper.getText("jail.success", admin)
                                        .replace("%player%", targetName)
                                        .replace("%time%", "30m")));
                        SoundHelper.playAt(admin, SoundHelper.CLICK);
                    }
                    admin.closeContainer();
                    admin.getServer().execute(() -> open(admin, targetUUID, targetName, isOnline));
                }
            }
            case 5 -> { // Team view
                if (!AdminPermissions.TEAMS.check(sp)) return;
                if (FTBTeamsReader.isAvailable()) {
                    FTBTeamsReader.Team team = FTBTeamsReader.getEffectiveTeamFor(targetUUID);
                    if (team != null) {
                        sp.closeContainer();
                        TeamDetailMenu.open(sp, team.id);
                    }
                }
            }
            case 1 -> { // Gamemode cycle (online only)
                if (!AdminPermissions.GAMEMODE.check(sp)) return;
                if (isOnline && canUseCommand("gamemode")) {
                    ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                    if (target != null) {
                        target.setGameMode(cycleGameMode(target.gameMode.getGameModeForPlayer()));
                        SoundHelper.playAt(admin, SoundHelper.CLICK);
                        buildMenu();
                    }
                }
            }
            case 3 -> { // Heal (left-click) / Feed (right-click) — online only
                if (!AdminPermissions.HEAL.check(sp)) return;
                if (isOnline) {
                    ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                    if (target != null) {
                        if (button == 1) {
                            target.getFoodData().setFoodLevel(20);
                            target.getFoodData().setSaturation(20f);
                            admin.sendSystemMessage(ArcadiaMessages.success(
                                    LanguageHelper.getText("feed.done", admin).replace("%player%", targetName)));
                        } else {
                            target.setHealth(target.getMaxHealth());
                            target.getFoodData().setFoodLevel(20);
                            target.getFoodData().setSaturation(20f);
                            target.clearFire();
                            admin.sendSystemMessage(ArcadiaMessages.success(
                                    LanguageHelper.getText("heal.done", admin).replace("%player%", targetName)));
                        }
                        SoundHelper.playAt(admin, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    }
                }
            }
            case 7 -> { // Next-login spawn override — left: pin admin pos, right: clear
                if (!AdminPermissions.NEXT_SPAWN.check(sp)) return;
                if (button == 1) {
                    boolean cleared = NextSpawnManager.getInstance().clear(targetUUID);
                    admin.sendSystemMessage(cleared
                            ? ArcadiaMessages.success(LanguageHelper.getText("nextspawn.cleared", admin)
                                    .replace("%player%", targetName))
                            : ArcadiaMessages.info(LanguageHelper.getText("nextspawn.none", admin)
                                    .replace("%player%", targetName)));
                } else {
                    NextSpawnManager.getInstance().setFromAdmin(targetUUID, admin);
                    admin.sendSystemMessage(ArcadiaMessages.success(
                            LanguageHelper.getText("nextspawn.set", admin).replace("%player%", targetName)));
                    SoundHelper.playAt(admin, SoundHelper.CLICK);
                }
                buildMenu();
            }
            case 53 -> { // Back
                sp.closeContainer();
                AdminPanelMenu.open(sp);
            }
            case 8 -> { // Info
                if (!AdminPermissions.INFO.check(sp)) return;
                showDetailedInfo();
                sp.closeContainer();
            }
            case 2 -> { // Reset progress
                if (!AdminPermissions.RESET_PROGRESS.check(sp)) return;
                if (canUseCommand("advancement") && nameSafeForCommands) {
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "advancement revoke " + targetName + " everything");
                    admin.closeContainer();
                }
            }
            case 6 -> { // InvSee
                if (!AdminPermissions.INVSEE.check(sp)) return;
                if (canUseCommand("invsee") && nameSafeForCommands) {
                    admin.closeContainer();
                    admin.getServer().getCommands().performPrefixedCommand(
                            admin.createCommandSourceStack(), "invsee " + targetName);
                }
            }
            case 46 -> { // Clear inventory
                if (!AdminPermissions.CLEAR_INV.check(sp)) return;
                if (canUseCommand("clear") && nameSafeForCommands) {
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
            case 47 -> { // TP here — teleport programmatically (avoid command-string concatenation entirely).
                if (!AdminPermissions.TELEPORT.check(sp)) return;
                if (isOnline && canUseCommand("tp")) {
                    ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                    if (target != null) {
                        target.teleportTo(admin.serverLevel(), admin.getX(), admin.getY(), admin.getZ(),
                                admin.getYRot(), admin.getXRot());
                        admin.closeContainer();
                    }
                }
            }
            case 48 -> { // TP to / last loc — programmatic for online; FTB last-seen for offline.
                if (!AdminPermissions.TELEPORT.check(sp)) return;
                if (isOnline) {
                    ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                    if (target != null) {
                        admin.teleportTo(target.serverLevel(), target.getX(), target.getY(), target.getZ(),
                                admin.getYRot(), admin.getXRot());
                    }
                } else {
                    FTBDataReader.PlayerFTBData ftbData = readFtbData();
                    if (ftbData != null && ftbData.lastSeen != null) {
                        executeTeleport(ftbData.lastSeen.dimension,
                                ftbData.lastSeen.x, ftbData.lastSeen.y, ftbData.lastSeen.z);
                    }
                }
                admin.closeContainer();
            }
            case 45 -> { // Mute/Unmute
                if (!AdminPermissions.MUTE.check(sp)) return;
                if (isOnline && StaffService.getRole(admin).atLeast(StaffRole.MOD)) {
                    boolean isMuted = StaffActions.isMuted(targetUUID);
                    if (isMuted) {
                        StaffActions.unmute(targetUUID, admin);
                        SoundHelper.playAt(admin, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    } else {
                        // Default mute: 10 minutes
                        StaffActions.mute(targetUUID, admin, LanguageHelper.getText("misc.admin_action", admin), 10 * 60_000L);
                        SoundHelper.playAt(admin, SoundHelper.CLICK);
                    }
                    admin.closeContainer();
                    admin.getServer().execute(() -> open(admin, targetUUID, targetName, isOnline));
                }
            }
            case 49 -> { // Kick — programmatic; avoids command-string injection via crafted names.
                if (!AdminPermissions.KICK.check(sp)) return;
                if (isOnline && canUseCommand("kick")) {
                    ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                    if (target != null) {
                        target.connection.disconnect(Component.literal(
                                LanguageHelper.getText("misc.admin_action", admin)));
                        admin.closeContainer();
                    }
                }
            }
            case 50 -> { // Ban/Unban — programmatic via PlayerList API.
                if (!AdminPermissions.BAN.check(sp)) return;
                if (canUseCommand("ban") || canUseCommand("pardon")) {
                    var profile = new com.mojang.authlib.GameProfile(targetUUID, targetName);
                    var bans = admin.getServer().getPlayerList().getBans();
                    boolean wasBanned = bans.isBanned(profile);
                    if (wasBanned) {
                        bans.remove(profile);
                    } else {
                        bans.add(new net.minecraft.server.players.UserBanListEntry(profile, null,
                                admin.getName().getString(), null,
                                LanguageHelper.getText("misc.admin_action", admin)));
                        ServerPlayer target = admin.getServer().getPlayerList().getPlayer(targetUUID);
                        if (target != null) {
                            target.connection.disconnect(Component.literal(
                                    LanguageHelper.getText("misc.admin_action", admin)));
                        }
                    }
                    admin.closeContainer();
                    open(admin, targetUUID, targetName, isOnline);
                }
            }
            case 51 -> { // Warn (chat mode)
                if (!AdminPermissions.WARN_EDIT.check(sp)) return;
                admin.closeContainer();
                ChatListener.startWarnSession(admin, targetUUID, targetName);
            }
            case 52 -> { // View warns
                if (!AdminPermissions.WARN_VIEW.check(sp)) return;
                admin.closeContainer();
                WarnListMenu.open(admin, targetUUID, targetName);
            }
            default -> {
                // Homes (9-35) — teleport action, so re-check TELEPORT (layer 2): a forged
                // slot-click on a home must not move a viewer who lacks the teleport node.
                if (slotId >= 9 && slotId <= 35) {
                    if (!AdminPermissions.TELEPORT.check(sp)) return;
                    FTBDataReader.PlayerFTBData ftbData = readFtbData();
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
                // History (36-44) — same TELEPORT re-check.
                if (slotId >= 36 && slotId <= 44) {
                    if (!AdminPermissions.TELEPORT.check(sp)) return;
                    FTBDataReader.PlayerFTBData ftbData = readFtbData();
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
