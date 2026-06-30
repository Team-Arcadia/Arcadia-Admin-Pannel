package com.arcadia.adminpanel.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Themed screen for a single FTB Team's detail view.
 *
 * @author vyrriox
 */
public class TeamDetailScreen extends ThemedContainerScreen {

    public TeamDetailScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
