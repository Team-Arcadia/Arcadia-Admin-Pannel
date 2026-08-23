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

    /**
     * Tab suggestions for the entity argument: every registered entity type (vanilla <em>and</em>
     * modpack mobs). Brigadier filters by the typed prefix client-side, so the full list — even a few
     * hundred entries on a big modpack — is fine. Non-living types still autocomplete but are rejected
     * at execution by {@link #isLivingType}.
     */
    private static final SuggestionProvider<CommandSourceStack> ENTITY_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ENTITY_TYPE.keySet(), builder);

    public static LiteralArgumentBuilder<CommandSourceStack> build(Predicate<CommandSourceStack> gate) {
        return Commands.literal("disguise")
                .requires(gate)

                // Server-wide operations, kept off the <target> branch so they cannot be reached by
                // a player who happens to be called "all".
                .then(Commands.literal("--all")
                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                .suggests(ENTITY_SUGGESTIONS)
                                .executes(DisguiseCommand::execAll)))
                .then(Commands.literal("--random")
                        .executes(DisguiseCommand::execRandomAll))
                .then(Commands.literal("--clear")
                        .executes(DisguiseCommand::execClearAll))
                .then(Commands.literal("--list")
                        .executes(DisguiseCommand::execList))

                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        // reset → clear the disguise
                        .then(Commands.literal("reset").executes(DisguiseCommand::execReset))
                        // random → pick a living type for them
                        .then(Commands.literal("random").executes(DisguiseCommand::execRandom))
                        // baby / adult → toggle the young variant where the mob has one
                        .then(Commands.literal("baby")
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"on", "off"}, b))
                                        .executes(DisguiseCommand::execBaby)))
                        // scale <0.25-4.0> → render size, visual only
                        .then(Commands.literal("scale")
                                .then(Commands.argument("value",
                                                com.mojang.brigadier.arguments.FloatArgumentType.floatArg(
                                                        DisguiseManager.MIN_SCALE, DisguiseManager.MAX_SCALE))
                                        .executes(DisguiseCommand::execScale)))
                        // name <on|off> → show the mob's own name above the disguise
                        .then(Commands.literal("name")
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"on", "off"}, b))
                                        .executes(DisguiseCommand::execShowName)))
                        // show → print the current disguise and its options
                        .then(Commands.literal("show").executes(DisguiseCommand::execShow))
                        // <entity> → set the disguise (any living mob, vanilla or modded)
                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                .suggests(ENTITY_SUGGESTIONS)
                                .executes(DisguiseCommand::execSet)));
    }

    // ── Options ──────────────────────────────────────────────────────────────

    private static int execBaby(CommandContext<CommandSourceStack> ctx) {
        boolean on = StringArgumentType.getString(ctx, "state").equalsIgnoreCase("on");
        return mutate(ctx, data -> data.withBaby(on), on ? "disguise.baby_on" : "disguise.baby_off", "");
    }

    private static int execScale(CommandContext<CommandSourceStack> ctx) {
        float value = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "value");
        return mutate(ctx, data -> data.withScale(value), "disguise.scale_set",
                String.format("%.2f", DisguiseManager.clampScale(value)));
    }

    private static int execShowName(CommandContext<CommandSourceStack> ctx) {
        boolean on = StringArgumentType.getString(ctx, "state").equalsIgnoreCase("on");
        return mutate(ctx, data -> data.withShowMobName(on),
                on ? "disguise.name_on" : "disguise.name_off", "");
    }

    /** Shared plumbing for the option sub-commands: resolve, mutate, broadcast, report. */
    private static int mutate(CommandContext<CommandSourceStack> ctx,
                              java.util.function.UnaryOperator<DisguiseManager.DisguiseData> change,
                              String messageKey, String value) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        String name = resolveName(source, uuid, targetName);
        if (DisguiseManager.getInstance().mutate(uuid, change) == null) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("disguise.none", admin).replace("%player%", name)));
            return 0;
        }
        DisguiseManager.getInstance().broadcastUpdate(source.getServer(), uuid);
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText(messageKey, admin)
                        .replace("%player%", name)
                        .replace("%value%", value)), true);
        return 1;
    }

    private static int execShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        String name = resolveName(source, uuid, targetName);
        DisguiseManager.DisguiseData data = DisguiseManager.getInstance().getData(uuid);
        if (data == null) {
            source.sendSuccess(() -> ArcadiaMessages.info(
                    LanguageHelper.getText("disguise.none", admin).replace("%player%", name)), false);
            return 1;
        }
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("disguise.show", admin)
                        .replace("%player%", name)
                        .replace("%value%", data.type().toString())
                        .replace("%baby%", String.valueOf(data.baby()))
                        .replace("%scale%", String.format("%.2f", data.scale()))
                        .replace("%name%", String.valueOf(data.showMobName()))), false);
        return 1;
    }

    // ── Random and server-wide ───────────────────────────────────────────────

    private static int execRandom(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        ResourceLocation id = randomLivingType(source.getLevel());
        if (id == null) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("disguise.no_random", admin)));
            return 0;
        }
        String name = resolveName(source, uuid, targetName);
        DisguiseManager.getInstance().setDisguise(uuid, id);
        DisguiseManager.getInstance().broadcastUpdate(source.getServer(), uuid);
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("disguise.set", admin)
                        .replace("%player%", name)
                        .replace("%value%", id.toString())), true);
        return 1;
    }

    private static int execAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "entity");
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                || !isLivingType(BuiltInRegistries.ENTITY_TYPE.get(id), source.getLevel())) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("disguise.not_living", admin).replace("%value%", id.toString())));
            return 0;
        }
        int n = 0;
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            DisguiseManager.getInstance().setDisguise(p.getUUID(), id);
            DisguiseManager.getInstance().broadcastUpdate(source.getServer(), p.getUUID());
            n++;
        }
        final int count = n;
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("disguise.all", admin)
                        .replace("%count%", String.valueOf(count))
                        .replace("%value%", id.toString())), true);
        return 1;
    }

    private static int execRandomAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        int n = 0;
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            ResourceLocation id = randomLivingType(source.getLevel());
            if (id == null) continue;
            DisguiseManager.getInstance().setDisguise(p.getUUID(), id);
            DisguiseManager.getInstance().broadcastUpdate(source.getServer(), p.getUUID());
            n++;
        }
        final int count = n;
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("disguise.random_all", admin)
                        .replace("%count%", String.valueOf(count))), true);
        return 1;
    }

    private static int execClearAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        var affected = new java.util.ArrayList<>(DisguiseManager.getInstance().getAll().keySet());
        int n = DisguiseManager.getInstance().clearAll();
        for (UUID uuid : affected) {
            DisguiseManager.getInstance().broadcastUpdate(source.getServer(), uuid);
        }
        final int count = n;
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("disguise.clear_all", admin)
                        .replace("%count%", String.valueOf(count))), true);
        return 1;
    }

    private static int execList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        var all = DisguiseManager.getInstance().getAll();
        if (all.isEmpty()) {
            source.sendSuccess(() -> ArcadiaMessages.info(
                    LanguageHelper.getText("disguise.list.empty", admin)), false);
            return 1;
        }
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("disguise.list.header", admin)
                        .replace("%count%", String.valueOf(all.size()))), false);
        all.forEach((uuid, data) -> {
            String name = resolveName(source, uuid, uuid.toString().substring(0, 8));
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "§7- §f" + name + " §8" + data.type()
                            + (data.baby() ? " §7(baby)" : "")
                            + (Math.abs(data.scale() - 1.0F) > 0.01F
                                    ? " §7x" + String.format("%.2f", data.scale()) : "")), false);
        });
        return 1;
    }

    /**
     * Picks a random living entity type from the registry.
     *
     * <p>Probing by creation is the only reliable way to know whether a modded type is living, and
     * doing that across the whole registry would be expensive, so this samples at random and gives
     * up after a bounded number of attempts rather than scanning.</p>
     */
    private static ResourceLocation randomLivingType(ServerLevel level) {
        var keys = new java.util.ArrayList<>(BuiltInRegistries.ENTITY_TYPE.keySet());
        if (keys.isEmpty()) return null;
        var random = level.getRandom();
        for (int attempt = 0; attempt < 40; attempt++) {
            ResourceLocation id = keys.get(random.nextInt(keys.size()));
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type != null && isLivingType(type, level)) return id;
        }
        return null;
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
