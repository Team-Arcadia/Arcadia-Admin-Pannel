package com.arcadia.adminpanel.client;

import com.arcadia.adminpanel.client.screen.AdminPanelScreen;
import com.arcadia.adminpanel.client.screen.PlayerDetailScreen;
import com.arcadia.adminpanel.client.screen.TeamDetailScreen;
import com.arcadia.adminpanel.client.screen.TeamListScreen;
import com.arcadia.adminpanel.client.screen.WarnListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side event handler — intercepts vanilla chest screens and replaces
 * them with themed ArcadiaTheme screens based on title matching.
 * No custom MenuType registration needed (zero registry footprint).
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class AdminPanelClient {

    private static final String TITLE_PANEL_EN = "Admin Panel";
    private static final String TITLE_PANEL_FR = "Panneau Admin";
    private static final String TITLE_DETAIL_EN = "Player:";
    private static final String TITLE_DETAIL_FR = "Joueur:";
    private static final String TITLE_WARNS_EN = "Warns:";
    private static final String TITLE_WARNS_FR = "Avertissements";
    // Team browser titles (team.list.title / team.detail.title in LanguageHelper). The list title is
    // the same in EN and FR ("FTB Teams"); the detail title differs per language.
    private static final String TITLE_TEAM_LIST = "FTB Teams";
    private static final String TITLE_TEAM_DETAIL_EN = "Team Details";
    private static final String TITLE_TEAM_DETAIL_FR = "Détails de la Team";

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof AbstractContainerScreen<?> cs)) return;
        if (!(cs.getMenu() instanceof ChestMenu chestMenu)) return;

        String title = cs.getTitle().getString();

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var inv = mc.player.getInventory();

        if (title.equals(TITLE_PANEL_EN) || title.equals(TITLE_PANEL_FR)) {
            event.setNewScreen(new AdminPanelScreen(chestMenu, inv, cs.getTitle()));
            return;
        }

        if (title.startsWith(TITLE_DETAIL_EN) || title.startsWith(TITLE_DETAIL_FR)) {
            event.setNewScreen(new PlayerDetailScreen(chestMenu, inv, cs.getTitle()));
            return;
        }

        if (title.startsWith(TITLE_WARNS_EN) || title.startsWith(TITLE_WARNS_FR)) {
            event.setNewScreen(new WarnListScreen(chestMenu, inv, cs.getTitle()));
            return;
        }

        if (title.equals(TITLE_TEAM_LIST)) {
            event.setNewScreen(new TeamListScreen(chestMenu, inv, cs.getTitle()));
            return;
        }

        if (title.equals(TITLE_TEAM_DETAIL_EN) || title.equals(TITLE_TEAM_DETAIL_FR)) {
            event.setNewScreen(new TeamDetailScreen(chestMenu, inv, cs.getTitle()));
        }
    }

    private AdminPanelClient() {}
}
