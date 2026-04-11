package com.vyrriox.arcadiaadminpanel;

import com.arcadia.lib.ArcadiaModRegistry;
import com.arcadia.lib.client.ArcadiaModCard;
import com.arcadia.lib.data.DatabaseManager;
import com.vyrriox.arcadiaadminpanel.command.AdminPanelCommand;
import com.vyrriox.arcadiaadminpanel.data.WarnTableDefinition;
import com.vyrriox.arcadiaadminpanel.gui.AdminPanelMenu;
import com.vyrriox.arcadiaadminpanel.event.ChatListener;
import com.vyrriox.arcadiaadminpanel.util.FTBDataReader;
import com.vyrriox.arcadiaadminpanel.util.OfflinePlayerManager;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
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

            // Register module card in Arcadia Hub
            ArcadiaModRegistry.registerCard(new ArcadiaModCard(
                    "adminpanel",
                    "\u2699",
                    "Admin Panel",
                    "Server Management",
                    0xB87333, // Copper
                    90,
                    true,
                    "arcadia.staff.mod"
            ));

            // Register tab opener so clicking the hub card opens the admin panel
            ArcadiaModRegistry.registerTabOpener(90,
                    player -> AdminPanelMenu.open((net.minecraft.server.level.ServerPlayer) player));

            // Register server action as alternative entry point
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
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Clear caches on server stop
        FTBDataReader.clearCache();
    }
}
