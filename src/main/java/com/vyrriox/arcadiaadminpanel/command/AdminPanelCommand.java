package com.vyrriox.arcadiaadminpanel.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vyrriox.arcadiaadminpanel.gui.AdminPanelMenu;
import com.vyrriox.arcadiaadminpanel.gui.WarnListMenu;
import com.vyrriox.arcadiaadminpanel.util.FTBDataReader;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.OfflinePlayerManager;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Collection;
import java.util.UUID;

/**
 * Main command handler for /arcadiaadmin
 * Consolidated commands:
 * - panel
 * - warn
 * - warnlist
 * - checkwarn
 * - delwarn
 * - reload
 * 
 * @author vyrriox
 */
public class AdminPanelCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("arcadiaadmin")
                        // Subcommand: panel
                        .then(Commands.literal("panel")
                                .requires(source -> source.hasPermission(2))
                                .executes(AdminPanelCommand::executePanel))

                        // Subcommand: reload
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> {
                                    context.getSource().sendSuccess(
                                            () -> Component
                                                    .literal("§e[ArcadiaAdmin] Reloading offline player cache..."),
                                            true);
                                    OfflinePlayerManager.getInstance()
                                            .reload(context.getSource().getServer());
                                    // Also clear FTBReader cache
                                    FTBDataReader.clearCache();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("§a[ArcadiaAdmin] Reload complete!"), true);
                                    return 1;
                                }))

                        // Subcommand: warn <targets> <reason>
                        .then(Commands.literal("warn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(AdminPanelCommand::executeWarn))))

                        // Subcommand: warnlist <target>
                        .then(Commands.literal("warnlist")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .executes(AdminPanelCommand::executeWarnList)))

                        // Subcommand: checkwarn (Self view)
                        .then(Commands.literal("checkwarn")
                                .executes(AdminPanelCommand::executeCheckWarn))

                        // Subcommand: delwarn <target> <index>
                        .then(Commands.literal("delwarn")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            // Suggest online players
                                            for (String name : context.getSource().getOnlinePlayerNames()) {
                                                if (name.toLowerCase()
                                                        .startsWith(builder.getRemaining().toLowerCase())) {
                                                    builder.suggest(name);
                                                }
                                            }
                                            // Suggest offline players
                                            var cache = OfflinePlayerManager.getInstance().getCache();
                                            for (var entry : cache.values()) {
                                                if (entry.name().toLowerCase()
                                                        .startsWith(builder.getRemaining().toLowerCase())) {
                                                    builder.suggest(entry.name());
                                                }
                                            }
                                            // Don't duplicate? builder handles it usually
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .suggests((context, builder) -> {
                                                    String targetName = StringArgumentType.getString(context, "target");
                                                    UUID targetUUID = resolveUUID(context.getSource(), targetName);
                                                    if (targetUUID != null) {
                                                        int count = WarnManager.getInstance().getWarns(targetUUID)
                                                                .size();
                                                        for (int i = 1; i <= count; i++) {
                                                            builder.suggest(i);
                                                        }
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(AdminPanelCommand::executeDelWarn)))));
    }

    private static int executePanel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cThis command can only be used by players"));
            return 0;
        }
        try {
            AdminPanelMenu.open(player);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cFailed to open admin panel: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeWarn(CommandContext<CommandSourceStack> context) {
        try {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
            String reason = StringArgumentType.getString(context, "reason");
            CommandSourceStack source = context.getSource();
            String by = source.getTextName();

            for (ServerPlayer target : targets) {
                WarnManager.getInstance().addWarn(target.getUUID(), reason, by);

                // Notify Admin
                ServerPlayer adminPlayer = source.getEntity() instanceof ServerPlayer
                        ? (ServerPlayer) source.getEntity()
                        : null;
                source.sendSuccess(() -> Component.literal("§a" + LanguageHelper.getText("warn.success", adminPlayer)
                        + " §7(" + target.getName().getString() + ")"), true);

                // Notify Target (Chat)
                target.sendSystemMessage(Component.literal(
                        "§c[WARNING] " + String.format(LanguageHelper.getText("warn.notification", target), by)));
                target.sendSystemMessage(Component.literal("§cReason: §f" + reason));

                // Title & Subtitle (Send Animation Packet First)
                target.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20)); // FadeIn, Stay, FadeOut
                target.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal("§c§l" + LanguageHelper.getText("warn.title", target))));
                target.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§e" + reason)));

                // Sound
                target.playNotifySound(SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0f, 1.0f);
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
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(Component.literal("§c" + LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            if (admin != null) {
                WarnListMenu.open(admin, targetUUID, targetName);
            } else {
                var warns = WarnManager.getInstance().getWarns(targetUUID);
                source.sendSuccess(() -> Component.literal("§eWarnings for " + targetName + ": " + warns.size()),
                        false);
                for (var w : warns) {
                    source.sendSuccess(() -> Component.literal(" - [" + w.by() + "] " + w.reason()), false);
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
            source.sendFailure(Component.literal("§cOnly players can check their own warnings."));
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
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer ? (ServerPlayer) source.getEntity() : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(Component.literal("§c" + LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            boolean success = WarnManager.getInstance().removeWarn(targetUUID, index);
            if (success) {
                source.sendSuccess(
                        () -> Component.literal(
                                "§a" + String.format(LanguageHelper.getText("warn.deleted", admin), index, targetName)),
                        true);
            } else {
                source.sendFailure(Component.literal("§c" + LanguageHelper.getText("error.invalid_index", admin)));
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static UUID resolveUUID(CommandSourceStack source, String targetName) {
        ServerPlayer onlineTarget = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (onlineTarget != null) {
            return onlineTarget.getUUID();
        }
        var cache = OfflinePlayerManager.getInstance().getCache();
        for (var entry : cache.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(targetName)) {
                return entry.getKey();
            }
        }
        return null; // Not found
    }
}
