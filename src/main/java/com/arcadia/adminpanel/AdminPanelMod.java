package com.arcadia.adminpanel;

import com.arcadia.lib.ArcadiaModRegistry;
import com.arcadia.lib.client.ArcadiaModCard;
import com.arcadia.lib.data.DatabaseManager;
import com.arcadia.adminpanel.command.AdminPanelCommand;
import com.arcadia.adminpanel.data.WarnTableDefinition;
import com.arcadia.adminpanel.gui.AdminPanelMenu;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LoginTracker;
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

            // Register hub card (row 2, tabIndex -1 = uses cardClickHandler)
            ArcadiaModRegistry.registerCard(new ArcadiaModCard(
                    "adminpanel",       // id
                    "\u2699",           // emoji
                    "Admin Panel",      // label
                    "Server Management",// sublabel
                    0xB87333,           // color (copper)
                    0,                  // sortOrder (first in row)
                    2,                  // row (third row)
                    -1,                 // tabIndex (-1 = click handler)
                    true,               // available
                    "arcadia.staff.mod",// permission
                    "adminpanel:open"   // serverActionId
            ));

            // Register click handler — client-only (sends command to server)
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
                ArcadiaModRegistry.registerCardClickHandler("adminpanel", () -> {
                    var player = net.minecraft.client.Minecraft.getInstance().player;
                    if (player != null) {
                        player.connection.sendCommand("arcadia_adminpanel panel");
                    }
                });
            }

            // Register server action so other mods can open the admin panel.
            // Hardened access check (1.2.3) — the previous defense-in-depth call relied solely on
            // PermissionService.hasPermission, which uses a NOOP fallback that returns TRUE for every
            // node when LuckPerms isn't loaded/initialized yet. On servers where LuckPerms had not
            // bound the arcadia.staff.mod node (or had failed to start), any player could navigate
            // the dashboard carousel into Admin Panel and the action would happily open it.
            //
            // Now: require OP level 2 (vanilla "permission level >= 2", set via /op or server.properties)
            // AND, if the player isn't OP, also require the LuckPerms node arcadia.staff.mod through a
            // strict check that ignores the NOOP fallback. The slash command uses hasPermission(2) and
            // is therefore unaffected — this just brings the carousel/serverAction path up to the same
            // bar.
            ArcadiaModRegistry.registerServerAction("adminpanel:open",
                    player -> {
                        if (canOpenAdminPanel(player)) {
                            AdminPanelMenu.open(player);
                        }
                    });
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
        LoginTracker.getInstance().init();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Clear caches on server stop
        FTBDataReader.clearCache();
        FTBTeamsReader.clearCache();
    }

    /**
     * Strict admin-panel access check. The player must pass at least one of:
     * <ol>
     *   <li>Vanilla OP level &gt;= 2 (set via /op or server.properties — immune to perm-backend state).</li>
     *   <li>{@code arcadia.staff.mod} via {@link com.arcadia.lib.permissions.PermissionService#hasPermissionStrict}
     *       — strict means the NOOP fallback returns false instead of true, so a server without a
     *       real perm plugin fails closed.</li>
     * </ol>
     */
    public static boolean canOpenAdminPanel(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        return com.arcadia.lib.permissions.PermissionService.hasPermissionStrict(player, "arcadia.staff.mod");
    }
}
