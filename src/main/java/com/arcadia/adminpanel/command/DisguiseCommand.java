package com.arcadia.adminpanel.command;

import com.arcadia.adminpanel.util.DisguiseManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.lib.ArcadiaMessages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The {@code /arcadia_adminpanel disguise …} sub-command tree — the admin control surface for the
 * mob-disguise system (1.2.9). Permission-checked server-side, mutates {@link DisguiseManager},
 * persists, and broadcasts a live update to every client.
 *
 * <ul>
 *   <li>{@code disguise <player> <entity>} — disguise the player as the given mob (e.g.
 *       {@code minecraft:pig}). Any living mob is accepted.</li>
 *   <li>{@code disguise <player> reset} — remove the disguise.</li>
 * </ul>
 *
 * <p>The change applies to online players immediately; for an offline target it is stored and takes
 * effect on their next login (the full map is synced then).</p>
 *
 * @author vyrriox
 */
public final class DisguiseCommand {

    private DisguiseCommand() {}

    /** Player-name suggestions (online + offline cache) — mirrors {@code NameTagCommand}. */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> online = ctx.getSource().getOnlinePlayerNames().stream();
        Stream<String> offline = OfflinePlayerManager.getInstance().getCache().values().stream()
                .map(OfflinePlayerManager.CachedPlayerSummary::name);
        return SharedSuggestionProvider.suggest(Stream.concat(online, offline).distinct(), builder);
    };

    /** A short list of popular, fun disguises shown as tab suggestions (any living mob still works). */
    private static final String[] POPULAR = {
            "minecraft:pig", "minecraft:cow", "minecraft:chicken", "minecraft:sheep",
            "minecraft:villager", "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
            "minecraft:allay", "minecraft:armadillo", "minecraft:fox", "minecraft:cat",
            "minecraft:wolf", "minecraft:axolotl", "minecraft:bee", "minecraft:slime"
    };

    public static LiteralArgumentBuilder<CommandSourceStack> build(Predicate<CommandSourceStack> gate) {
        return Commands.literal("disguise")
                .requires(gate)
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        // reset → clear the disguise
                        .then(Commands.literal("reset").executes(DisguiseCommand::execReset))
                        // <entity> → set the disguise
                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(POPULAR, b))
                                .executes(DisguiseCommand::execSet)));
    }

    private static int execSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        String name = resolveName(source, uuid, targetName);

        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("disguise.invalid_entity", admin).replace("%value%", id.toString())));
            return 0;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        if (!isLivingType(type, source.getLevel())) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("disguise.not_living", admin).replace("%value%", id.toString())));
            return 0;
        }

        DisguiseManager.getInstance().setDisguise(uuid, id);
        DisguiseManager.getInstance().broadcastUpdate(source.getServer(), uuid);
        final String entityName = id.toString();
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("disguise.set", admin)
                        .replace("%player%", name)
                        .replace("%value%", entityName)), true);
        return 1;
    }

    private static int execReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        String name = resolveName(source, uuid, targetName);
        boolean had = DisguiseManager.getInstance().clearDisguise(uuid);
        DisguiseManager.getInstance().broadcastUpdate(source.getServer(), uuid);
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText(had ? "disguise.cleared" : "disguise.none", admin)
                        .replace("%player%", name)), true);
        return 1;
    }

    /** True if the type produces a {@link LivingEntity}. Probes by creating one and discarding it. */
    private static boolean isLivingType(EntityType<?> type, ServerLevel level) {
        try {
            Entity probe = type.create(level);
            boolean living = probe instanceof LivingEntity;
            if (probe != null) probe.discard();
            return living;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Resolution helpers (mirror NameTagCommand) ───────────────────────────

    private static UUID resolveUUID(CommandSourceStack source, String targetName) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (online != null) return online.getUUID();
        UUID exact = null;
        for (var entry : OfflinePlayerManager.getInstance().getCache().entrySet()) {
            String n = entry.getValue().name();
            if (n.equals(targetName)) { exact = entry.getKey(); break; }
            if (n.equalsIgnoreCase(targetName) && exact == null) exact = entry.getKey();
        }
        return exact;
    }

    private static String resolveName(CommandSourceStack source, UUID uuid, String fallback) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) return online.getName().getString();
        var cached = OfflinePlayerManager.getInstance().getCache().get(uuid);
        return cached != null ? cached.name() : fallback;
    }
}
