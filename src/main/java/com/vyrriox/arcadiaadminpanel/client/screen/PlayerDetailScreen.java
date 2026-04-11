package com.vyrriox.arcadiaadminpanel.client.screen;

import com.vyrriox.arcadiaadminpanel.gui.PlayerDetailMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Themed screen for player detail view.
 *
 * @author vyrriox
 */
public class PlayerDetailScreen extends ThemedContainerScreen<PlayerDetailMenu> {

    public PlayerDetailScreen(PlayerDetailMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
