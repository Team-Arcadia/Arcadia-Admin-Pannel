package com.arcadia.adminpanel.client.screen;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Themed screen for the FTB Teams browser list — same ArcadiaTheme chrome as every other admin menu.
 *
 * @author vyrriox
 */
public class TeamListScreen extends ThemedContainerScreen {

    public TeamListScreen(ChestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
