package com.vyrriox.arcadiaadminpanel;

import com.vyrriox.arcadiaadminpanel.command.AdminPanelCommand;
import com.vyrriox.arcadiaadminpanel.event.ChatListener;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Paths;

/**
 * Arcadia Admin Panel
 * Lightweight server-side admin tool
 * 
 * @version 1.0.0
 * @author vyrriox
 */
@Mod("arcadiaadminpannel")
public class AdminPanelMod {

    public AdminPanelMod(IEventBus modEventBus) {
        // Register command
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Register Chat Listener
        NeoForge.EVENT_BUS.register(new ChatListener());

        // Initialize FTB Data Reader when server starts
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        AdminPanelCommand.register(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        // Init offline player manager (async scan)
        com.vyrriox.arcadiaadminpanel.util.OfflinePlayerManager.getInstance()
                .init(event.getServer(), Paths.get("").toAbsolutePath());
    }
}
