package com.arcadia.adminpanel.event;

import com.arcadia.adminpanel.util.AfkTracker;
import com.arcadia.adminpanel.util.AutoBroadcast;
import com.arcadia.adminpanel.util.BanManager;
import com.arcadia.adminpanel.util.DeathSnapshotManager;
import com.arcadia.adminpanel.util.FreezeManager;
import com.arcadia.adminpanel.util.LoginQueueAuto;
import com.arcadia.adminpanel.util.RestartScheduler;
import com.arcadia.adminpanel.util.SpyManager;
import com.arcadia.adminpanel.util.VanishManager;
import com.arcadia.adminpanel.util.AdminConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The engine hooks behind vanish, freeze, AFK tracking, the spy feeds, death snapshots, the
 * scheduled restart and the cross-server ban check.
 *
 * <p>They live together because they share one property that matters more than their subject: every
 * one of them must cost nothing when its feature is idle. Each handler opens with the cheapest
 * possible test, usually an emptiness check on a set that is empty on a normal server, and returns.
 * The only handler that runs work unconditionally is the server tick, and there it is an integer
 * compare per subsystem.</p>
 *
 * @author vyrriox
 */
public final class StaffModeEvents {

    // -- Freeze enforcement --------------------------------------------------

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (FreezeManager.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        FreezeManager.enforce(sp);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (FreezeManager.isEmpty()) return;
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (!FreezeManager.isFrozen(sp.getUUID())) return;
        event.setCanceled(true);
        FreezeManager.deny(sp);
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (FreezeManager.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!FreezeManager.isFrozen(sp.getUUID())) return;
        event.setCanceled(true);
        FreezeManager.deny(sp);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (denyIfFrozen(event.getEntity())) event.setCanceled(true);
        else markActive(event.getEntity());
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (denyIfFrozen(event.getEntity())) event.setCanceled(true);
        else markActive(event.getEntity());
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (denyIfFrozen(event.getEntity())) event.setCanceled(true);
        else markActive(event.getEntity());
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (denyIfFrozen(event.getEntity())) event.setCanceled(true);
        else markActive(event.getEntity());
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (denyIfFrozen(event.getEntity())) event.setCanceled(true);
        else markActive(event.getEntity());
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (FreezeManager.isEmpty()) return;
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (!FreezeManager.isFrozen(sp.getUUID())) return;
        event.setCanceled(true);
        FreezeManager.deny(sp);
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (FreezeManager.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!FreezeManager.isFrozen(sp.getUUID())) return;
        // The open cannot be cancelled from here, so it is closed on the next tick instead. The
        // player sees the screen flash rather than an inventory they could edit.
        com.arcadia.lib.scheduler.SchedulerService.runNextTick(() -> {
            if (!sp.hasDisconnected()) sp.closeContainer();
        });
        FreezeManager.deny(sp);
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (FreezeManager.isEmpty()) return;
        if (!AdminConfig.get().freezeDamageImmunity) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!FreezeManager.isFrozen(sp.getUUID())) return;
        event.setCanceled(true);
    }

    /** Shared frozen test used by the interaction handlers. */
    private static boolean denyIfFrozen(net.minecraft.world.entity.player.Player player) {
        if (FreezeManager.isEmpty()) return false;
        if (!(player instanceof ServerPlayer sp)) return false;
        if (!FreezeManager.isFrozen(sp.getUUID())) return false;
        FreezeManager.deny(sp);
        return true;
    }

    private static void markActive(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer sp) AfkTracker.markActive(sp);
    }

    // -- Vanish --------------------------------------------------------------

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (VanishManager.count() == 0) return;
        if (!(event.getEntity() instanceof ServerPlayer observer)) return;
        Entity target = event.getTarget();
        if (!(target instanceof ServerPlayer hidden)) return;
        VanishManager.onStartTracking(observer, hidden);
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (VanishManager.count() == 0) return;
        if (!AdminConfig.get().vanishNoPickup) return;
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (!VanishManager.isVanished(sp.getUUID())) return;
        event.setCanPickup(TriState.FALSE);
    }

    @SubscribeEvent
    public void onChangeTarget(LivingChangeTargetEvent event) {
        if (VanishManager.count() == 0) return;
        if (!AdminConfig.get().vanishNoMobTarget) return;
        if (!(event.getNewAboutToBeSetTarget() instanceof ServerPlayer sp)) return;
        if (!VanishManager.isVanished(sp.getUUID())) return;
        event.setCanceled(true);
    }

    // -- Death snapshots -----------------------------------------------------

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        String cause = event.getSource() != null ? event.getSource().getMsgId() : "unknown";
        DeathSnapshotManager.capture(sp, cause);
        // Optional event rule: dying ends the disguise.
        com.arcadia.adminpanel.util.DisguiseManager.getInstance().onDeath(sp);
    }

    // -- Spy feeds and frozen command gate -----------------------------------

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        String raw = event.getParseResults().getReader().getString();

        if (FreezeManager.isFrozen(sp.getUUID()) && !FreezeManager.isCommandAllowed(raw)) {
            event.setCanceled(true);
            FreezeManager.deny(sp);
            return;
        }

        AfkTracker.markActive(sp);
        SpyManager.onCommand(sp, raw);
        String[] pm = SpyManager.parsePrivateMessage(raw);
        if (pm != null) SpyManager.onPrivateMessage(sp, pm[0], pm[1]);
    }

    // -- Cross-server bans ---------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onNegotiation(PlayerNegotiationEvent event) {
        MinecraftServer server = com.arcadia.adminpanel.util.StaffFeed.server();
        if (server == null) return;
        Component refusal = BanManager.checkRemoteBan(server, event.getProfile());
        if (refusal != null) event.getConnection().disconnect(refusal);
    }

    // -- Server tick ---------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        AfkTracker.onServerTick(server);
        RestartScheduler.onServerTick(server);
        AutoBroadcast.onServerTick(server);
        LoginQueueAuto.onServerTick(server);
        com.arcadia.adminpanel.util.InventoryBackupManager.onServerTick(server);
    }
}
