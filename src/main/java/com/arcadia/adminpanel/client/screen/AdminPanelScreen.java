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

    @Override
    protected void renderSlot(@NotNull GuiGraphics g, @NotNull Slot slot) {
        super.renderSlot(g, slot);

        if (!searchQuery.isEmpty() && slot.index < 45 && slot.hasItem()) {
            var stack = slot.getItem();
            if (stack.is(Items.PLAYER_HEAD)) {
                String name = stack.getHoverName().getString().toLowerCase()
                        .replaceAll("§[0-9a-fk-or]", "");
                if (!name.contains(searchQuery)) {
                    g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0xCC0A0810);
                }
            }
        }
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
        if (!searchQuery.isEmpty() && this.hoveredSlot != null && this.hoveredSlot.index < 45
                && this.hoveredSlot.hasItem()) {
            var stack = this.hoveredSlot.getItem();
            if (stack.is(Items.PLAYER_HEAD)) {
                String name = stack.getHoverName().getString().toLowerCase()
                        .replaceAll("§[0-9a-fk-or]", "");
                if (!name.contains(searchQuery)) {
                    return false;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
