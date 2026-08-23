package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.BanManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * The ban list, with expiry times and one-click unban.
 *
 * <p>Reading {@code banned-players.json} over SSH to answer "is this player still banned and until
 * when" was the previous workflow. This is the same information, in game, sorted newest first, with
 * the temporary entries counting down.</p>
 *
 * <p>Unban asks for a second click. The confirmation is per row, so paging away or clicking anything
 * else drops it.</p>
 *
 * @author vyrriox
 */
public class BanListMenu extends PagedMenu {

    private List<BanManager.BanView> rows;
    /** Row index awaiting an unban confirmation, or -1. */
    private int pendingUnban = -1;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new BanListMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("banlist.title", admin))));
    }

    public BanListMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        rows = server == null ? List.of() : BanManager.list(server);
    }

    // -- Content -------------------------------------------------------------

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "banlist.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        BanManager.BanView view = rows.get(index);
        boolean confirming = index == pendingUnban;

        ItemStack base = view.uuid() != null
                ? SkullCache.createSkull(view.uuid(), view.name())
                : ItemBuilder.of(Items.RED_DYE).build();

        ItemBuilder b = ItemBuilder.of(base)
                .name(Component.literal("§c" + view.name()));

        if (view.isPermanent()) {
            b.addLore(Component.literal("§4" + LanguageHelper.getText("banlist.permanent", admin)));
        } else {
            b.addLore(Component.literal("§e" + LanguageHelper.getText("banlist.expires", admin)
                    + " §f" + TextFormatter.formatMs(view.remainingMs())));
        }
        if (!view.reason().isBlank()) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("mute.reason", admin)
                    + " §f" + view.reason()));
        }
        if (!view.source().isBlank()) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("audit.by", admin)
                    + " §e" + view.source()));
        }
        if (view.createdAt() > 0) {
            b.addLore(Component.literal("§8" + date(view.createdAt())));
        }
        b.addLore(Component.literal(confirming
                ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                : "§a" + LanguageHelper.getText("banlist.click_unban", admin)));

        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (!AdminPermissions.BAN.check(admin)) return;
        MinecraftServer server = admin.getServer();
        if (server == null) return;

        if (pendingUnban != index) {
            pendingUnban = index;
            rebuild();
            return;
        }
        pendingUnban = -1;

        BanManager.BanView view = rows.get(index);
        UUID uuid = view.uuid();
        if (uuid != null) {
            BanManager.unban(admin, server, uuid, view.name());
        } else {
            // No resolvable UUID: lift the entry directly off the vanilla list.
            BanManager.unbanEntry(admin, server, view);
        }
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("banlist.unbanned", admin).replace("%player%", view.name())));
        SoundHelper.success(admin);

        reload();
        rebuild();
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
