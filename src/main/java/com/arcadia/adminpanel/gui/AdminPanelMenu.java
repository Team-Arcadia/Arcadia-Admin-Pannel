package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.scheduler.SchedulerService;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Main Admin Panel menu — player list with pagination and filter.
 *
 * @author vyrriox
 */
public class AdminPanelMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final String filter;
    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 45;

    /**
     * What the grid is narrowed to. 1.3.0 replaced the online/offline boolean with a cycle: a staff
     * member arriving after an incident wants "who is jailed" or "who is flagged", and building that
     * answer by scrolling an alphabetical list of every player who ever joined does not scale.
     */
    private enum Filter {
        ALL, ONLINE, OFFLINE, JAILED, MUTED, BANNED, WARNED, WATCHED, FROZEN, VANISHED, AFK, SELECTED
    }

    /** How the grid is ordered. */
    private enum Sort { NAME, LAST_SEEN, PLAYTIME, WARNS }

    private Filter activeFilter = Filter.ALL;
    private Sort activeSort = Sort.NAME;

    // Authoritative slot -> player mapping for the current page. We act on the head's UUID (not its
    // display name) so duplicate / case-colliding names can never operate on the wrong player.
    private final Map<Integer, UUID> slotUuid = new HashMap<>();
    private final Map<Integer, String> slotName = new HashMap<>();

    // Deferred head-skin refresh: skins resolve async (Mojang), so we re-send the head slots in
    // place a few times after build until they're all textured (capped, never on offline mode).
    private boolean headRefreshScheduled = false;
    private int headRefreshAttempts = 0;
    private static final int MAX_HEAD_REFRESH = 4;

    /** Server-side constructor — builds menu content. */
    public static void open(ServerPlayer admin) {
        open(admin, "");
    }

    public static void open(ServerPlayer admin, String filter) {
        admin.openMenu(new SimpleMenuProvider(
                (id, playerInv, player) -> new AdminPanelMenu(id, playerInv, (ServerPlayer) player, filter),
                LanguageHelper.getComponent("menu.title", admin)
        ));
    }

    /** Server constructor. */
    public AdminPanelMenu(int id, Inventory playerInv, ServerPlayer admin, String filter) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.filter = filter != null ? filter : "";
        buildMenu();
    }

    /** Client constructor (items sync from server). */
    public AdminPanelMenu(int id, Inventory playerInv, String filter) {
        super(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.filter = filter != null ? filter : "";
    }

    private void buildMenu() {
        if (admin == null) return;

        List<PlayerInfo> allPlayers = new ArrayList<>();

        for (ServerPlayer player : admin.getServer().getPlayerList().getPlayers()) {
            allPlayers.add(new PlayerInfo(player.getUUID(), player.getName().getString(), true));
        }

        if (activeFilter != Filter.ONLINE) {
            Map<UUID, OfflinePlayerManager.CachedPlayerSummary> cache =
                    OfflinePlayerManager.getInstance().getCache();
            for (var summary : cache.values()) {
                boolean isOnline = admin.getServer().getPlayerList().getPlayer(summary.uuid()) != null;
                if (!isOnline) {
                    allPlayers.add(new PlayerInfo(summary.uuid(), summary.name(), false));
                }
            }
        }

        // Apply search filter
        if (!filter.isEmpty()) {
            String lowerFilter = filter.toLowerCase(Locale.ROOT);
            allPlayers.removeIf(p -> !p.name.toLowerCase(Locale.ROOT).contains(lowerFilter));
        }

        allPlayers.removeIf(p -> !matchesFilter(p));
        sortPlayers(allPlayers);

        // Fill background
        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler.copy());
        }

        // Place heads
        slotUuid.clear();
        slotName.clear();
        headRefreshAttempts = 0;
        boolean anyPlaceholder = false;
        int start = currentPage * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, allPlayers.size());
        for (int i = start; i < end; i++) {
            PlayerInfo info = allPlayers.get(i);
            int slot = i - start;
            this.getContainer().setItem(slot, buildHead(info));
            slotUuid.put(slot, info.uuid);
            slotName.put(slot, info.name);
            if (!SkullCache.hasTexture(info.uuid)) anyPlaceholder = true;
        }
        // Some skins are still resolving — re-send the head slots in place once they arrive.
        if (anyPlaceholder) scheduleHeadRefresh();

        // Controls row
        if (currentPage > 0) {
            this.getContainer().setItem(45, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e<< " + LanguageHelper.getText("nav.previous", admin))).build());
        }

        // Search button (slot 47)
        this.getContainer().setItem(47, ItemBuilder.of(Items.COMPASS)
                .name(Component.literal("§b" + LanguageHelper.getText("action.search", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("action.search.hint", admin)))
                .build());

        // Teams browser (slot 46) — only if FTB Teams data exists AND the viewer holds TEAMS.
        if (FTBTeamsReader.isAvailable() && AdminPermissions.TEAMS.check(admin)) {
            this.getContainer().setItem(46, ItemBuilder.of(Items.WHITE_BANNER)
                    .name(Component.literal("§b" + LanguageHelper.getText("team.browse", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("team.browse.hint", admin)))
                    .build());
        }

        // Filter cycle (slot 49)
        this.getContainer().setItem(49, ItemBuilder
                .of(activeFilter == Filter.ALL ? Items.LIME_DYE : Items.ORANGE_DYE)
                .name(Component.literal("§6" + LanguageHelper.getText("menu.filter", admin) + " §f"
                        + LanguageHelper.getText("menu.filter." + activeFilter.name().toLowerCase(), admin)))
                .addLore(Component.literal("§7" + allPlayers.size() + " "
                        + LanguageHelper.getText("menu.filter.results", admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("menu.filter.cycle", admin)))
                .build());

        // Sort cycle (slot 50)
        this.getContainer().setItem(50, ItemBuilder.of(Items.HOPPER)
                .name(Component.literal("§e" + LanguageHelper.getText("menu.sort", admin) + " §f"
                        + LanguageHelper.getText("menu.sort." + activeSort.name().toLowerCase(), admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("menu.sort.cycle", admin)))
                .build());

        // Staff tools (slot 48)
        this.getContainer().setItem(48, ItemBuilder.of(Items.BEACON)
                .name(Component.literal("§b" + LanguageHelper.getText("tools.title", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("tools.hint", admin)))
                .build());

        // Selection (slot 52) — only when the viewer can act on one.
        if (AdminPermissions.BULK.check(admin)) {
            int selected = com.arcadia.adminpanel.util.SelectionManager.size(admin.getUUID());
            this.getContainer().setItem(52, ItemBuilder
                    .of(selected > 0 ? Items.SHULKER_SHELL : Items.GLASS_PANE)
                    .name(Component.literal((selected > 0 ? "§a" : "§7")
                            + LanguageHelper.getText("tools.selection", admin)))
                    .addLore(Component.literal("§7" + selected + " "
                            + LanguageHelper.getText("tools.selection.count", admin)))
                    .addLore(Component.literal("§8" + LanguageHelper.getText("menu.select.hint", admin)))
                    .build());
        }

        // Clear search (slot 51) — only if filter active. Otherwise the slot carries the legend:
        // every control on this row does something different on a right-click, and until 1.3.1 the
        // only way to find that out was to try it.
        if (!filter.isEmpty()) {
            this.getContainer().setItem(51, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("action.search.clear", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("action.search.current", admin)
                            + " §e" + filter))
                    .build());
        } else {
            this.getContainer().setItem(51, ItemBuilder.of(Items.OAK_SIGN)
                    .name(Component.literal("§f" + LanguageHelper.getText("menu.legend", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("menu.legend.open", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("menu.legend.select", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("menu.legend.cycle", admin)))
                    .addLore(Component.literal("§8" + LanguageHelper.getText("menu.legend.colours", admin)))
                    .addLore(Component.literal("§a" + LanguageHelper.getText("menu.legend.online", admin)
                            + " §8/ §2" + LanguageHelper.getText("menu.legend.afk", admin)
                            + " §8/ §c" + LanguageHelper.getText("menu.legend.offline", admin)
                            + " §8/ §b" + LanguageHelper.getText("menu.legend.selected", admin)))
                    .build());
        }

        if (end < allPlayers.size()) {
            this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>")).build());
        }
    }

    /**
     * Builds one head, with the status markers a moderator scans the grid for.
     *
     * <p>The colour of the name carries the primary state and the lore carries the rest, so a staff
     * member can see at a glance that the player they are looking for is jailed, flagged, or already
     * in their selection, without opening the sheet.</p>
     */
    private net.minecraft.world.item.ItemStack buildHead(PlayerInfo info) {
        boolean selected = com.arcadia.adminpanel.util.SelectionManager
                .isSelected(admin.getUUID(), info.uuid);
        boolean watched = com.arcadia.adminpanel.util.WatchlistManager.isWatched(info.uuid);
        boolean jailed = com.arcadia.adminpanel.util.JailManager.getInstance().isJailed(info.uuid);
        boolean muted = com.arcadia.lib.staff.StaffActions.isMuted(info.uuid);
        boolean frozen = com.arcadia.adminpanel.util.FreezeManager.isFrozen(info.uuid);
        boolean vanished = com.arcadia.adminpanel.util.VanishManager.isVanished(info.uuid);
        boolean afk = com.arcadia.adminpanel.util.AfkTracker.isAfk(info.uuid);
        int warns = com.arcadia.adminpanel.util.WarnManager.getInstance().getWarnCount(info.uuid);

        String colour = selected ? "§b" : info.online ? (afk ? "§2" : "§a") : "§c";
        var builder = ItemBuilder.of(SkullCache.createSkull(info.uuid, info.name))
                .name(Component.literal(colour + info.name + (selected ? " §b*" : "")));

        if (afk) builder.addLore(Component.literal("§8" + LanguageHelper.getText("perf.afk", admin)));
        if (jailed) builder.addLore(Component.literal("§c" + LanguageHelper.getText("menu.mark.jailed", admin)));
        if (muted) builder.addLore(Component.literal("§c" + LanguageHelper.getText("menu.mark.muted", admin)));
        if (frozen) builder.addLore(Component.literal("§b" + LanguageHelper.getText("menu.mark.frozen", admin)));
        if (vanished) builder.addLore(Component.literal("§8" + LanguageHelper.getText("menu.mark.vanished", admin)));
        if (watched) builder.addLore(Component.literal("§d" + LanguageHelper.getText("menu.mark.watched", admin)));
        if (warns > 0) {
            builder.addLore(Component.literal("§e" + warns + " "
                    + LanguageHelper.getText("menu.mark.warns", admin)));
        }
        if (AdminPermissions.BULK.check(admin)) {
            builder.addLore(Component.literal("§8" + LanguageHelper.getText("menu.select.hint", admin)));
        }
        return builder.build();
    }

    /**
     * Decides whether one player survives the active filter.
     *
     * <p>Every branch is an in-memory lookup: a set membership, a cached warn count or a live player
     * list check. Nothing here touches disk, so cycling the filter on a server with thousands of
     * known players stays instant.</p>
     */
    private boolean matchesFilter(PlayerInfo p) {
        return switch (activeFilter) {
            case ALL -> true;
            case ONLINE -> p.online;
            case OFFLINE -> !p.online;
            case JAILED -> com.arcadia.adminpanel.util.JailManager.getInstance().isJailed(p.uuid);
            case MUTED -> com.arcadia.lib.staff.StaffActions.isMuted(p.uuid);
            case BANNED -> admin.getServer() != null
                    && com.arcadia.adminpanel.util.BanManager.isBanned(admin.getServer(), p.uuid, p.name);
            case WARNED -> com.arcadia.adminpanel.util.WarnManager.getInstance().getWarnCount(p.uuid) > 0;
            case WATCHED -> com.arcadia.adminpanel.util.WatchlistManager.isWatched(p.uuid);
            case FROZEN -> com.arcadia.adminpanel.util.FreezeManager.isFrozen(p.uuid);
            case VANISHED -> com.arcadia.adminpanel.util.VanishManager.isVanished(p.uuid);
            case AFK -> com.arcadia.adminpanel.util.AfkTracker.isAfk(p.uuid);
            case SELECTED -> com.arcadia.adminpanel.util.SelectionManager
                    .isSelected(admin.getUUID(), p.uuid);
        };
    }

    /** Orders the grid. Online players always float to the top of whichever ordering is active. */
    private void sortPlayers(List<PlayerInfo> players) {
        java.util.Comparator<PlayerInfo> comparator = switch (activeSort) {
            case NAME -> (a, b) -> a.name.compareToIgnoreCase(b.name);
            case LAST_SEEN -> (a, b) -> Long.compare(lastSeen(b), lastSeen(a));
            case PLAYTIME -> (a, b) -> Long.compare(playtime(b), playtime(a));
            case WARNS -> (a, b) -> Integer.compare(
                    com.arcadia.adminpanel.util.WarnManager.getInstance().getWarnCount(b.uuid),
                    com.arcadia.adminpanel.util.WarnManager.getInstance().getWarnCount(a.uuid));
        };
        players.sort((a, b) -> {
            if (a.online != b.online) return a.online ? -1 : 1;
            return comparator.compare(a, b);
        });
    }

    private static long lastSeen(PlayerInfo p) {
        var rec = com.arcadia.adminpanel.util.LoginTracker.getInstance().get(p.uuid);
        return rec == null ? 0L : Math.max(rec.lastLoginMs(), rec.lastLogoutMs());
    }

    private static long playtime(PlayerInfo p) {
        var rec = com.arcadia.adminpanel.util.LoginTracker.getInstance().get(p.uuid);
        return rec == null ? 0L : rec.playtimeMs(p.online);
    }

    /**
     * Re-send the current page's head slots once their skins finish resolving. Bounded retries so a
     * permanently-unresolvable head (offline-mode UUID) doesn't loop forever. Only runs while this
     * menu is still the player's open container.
     */
    private void scheduleHeadRefresh() {
        if (admin == null || headRefreshScheduled || headRefreshAttempts >= MAX_HEAD_REFRESH) return;
        headRefreshScheduled = true;
        headRefreshAttempts++;
        SchedulerService.delayed(30, () -> {
            headRefreshScheduled = false;
            if (admin.containerMenu != this) return;
            boolean stillPlaceholder = false;
            for (var e : slotUuid.entrySet()) {
                int slot = e.getKey();
                UUID uuid = e.getValue();
                String name = slotName.get(slot);
                if (name == null) continue;
                boolean online = admin.getServer().getPlayerList().getPlayer(uuid) != null;
                this.getContainer().setItem(slot, buildHead(new PlayerInfo(uuid, name, online)));
                if (!SkullCache.hasTexture(uuid)) stillPlaceholder = true;
            }
            this.broadcastChanges();
            if (stillPlaceholder) scheduleHeadRefresh();
        });
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        // Defense in depth (1.2.4): re-check staff perms on every click.
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;
        var clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE)) return;

        // Player head click (slots 0-44) — act on the authoritative slot UUID, never the display
        // name (duplicate / case-colliding names would otherwise target the wrong player).
        if (slotId >= 0 && slotId < 45) {
            UUID targetUUID = slotUuid.get(slotId);
            if (targetUUID == null) return;
            boolean isOnline = sp.getServer().getPlayerList().getPlayer(targetUUID) != null;
            String name = slotName.getOrDefault(slotId,
                    clicked.getHoverName().getString().replaceAll("§[0-9a-fk-or]", ""));

            // Shift-click builds the bulk selection instead of opening the sheet. Keeping it on a
            // modifier means the ordinary click still does the ordinary thing.
            if ((clickType == ClickType.QUICK_MOVE || button == 1)
                    && AdminPermissions.BULK.check(sp)) {
                com.arcadia.adminpanel.util.SelectionManager.toggle(sp.getUUID(), targetUUID, name);
                com.arcadia.lib.util.SoundHelper.playAt(sp, com.arcadia.lib.util.SoundHelper.CLICK);
                buildMenu();
                this.broadcastChanges();
                return;
            }

            sp.closeContainer();
            PlayerDetailMenu.open(sp, targetUUID, name, isOnline);
            return;
        }

        // Search (47)
        if (slotId == 47) {
            sp.closeContainer();
            com.arcadia.adminpanel.event.ChatListener.startSearchSession(sp);
            return;
        }

        // Teams browser (46) — re-check TEAMS (layer 2) so a forged click can't open the roster
        // without the node. Mirrors the gate PlayerDetailMenu uses for the same feature.
        if (slotId == 46 && FTBTeamsReader.isAvailable()) {
            if (!AdminPermissions.TEAMS.check(sp)) return;
            sp.closeContainer();
            TeamListMenu.open(sp);
            return;
        }

        // Filter cycle (49) — right click steps backwards so a missed target is one click away.
        if (slotId == 49) {
            Filter[] all = Filter.values();
            int step = button == 1 ? all.length - 1 : 1;
            activeFilter = all[(activeFilter.ordinal() + step) % all.length];
            currentPage = 0;
            buildMenu();
            return;
        }

        // Sort cycle (50)
        if (slotId == 50) {
            Sort[] all = Sort.values();
            int step = button == 1 ? all.length - 1 : 1;
            activeSort = all[(activeSort.ordinal() + step) % all.length];
            currentPage = 0;
            buildMenu();
            return;
        }

        // Staff tools (48)
        if (slotId == 48) {
            sp.closeContainer();
            StaffToolsMenu.open(sp);
            return;
        }

        // Selection (52) — left opens the bulk screen, right clears the selection.
        if (slotId == 52 && AdminPermissions.BULK.check(sp)) {
            if (button == 1) {
                com.arcadia.adminpanel.util.SelectionManager.clear(sp.getUUID());
                buildMenu();
                this.broadcastChanges();
            } else {
                sp.closeContainer();
                BulkActionsMenu.open(sp);
            }
            return;
        }

        // Clear search (51). The legend that occupies the slot when no search is active is inert.
        if (slotId == 51 && !filter.isEmpty()) {
            sp.closeContainer();
            open(sp, "");
            return;
        }

        // Pagination
        if (slotId == 45 && currentPage > 0) {
            currentPage--;
            buildMenu();
        } else if (slotId == 53) {
            currentPage++;
            buildMenu();
        }
    }

    @Override
    public @NotNull net.minecraft.world.item.ItemStack quickMoveStack(@NotNull Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    private record PlayerInfo(UUID uuid, String name, boolean online) {}
}
