package com.arcadia.adminpanel.event;

import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.lib.ArcadiaMessages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps jailed players inside the jail box. Three independent lines of defense:
 *
 * <ol>
 *   <li><b>Teleport event cancel</b> — {@link EntityTeleportEvent} fires for ender pearls, chorus
 *       fruit, the {@code /tp} command, and any third-party mod that goes through the standard
 *       entity-teleport pipeline. We cancel it for jailed players outright.</li>
 *   <li><b>Right-click intercept</b> — chorus fruit and ender pearl event-cancellation works in
 *       most cases, but some mods (waystones, several magic mods) call their own teleport API
 *       that bypasses {@code EntityTeleportEvent}. We additionally cancel right-click usage of
 *       a curated list of known teleport items.</li>
 *   <li><b>Proximity sweep</b> — every {@link AdminConfig.Data#jailEnforceTickInterval} ticks
 *       (default 1 s) we check every online jailed player; if they're outside
 *       {@link AdminConfig.Data#jailProximityRadius} blocks of the jail point (or in a different
 *       dimension), they get teleported back. Catches everything the event hooks miss.</li>
 * </ol>
 *
 * <p>All three are cheap: teleport events are rare, the right-click filter is a
 * single-item-id lookup, and the sweep is O(jailed_online_players) — typically 0–3 entries.</p>
 *
 * @author vyrriox
 */
public final class JailEnforcer {

    private int tickCounter = 0;

    // ── 1. Teleport event cancel ────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return;
        // Don't block our own teleport-to-jail call — the new position IS the jail, that's fine.
        // Heuristic: if the destination is within the jail proximity radius of the jail point,
        // allow it. Otherwise cancel.
        JailManager.JailLocation loc = JailManager.getInstance().getJailLocation();
        if (loc != null) {
            double dx = event.getTargetX() - loc.x();
            double dy = event.getTargetY() - loc.y();
            double dz = event.getTargetZ() - loc.z();
            int r = AdminConfig.get().jailProximityRadius;
            if (dx * dx + dy * dy + dz * dz <= (double) r * r) return;
        }
        event.setCanceled(true);
    }

    // ── 2. Right-click intercept for known teleport items ───────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return;
        var item = event.getItemStack().getItem();
        boolean blocked = item == Items.ENDER_PEARL || item == Items.CHORUS_FRUIT;
        if (!blocked) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            String idStr = id.toString();
            blocked = idStr.contains("waystone") || idStr.contains("warp")
                    || idStr.contains("teleport") || idStr.contains("return_stone");
        }
        if (blocked) {
            event.setCanceled(true);
            sp.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("jail.blocked.teleport", sp)));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Waystone activation typically goes through RightClickBlock with a waystone block.
        // Conservative: if the player is jailed, deny block-interaction with anything whose
        // registry name contains "waystone"/"warp"/"portal" outside the jail radius.
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return;
        var block = event.getLevel().getBlockState(event.getPos()).getBlock();
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        String idStr = id.toString();
        if (idStr.contains("waystone") || idStr.contains("warp_stone")
                || idStr.contains("portal") || idStr.contains("teleport")) {
            event.setCanceled(true);
            sp.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("jail.blocked.teleport", sp)));
        }
    }

    // ── 3. Proximity sweep ──────────────────────────────────────────────────

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AdminConfig.Data cfg = AdminConfig.get();
        if (!cfg.jailEnforceProximity) return;
        int interval = Math.max(1, cfg.jailEnforceTickInterval);
        if (++tickCounter < interval) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        JailManager jm = JailManager.getInstance();
        JailManager.JailLocation loc = jm.getJailLocation();
        if (loc == null) return;

        int r = cfg.jailProximityRadius;
        long rSq = (long) r * r;

        // Parse the jail dimension to a ResourceLocation ONCE so the per-player comparison below
        // compares ResourceLocation objects directly instead of allocating a fresh String from
        // each player's dimension every sweep tick.
        net.minecraft.resources.ResourceLocation jailDim =
                net.minecraft.resources.ResourceLocation.tryParse(loc.dimension());

        // Iterate the jailed set directly rather than every online player — on a 100-player server
        // with 0 jailed entries the previous code did 100 HashMap lookups per second for nothing.
        // The jailed map is typically 0-3 entries.
        var jailed = jm.getAllJailed();
        if (jailed.isEmpty()) return;
        for (var entry : jailed.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(entry.getKey());
            if (sp == null) continue; // offline; nothing to enforce
            // Different dimension OR outside the radius -> bounce them back.
            boolean wrongDim = !sp.serverLevel().dimension().location().equals(jailDim);
            double dx = sp.getX() - loc.x();
            double dy = sp.getY() - loc.y();
            double dz = sp.getZ() - loc.z();
            boolean outOfRange = (dx * dx + dy * dy + dz * dz) > rSq;
            if (wrongDim || outOfRange) {
                jm.teleportToJail(sp, server);
                // Stop fall damage / momentum from a chorus-fruit-style yank.
                sp.setDeltaMovement(0, 0, 0);
                ((LivingEntity) sp).fallDistance = 0;
            }
        }
    }
}
