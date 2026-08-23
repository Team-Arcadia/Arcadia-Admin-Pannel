package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AfkTracker;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.BackManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.VanishManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * Who is around, sorted by distance.
 *
 * <p>Answers the question a staff member asks after arriving at a report: who else is here, and how
 * far. Only the current dimension is scanned, since a distance across dimensions is meaningless.
 * Vanished staff appear only to those allowed to see them, matching what the world itself shows.</p>
 *
 * @author vyrriox
 */
public class RadarMenu extends PagedMenu {

    private record Contact(UUID uuid, String name, int distance, double x, double y, double z,
                           boolean afk, boolean vanished) {}

    private List<Contact> contacts = List.of();

    public static void open(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new RadarMenu(id, inv, (ServerPlayer) p),
                PanelTitles.of(LanguageHelper.getText("radar.title", admin))));
    }

    public RadarMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(id, playerInv, admin);
        reload();
        rebuild();
    }

    private void reload() {
        if (!(admin.level() instanceof ServerLevel level)) return;
        int radius = Math.max(16, AdminConfig.get().radarRadius);
        double radiusSq = (double) radius * radius;
        boolean seeVanished = VanishManager.canSee(admin);

        List<Contact> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.getUUID().equals(admin.getUUID())) continue;
            boolean vanished = VanishManager.isVanished(p.getUUID());
            if (vanished && !seeVanished) continue;
            double dSq = p.distanceToSqr(admin);
            if (dSq > radiusSq) continue;
            out.add(new Contact(p.getUUID(), p.getName().getString(), (int) Math.sqrt(dSq),
                    p.getX(), p.getY(), p.getZ(), AfkTracker.isAfk(p.getUUID()), vanished));
        }
        out.sort(Comparator.comparingInt(Contact::distance));
        contacts = out;
    }

    @Override
    protected int contentSize() {
        return contacts.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "radar.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        Contact c = contacts.get(index);
        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(c.uuid(), c.name()))
                .name(Component.literal((c.vanished() ? "§8" : c.afk() ? "§7" : "§a") + c.name()))
                .addLore(Component.literal("§e" + c.distance() + " "
                        + LanguageHelper.getText("radar.blocks", admin)))
                .addLore(Component.literal("§8" + (int) c.x() + ", " + (int) c.y() + ", " + (int) c.z()));
        if (c.afk()) b.addLore(Component.literal("§8" + LanguageHelper.getText("perf.afk", admin)));
        if (c.vanished()) b.addLore(Component.literal("§8" + LanguageHelper.getText("radar.vanished", admin)));
        if (AdminPermissions.TELEPORT.check(admin)) {
            b.addLore(Component.literal("§a" + LanguageHelper.getText("misc.click_tp", admin)));
        }
        b.addLore(Component.literal("§8" + LanguageHelper.getText("radar.click_detail", admin)));
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        Contact c = contacts.get(index);
        if (button == 1) {
            admin.closeContainer();
            PlayerDetailMenu.open(admin, c.uuid(), c.name(), true);
            return;
        }
        if (!AdminPermissions.TELEPORT.check(admin)) return;
        if (!(admin.level() instanceof ServerLevel level)) return;
        BackManager.push(admin);
        admin.closeContainer();
        admin.teleportTo(level, c.x(), c.y(), c.z(), admin.getYRot(), admin.getXRot());
        SoundHelper.playAt(admin, SoundHelper.TELEPORT);
        AuditManager.record(admin, AdminAction.TELEPORT, c.uuid(), c.name(), "radar");
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(47, ItemBuilder.of(Items.SPYGLASS)
                .name(Component.literal("§b" + LanguageHelper.getText("radar.range", admin)))
                .addLore(Component.literal("§7" + AdminConfig.get().radarRadius + " "
                        + LanguageHelper.getText("radar.blocks", admin)))
                .build());
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }
}
