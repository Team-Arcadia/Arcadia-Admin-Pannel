package com.vyrriox.arcadiaadminpanel.client.screen;

import com.arcadia.lib.client.ArcadiaTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import org.jetbrains.annotations.NotNull;

/**
 * Base themed container screen using ArcadiaTheme steampunk rendering.
 * Replaces the vanilla chest texture with copper-toned panels and slots.
 *
 * @author vyrriox
 */
public class ThemedContainerScreen<T extends ChestMenu> extends AbstractContainerScreen<T> {

    public ThemedContainerScreen(T menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageHeight = 222;
        this.imageWidth = 176;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Full-screen overlay
        g.fill(0, 0, this.width, this.height, ArcadiaTheme.OVERLAY_BG);

        // Themed container background
        ArcadiaTheme.drawContainerBg(g, this.leftPos, this.topPos, this.imageWidth, 6);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics g, int mouseX, int mouseY) {
        // Render title centered with ArcadiaTheme styling
        ArcadiaTheme.drawTitleBar(g, this.title, this.imageWidth / 2, 4, this.imageWidth - 16);
    }
}
