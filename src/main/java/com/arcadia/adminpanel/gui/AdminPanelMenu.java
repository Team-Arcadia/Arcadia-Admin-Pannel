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
    private boolean showOffline = true;
    private static final int ITEMS_PER_PAGE = 45;

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

        if (showOffline) {
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

        allPlayers.sort((p1, p2) -> {
            if (p1.online != p2.online) return p1.online ? -1 : 1;
            return p1.name.compareToIgnoreCase(p2.name);
        });

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
            var skull = SkullCache.createSkull(info.uuid, info.name);
            skull.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal((info.online ? "§a" : "§c") + info.name));
            this.getContainer().setItem(slot, skull);
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

        // Filter toggle (slot 49)
        this.getContainer().setItem(49, ItemBuilder.of(showOffline ? Items.LIME_DYE : Items.GRAY_DYE)
                .name(Component.literal("§6" + (showOffline
                        ? LanguageHelper.getText("menu.filter.all", admin)
                        : LanguageHelper.getText("menu.filter.online", admin))))
                .build());

        // Clear search (slot 51) — only if filter active
        if (!filter.isEmpty()) {
            this.getContainer().setItem(51, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("action.search.clear", admin)))
                    .addLore(Component.literal("§7" + LanguageHelper.getText("action.search.current", admin)
                            + " §e" + filter))
                    .build());
        }

        if (end < allPlayers.size()) {
            this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>")).build());
        }
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
                var skull = SkullCache.createSkull(uuid, name);
                skull.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                        Component.literal((online ? "§a" : "§c") + name));
                this.getContainer().setItem(slot, skull);
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

        // Filter toggle (49)
        if (slotId == 49) {
            showOffline = !showOffline;
            currentPage = 0;
            buildMenu();
            return;
        }

        // Clear search (51)
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
