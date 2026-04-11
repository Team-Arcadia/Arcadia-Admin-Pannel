package com.vyrriox.arcadiaadminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.lib.util.SoundHelper;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Warn list menu — displays all warnings for a player.
 *
 * @author vyrriox
 */
public class WarnListMenu extends ChestMenu {

    private final ServerPlayer admin;
    private final UUID targetUUID;
    private final String targetName;
    private int page = 0;
    private static final int ITEMS_PER_PAGE = 45;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public static void open(ServerPlayer admin, UUID targetUUID, String targetName) {
        admin.openMenu(new SimpleMenuProvider(
                (id, playerInv, player) -> new WarnListMenu(id, playerInv, (ServerPlayer) player,
                        targetUUID, targetName),
                Component.literal(String.format(LanguageHelper.getText("warn.list.title", admin), targetName))
        ), buf -> {
            buf.writeUtf(targetName);
            buf.writeLong(targetUUID.getMostSignificantBits());
            buf.writeLong(targetUUID.getLeastSignificantBits());
        });
    }

    /** Server constructor. */
    public WarnListMenu(int id, Inventory playerInv, ServerPlayer admin,
                        UUID targetUUID, String targetName) {
        super(ModMenuTypes.WARN_LIST.get(), id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
        buildMenu();
    }

    /** Client constructor with data. */
    public WarnListMenu(int id, Inventory playerInv, UUID targetUUID, String targetName) {
        super(ModMenuTypes.WARN_LIST.get(), id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.targetUUID = targetUUID;
        this.targetName = targetName;
    }

    /** Client fallback constructor. */
    public WarnListMenu(int id, Inventory playerInv) {
        super(ModMenuTypes.WARN_LIST.get(), id, playerInv, new SimpleContainer(54), 6);
        this.admin = null;
        this.targetUUID = UUID.randomUUID();
        this.targetName = "Unknown";
    }

    private void buildMenu() {
        if (admin == null) return;

        var filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE).name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) {
            this.getContainer().setItem(i, filler.copy());
        }

        List<WarnManager.WarnEntry> warns = WarnManager.getInstance().getWarns(targetUUID);
        List<WarnManager.WarnEntry> sortedWarns = new ArrayList<>(warns);
        sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

        if (sortedWarns.isEmpty()) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§c" + LanguageHelper.getText("warn.list.empty", admin))).build());
        } else {
            int start = page * ITEMS_PER_PAGE;
            int end = Math.min(start + ITEMS_PER_PAGE, sortedWarns.size());

            for (int i = start; i < end; i++) {
                WarnManager.WarnEntry warn = sortedWarns.get(i);
                int slot = i - start;
                String title = String.format(LanguageHelper.getText("warn.item.title", admin), sortedWarns.size() - i);

                var builder = ItemBuilder.of(Items.PAPER)
                        .name(Component.literal("§c" + title))
                        .addLore(Component.literal("§7" + String.format(
                                LanguageHelper.getText("warn.item.by", admin), "§e" + warn.by())))
                        .addLore(Component.literal("§7" + String.format(
                                LanguageHelper.getText("warn.item.date", admin),
                                "§f" + DATE_FORMAT.format(new Date(warn.timestamp())))))
                        .addLore(Component.literal(" "))
                        .addLore(Component.literal("§7" + String.format(
                                LanguageHelper.getText("warn.item.reason", admin), "§c" + warn.reason())));

                // Show server origin if multi-server
                if (warn.serverId() != null && !warn.serverId().isEmpty()) {
                    builder.addLore(Component.literal("§8Server: " + warn.serverId()));
                }

                // Delete hint for admins
                if (!admin.getUUID().equals(targetUUID)) {
                    builder.addLore(Component.literal(" "));
                    builder.addLore(Component.literal("§e" + LanguageHelper.getText("warn.click_delete", admin)));
                }

                this.getContainer().setItem(slot, builder.build());
            }
        }

        // Pagination
        if (page > 0) {
            this.getContainer().setItem(45, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e<< " + LanguageHelper.getText("nav.previous", admin))).build());
        }
        if ((page + 1) * ITEMS_PER_PAGE < sortedWarns.size()) {
            this.getContainer().setItem(53, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>")).build());
        }

        // Back button (slot 49)
        if (!admin.getUUID().equals(targetUUID)) {
            this.getContainer().setItem(49, ItemBuilder.of(Items.OAK_DOOR)
                    .name(Component.literal("§e" + LanguageHelper.getText("action.back", admin))).build());
        }
    }

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        var clicked = this.getContainer().getItem(slotId);
        if (clicked.isEmpty() || clicked.is(Items.GRAY_STAINED_GLASS_PANE)) return;

        if (slotId == 49) {
            boolean online = sp.getServer().getPlayerList().getPlayer(targetUUID) != null;
            sp.closeContainer();
            PlayerDetailMenu.open(sp, targetUUID, targetName, online);
            return;
        }

        if (slotId == 45 && page > 0) {
            page--;
            buildMenu();
            return;
        }
        if (slotId == 53) {
            page++;
            buildMenu();
            return;
        }

        // Delete warn on click (admin only)
        if (!sp.getUUID().equals(targetUUID) && slotId >= 0 && slotId < ITEMS_PER_PAGE) {
            List<WarnManager.WarnEntry> warns = WarnManager.getInstance().getWarns(targetUUID);
            List<WarnManager.WarnEntry> sortedWarns = new ArrayList<>(warns);
            sortedWarns.sort((w1, w2) -> Long.compare(w2.timestamp(), w1.timestamp()));

            int absoluteIndex = page * ITEMS_PER_PAGE + slotId;
            if (absoluteIndex < sortedWarns.size()) {
                boolean success = WarnManager.getInstance().removeWarn(targetUUID, absoluteIndex + 1);
                if (success) {
                    SoundHelper.playAt(sp, SoundHelper.CLICK);
                    sp.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.success(
                            LanguageHelper.getText("warn.deleted", sp)
                                    .replace("%d", String.valueOf(absoluteIndex + 1))
                                    .replace("%s", targetName)));
                    buildMenu();
                }
            }
        }
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
