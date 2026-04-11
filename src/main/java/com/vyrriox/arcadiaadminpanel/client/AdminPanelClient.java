package com.vyrriox.arcadiaadminpanel.client;

import com.vyrriox.arcadiaadminpanel.client.screen.AdminPanelScreen;
import com.vyrriox.arcadiaadminpanel.client.screen.PlayerDetailScreen;
import com.vyrriox.arcadiaadminpanel.client.screen.WarnListScreen;
import com.vyrriox.arcadiaadminpanel.gui.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-side event handler — registers themed screens for custom menu types.
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AdminPanelClient {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.ADMIN_PANEL.get(), AdminPanelScreen::new);
        event.register(ModMenuTypes.PLAYER_DETAIL.get(), PlayerDetailScreen::new);
        event.register(ModMenuTypes.WARN_LIST.get(), WarnListScreen::new);
    }

    private AdminPanelClient() {}
}
