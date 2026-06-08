package com.arcadia.adminpanel.client.screen;

import com.arcadia.lib.client.ArcadiaTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

/**
 * Admin Panel screen with ArcadiaTheme rendering and client-side search bar.
 *
 * @author vyrriox
 */
public class AdminPanelScreen extends ThemedContainerScreen {

    // Color-code stripper, compiled once. isFilteredOut() runs for up to 45 head slots every render
    // frame; compiling the pattern inline (replaceAll) re-looked it up on every call.
    private static final java.util.regex.Pattern COLOR_CODE =
            java.util.regex.Pattern.compile("§[0-9a-fk-or]");

    private EditBox searchBox;
    private String searchQuery = "";

    public AdminPanelScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        super.init();

        int searchWidth = 120;
        int searchX = this.leftPos + (this.imageWidth - searchWidth) / 2;
        int searchY = this.topPos - 16;

        searchBox = new EditBox(this.font, searchX, searchY, searchWidth, 14,
                Component.translatable("arcadiaadminpanel.search.placeholder"));
        searchBox.setMaxLength(32);
        searchBox.setBordered(true);
        searchBox.setVisible(true);
        searchBox.setTextColor(ArcadiaTheme.TEXT_PRIMARY);
        searchBox.setHint(Component.translatable("arcadiaadminpanel.search.placeholder"));
        searchBox.setResponder(query -> searchQuery = query.toLowerCase());

        this.addRenderableWidget(searchBox);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        super.renderBg(g, partialTick, mouseX, mouseY);

        if (searchBox != null) {
            int px = searchBox.getX() - 4;
            int py = searchBox.getY() - 3;
            int pw = searchBox.getWidth() + 8;
            int ph = searchBox.getHeight() + 6;
            ArcadiaTheme.drawPanel(g, px, py, pw, ph, searchBox.isFocused(), ArcadiaTheme.PATINA);
        }
    }

    /**
     * Returns true if {@code slot} should be hidden by the active search query. A slot is hidden
     * when (1) the slot belongs to the player-head grid (index &lt; 45), (2) it actually holds a
     * head, and (3) the name doesn't match. Filler glass panes and the bottom action row are
     * unaffected. Centralised so render + tooltip + click stay in sync.
     */
    private boolean isFilteredOut(Slot slot) {
        if (searchQuery.isEmpty()) return false;
        if (slot.index >= 45) return false;
        if (!slot.hasItem()) return false;
        var stack = slot.getItem();
        if (!stack.is(Items.PLAYER_HEAD)) return false;
        String name = COLOR_CODE.matcher(stack.getHoverName().getString().toLowerCase())
                .replaceAll("");
        return !name.contains(searchQuery);
    }

    @Override
    protected void renderSlot(@NotNull GuiGraphics g, @NotNull Slot slot) {
        if (isFilteredOut(slot)) {
            // Skip rendering entirely — no head, no name overlay, no count. Paint a flat fill so
            // the slot looks deliberately empty rather than darkened. Previously we drew a 0xCC
            // overlay ON TOP of the still-visible head which was unreadable when many heads were
            // filtered at once.
            g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0xFF161018);
            return;
        }
        super.renderSlot(g, slot);
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics g, int mouseX, int mouseY) {
        // Suppress the vanilla "hovered slot tooltip" when the slot is filtered out — otherwise
        // the player's name floats over the blank square and looks broken.
        if (this.hoveredSlot != null && isFilteredOut(this.hoveredSlot)) return;
        super.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == 256) {
                searchBox.setFocused(false);
                return true;
            }
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.hoveredSlot != null && isFilteredOut(this.hoveredSlot)) return false;
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
