package com.vyrriox.arcadiaadminpanel.client.screen;

import com.vyrriox.arcadiaadminpanel.gui.WarnListMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Themed screen for warn list view.
 *
 * @author vyrriox
 */
public class WarnListScreen extends ThemedContainerScreen<WarnListMenu> {

    public WarnListScreen(WarnListMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }
}
