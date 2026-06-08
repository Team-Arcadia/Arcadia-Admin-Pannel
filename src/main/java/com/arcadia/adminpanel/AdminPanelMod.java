package com.arcadia.adminpanel;

import com.arcadia.lib.ArcadiaModRegistry;
import com.arcadia.lib.client.ArcadiaModCard;
import com.arcadia.lib.data.DatabaseManager;
import com.arcadia.adminpanel.command.AdminPanelCommand;
import com.arcadia.adminpanel.data.WarnTableDefinition;
import com.arcadia.adminpanel.gui.AdminPanelMenu;
import com.arcadia.adminpanel.event.ChatListener;
import com.arcadia.adminpanel.event.JailEnforcer;
import com.arcadia.adminpanel.event.LoginQueue;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LoginTracker;
import com.arcadia.adminpanel.util.NextSpawnManager;
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
 * @version 1.2.6
 * @author vyrriox
 */
@Mod("arcadiaadminpanel")
public class AdminPanelMod {

    public static final String MOD_ID = "arcadiaadminpanel";

    public AdminPanelMod(IEventBus modEventBus) {
        // Item registry — must be attached BEFORE FMLCommonSetupEvent fires.
        com.arcadia.adminpanel.item.AdminPanelItems.ITEMS.register(modEventBus);

        // Common setup (database tables, module registration)
        modEventBus.addListener(this::onCommonSetup);

        // Game events
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.register(new ChatListener());
        NeoForge.EVENT_BUS.register(new JailEnforcer());
        NeoForge.EVENT_BUS.register(new LoginQueue());
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
        // Load operator config first — every other init may consult it (warn expiry, jail enforce…).
        AdminConfig.init();

        // Initialize offline player manager (async scan)
        OfflinePlayerManager.getInstance().init(event.getServer(), Paths.get("").toAbsolutePath());

        // Initialize warn manager (loads from DB or JSON, runs configured purge).
        WarnManager.getInstance().init();
        JailManager.getInstance().init();
        LoginTracker.getInstance().init();
        NextSpawnManager.getInstance().init();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Flush the login tracker's coalesced write + stop its IO thread so no record is lost.
        LoginTracker.getInstance().shutdown();
        // Clear caches on server stop
        FTBDataReader.clearCache();
        FTBTeamsReader.clearCache();
        com.arcadia.adminpanel.util.FTBChunksReader.clearCache();
        com.arcadia.adminpanel.util.AdminPermissions.invalidateAll();
    }

    /**
     * Strict admin-panel access check. The player must pass at least one of:
     * <ol>
     *   <li>Vanilla OP level &gt;= 2 (set via /op or server.properties — immune to perm-backend state).</li>
     *   <li>The new granular {@code arcadia.adminpanel.open} node (1.2.4+ permission rework).</li>
     *   <li>The dashboard-visibility node {@code arcadia.hub.adminpanel} (1.2.5). This is the SAME
     *       node Arcadia Lib's carousel uses to decide whether the Admin Panel card is even visible
     *       ({@code canSeeHubCard} checks {@code arcadia.hub.<cardId>}). Before 1.2.5 a player granted
     *       only the open node could not SEE the card, and a player granted only the hub node could
     *       see it but clicking did nothing — the two gates disagreed. Accepting it here (strictly,
     *       so it still fails closed when no perm backend is bound) means one node now both reveals
     *       and opens the panel.</li>
     *   <li>Legacy {@code arcadia.staff.mod} — kept so existing LuckPerms groups don't lose access
     *       on upgrade. New deployments should grant {@code arcadia.hub.adminpanel} (see + open) and
     *       optionally {@code arcadia.adminpanel.*} for the per-action nodes.</li>
     * </ol>
     */
    public static boolean canOpenAdminPanel(net.minecraft.server.level.ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        if (com.arcadia.adminpanel.util.AdminPermissions.OPEN.check(player)) return true;
        if (com.arcadia.lib.permissions.PermissionService.hasPermissionStrict(player, "arcadia.hub.adminpanel")) return true;
        return com.arcadia.lib.permissions.PermissionService.hasPermissionStrict(player, "arcadia.staff.mod");
    }
}
