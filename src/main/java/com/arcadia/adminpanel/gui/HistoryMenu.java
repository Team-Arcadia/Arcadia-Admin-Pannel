package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.staff.StaffActions;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.adminpanel.util.AdminAction;
import com.arcadia.adminpanel.util.AuditManager;
import com.arcadia.adminpanel.util.BanManager;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.RecordStore;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.WarnManager;
import com.arcadia.adminpanel.util.WarnPolicy;
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
 * One player's complete moderation history: warns, mutes, jails, kicks and bans on a single
 * timeline.
 *
 * <p>Before this, answering "how many chances has this person had" meant opening the warn list, then
 * checking the jail list, then reading the ban file, then asking in the staff channel whether anyone
 * remembered a mute. The three sanction stores are merged here and sorted by date, newest first,
 * with a summary tile that gives the counts at a glance.</p>
 *
 * <p>Warns come from the warn manager (which owns their expiry), everything else from the sanction
 * store. Currently-active sanctions are marked, so a jail from last year does not read like a jail
 * in progress.</p>
 *
 * @author vyrriox
 */
public class HistoryMenu extends PagedMenu {

    private static final int SLOT_SUMMARY = 47;

    private record Row(long time, AdminAction action, String by, String reason,
                       long durationMs, String server, boolean active) {}

    private final UUID target;
    private final String targetName;
    private List<Row> rows = new ArrayList<>();
    private int warnCount;
    private int sanctionCount;

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new HistoryMenu(id, inv, (ServerPlayer) p, target, targetName),
                PanelTitles.of(LanguageHelper.getText("history.title", admin) + ": " + targetName)));
    }

    public HistoryMenu(int id, Inventory playerInv, ServerPlayer admin, UUID target, String targetName) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        reload();
        rebuild();
    }

    private void reload() {
        MinecraftServer server = admin.getServer();
        List<Row> out = new ArrayList<>();

        for (WarnManager.WarnEntry w : WarnManager.getInstance().getWarns(target)) {
            out.add(new Row(w.timestamp(), AdminAction.WARN, w.by(), w.reason(), 0L,
                    w.serverId(), WarnPolicy.isActive(w)));
        }
        warnCount = out.size();

        for (RecordStore.Entry<AuditManager.AuditEntry> e : AuditManager.sanctionsFor(target)) {
            AdminAction action = AdminAction.byId(e.payload().action());
            if (action == null || action == AdminAction.WARN) continue;
            out.add(new Row(e.createdAt(), action, e.payload().actorName(), e.payload().detail(),
                    e.payload().durationMs(), e.serverId(), isStillActive(server, action)));
        }
        sanctionCount = out.size() - warnCount;

        out.sort(Comparator.comparingLong(Row::time).reversed());
        rows = out;
    }

    /** A sanction type counts as active when the live state says so, not because it is recent. */
    private boolean isStillActive(MinecraftServer server, AdminAction action) {
        return switch (action) {
            case MUTE -> StaffActions.isMuted(target);
            case JAIL -> JailManager.getInstance().isJailed(target);
            case BAN, TEMPBAN -> server != null && BanManager.isBanned(server, target, targetName);
            default -> false;
        };
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "history.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        Row r = rows.get(index);
        String label = LanguageHelper.getText(r.action().labelKey(), admin);
        ItemBuilder b = ItemBuilder.of(r.action().icon())
                .name(Component.literal((r.active() ? "§c" : "§7") + label));

        if (r.active()) {
            b.addLore(Component.literal("§c" + LanguageHelper.getText("history.active", admin)));
        }
        b.addLore(Component.literal("§7" + LanguageHelper.getText("audit.by", admin) + " §e" + r.by()));
        if (r.reason() != null && !r.reason().isBlank()) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("mute.reason", admin)
                    + " §f" + r.reason()));
        }
        if (r.durationMs() > 0) {
            b.addLore(Component.literal("§7" + LanguageHelper.getText("audit.duration", admin)
                    + " §f" + TextFormatter.formatMs(r.durationMs())));
        }
        b.addLore(Component.literal("§8" + date(r.time())));
        if (r.server() != null && !r.server().isBlank()) {
            b.addLore(Component.literal("§8" + r.server()));
        }
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        // Read-only by design: removing a sanction is done from the tool that owns it, so the
        // history can never disagree with the live state.
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(SkullCache.createSkull(target, targetName))
                .name(Component.literal("§6" + targetName))
                .addLore(Component.literal("§7" + LanguageHelper.getText("history.warns", admin)
                        + " §f" + warnCount))
                .addLore(Component.literal("§7" + LanguageHelper.getText("history.sanctions", admin)
                        + " §f" + sanctionCount))
                .addLore(Component.literal("§7" + LanguageHelper.getText("history.total", admin)
                        + " §f" + rows.size()))
                .build());

        this.getContainer().setItem(51, ItemBuilder.of(Items.WRITABLE_BOOK)
                .name(Component.literal("§b" + LanguageHelper.getText("history.audit", admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("history.audit.hint", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (slot != 51) return;
        if (!com.arcadia.adminpanel.util.AdminPermissions.AUDIT.check(admin)) return;
        admin.closeContainer();
        AuditLogMenu.open(admin, target, null, targetName);
    }

    @Override
    protected void goBack() {
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerDetailMenu.open(admin, target, targetName, online);
    }
}
