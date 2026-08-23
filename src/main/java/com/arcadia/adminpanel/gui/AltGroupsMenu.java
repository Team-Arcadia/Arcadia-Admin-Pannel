package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.adminpanel.util.AltDetector;
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

import java.util.List;

/**
 * Accounts that connect from the same place, grouped.
 *
 * <p>Two levels: the overview lists every group, and clicking one opens its members. A group whose
 * members include a banned account is highlighted, because that is the case worth acting on.</p>
 *
 * <p>No address is shown anywhere, on any screen, in any tooltip. The grouping comes from a salted
 * hash and the panel deliberately has nothing else to display. See {@link AltDetector} for why.</p>
 *
 * @author vyrriox
 */
public class AltGroupsMenu extends PagedMenu {

    private final @Nullable Integer groupIndex;
    private List<List<AltDetector.Alt>> groups;
    private List<AltDetector.Alt> members;

    public static void open(ServerPlayer admin) {
        openGroup(admin, null);
    }

    public static void openGroup(ServerPlayer admin, @Nullable Integer index) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new AltGroupsMenu(id, inv, (ServerPlayer) p, index),
                PanelTitles.of(LanguageHelper.getText("alts.title", admin))));
    }

    public AltGroupsMenu(int id, Inventory playerInv, ServerPlayer admin, @Nullable Integer index) {
        super(id, playerInv, admin);
        this.groupIndex = index;
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        groups = server == null ? List.of() : AltDetector.groups(server);
        if (groupIndex != null && groupIndex >= 0 && groupIndex < groups.size()) {
            members = groups.get(groupIndex);
        } else {
            members = null;
        }
    }

    @Override
    protected int contentSize() {
        return members != null ? members.size() : groups.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "alts.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        if (members != null) {
            AltDetector.Alt alt = members.get(index);
            ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(alt.uuid(), alt.name()))
                    .name(Component.literal((alt.banned() ? "§c" : alt.online() ? "§a" : "§7") + alt.name()))
                    .addLore(Component.literal("§8" + LanguageHelper.getText(
                            alt.online() ? "player.online" : "player.offline", admin)));
            if (alt.banned()) {
                b.addLore(Component.literal("§c" + LanguageHelper.getText("info.banned", admin)));
            }
            if (alt.lastLoginMs() > 0) {
                b.addLore(Component.literal("§8" + LanguageHelper.getText("sessions.last", admin)
                        + " " + date(alt.lastLoginMs())));
            }
            this.getContainer().setItem(slot, b.build());
            return;
        }

        List<AltDetector.Alt> group = groups.get(index);
        boolean anyBanned = group.stream().anyMatch(AltDetector.Alt::banned);
        AltDetector.Alt head = group.get(0);

        ItemBuilder b = ItemBuilder.of(SkullCache.createSkull(head.uuid(), head.name()))
                .name(Component.literal((anyBanned ? "§c" : "§6")
                        + LanguageHelper.getText("alts.group", admin) + " #" + (index + 1)))
                .addLore(Component.literal("§7" + group.size() + " "
                        + LanguageHelper.getText("alts.accounts", admin)));
        int shown = 0;
        for (AltDetector.Alt alt : group) {
            if (shown++ >= 6) {
                b.addLore(Component.literal("§8..."));
                break;
            }
            b.addLore(Component.literal((alt.banned() ? "§c- " : "§7- ") + alt.name()));
        }
        if (anyBanned) {
            b.addLore(Component.literal("§c" + LanguageHelper.getText("alts.has_banned", admin)));
        }
        b.addLore(Component.literal("§8" + LanguageHelper.getText("alts.click", admin)));
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (members != null) {
            AltDetector.Alt alt = members.get(index);
            admin.closeContainer();
            PlayerDetailMenu.open(admin, alt.uuid(), alt.name(), alt.online());
            return;
        }
        admin.closeContainer();
        openGroup(admin, index);
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(47, ItemBuilder.of(Items.PAPER)
                .name(Component.literal("§8" + LanguageHelper.getText("alts.privacy.title", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("alts.privacy.line1", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("alts.privacy.line2", admin)))
                .build());
    }

    @Override
    protected void goBack() {
        if (members != null) open(admin);
        else StaffToolsMenu.open(admin);
    }
}
