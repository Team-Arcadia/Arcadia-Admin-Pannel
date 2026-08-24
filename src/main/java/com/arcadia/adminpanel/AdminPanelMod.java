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
 * @version 1.3.1
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

        // Network payload registration (name-tag sync S2C packets).
        modEventBus.addListener(com.arcadia.adminpanel.network.AdminPanelNet::registerPayloads);

        // Game events
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.register(new ChatListener());
        NeoForge.EVENT_BUS.register(new JailEnforcer());
        NeoForge.EVENT_BUS.register(new LoginQueue());
        NeoForge.EVENT_BUS.register(new com.arcadia.adminpanel.event.StaffModeEvents());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Register database tables for multi-server warn sync
            DatabaseManager.registerTables(new WarnTableDefinition());
            // Generic record table backing the 1.3.0 audit log, notes, mail, bans and watchlist.
            DatabaseManager.registerTables(new com.arcadia.adminpanel.data.AdminTableDefinition());

            // The freeze overlay lives behind an optional payload; wiring it through a seam keeps
            // the manager free of a compile-time dependency on the network package.
            com.arcadia.adminpanel.util.FreezeManager.bindSyncer(
                    com.arcadia.adminpanel.network.AdminPanelNet::sendFreezeState);

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

        // Bind the server handle the 1.3.0 notification paths need, before anything can fire.
        com.arcadia.adminpanel.util.StaffFeed.bind(event.getServer());

        // Initialize offline player manager (async scan)
        OfflinePlayerManager.getInstance().init(event.getServer(), Paths.get("").toAbsolutePath());

        // Initialize warn manager (loads from DB or JSON, runs configured purge).
        WarnManager.getInstance().init();
        JailManager.getInstance().init();
        LoginTracker.getInstance().init();
        NextSpawnManager.getInstance().init();
        com.arcadia.adminpanel.util.NameTagManager.getInstance().init();
        com.arcadia.adminpanel.util.DisguiseManager.getInstance().init();

        // ── 1.3.0 subsystems ────────────────────────────────────────────────
        // Salt first: the login tracker hashes connection fingerprints with it from the first join.
        com.arcadia.adminpanel.util.AltDetector.init();
        // Restores anyone still frozen from the previous session before the first player
        // can connect, so a suspect cannot escape a screenshare by waiting out a restart.
        com.arcadia.adminpanel.util.FreezeManager.init();
        com.arcadia.adminpanel.util.RecordStore.initAll();
        com.arcadia.adminpanel.util.SanctionTemplates.init();
        com.arcadia.adminpanel.util.InventoryAccess.init();
        com.arcadia.adminpanel.util.DeathSnapshotManager.init();
        com.arcadia.adminpanel.util.DiscordWebhook.init();
        com.arcadia.adminpanel.util.RestartScheduler.armFromConfig();
        com.arcadia.adminpanel.util.LoginQueueAuto.onServerStarted();
        com.arcadia.adminpanel.util.AuditManager.purgeExpired();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Put anyone mid-session back where they belong before the world saves.
        com.arcadia.adminpanel.util.SpectateManager.restoreAll(event.getServer());

        // Flush the login tracker's coalesced write + stop its IO thread so no record is lost.
        LoginTracker.getInstance().shutdown();

        // ── 1.3.0 subsystems ────────────────────────────────────────────────
        com.arcadia.adminpanel.util.RecordStore.shutdownAll();
        com.arcadia.adminpanel.util.DeathSnapshotManager.shutdown();
        com.arcadia.adminpanel.util.InventoryAccess.shutdown();
        com.arcadia.adminpanel.util.DiscordWebhook.shutdown();
        com.arcadia.adminpanel.util.VanishManager.reset();
        com.arcadia.adminpanel.util.FreezeManager.shutdown();
        com.arcadia.adminpanel.util.AfkTracker.reset();
        com.arcadia.adminpanel.util.ClientModsRegistry.reset();
        com.arcadia.adminpanel.util.RestartScheduler.reset();
        com.arcadia.adminpanel.util.AutoBroadcast.reset();
        com.arcadia.adminpanel.util.LoginQueueAuto.reset();
        com.arcadia.adminpanel.util.ChatControl.reset();
        com.arcadia.adminpanel.util.LagMonitor.invalidate();
        com.arcadia.adminpanel.util.ChunkReport.invalidate();
        com.arcadia.adminpanel.gui.DisguiseMenu.invalidate();
        com.arcadia.adminpanel.util.StaffFeed.unbind();

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
