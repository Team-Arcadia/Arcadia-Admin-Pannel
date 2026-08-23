package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.WatchlistManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Players flagged for a second look, and who flagged them.
 *
 * <p>Left click opens the player's sheet, right click clears the flag. Online players are marked, so
 * the list doubles as "is the person I was watching for connected right now".</p>
 *
 * @author vyrriox
 */
public class WatchlistMenu extends PagedMenu {

    private List<Map.Entry<UUID, WatchlistManager.WatchEntry>> rows;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WatchlistMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("watchlist.title", admin))));
    }

    public WatchlistMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        rows = WatchlistManager.all();
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "watchlist.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        var row = rows.get(index);
        UUID uuid = row.getKey();
        WatchlistManager.WatchEntry entry = row.getValue();
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(uuid) != null;

        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(uuid, entry.targetName()))
                .name(Component.literal((online ? "§a" : "§7") + entry.targetName()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("audit.by", admin)
                        + " §e" + entry.byName()));
        if (entry.reason() != null && !entry.reason().isBlank()) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("mute.reason", admin)
                    + " §f" + entry.reason()));
        }
        b.addLore(Component.literal("§8" + LanguageHelper.getText(
                online ? "player.online" : "player.offline", admin)));
        b.addLore(Component.literal("§8" + LanguageHelper.getText("watchlist.click", admin)));

        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        var row = rows.get(index);
        UUID uuid = row.getKey();
        String name = row.getValue().targetName();

        if (button == 1) {
            if (!AdminPermissions.WATCHLIST.check(admin)) return;
            WatchlistManager.remove(admin, uuid, name);
            SoundHelper.success(admin);
            reload();
            rebuild();
            return;
        }

        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(uuid) != null;
        admin.closeContainer();
        PlayerDetailMenu.open(admin, uuid, name, online);
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
