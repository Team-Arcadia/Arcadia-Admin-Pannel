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

    /** Entity types whose renderer threw — skipped from then on so a bad modded model can't crash
     *  the client or spam exceptions every frame. The player keeps their normal model instead. */
    private static final java.util.Set<EntityType<?>> FAILED_TYPES = ConcurrentHashMap.newKeySet();

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Holds a dummy together with what it was built for, so a change to either recreates it. Baby
     * form is part of the key because {@code setBaby} resizes the model and some mobs bake the
     * decision into their renderer state.
     */
    private record Dummy(EntityType<?> type, boolean baby, LivingEntity entity) {}

    private DisguiseRenderer() {}

    // ── Render: swap the player model for the mob ──────────────────────────────

    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        if (ClientDisguiseState.isEmpty()) return;
        Player player = event.getEntity();
        ClientDisguiseState.Entry entry = ClientDisguiseState.entryFor(player.getUUID());
        if (entry == null) return;
        EntityType<?> type = entry.type();
        if (FAILED_TYPES.contains(type)) return; // known-bad renderer → keep the vanilla player model

        Minecraft mc = Minecraft.getInstance();
        // Respect invisibility: an invisible disguised player shows nothing, like vanilla.
        if (mc.player != null && player.isInvisibleTo(mc.player)) {
            event.setCanceled(true);
            return;
        }

        LivingEntity dummy = dummyFor(player, type, entry.baby());
        if (dummy == null) return; // creation failed (unknown type / no level) → leave vanilla model

        float partialTick = event.getPartialTick();
        syncPose(dummy, player);
        if (entry.showMobName()) {
            dummy.setCustomName(type.getDescription());
            dummy.setCustomNameVisible(true);
        } else {
            dummy.setCustomNameVisible(false);
        }

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        // Only cancel the vanilla render once the mob render itself succeeds — if a (modded) renderer
        // throws, we catch it, blacklist the type, and fall through to the normal player model rather
        // than crashing the client or leaving an empty space where the player was.
        try {
            renderDummy(dispatcher, dummy, bodyYaw, partialTick, event, entry.scale());
            event.setCanceled(true); // mob drawn — suppress the vanilla player body, armor, name
        } catch (Throwable t) {
            FAILED_TYPES.add(type);
            DUMMIES.remove(player.getUUID());
            LOGGER.warn("[AdminPanel] Disguise renderer for {} failed; falling back to the player model",
                    EntityType.getKey(type), t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderDummy(EntityRenderDispatcher dispatcher, LivingEntity dummy,
                                    float bodyYaw, float partialTick, RenderPlayerEvent.Pre event,
                                    float scale) {
        EntityRenderer renderer = dispatcher.getRenderer(dummy);
        var pose = event.getPoseStack();
        boolean scaled = Math.abs(scale - 1.0F) > 1.0E-3F;
        if (scaled) {
            // Scale about the model's feet so a giant grows upward from where the player stands
            // rather than sinking half of itself into the floor.
            pose.pushPose();
            pose.scale(scale, scale, scale);
        }
        try {
            renderer.render(dummy, bodyYaw, partialTick, pose,
                    event.getMultiBufferSource(), event.getPackedLight());
        } finally {
            if (scaled) pose.popPose();
        }
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

    /** Returns the cached dummy for this player, (re)creating it if absent or the shape changed. */
    private static LivingEntity dummyFor(Player player, EntityType<?> type, boolean baby) {
        UUID uuid = player.getUUID();
        Dummy cached = DUMMIES.get(uuid);
        if (cached != null && cached.type == type && cached.baby == baby) return cached.entity;

        LivingEntity created = create(type, baby);
        if (created == null) {
            DUMMIES.remove(uuid);
            return null;
        }
        DUMMIES.put(uuid, new Dummy(type, baby, created));
        return created;
    }

    /** Spawns a throwaway, AI-less {@link LivingEntity} in the client level for rendering only. */
    private static LivingEntity create(EntityType<?> type, boolean baby) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity e = type.create(level);
        if (!(e instanceof LivingEntity living)) {
            if (e != null) e.discard();
            return null;
        }
        if (living instanceof Mob mob) mob.setNoAi(true); // never tick AI — purely decorative
        // Only mobs that actually have a baby form respond; asking for one on a creeper is a no-op
        // rather than an error, which is what an operator typing "baby" on a mob list expects.
        if (baby && living instanceof net.minecraft.world.entity.AgeableMob ageable) {
            ageable.setBaby(true);
        }
        living.setSilent(true);
        return living;
    }
}
