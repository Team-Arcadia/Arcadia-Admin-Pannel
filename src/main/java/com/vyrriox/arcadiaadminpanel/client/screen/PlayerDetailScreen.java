package com.vyrriox.arcadiaadminpanel.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Themed screen for player detail view.
 *
 * @author vyrriox
 */
public class PlayerDetailScreen extends ThemedContainerScreen {

    public PlayerDetailScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
