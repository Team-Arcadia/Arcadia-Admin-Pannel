package com.arcadia.adminpanel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client half of the mob-disguise system (1.2.9). On {@link RenderPlayerEvent.Pre} for a
 * disguised player, it cancels the vanilla player render and draws the synced mob instead — at the
 * player's exact position and rotation, with full mob animation (walk cycle, head tracking, idle).
 *
 * <p>How it works: a single throwaway {@link LivingEntity} of the disguise type is cached per player
 * (created in the client level, never added to it, never ticked — so no AI runs). Each frame the
 * dummy's rotations/pose are copied from the real player and its renderer is invoked on the same
 * {@code PoseStack} the player would have used, so it lands exactly where the player is. The leg /
 * body walk animation is advanced once per client tick from the player's own walk speed.</p>
 *
 * <p>The disguise is visual only — the server keeps the player's real hitbox and physics. The
 * player's floating name is suppressed separately by {@link NameTagRenderer}.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class DisguiseRenderer {

    /** One reusable dummy entity per disguised player (keyed by player UUID). */
    private static final Map<UUID, Dummy> DUMMIES = new ConcurrentHashMap<>();

    /** Holds a dummy together with the entity type it was created for, so a type change recreates it. */
    private record Dummy(EntityType<?> type, LivingEntity entity) {}

    private DisguiseRenderer() {}

    // ── Render: swap the player model for the mob ──────────────────────────────

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (ClientDisguiseState.isEmpty()) return;
        Player player = event.getEntity();
        EntityType<?> type = ClientDisguiseState.disguiseFor(player.getUUID());
        if (type == null) return;

        Minecraft mc = Minecraft.getInstance();
        // Respect invisibility: an invisible disguised player shows nothing, like vanilla.
        if (mc.player != null && player.isInvisibleTo(mc.player)) {
            event.setCanceled(true);
            return;
        }

        LivingEntity dummy = dummyFor(player, type);
        if (dummy == null) return; // creation failed (unknown type / no level) → leave vanilla model

        // From here we own the render: skip the vanilla player body, armor, held items and name.
        event.setCanceled(true);

        float partialTick = event.getPartialTick();
        syncPose(dummy, player);

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        renderDummy(dispatcher, dummy, bodyYaw, partialTick, event);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderDummy(EntityRenderDispatcher dispatcher, LivingEntity dummy,
                                    float bodyYaw, float partialTick, RenderPlayerEvent.Pre event) {
        EntityRenderer renderer = dispatcher.getRenderer(dummy);
        renderer.render(dummy, bodyYaw, partialTick, event.getPoseStack(),
                event.getMultiBufferSource(), event.getPackedLight());
    }

    /** Mirror the player's per-frame visual state onto the dummy so the mob faces/poses identically. */
    private static void syncPose(LivingEntity dummy, Player player) {
        dummy.setPos(player.getX(), player.getY(), player.getZ());
        dummy.xo = player.xo; dummy.yo = player.yo; dummy.zo = player.zo;

        dummy.yBodyRot = player.yBodyRot;   dummy.yBodyRotO = player.yBodyRotO;
        dummy.yHeadRot = player.yHeadRot;   dummy.yHeadRotO = player.yHeadRotO;
        dummy.setYRot(player.getYRot());    dummy.yRotO = player.yRotO;
        dummy.setXRot(player.getXRot());    dummy.xRotO = player.xRotO;

        dummy.tickCount = player.tickCount; // drives idle animations (e.g. head bob, tail)
        dummy.hurtTime = player.hurtTime;
        dummy.setOnGround(player.onGround());
        dummy.setSprinting(player.isSprinting());
        dummy.setShiftKeyDown(player.isShiftKeyDown());
        dummy.setSwimming(player.isSwimming());
    }

    // ── Tick: advance walk animation + prune ──────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (DUMMIES.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;

        Iterator<Map.Entry<UUID, Dummy>> it = DUMMIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Dummy> e = it.next();
            UUID uuid = e.getKey();
            // Drop dummies whose player is gone or no longer disguised.
            if (level == null || !ClientDisguiseState.isDisguised(uuid)) { it.remove(); continue; }
            Entity ent = level.getPlayerByUUID(uuid);
            if (!(ent instanceof Player player)) { it.remove(); continue; }

            // Mirror the player's walk speed so the legs swing in step with their movement. decay 1.0
            // snaps speed to the player's, matching their stride exactly each tick.
            LivingEntity dummy = e.getValue().entity;
            dummy.walkAnimation.update(player.walkAnimation.speed(), 1.0f);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DUMMIES.clear();
        ClientDisguiseState.clear();
    }

    // ── Dummy lifecycle ───────────────────────────────────────────────────────

    /** Returns the cached dummy for this player, (re)creating it if absent or the type changed. */
    private static LivingEntity dummyFor(Player player, EntityType<?> type) {
        UUID uuid = player.getUUID();
        Dummy cached = DUMMIES.get(uuid);
        if (cached != null && cached.type == type) return cached.entity;

        LivingEntity created = create(type);
        if (created == null) {
            DUMMIES.remove(uuid);
            return null;
        }
        DUMMIES.put(uuid, new Dummy(type, created));
        return created;
    }

    /** Spawns a throwaway, AI-less {@link LivingEntity} in the client level for rendering only. */
    private static LivingEntity create(EntityType<?> type) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity e = type.create(level);
        if (!(e instanceof LivingEntity living)) {
            if (e != null) e.discard();
            return null;
        }
        if (living instanceof Mob mob) mob.setNoAi(true); // never tick AI — purely decorative
        living.setSilent(true);
        return living;
    }
}
