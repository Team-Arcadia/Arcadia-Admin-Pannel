package com.vyrriox.arcadiaadminpanel.command;

import com.arcadia.lib.ArcadiaMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.vyrriox.arcadiaadminpanel.gui.AdminPanelMenu;
import com.vyrriox.arcadiaadminpanel.gui.WarnListMenu;
import com.vyrriox.arcadiaadminpanel.util.FTBDataReader;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.OfflinePlayerManager;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Command handler for /arcadia_adminpanel.
 * All sub-commands with pre-filled suggestions.
 *
 * @author vyrriox
 */
public final class AdminPanelCommand {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (context, builder) -> {
        // Online players
        Stream<String> online = context.getSource().getOnlinePlayerNames().stream();
        // Offline players
        Stream<String> offline = OfflinePlayerManager.getInstance().getCache().values().stream()
                .map(OfflinePlayerManager.CachedPlayerSummary::name);
        return SharedSuggestionProvider.suggest(Stream.concat(online, offline).distinct(), builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("arcadia_adminpanel")

                        // /arcadia_adminpanel panel [filter]
                        .then(Commands.literal("panel")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> executePanel(ctx, ""))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> executePanel(ctx,
                                                StringArgumentType.getString(ctx, "filter")))))

                        // /arcadia_adminpanel reload
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(2))
                                .executes(AdminPanelCommand::executeReload))

                        // /arcadia_adminpanel warn <targets> <reason>
                        .then(Commands.literal("warn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(AdminPanelCommand::executeWarn))))

                        // /arcadia_adminpanel warnlist <target>
                        .then(Commands.literal("warnlist")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeWarnList)))

                        // /arcadia_adminpanel checkwarn
                        .then(Commands.literal("checkwarn")
                                .executes(AdminPanelCommand::executeCheckWarn))

                        // /arcadia_adminpanel delwarn <target> <index>
                        .then(Commands.literal("delwarn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .suggests((context, builder) -> {
                                                    String targetName = StringArgumentType.getString(context, "target");
                                                    UUID targetUUID = resolveUUID(context.getSource(), targetName);
                                                    if (targetUUID != null) {
                                                        int count = WarnManager.getInstance().getWarns(targetUUID).size();
                                                        for (int i = 1; i <= count; i++) builder.suggest(i);
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(AdminPanelCommand::executeDelWarn))))

                        // /arcadia_adminpanel clearwarns <target>
                        .then(Commands.literal("clearwarns")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeClearWarns)))
        );
    }

    private static int executePanel(CommandContext<CommandSourceStack> context, String filter) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.player_only", (ServerPlayer) null)));
            return 0;
        }
        try {
            AdminPanelMenu.open(player, filter);
            return 1;
        } catch (Exception e) {
            source.sendFailure(ArcadiaMessages.error("Failed to open admin panel: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

        source.sendSuccess(() -> ArcadiaMessages.info(LanguageHelper.getText("reload.start", admin)), true);

        OfflinePlayerManager.getInstance().reload(source.getServer());
        FTBDataReader.clearCache();
        WarnManager.getInstance().reload();

        source.sendSuccess(() -> ArcadiaMessages.success(LanguageHelper.getText("reload.done", admin)), true);
        return 1;
    }

    private static int executeWarn(CommandContext<CommandSourceStack> context) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
            String reason = StringArgumentType.getString(context, "reason");
            CommandSourceStack source = context.getSource();
            String by = source.getTextName();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            for (ServerPlayer target : targets) {
                WarnManager.getInstance().addWarn(target.getUUID(), reason, by);

                source.sendSuccess(() -> ArcadiaMessages.success(
                        LanguageHelper.getText("warn.success", admin) + " §7(" + target.getName().getString() + ")"), true);

                target.sendSystemMessage(ArcadiaMessages.error(
                        String.format(LanguageHelper.getText("warn.notification", target), by)));
                target.sendSystemMessage(Component.literal("§cReason: §f" + reason));

                com.arcadia.lib.text.MessageHelper.sendTitle(target,
                        Component.literal("§c§l" + LanguageHelper.getText("warn.title", target)),
                        Component.literal("§e" + reason),
                        10, 70, 20);
                com.arcadia.lib.util.SoundHelper.error(target);
            }
            return targets.size();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeWarnList(CommandContext<CommandSourceStack> context) {
        try {
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            if (admin != null) {
                WarnListMenu.open(admin, targetUUID, targetName);
            } else {
                var warns = WarnManager.getInstance().getWarns(targetUUID);
                source.sendSuccess(() -> ArcadiaMessages.info("Warnings for " + targetName + ": " + warns.size()), false);
                for (var w : warns) {
                    source.sendSuccess(() -> Component.literal(" §8- §7[" + w.by() + "] §f" + w.reason()), false);
                }
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeCheckWarn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.player_only", (ServerPlayer) null)));
            return 0;
        }
        WarnListMenu.open(player, player.getUUID(), player.getName().getString());
        return 1;
    }

    private static int executeDelWarn(CommandContext<CommandSourceStack> context) {
        try {
            String targetName = StringArgumentType.getString(context, "target");
            int index = IntegerArgumentType.getInteger(context, "index");
            CommandSourceStack source = context.getSource();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            boolean success = WarnManager.getInstance().removeWarn(targetUUID, index);
            if (success) {
                source.sendSuccess(() -> ArcadiaMessages.success(
                        String.format(LanguageHelper.getText("warn.deleted", admin), index, targetName)), true);
            } else {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_index", admin)));
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeClearWarns(CommandContext<CommandSourceStack> context) {
        try {
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            int count = WarnManager.getInstance().clearWarns(targetUUID);
            source.sendSuccess(() -> ArcadiaMessages.success(
                    String.format(LanguageHelper.getText("warn.cleared", admin), targetName, count)), true);
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static UUID resolveUUID(CommandSourceStack source, String targetName) {
        ServerPlayer onlineTarget = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (onlineTarget != null) return onlineTarget.getUUID();
        for (var entry : OfflinePlayerManager.getInstance().getCache().entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(targetName)) return entry.getKey();
        }
        return null;
    }

    private AdminPanelCommand() {}
}
