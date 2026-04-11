package com.vyrriox.arcadiaadminpanel.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Themed screen for warn list view.
 *
 * @author vyrriox
 */
public class WarnListScreen extends ThemedContainerScreen {

    public WarnListScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
