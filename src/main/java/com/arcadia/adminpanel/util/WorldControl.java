package com.arcadia.adminpanel.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Time, weather, difficulty and the game rules staff actually change, without typing a command.
 *
 * <p>Every one of these is a one-line vanilla call. The value is in not having to remember whether
 * it is {@code doMobGriefing} or {@code mobGriefing}, in seeing the current value before changing
 * it, and in the change being audited like any other staff action.</p>
 *
 * <p>Time and weather are per dimension, because that is how the game stores them; difficulty and
 * game rules are server-wide.</p>
 *
 * @author vyrriox
 */
public final class WorldControl {

    /** A boolean game rule surfaced in the menu, with the icon its row uses. */
    public record RuleToggle(String id, GameRules.Key<GameRules.BooleanValue> key, Item icon) {}

    /** The rules staff change often enough to deserve a button. */
    public static final List<RuleToggle> TOGGLES = List.of(
            new RuleToggle("keepInventory", GameRules.RULE_KEEPINVENTORY, Items.TOTEM_OF_UNDYING),
            new RuleToggle("mobGriefing", GameRules.RULE_MOBGRIEFING, Items.CREEPER_HEAD),
            new RuleToggle("doDaylightCycle", GameRules.RULE_DAYLIGHT, Items.CLOCK),
            new RuleToggle("doWeatherCycle", GameRules.RULE_WEATHER_CYCLE, Items.WATER_BUCKET),
            new RuleToggle("doFireTick", GameRules.RULE_DOFIRETICK, Items.FLINT_AND_STEEL),
            new RuleToggle("doInsomnia", GameRules.RULE_DOINSOMNIA, Items.PHANTOM_MEMBRANE),
            new RuleToggle("naturalRegeneration", GameRules.RULE_NATURAL_REGENERATION, Items.GOLDEN_APPLE),
            new RuleToggle("doMobSpawning", GameRules.RULE_DOMOBSPAWNING, Items.ZOMBIE_HEAD),
            new RuleToggle("fallDamage", GameRules.RULE_FALL_DAMAGE, Items.FEATHER),
            new RuleToggle("fireDamage", GameRules.RULE_FIRE_DAMAGE, Items.MAGMA_BLOCK),
            new RuleToggle("drowningDamage", GameRules.RULE_DROWNING_DAMAGE, Items.PUFFERFISH),
            new RuleToggle("showDeathMessages", GameRules.RULE_SHOWDEATHMESSAGES, Items.SKELETON_SKULL),
            new RuleToggle("announceAdvancements", GameRules.RULE_ANNOUNCE_ADVANCEMENTS, Items.EXPERIENCE_BOTTLE),
            new RuleToggle("doImmediateRespawn", GameRules.RULE_DO_IMMEDIATE_RESPAWN, Items.RESPAWN_ANCHOR));

    /** Preset times of day, in ticks. */
    public enum TimePreset {
        DAWN(23000), DAY(1000), NOON(6000), DUSK(12000), NIGHT(13000), MIDNIGHT(18000);

        public final int ticks;
        TimePreset(int ticks) { this.ticks = ticks; }
    }

    private WorldControl() {}

    // -- Time ----------------------------------------------------------------

    /** Sets the time of day in one dimension, preserving the elapsed-days part. */
    public static void setTime(ServerPlayer actor, ServerLevel level, TimePreset preset) {
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + preset.ticks);
        AuditManager.recordServer(actor, AdminAction.WORLD_EDIT,
                "time=" + preset.name().toLowerCase() + " @" + level.dimension().location());
    }

    /** Current time of day, 0-23999. */
    public static long timeOfDay(ServerLevel level) {
        return level.getDayTime() % 24000L;
    }

    // -- Weather -------------------------------------------------------------

    public enum WeatherPreset { CLEAR, RAIN, THUNDER }

    public static void setWeather(ServerPlayer actor, ServerLevel level, WeatherPreset preset) {
        int duration = 6000 * 20;
        switch (preset) {
            case CLEAR -> level.setWeatherParameters(duration, 0, false, false);
            case RAIN -> level.setWeatherParameters(0, duration, true, false);
            case THUNDER -> level.setWeatherParameters(0, duration, true, true);
        }
        AuditManager.recordServer(actor, AdminAction.WORLD_EDIT,
                "weather=" + preset.name().toLowerCase() + " @" + level.dimension().location());
    }

    public static WeatherPreset currentWeather(ServerLevel level) {
        if (level.isThundering()) return WeatherPreset.THUNDER;
        if (level.isRaining()) return WeatherPreset.RAIN;
        return WeatherPreset.CLEAR;
    }

    // -- Difficulty ----------------------------------------------------------

    /** Advances the difficulty one step, wrapping past hard back to peaceful. */
    public static Difficulty cycleDifficulty(ServerPlayer actor, MinecraftServer server) {
        Difficulty current = server.getWorldData().getDifficulty();
        Difficulty next = switch (current) {
            case PEACEFUL -> Difficulty.EASY;
            case EASY -> Difficulty.NORMAL;
            case NORMAL -> Difficulty.HARD;
            case HARD -> Difficulty.PEACEFUL;
        };
        server.setDifficulty(next, true);
        AuditManager.recordServer(actor, AdminAction.WORLD_EDIT, "difficulty=" + next.getKey());
        return next;
    }

    public static Difficulty difficulty(MinecraftServer server) {
        return server.getWorldData().getDifficulty();
    }

    // -- Game rules ----------------------------------------------------------

    public static boolean ruleValue(MinecraftServer server, RuleToggle toggle) {
        return server.getGameRules().getBoolean(toggle.key());
    }

    /** Flips a boolean rule server-wide and returns the new value. */
    public static boolean toggleRule(ServerPlayer actor, MinecraftServer server, RuleToggle toggle) {
        GameRules rules = server.getGameRules();
        boolean next = !rules.getBoolean(toggle.key());
        rules.getRule(toggle.key()).set(next, server);
        AuditManager.recordServer(actor, AdminAction.WORLD_EDIT,
                toggle.id() + "=" + next);
        return next;
    }

    @Nullable
    public static RuleToggle toggleById(String id) {
        for (RuleToggle t : TOGGLES) if (t.id().equals(id)) return t;
        return null;
    }
}
