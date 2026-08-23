package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.LoginTracker;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Playtime and session statistics, ranked.
 *
 * <p>Built from the login tracker, which has recorded connect and disconnect times since 1.2.x and
 * from 1.3.0 also accumulates total time and session count. Online players include the session in
 * progress, so the numbers do not stall while somebody is actually playing.</p>
 *
 * <p>Two orderings, toggled from the control strip: total playtime, and most recently seen. The
 * first answers "who are the regulars", the second "who has drifted away".</p>
 *
 * @author vyrriox
 */
public class SessionsMenu extends PagedMenu {

    private static final int SLOT_SORT = 47;
    private static final int MAX_ROWS = 450;

    private record Row(UUID uuid, String name, long playtime, int sessions,
                       long lastLogin, long average, boolean online) {}

    private boolean sortByPlaytime = true;
    private List<Row> rows = new ArrayList<>();

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new SessionsMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("sessions.title", admin))));
    }

    public SessionsMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        List<Row> out = new ArrayList<>();
        for (var e : LoginTracker.getInstance().snapshot().entrySet()) {
            UUID uuid = e.getKey();
            LoginTracker.LoginRecord rec = e.getValue();
            boolean online = server != null && server.getPlayerList().getPlayer(uuid) != null;
            String name = OfflinePlayerManager.getInstance().getName(uuid);
            if (name == null) name = uuid.toString().substring(0, 8);
            out.add(new Row(uuid, name, rec.playtimeMs(online), rec.sessions(),
                    rec.lastLoginMs(), rec.averageSessionMs(), online));
        }
        out.sort(sortByPlaytime
                ? Comparator.comparingLong(Row::playtime).reversed()
                : Comparator.comparingLong(Row::lastLogin).reversed());
        rows = out.size() > MAX_ROWS ? new ArrayList<>(out.subList(0, MAX_ROWS)) : out;
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "sessions.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        Row r = rows.get(index);
        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(r.uuid(), r.name()))
                .name(Component.literal((r.online() ? "§a" : "§7") + r.name()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("sessions.playtime", admin)
                        + " §f" + TextFormatter.formatMs(r.playtime())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("sessions.count", admin)
                        + " §f" + r.sessions()));
        if (r.average() > 0) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("sessions.average", admin)
                    + " §f" + TextFormatter.formatMs(r.average())));
        }
        if (r.lastLogin() > 0) {
            b.addLore(Component.literal("§8" + LanguageHelper.getText("sessions.last", admin)
                    + " " + date(r.lastLogin())));
        }
        b.addLore(Component.literal("§8#" + (index + 1)));
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        Row r = rows.get(index);
        admin.closeContainer();
        PlayerDetailMenu.open(admin, r.uuid(), r.name(), r.online());
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(SLOT_SORT, ItemBuilder.of(Items.HOPPER)
                .name(Component.literal("§e" + LanguageHelper.getText("sessions.sort", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText(
                        sortByPlaytime ? "sessions.sort.playtime" : "sessions.sort.recent", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (slot != SLOT_SORT) return;
        sortByPlaytime = !sortByPlaytime;
        page = 0;
        reload();
        rebuild();
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
