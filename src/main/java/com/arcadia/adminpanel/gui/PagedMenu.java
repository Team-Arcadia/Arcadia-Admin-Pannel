package com.arcadia.adminpanel.gui;

import com.arcadia.lib.item.ItemBuilder;
import com.arcadia.adminpanel.util.LanguageHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

/**
 * Shared skeleton for the list-shaped menus added in 1.3.0.
 *
 * <p>Every one of them is the same screen: up to forty-five rows of content, a control strip along
 * the bottom, a back button, and pagination that only appears when it is needed. Writing that
 * fifteen times would have meant fifteen chances to get the click dispatch subtly wrong, and the
 * click dispatch is where a permission bypass hides.</p>
 *
 * <p>Two invariants the subclasses inherit for free:</p>
 * <ul>
 *   <li><b>Navigation is never gated.</b> Back and pagination are handled before any subclass code
 *       runs, so a permission that flushes mid-session can never trap a staff member inside a
 *       server-side container. This repeats a lesson from 1.2.6.</li>
 *   <li><b>Nothing can be taken out.</b> Shift-click transfer is disabled and the container is
 *       display-only unless a subclass explicitly opts in.</li>
 * </ul>
 *
 * @author vyrriox
 */
public abstract class PagedMenu extends ChestMenu {

    protected static final int PER_PAGE = 45;
    protected static final int SLOT_PREV = 45;
    protected static final int SLOT_NEXT = 53;
    protected static final int SLOT_BACK = 49;

    protected final ServerPlayer admin;
    protected int page = 0;

    protected PagedMenu(int id, Inventory playerInv, ServerPlayer admin) {
        super(MenuType.GENERIC_9x6, id, playerInv, new SimpleContainer(54), 6);
        this.admin = admin;
    }

    // -- Subclass contract ---------------------------------------------------

    /** Total number of content entries across every page. */
    protected abstract int contentSize();

    /** Places the item for content entry {@code index} into container slot {@code slot}. */
    protected abstract void renderEntry(int index, int slot);

    /** Handles a click on content entry {@code index}. */
    protected abstract void onEntryClick(int index, int button, ClickType clickType);

    /** Where the back button leads. Default closes the container. */
    protected void goBack() {
        admin.closeContainer();
    }

    /** Extra buttons on the control strip (slots 46-52). Override to add some. */
    protected void renderExtraControls() {}

    /** Clicks on the control strip that are not back or pagination. */
    protected void onExtraControlClick(int slot, int button, ClickType clickType) {}

    /** Lore line shown when the list is empty. Override for a friendlier message. */
    protected String emptyMessageKey() {
        return "list.empty";
    }

    // -- Rendering -----------------------------------------------------------

    protected final void rebuild() {
        if (admin == null) return;

        ItemStack filler = ItemBuilder.of(Items.GRAY_STAINED_GLASS_PANE)
                .name(Component.literal(" ")).build();
        for (int i = 0; i < 54; i++) this.getContainer().setItem(i, filler.copy());

        int total = contentSize();
        int maxPage = Math.max(0, (total - 1) / PER_PAGE);
        if (page > maxPage) page = maxPage;
        if (page < 0) page = 0;

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, total);
        for (int i = start; i < end; i++) renderEntry(i, i - start);

        if (total == 0) {
            this.getContainer().setItem(22, ItemBuilder.of(Items.BARRIER)
                    .name(Component.literal("§7" + LanguageHelper.getText(emptyMessageKey(), admin)))
                    .build());
        }

        if (page > 0) {
            this.getContainer().setItem(SLOT_PREV, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e<< " + LanguageHelper.getText("nav.previous", admin)))
                    .addLore(Component.literal("§7" + (page + 1) + " / " + (maxPage + 1)))
                    .build());
        }
        if (end < total) {
            this.getContainer().setItem(SLOT_NEXT, ItemBuilder.of(Items.ARROW)
                    .name(Component.literal("§e" + LanguageHelper.getText("nav.next", admin) + " >>"))
                    .addLore(Component.literal("§7" + (page + 1) + " / " + (maxPage + 1)))
                    .build());
        }

        this.getContainer().setItem(SLOT_BACK, ItemBuilder.of(Items.BARRIER)
                .name(Component.literal("§c" + LanguageHelper.getText("action.back", admin)))
                .build());

        renderExtraControls();
        this.broadcastChanges();
    }

    // -- Click dispatch ------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, @NotNull ClickType clickType, @NotNull Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;

        // Navigation first and unconditionally: a permission change mid-session must never leave
        // somebody stuck in a container with no way out.
        if (slotId == SLOT_BACK) {
            sp.closeContainer();
            goBack();
            return;
        }
        if (slotId == SLOT_PREV) {
            if (page > 0) { page--; rebuild(); }
            return;
        }
        if (slotId == SLOT_NEXT) {
            if ((page + 1) * PER_PAGE < contentSize()) { page++; rebuild(); }
            return;
        }
        if (slotId >= 46 && slotId <= 52) {
            onExtraControlClick(slotId, button, clickType);
            return;
        }

        if (slotId < 0 || slotId >= PER_PAGE) return;
        int index = page * PER_PAGE + slotId;
        if (index >= contentSize()) return;
        onEntryClick(index, button, clickType);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Formats an epoch timestamp the way every panel list shows dates. */
    protected static String date(long epochMs) {
        return new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date(epochMs));
    }
}
