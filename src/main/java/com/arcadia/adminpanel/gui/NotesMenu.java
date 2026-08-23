package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.NotesManager;
import com.arcadia.adminpanel.util.RecordStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Private staff notes on one player.
 *
 * <p>Add with the button (the panel closes, you type, it reopens), pin by starting the line with an
 * exclamation mark, delete by right-clicking a note. Nothing here is ever shown to the subject, and
 * nothing here counts toward the escalation ladder: that is what warns are for.</p>
 *
 * @author vyrriox
 */
public class NotesMenu extends PagedMenu {

    private static final int SLOT_ADD = 47;

    private final UUID target;
    private final String targetName;
    private List<RecordStore.Entry<NotesManager.Note>> rows;
    /** Row awaiting a delete confirmation, or -1. */
    private int pendingDelete = -1;

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new NotesMenu(id, inv, (ServerPlayer) p, target, targetName),
                PanelTitles.of(LanguageHelper.getText("notes.title", admin) + ": " + targetName)));
    }

    public NotesMenu(int id, Inventory playerInv, ServerPlayer admin, UUID target, String targetName) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        reload();
        rebuild();
    }

    private void reload() {
        rows = NotesManager.forPlayer(target);
    }

    @Override
    protected int contentSize() {
        return rows.size();
    }

    @Override
    protected String emptyMessageKey() {
        return "notes.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        var row = rows.get(index);
        NotesManager.Note note = row.payload();
        boolean confirming = index == pendingDelete;

        ItemBuilder b = ItemBuilder.of(note.pinned() ? Items.WRITTEN_BOOK : Items.PAPER)
                .name(Component.literal((note.pinned() ? "§6" : "§f") + wrapFirst(note.text())));
        for (String line : wrapRest(note.text())) {
            b.addLore(Component.literal("§7" + line));
        }
        b.addLore(Component.literal("§8" + LanguageHelper.getText("audit.by", admin)
                + " " + note.authorName() + " - " + date(row.createdAt())));
        if (note.pinned()) {
            b.addLore(Component.literal("§6" + LanguageHelper.getText("notes.pinned", admin)));
        }
        b.addLore(Component.literal(confirming
                ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                : "§8" + LanguageHelper.getText("notes.click_delete", admin)));
        this.getContainer().setItem(slot, b.build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (button != 1) return;
        if (!AdminPermissions.NOTES.check(admin)) return;

        if (pendingDelete != index) {
            pendingDelete = index;
            rebuild();
            return;
        }
        pendingDelete = -1;
        NotesManager.delete(admin, target, targetName, rows.get(index).id());
        SoundHelper.success(admin);
        reload();
        rebuild();
    }

    @Override
    protected void renderExtraControls() {
        this.getContainer().setItem(SLOT_ADD, ItemBuilder.of(Items.WRITABLE_BOOK)
                .name(Component.literal("§a" + LanguageHelper.getText("notes.add", admin)))
                .addLore(Component.literal("§7" + LanguageHelper.getText("notes.add.hint", admin)))
                .addLore(Component.literal("§8" + LanguageHelper.getText("notes.pin.hint", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (slot != SLOT_ADD) return;
        if (!AdminPermissions.NOTES.check(admin)) return;
        admin.closeContainer();
        ChatListener.startNoteSession(admin, target, targetName);
    }

    @Override
    protected void goBack() {
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerDetailMenu.open(admin, target, targetName, online);
    }

    // -- Text wrapping -------------------------------------------------------

    private static final int WRAP = 38;

    private static String wrapFirst(String text) {
        return text.length() <= WRAP ? text : text.substring(0, WRAP);
    }

    private static List<String> wrapRest(String text) {
        if (text.length() <= WRAP) return List.of();
        List<String> out = new java.util.ArrayList<>();
        int i = WRAP;
        while (i < text.length() && out.size() < 6) {
            int end = Math.min(i + WRAP, text.length());
            out.add(text.substring(i, end));
            i = end;
        }
        return out;
    }
}
