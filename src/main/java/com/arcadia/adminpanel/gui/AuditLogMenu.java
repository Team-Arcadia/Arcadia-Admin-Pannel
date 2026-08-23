package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.RecordStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The staff action log.
 *
 * <p>Three views over the same data, chosen by what the caller passes: everything, everything done
 * to one player, or everything done by one staff member. The third is the one that makes the log
 * worth keeping, because "who did this" is the question that actually comes up.</p>
 *
 * @author vyrriox
 */
public class AuditLogMenu extends PagedMenu {

    private static final int SLOT_FILTER = 47;
    private static final int SLOT_TOOLS = 51;
    private static final int VIEW_LIMIT = 450;

    private final @Nullable UUID targetFilter;
    private final @Nullable UUID actorFilter;
    private final @Nullable String targetName;
    private List<RecordStore.Entry<AuditManager.AuditEntry>> rows;

    /** Opens the log. Pass {@code null} for both filters to see everything. */
    public static void open(ServerPlayer admin, @Nullable UUID target, @Nullable UUID actor) {
        open(admin, target, actor, null);
    }

    public static void open(ServerPlayer admin, @Nullable UUID target, @Nullable UUID actor,
                            @Nullable String targetName) {
        String title = LanguageHelper.getText("audit.title", admin);
        if (targetName != null) title = title + ": " + targetName;
        final String finalTitle = title;
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new AuditLogMenu(id, inv, (ServerPlayer) p, target, actor, targetName),
                PanelTitles.of(finalTitle)));
    }

    public AuditLogMenu(int id, Inventory playerInv, ServerPlayer admin,
                        @Nullable UUID target, @Nullable UUID actor, @Nullable String targetName) {
        super(id, playerInv, admin);
        this.targetFilter = target;
        this.actorFilter = actor;
        this.targetName = targetName;
        reload();
        rebuild();
    }

    private void reload() {
        if (targetFilter != null) rows = AuditManager.forTarget(targetFilter);
        else if (actorFilter != null) rows = AuditManager.byActor(actorFilter, VIEW_LIMIT);
        else rows = AuditManager.recent(VIEW_LIMIT);
    }

    // -- Content -------------------------------------------------------------

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "audit.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        RecordStore.Entry<AuditManager.AuditEntry> row = rows.get(index);
        AuditManager.AuditEntry e = row.payload();
        AdminAction action = AdminAction.byId(e.action());

        String label = action != null
                ? LanguageHelper.getText(action.labelKey(), admin)
                : e.action();
        String colour = action != null && action.isSanction() ? "§c" : "§b";

        ItemBuilder b = ItemBuilder.of(action != null ? action.icon() : Items.PAPER)
                .name(Component.literal(colour + label + " §7- §f" + e.targetName()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("audit.by", admin)
                        + " §e" + e.actorName()))
                .addLore(Component.literal("§8" + date(row.createdAt())));

        if (e.durationMs() > 0) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("audit.duration", admin)
                    + " §f" + TextFormatter.formatMs(e.durationMs())));
        }
        if (e.detail() != null && !e.detail().isBlank()) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("audit.detail", admin)
                    + " §f" + trim(e.detail())));
        }
        if (e.silent()) {
            b.addLore(Component.literal("§8[" + LanguageHelper.getText("audit.silent", admin) + "]"));
        }
        b.addLore(Component.literal("§8" + row.serverId()));
        b.addLore(Component.literal("§8" + LanguageHelper.getText("audit.click_actor", admin)));

        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        RecordStore.Entry<AuditManager.AuditEntry> row = rows.get(index);
        AuditManager.AuditEntry e = row.payload();

        // Right click pivots the view onto the staff member who performed the action.
        if (button == 1 && !e.actorUuid().isEmpty()) {
            try {
                UUID actor = UUID.fromString(e.actorUuid());
                admin.closeContainer();
                open(admin, null, actor, e.actorName());
            } catch (IllegalArgumentException ignored) {
                // A console entry has no UUID; there is nothing to pivot to.
            }
            return;
        }

        // Left click opens the affected player's sheet.
        if (e.targetName() == null || e.targetName().isBlank()) return;
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(row.subject()) != null;
        admin.closeContainer();
        PlayerDetailMenu.open(admin, row.subject(), e.targetName(), online);
    }

    // -- Controls ------------------------------------------------------------

    @Override
    protected void renderExtraControls() {
        String scope = targetFilter != null ? "audit.scope.target"
                     : actorFilter != null ? "audit.scope.actor"
                     : "audit.scope.all";
        this.getContainer().setItem(SLOT_FILTER, ItemBuilder.of(Items.HOPPER)
                .name(Component.literal("§e" + LanguageHelper.getText("audit.filter", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText(scope, admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("audit.filter.clear", admin)))
                .build());

        this.getContainer().setItem(SLOT_TOOLS, ItemBuilder.of(Items.COMPASS)
                .name(Component.literal("§b" + LanguageHelper.getText("tools.title", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (!AdminPermissions.AUDIT.check(admin)) return;
        if (slot == SLOT_FILTER) {
            admin.closeContainer();
            open(admin, null, null, null);
        } else if (slot == SLOT_TOOLS) {
            admin.closeContainer();
            StaffToolsMenu.open(admin);
        }
    }

    @Override
    protected void goBack() {
        StaffToolsMenu.open(admin);
    }

    private static String trim(String s) {
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }
}
