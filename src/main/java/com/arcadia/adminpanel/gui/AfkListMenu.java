package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.adminpanel.util.AfkTracker;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Who is idle, and for how long.
 *
 * <p>Each row shows the dimension the player is parked in, because an AFK player holding chunks
 * loaded in a busy dimension is a performance question as much as a social one. Left click opens
 * their sheet; right click teleports to them, so "go see what that farm is doing" is one action.</p>
 *
 * @author vyrriox
 */
public class AfkListMenu extends PagedMenu {

    private List<AfkTracker.AfkPlayer> rows;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new AfkListMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("afk.title", admin))));
    }

    public AfkListMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        rows = server == null ? List.of() : AfkTracker.list(server);
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "afk.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        AfkTracker.AfkPlayer row = rows.get(index);
        MinecraftServer server = admin.getServer();
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(row.uuid());

        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(row.uuid(), row.name()))
                .name(Component.literal("§7" + row.name()))
                .addLore(Component.literal("§e" + LanguageHelper.getText("afk.duration", admin)
                        + " §f" + TextFormatter.formatMs(row.durationMs())));
        if (target != null) {
            b.addLore(Component.literal("§7" + target.level().dimension().location().getPath()));
            b.addLore(Component.literal("§8" + (int) target.getX() + ", "
                    + (int) target.getY() + ", " + (int) target.getZ()));
        }
        b.addLore(Component.literal("§8" + LanguageHelper.getText("afk.click", admin)));
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        AfkTracker.AfkPlayer row = rows.get(index);
        MinecraftServer server = admin.getServer();
        if (server == null) return;
        ServerPlayer target = server.getPlayerList().getPlayer(row.uuid());

        if (button == 1) {
            if (target == null) return;
            if (!com.arcadia.adminpanel.util.AdminPermissions.TELEPORT.check(admin)) return;
            com.arcadia.adminpanel.util.BackManager.push(admin);
            admin.closeContainer();
            admin.teleportTo((net.minecraft.server.level.ServerLevel) target.level(),
                    target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
            com.arcadia.lib.util.SoundHelper.playAt(admin, com.arcadia.lib.util.SoundHelper.TELEPORT);
            com.arcadia.adminpanel.util.AuditManager.record(admin,
                    com.arcadia.adminpanel.util.AdminAction.TELEPORT,
                    row.uuid(), row.name(), "afk list");
            return;
        }

        admin.closeContainer();
        PlayerDetailMenu.open(admin, row.uuid(), row.name(), target != null);
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(47, ItemBuilder.of(Items.CLOCK)
                .name(Component.literal("§e" + LanguageHelper.getText("afk.threshold", admin)))
                .addLore(Component.literal("§7"
                        + com.arcadia.adminpanel.util.AdminConfig.get().afkMinutes + " min"))
                .build());
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
