package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.adminpanel.util.ClientModsRegistry;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.SkullCache;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What connected clients report they are running.
 *
 * <p>The overview lists online players and whether they declared anything, with blacklist hits in
 * red. Opening a player shows their declared ids, flagged ones first.</p>
 *
 * <p>Every screen here labels the data as self-declared, because that is what it is. A client that
 * does not run this mod reports nothing and is shown as such rather than as clean.</p>
 *
 * @author vyrriox
 */
public class ClientModsMenu extends PagedMenu {

    private final @Nullable UUID subject;
    private final @Nullable String subjectName;
    private List<Row> rows = new ArrayList<>();
    private List<String> mods = List.of();

    private record Row(UUID uuid, String name, int modCount, boolean flagged, boolean reported) {}

    public static void openOverview(ServerPlayer admin) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ClientModsMenu(id, inv, (ServerPlayer) p, null, null),
                PanelTitles.of(LanguageHelper.getText("clientmods.title", admin))));
    }

    public static void openPlayer(ServerPlayer admin, UUID target, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new ClientModsMenu(id, inv, (ServerPlayer) p, target, targetName),
                PanelTitles.of(LanguageHelper.getText("clientmods.title", admin) + ": " + targetName)));
    }

    public ClientModsMenu(int id, Inventory playerInv, ServerPlayer admin,
                          @Nullable UUID subject, @Nullable String subjectName) {
        super(id, playerInv, admin);
        this.subject = subject;
        this.subjectName = subjectName;
        reload();
        rebuild();
    }

    private void reload() {
        if (subject != null) {
            ClientModsRegistry.Report report = ClientModsRegistry.get(subject);
            if (report == null) {
                mods = List.of();
                return;
            }
            List<String> ordered = new ArrayList<>(report.flagged());
            for (String id : report.mods()) {
                if (!ordered.contains(id)) ordered.add(id);
            }
            mods = ordered;
            return;
        }

        MinecraftServer server = admin.getServer();
        List<Row> out = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                int count = ClientModsRegistry.modCount(p.getUUID());
                out.add(new Row(p.getUUID(), p.getName().getString(),
                        Math.max(0, count), ClientModsRegistry.isFlagged(p.getUUID()), count >= 0));
            }
            out.sort((a, b) -> {
                if (a.flagged() != b.flagged()) return a.flagged() ? -1 : 1;
                return Integer.compare(b.modCount(), a.modCount());
            });
        }
        rows = out;
    }

    @Override
    protected int contentSize() {
        return subject != null ? mods.size() : rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return subject != null ? "clientmods.none" : "clientmods.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        if (subject != null) {
            String id = mods.get(index);
            ClientModsRegistry.Report report = ClientModsRegistry.get(subject);
            boolean flagged = report != null && report.flagged().contains(id);
            this.getContainer().setItem(slot, ItemBuilder
                    .of(flagged ? Items.REDSTONE_BLOCK : Items.PAPER)
                    .name(Component.literal((flagged ? "§c" : "§7") + id))
                    .addLore(Component.literal(flagged
                            ? "§c" + LanguageHelper.getText("clientmods.blacklisted", admin)
                            : "§8" + LanguageHelper.getText("clientmods.declared", admin)))
                    .build());
            return;
        }

        Row row = rows.get(index);
        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(row.uuid(), row.name()))
                .name(Component.literal((row.flagged() ? "§c" : "§a") + row.name()));
        if (!row.reported()) {
            b.addLore(Component.literal("§8" + LanguageHelper.getText("clientmods.no_report", admin)));
        } else {
            b.addLore(Component.literal("§7" + row.modCount() + " "
                    + LanguageHelper.getText("clientmods.mods", admin)));
            if (row.flagged()) {
                b.addLore(Component.literal("§c" + LanguageHelper.getText("clientmods.flagged_short", admin)));
            }
            b.addLore(Component.literal("§8" + LanguageHelper.getText("clientmods.click", admin)));
        }
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (subject != null) return;
        Row row = rows.get(index);
        if (!row.reported()) return;
        admin.closeContainer();
        openPlayer(admin, row.uuid(), row.name());
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(47, ItemBuilder.of(Items.PAPER)
                .name(Component.literal("§8" + LanguageHelper.getText("clientmods.disclaimer.title", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("clientmods.disclaimer.line1", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("clientmods.disclaimer.line2", admin)))
                .build());
    }

    @Override
    protected void goBack() {
        if (subject != null) openOverview(admin);
        else StaffToolsMenu.open(admin);
    }
}
