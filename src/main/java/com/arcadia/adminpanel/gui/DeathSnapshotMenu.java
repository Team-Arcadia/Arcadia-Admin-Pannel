package com.arcadia.adminpanel.gui;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.DeathSnapshotManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's recent deaths, with what they were carrying, and a way to give it back.
 *
 * <p>The list view shows one row per death: when, how, where, and how many stacks. Opening a row
 * shows the actual items, which is what makes the difference between believing a support claim and
 * verifying it. Restoring hands everything back, to a connected player directly and to a
 * disconnected one by filling the empty slots of their stored inventory.</p>
 *
 * <p>Restore asks for a second click, and is audited with the stack count.</p>
 *
 * @author vyrriox
 */
public class DeathSnapshotMenu extends PagedMenu {

    private static final int SLOT_SUMMARY = 46;
    private static final int SLOT_RESTORE = 47;

    private final UUID target;
    private final String targetName;
    private final @Nullable Integer viewing;

    private List<DeathSnapshotManager.Snapshot> snapshots = new ArrayList<>();
    private boolean loading = true;
    private boolean confirmRestore = false;

    public static void open(ServerPlayer admin, UUID target, String targetName) {
        openSnapshot(admin, target, targetName, null);
    }

    public static void openSnapshot(ServerPlayer admin, UUID target, String targetName,
                                    @Nullable Integer index) {
        admin.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new DeathSnapshotMenu(id, inv, (ServerPlayer) p, target, targetName, index),
                PanelTitles.of(LanguageHelper.getText("deaths.title", admin) + ": " + targetName)));
    }

    public DeathSnapshotMenu(int id, Inventory playerInv, ServerPlayer admin,
                             UUID target, String targetName, @Nullable Integer index) {
        super(id, playerInv, admin);
        this.target = target;
        this.targetName = targetName;
        this.viewing = index;
        load();
    }

    private void load() {
        MinecraftServer server = admin.getServer();
        if (server == null) {
            loading = false;
            rebuild();
            return;
        }
        rebuild();
        DeathSnapshotManager.loadAsync(server, target, list -> {
            snapshots = list;
            loading = false;
            // Rebuilt unconditionally. When the list is already cached this callback runs inside the
            // constructor, before openMenu() has assigned containerMenu — and the old
            // "containerMenu == this" guard threw away the only render the screen would ever get,
            // which is why the death history looked empty for anyone who had died this session.
            rebuild();
        });
    }

    @Nullable
    private DeathSnapshotManager.Snapshot current() {
        if (viewing == null || viewing < 0 || viewing >= snapshots.size()) return null;
        return snapshots.get(viewing);
    }

    // -- Content -------------------------------------------------------------

    @Override
    protected int contentSize() {
        DeathSnapshotManager.Snapshot snap = current();
        return snap != null ? snap.items().length : snapshots.size();
    }

    @Override
    protected String emptyMessageKey() {
        return loading ? "deaths.loading" : "deaths.empty";
    }

    @Override
    protected void renderEntry(int index, int slot) {
        DeathSnapshotManager.Snapshot snap = current();
        if (snap != null) {
            if (index >= snap.items().length) return;
            ItemStack stack = snap.items()[index];
            if (stack == null || stack.isEmpty()) return;
            // Laid out the way an inventory screen is laid out — bag, then hotbar, then armour and
            // off-hand — instead of a flat 41-slot run that put the hotbar on the top row and the
            // helmet in the middle of the grid. Staff read what they were carrying, not an array.
            this.getContainer().setItem(layoutSlot(index), stack.copy());
            return;
        }

        DeathSnapshotManager.Snapshot row = snapshots.get(index);
        this.getContainer().setItem(slot, ItemBuilder.of(Items.SKELETON_SKULL)
                .name(Component.literal("§c" + LanguageHelper.getText("deaths.entry", admin)
                        + " #" + (index + 1)))
                .addLore(Component.literal("§8" + date(row.time())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.cause", admin)
                        + " §f" + row.cause()))
                .addLore(Component.literal("§7" + shortDim(row.dimension()) + " §8"
                        + (int) row.x() + ", " + (int) row.y() + ", " + (int) row.z()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.items", admin)
                        + " §f" + row.itemCount()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.xp", admin)
                        + " §f" + row.xpLevel()))
                .addLore(Component.literal("§a" + LanguageHelper.getText("deaths.click_open", admin)))
                .build());
    }

    @Override
    protected void onEntryClick(int index, int button, ClickType clickType) {
        if (current() != null) return; // viewing items: read-only
        if (index >= snapshots.size()) return;
        admin.closeContainer();
        openSnapshot(admin, target, targetName, index);
    }

    // -- Controls ------------------------------------------------------------

    @Override
    protected void renderExtraControls() {
        DeathSnapshotManager.Snapshot snap = current();
        if (snap == null) return;

        // Which death you are looking at, kept on screen. Opening a row used to replace the list
        // with an unlabelled grid of items and no way to tell one death from another.
        this.getContainer().setItem(SLOT_SUMMARY, ItemBuilder.of(Items.SKELETON_SKULL)
                .name(Component.literal("§c" + LanguageHelper.getText("deaths.entry", admin)
                        + " #" + (viewing + 1) + " §8/ " + snapshots.size()))
                .addLore(Component.literal("§8" + date(snap.time())))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.cause", admin)
                        + " §f" + snap.cause()))
                .addLore(Component.literal("§7" + shortDim(snap.dimension()) + " §8"
                        + (int) snap.x() + ", " + (int) snap.y() + ", " + (int) snap.z()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.items", admin)
                        + " §f" + snap.itemCount()))
                .addLore(Component.literal("§7" + LanguageHelper.getText("deaths.xp", admin)
                        + " §f" + snap.xpLevel()))
                .addLore(Component.literal("§8" + LanguageHelper.getText("deaths.layout", admin)))
                .build());

        if (!AdminPermissions.DEATH_RESTORE.check(admin)) return;

        this.getContainer().setItem(SLOT_RESTORE, ItemBuilder.of(Items.TOTEM_OF_UNDYING)
                .name(Component.literal("§a" + LanguageHelper.getText("deaths.restore", admin)))
                .addLore(Component.literal("§7" + snap.itemCount() + " "
                        + LanguageHelper.getText("deaths.stacks", admin)))
                .addLore(Component.literal(confirmRestore
                        ? "§c" + LanguageHelper.getText("misc.confirm", admin)
                        : "§8" + LanguageHelper.getText("deaths.restore.hint", admin)))
                .build());
    }

    @Override
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {
        if (slot != SLOT_RESTORE) return;
        if (!AdminPermissions.DEATH_RESTORE.check(admin)) return;
        DeathSnapshotManager.Snapshot snap = current();
        if (snap == null) return;

        if (!confirmRestore) {
            confirmRestore = true;
            rebuild();
            return;
        }
        confirmRestore = false;

        MinecraftServer server = admin.getServer();
        if (server == null) return;
        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(target);

        if (targetPlayer != null) {
            int given = DeathSnapshotManager.restoreOnline(admin, targetPlayer, snap);
            admin.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("deaths.restored", admin)
                            .replace("%count%", String.valueOf(given))
                            .replace("%player%", targetName)));
            SoundHelper.success(admin);
            rebuild();
            return;
        }

        DeathSnapshotManager.restoreOffline(admin, server, target, targetName, snap, result -> {
            int restored = result[0];
            int skipped = result[1];
            if (restored == 0) {
                admin.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("deaths.restore_failed", admin)
                                .replace("%player%", targetName)));
                SoundHelper.error(admin);
                return;
            }
            admin.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("deaths.restored", admin)
                            .replace("%count%", String.valueOf(restored))
                            .replace("%player%", targetName)));
            if (skipped > 0) {
                admin.sendSystemMessage(ArcadiaMessages.warning(
                        LanguageHelper.getText("deaths.restore_partial", admin)
                                .replace("%count%", String.valueOf(skipped))));
            }
            SoundHelper.success(admin);
        });
    }

    @Override
    protected void goBack() {
        if (current() != null) {
            open(admin, target, targetName);
            return;
        }
        boolean online = admin.getServer() != null
                && admin.getServer().getPlayerList().getPlayer(target) != null;
        PlayerDetailMenu.open(admin, target, targetName, online);
    }

    /**
     * Maps a dense inventory index onto the slot it should occupy in the 45-slot content grid:
     * three rows of bag, one row of hotbar, then armour (helmet to boots) and the off-hand.
     */
    private static int layoutSlot(int index) {
        if (index < 9) return 27 + index;            // hotbar, its own row under the bag
        if (index < 36) return index - 9;            // main inventory, rows one to three
        if (index < 40) return 38 + (39 - index);    // armour: helmet, chest, legs, boots
        return 43;                                    // off-hand, set apart from the armour
    }

    private static String shortDim(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
