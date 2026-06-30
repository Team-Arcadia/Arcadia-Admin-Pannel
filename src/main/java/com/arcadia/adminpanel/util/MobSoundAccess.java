package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Reads a {@link LivingEntity}'s protected hurt / death sounds (1.2.9, for the disguise system).
 *
 * <p>{@code getHurtSound} / {@code getDeathSound} are {@code protected} on {@link LivingEntity}, so
 * the disguise sound handler — which lives outside the entity — can't call them directly. We resolve
 * the two methods once (Mojang-mapped names, stable at NeoForge runtime) and invoke them on the
 * disguise dummy. Because {@code Method.invoke} dispatches virtually, a modded mob that overrides
 * these methods returns <em>its</em> sound, so modpack mobs sound correct too. The ambient sound is
 * handled separately via the public {@code Mob.playAmbientSound()} and needs no reflection.</p>
 *
 * <p>If resolution ever fails (e.g. a future mapping change), every accessor degrades to {@code null}
 * — the disguise simply makes no hurt/death sound rather than crashing.</p>
 *
 * @author vyrriox
 */
public final class MobSoundAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable private static final Method GET_HURT_SOUND;
    @Nullable private static final Method GET_DEATH_SOUND;

    static {
        Method hurt = null, death = null;
        try {
            hurt = LivingEntity.class.getDeclaredMethod("getHurtSound", DamageSource.class);
            hurt.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.warn("[AdminPanel] Could not resolve LivingEntity#getHurtSound; disguise hurt sounds disabled", t);
        }
        try {
            death = LivingEntity.class.getDeclaredMethod("getDeathSound");
            death.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.warn("[AdminPanel] Could not resolve LivingEntity#getDeathSound; disguise death sounds disabled", t);
        }
        GET_HURT_SOUND = hurt;
        GET_DEATH_SOUND = death;
    }

    private MobSoundAccess() {}

    @Nullable
    public static SoundEvent hurtSound(LivingEntity mob, DamageSource source) {
        if (GET_HURT_SOUND == null) return null;
        try {
            return (SoundEvent) GET_HURT_SOUND.invoke(mob, source);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static SoundEvent deathSound(LivingEntity mob) {
        if (GET_DEATH_SOUND == null) return null;
        try {
            return (SoundEvent) GET_DEATH_SOUND.invoke(mob);
        } catch (Throwable t) {
            return null;
        }
    }
}
