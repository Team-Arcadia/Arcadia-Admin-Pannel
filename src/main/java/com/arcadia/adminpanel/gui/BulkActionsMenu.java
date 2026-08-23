package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SelectionManager;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one action against every player in the staff member's selection.
 *
 * <p>Selection happens in the player grid (shift-click a head); this screen is where it is spent.
 * The point is the cases that were previously six identical commands in a row: gathering an event
 * group, messaging a raid party, healing everyone after a fight.</p>
 *
 * <p>Kick asks twice, because it is the one action here that cannot be undone with another click.
 * Offline members of the selection are skipped and counted rather than silently dropped.</p>
 *
 * @author vyrriox
 */
public class BulkActionsMenu extends PagedMenu {

    private static final int SLOT_MESSAGE = 46;
    private static final int SLOT_GATHER = 47;
    private static final int SLOT_HEAL = 48;
    private static final int SLOT_WARN = 50;
    private static final int SLOT_KICK = 51;
    private static final int SLOT_CLEAR = 52;

    private List<Map.Entry<UUID, String>> rows;
    private boolean confirmKick = false;

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new BulkActionsMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("bulk.title", admin))));
    }

    public BulkActionsMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        rows = SelectionManager.entries(admin.getUUID());
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "bulk.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        var row = rows.get(index);
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(row.getKey()) != null;
        this.getContainer().setItem(slot, ItemBuilder
                .of(SkullCache.createSkull(row.getKey(), row.getValue()))
                .name(Component.literal((online ? "§a" : "§7") + row.getValue()))
                .addLore(Component.literal("§8" + LanguageHelper.getText(
                        online ? "player.online" : "player.offline", admin)))
                .addLore(Component.literal("§c" + LanguageHelper.getText("bulk.click_remove", admin)))
                .build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        var row = rows.get(index);
        SelectionManager.toggle(admin.getUUID(), row.getKey(), row.getValue());
        SoundHelper.playAt(admin, SoundHelper.CLICK);
        reload();
        rebuild();
    }

    // -- Actions -------------------------------------------------------------

    @Override
    protected void renderExtraControls() {
        int online = SelectionManager.onlineTargets(admin).size();
        control(SLOT_MESSAGE, Items.PAPER, "§b", "bulk.message", online);
        if (AdminPermissions.TELEPORT.check(admin)) {
            control(SLOT_GATHER, Items.ENDER_PEARL, "§d", "bulk.gather", online);
        }
        if (AdminPermissions.HEAL.check(admin)) {
            control(SLOT_HEAL, Items.GOLDEN_APPLE, "§a", "bulk.heal", online);
        }
        if (AdminPermissions.WARN_EDIT.check(admin)) {
            control(SLOT_WARN, Items.TNT, "§e", "bulk.warn", rows.size());
        }
        if (AdminPermissions.KICK.check(admin)) {
            this.getContainer().setItem(SLOT_KICK, ItemBuilder.of(Items.IRON_BOOTS)
                    .name(Component.literal("§c" + LanguageHelper.getText("bulk.kick", admin)))
                    .addLore(Component.literal("§7" + online + " "
                            + LanguageHelper.getText("bulk.targets", admin)))
                    .addLore(Component.literal(confirmKick
                            ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                            : "§8" + LanguageHelper.getText("bulk.kick.hint", admin)))
                    .build());
        }
        this.getContainer().setItem(SLOT_CLEAR, ItemBuilder.of(Items.LAVA_BUCKET)
                .name(Component.literal("§6" + LanguageHelper.getText("bulk.clear", admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("bulk.clear.hint", admin)))
                .build());
    }

    private void control(int slot, net.minecraft.world.item.Item item, String colour,
                         String key, int count) {
        this.getContainer().setItem(slot, ItemBuilder.of(item)
                .name(Component.literal(colour + LanguageHelper.getText(key, admin)))
                .addLore(Component.literal("§7" + count + " "
                        + LanguageHelper.getText("bulk.targets", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (!AdminPermissions.BULK.check(admin)) return;
        if (slot != SLOT_KICK) confirmKick = false;

        switch (slot) {
            case SLOT_MESSAGE -> {
                admin.closeContainer();
                ChatListener.startBulkMessageSession(admin);
            }
            case SLOT_GATHER -> {
                if (!AdminPermissions.TELEPORT.check(admin)) return;
                if (!(admin.level() instanceof ServerLevel level)) return;
                int moved = 0;
                for (ServerPlayer target : SelectionManager.onlineTargets(admin)) {
                    if (target.getUUID().equals(admin.getUUID())) continue;
                    target.teleportTo(level, admin.getX(), admin.getY(), admin.getZ(),
                            admin.getYRot(), admin.getXRot());
                    SoundHelper.playAt(target, SoundHelper.TELEPORT);
                    moved++;
                }
                report("bulk.gathered", moved);
                AuditManager.recordServer(admin, AdminAction.BULK, "gather " + moved);
            }
            case SLOT_HEAL -> {
                if (!AdminPermissions.HEAL.check(admin)) return;
                int healed = 0;
                for (ServerPlayer target : SelectionManager.onlineTargets(admin)) {
                    target.setHealth(target.getMaxHealth());
                    target.getFoodData().setFoodLevel(20);
                    target.getFoodData().setSaturation(20.0F);
                    target.clearFire();
                    healed++;
                }
                report("bulk.healed", healed);
                AuditManager.recordServer(admin, AdminAction.BULK, "heal " + healed);
            }
            case SLOT_WARN -> {
                if (!AdminPermissions.WARN_EDIT.check(admin)) return;
                admin.closeContainer();
                ChatListener.startBulkWarnSession(admin);
            }
            case SLOT_KICK -> {
                if (!AdminPermissions.KICK.check(admin)) return;
                if (!confirmKick) {
                    confirmKick = true;
                    rebuild();
                    return;
                }
                confirmKick = false;
                int kicked = 0;
                for (ServerPlayer target : SelectionManager.onlineTargets(admin)) {
                    if (target.getUUID().equals(admin.getUUID())) continue;
                    target.connection.disconnect(Component.literal("§c"
                            + LanguageHelper.getText("misc.admin_action", target)));
                    kicked++;
                }
                report("bulk.kicked", kicked);
                AuditManager.recordServer(admin, AdminAction.BULK, "kick " + kicked);
                reload();
                rebuild();
            }
            case SLOT_CLEAR -> {
                if (button == 1) {
                    int n = SelectionManager.selectAllOnline(admin);
                    report("bulk.selected", n);
                } else {
                    SelectionManager.clear(admin.getUUID());
                }
                reload();
                rebuild();
            }
            default -> { }
        }
    }

    private void report(String key, int count) {
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText(key, admin).replace("%count%", String.valueOf(count))));
        SoundHelper.success(admin);
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
