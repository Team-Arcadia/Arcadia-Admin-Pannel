package com.arcadia.adminpanel.event;

import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.DisguiseManager;
import com.arcadia.adminpanel.util.MobSoundAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half of the disguise <b>sound</b> system (1.2.9). Makes a disguised player audibly behave
 * like the mob they're disguised as:
 *
 * <ul>
 *   <li><b>Ambient</b> — on a randomised vanilla-like interval, the mob's ambient call (oink, moan…)
 *       is broadcast from the player's position.</li>
 *   <li><b>Hurt / death</b> — when the disguised player takes damage or dies, the mob's hurt / death
 *       sound plays at their position.</li>
 * </ul>
 *
 * <p>All sounds are <b>server-broadcast</b> ({@code level.playSound}), so every nearby client hears
 * them in sync with correct distance attenuation — no per-client guesswork, one cheap packet per
 * event. To keep it server-friendly the work is tiny: one int countdown per disguised player per
 * tick, and a single reusable, never-added, AI-less dummy entity per disguised player (also the
 * source of the mob's sound events, including modded overrides). The whole feature is gated by
 * {@link AdminConfig.Data#disguiseSounds}.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel")
public final class DisguiseSoundHandler {

    /** A reusable server-side sound source per disguised player. Never added to the world / ticked. */
    private record SoundDummy(EntityType<?> type, ServerLevel level, LivingEntity entity) {}

    private static final Map<UUID, SoundDummy> DUMMIES = new ConcurrentHashMap<>();
    /** Ticks until the next ambient sound, per disguised player. */
    private static final Map<UUID, Integer> AMBIENT_COOLDOWN = new ConcurrentHashMap<>();

    private DisguiseSoundHandler() {}

    // ── Ambient loop ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!AdminConfig.get().disguiseSounds) { clear(); return; }
        MinecraftServer server = event.getServer();
        DisguiseManager mgr = DisguiseManager.getInstance();

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            UUID uuid = sp.getUUID();
            ResourceLocation id = mgr.getDisguise(uuid);
            if (id == null) continue;
            SoundDummy dummy = dummyFor(uuid, id, sp.serverLevel());
            if (dummy == null) continue;

            int cd = AMBIENT_COOLDOWN.getOrDefault(uuid, 0);
            if (cd <= 0) {
                if (dummy.entity instanceof Mob mob) {
                    mob.setPos(sp.getX(), sp.getY(), sp.getZ());
                    mob.playAmbientSound(); // null-checked internally; broadcast at the player's pos
                }
                AMBIENT_COOLDOWN.put(uuid, nextAmbientDelay(sp.serverLevel()));
            } else {
                AMBIENT_COOLDOWN.put(uuid, cd - 1);
            }
        }

        pruneStale(server);
    }

    private static int nextAmbientDelay(ServerLevel level) {
        int min = Math.max(20, AdminConfig.get().disguiseAmbientMinTicks);
        int max = Math.max(min, AdminConfig.get().disguiseAmbientMaxTicks);
        return min + level.random.nextInt(max - min + 1);
    }

    /** Drop dummies / cooldowns for players who are offline or no longer disguised. */
    private static void pruneStale(MinecraftServer server) {
        DisguiseManager mgr = DisguiseManager.getInstance();
        Iterator<UUID> it = DUMMIES.keySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            if (!mgr.isDisguised(uuid) || server.getPlayerList().getPlayer(uuid) == null) {
                it.remove();
                AMBIENT_COOLDOWN.remove(uuid);
            }
        }
    }

    // ── Hurt / death ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!AdminConfig.get().disguiseSounds) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ResourceLocation id = DisguiseManager.getInstance().getDisguise(sp.getUUID());
        if (id == null) return;
        SoundDummy dummy = dummyFor(sp.getUUID(), id, sp.serverLevel());
        if (dummy == null) return;
        SoundEvent sound = MobSoundAccess.hurtSound(dummy.entity, event.getSource());
        playAt(sp, dummy, sound);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!AdminConfig.get().disguiseSounds) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        ResourceLocation id = DisguiseManager.getInstance().getDisguise(sp.getUUID());
        if (id == null) return;
        SoundDummy dummy = dummyFor(sp.getUUID(), id, sp.serverLevel());
        if (dummy == null) return;
        SoundEvent sound = MobSoundAccess.deathSound(dummy.entity);
        playAt(sp, dummy, sound);
    }

    private static void playAt(ServerPlayer sp, SoundDummy dummy, SoundEvent sound) {
        if (sound == null) return;
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                sound, dummy.entity.getSoundSource(), 1.0F, dummy.entity.getVoicePitch());
    }

    // ── Dummy lifecycle ───────────────────────────────────────────────────────

    private static SoundDummy dummyFor(UUID uuid, ResourceLocation id, ServerLevel level) {
        SoundDummy cached = DUMMIES.get(uuid);
        if (cached != null && cached.level == level && cached.type == typeOf(id)) return cached;

        EntityType<?> type = typeOf(id);
        if (type == null) return null;
        LivingEntity living = create(type, level);
        if (living == null) { DUMMIES.remove(uuid); return null; }
        SoundDummy dummy = new SoundDummy(type, level, living);
        DUMMIES.put(uuid, dummy);
        return dummy;
    }

    private static EntityType<?> typeOf(ResourceLocation id) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id) ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
    }

    private static LivingEntity create(EntityType<?> type, ServerLevel level) {
        try {
            Entity e = type.create(level);
            if (!(e instanceof LivingEntity living)) {
                if (e != null) e.discard();
                return null;
            }
            if (living instanceof Mob mob) mob.setNoAi(true);
            living.setSilent(false); // it IS our sound source
            return living;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void clear() {
        if (!DUMMIES.isEmpty()) DUMMIES.clear();
        if (!AMBIENT_COOLDOWN.isEmpty()) AMBIENT_COOLDOWN.clear();
    }
}
