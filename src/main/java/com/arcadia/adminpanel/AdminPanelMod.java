package com.arcadia.adminpanel;

import com.arcadia.lib.ArcadiaModRegistry;
import com.arcadia.lib.client.ArcadiaModCard;
import com.arcadia.lib.data.DatabaseManager;
import com.arcadia.adminpanel.command.AdminPanelCommand;
import com.arcadia.adminpanel.data.WarnTableDefinition;
import com.arcadia.adminpanel.gui.AdminPanelMenu;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.WarnManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Paths;

/**
 * Arcadia Admin Panel — Steampunk-themed server management mod.
 * Both-sided mod powered by Arcadia Lib.
 *
 * @version 1.2.0
 * @author vyrriox
 */
@Mod("arcadiaadminpanel")
public class AdminPanelMod {

    public static final String MOD_ID = "arcadiaadminpanel";

    public AdminPanelMod(IEventBus modEventBus) {
        // Common setup (database tables, module registration)
        modEventBus.addListener(this::onCommonSetup);

        // Game events
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.register(new ChatListener());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Register database tables for multi-server warn sync
            DatabaseManager.registerTables(new WarnTableDefinition());

            // Register hub card (visible in Arcadia Hub via L key, row 2)
            ArcadiaModRegistry.registerCard(new ArcadiaModCard(
                    "adminpanel",
                    "\u2699",
                    "Admin Panel",
                    "Server Management",
                    0xB87333,
                    0,
                    2,
                    true,
                    "arcadia.staff.mod"
            ));

            // Register click handler — sends command to server (bypasses tab system)
            ArcadiaModRegistry.registerCardClickHandler("adminpanel", () -> {
                var player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    player.connection.sendCommand("arcadia_adminpanel panel");
                }
            });

            // Register server action so other mods can open the admin panel
            ArcadiaModRegistry.registerServerAction("adminpanel:open",
                    player -> AdminPanelMenu.open(player));
        });
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AdminPanelCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Initialize offline player manager (async scan)
        OfflinePlayerManager.getInstance().init(event.getServer(), Paths.get("").toAbsolutePath());

        // Initialize warn manager (loads from DB or JSON)
        WarnManager.getInstance().init();
        JailManager.getInstance().init();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Clear caches on server stop
        FTBDataReader.clearCache();
    }
}
