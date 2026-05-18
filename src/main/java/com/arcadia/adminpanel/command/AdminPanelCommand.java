package com.arcadia.adminpanel.command;

import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.staff.StaffActions;
import com.arcadia.lib.staff.StaffChatService;
import com.arcadia.lib.staff.StaffRole;
import com.arcadia.lib.staff.StaffService;
import com.arcadia.lib.text.TextFormatter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.arcadia.adminpanel.gui.AdminPanelMenu;
import com.arcadia.adminpanel.gui.WarnListMenu;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.FTBDataReader;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.WarnManager;
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

    /**
     * Requires-predicate for a granular {@link AdminPermissions} node. Non-player sources (console,
     * command blocks, functions) always pass — preserves automation. Player sources pass if they
     * have OP level 2 (vanilla short-circuit) OR the granular node OR the legacy {@code arcadia.staff.mod}
     * node (so existing LuckPerms groups don't lose access).
     */
    private static java.util.function.Predicate<CommandSourceStack> require(AdminPermissions perm) {
        return source -> {
            if (!(source.getEntity() instanceof ServerPlayer sp)) return source.hasPermission(2);
            if (sp.hasPermissions(2)) return true;
            if (perm.check(sp)) return true;
            return com.arcadia.lib.permissions.PermissionService.hasPermissionStrict(sp, "arcadia.staff.mod");
        };
    }

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
                                .requires(require(AdminPermissions.OPEN))
                                .executes(ctx -> executePanel(ctx, ""))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> executePanel(ctx,
                                                StringArgumentType.getString(ctx, "filter")))))

                        // /arcadia_adminpanel reload
                        .then(Commands.literal("reload")
                                .requires(require(AdminPermissions.RELOAD))
                                .executes(AdminPanelCommand::executeReload))

                        // /arcadia_adminpanel warn <targets> <reason> — entity selector form (online,
                        // multi-target). Preserved for backwards compat with /warn @a etc.
                        .then(Commands.literal("warn")
                                .requires(require(AdminPermissions.WARN_EDIT))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(AdminPanelCommand::executeWarn))))

                        // /arcadia_adminpanel warnoffline <name> <reason> — string-name form. Works
                        // for both online AND offline targets; offline players get the warn now and
                        // see the notification on their next login.
                        .then(Commands.literal("warnoffline")
                                .requires(require(AdminPermissions.WARN_EDIT))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(AdminPanelCommand::executeWarnOffline))))

                        // /arcadia_adminpanel warnlist <target>
                        .then(Commands.literal("warnlist")
                                .requires(require(AdminPermissions.WARN_VIEW))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeWarnList)))

                        // /arcadia_adminpanel checkwarn
                        .then(Commands.literal("checkwarn")
                                .executes(AdminPanelCommand::executeCheckWarn))

                        // /arcadia_adminpanel delwarn <target> <index>
                        .then(Commands.literal("delwarn")
                                .requires(require(AdminPermissions.WARN_EDIT))
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
                                .requires(require(AdminPermissions.WARN_EDIT))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeClearWarns)))

                        // ── Staff commands (moved from lib) ─────────────────────

                        // /arcadia_adminpanel staffchat <message>
                        .then(Commands.literal("staffchat")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
                                            if (!StaffService.requireRole(ctx.getSource(), StaffRole.HELPER)) return 0;
                                            StaffChatService.broadcast(sp, StringArgumentType.getString(ctx, "message"));
                                            return 1;
                                        })))

                        // /arcadia_adminpanel stafftoggle
                        .then(Commands.literal("stafftoggle")
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
                                    if (!StaffService.requireRole(ctx.getSource(), StaffRole.HELPER)) return 0;
                                    boolean on = StaffChatService.toggle(sp.getUUID());
                                    sp.sendSystemMessage(ArcadiaMessages.info(
                                            LanguageHelper.getText(on ? "staff.chat.enabled" : "staff.chat.disabled", sp)));
                                    return 1;
                                }))

                        // /arcadia_adminpanel stafflist
                        .then(Commands.literal("stafflist")
                                .executes(ctx -> {
                                    if (!StaffService.requireRole(ctx.getSource(), StaffRole.HELPER)) return 0;
                                    var staff = StaffService.getStaffOnline();
                                    ServerPlayer admin = ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
                                    if (staff.isEmpty()) {
                                        ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(
                                                LanguageHelper.getText("staff.none_online", admin)), false);
                                    } else {
                                        StringBuilder sb = new StringBuilder();
                                        sb.append(LanguageHelper.getText("staff.online", admin)
                                                .replace("%d", String.valueOf(staff.size()))).append(" ");
                                        for (int i = 0; i < staff.size(); i++) {
                                            if (i > 0) sb.append(", ");
                                            ServerPlayer s = staff.get(i);
                                            sb.append("§").append(StaffService.getRole(s).getColor().getChar())
                                              .append(s.getName().getString());
                                        }
                                        ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(sb.toString()), false);
                                    }
                                    return 1;
                                }))

                        // /arcadia_adminpanel mute <player> <minutes> [reason]
                        .then(Commands.literal("mute")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("minutes", LongArgumentType.longArg(1))
                                                .executes(ctx -> executeMute(ctx, null))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> executeMute(ctx,
                                                                StringArgumentType.getString(ctx, "reason")))))))

                        // /arcadia_adminpanel unmute <player>
                        .then(Commands.literal("unmute")
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
                                            if (!StaffService.requireRole(ctx.getSource(), StaffRole.MOD)) return 0;
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            StaffActions.unmute(target.getUUID(), sp);
                                            return 1;
                                        })))

                        // ── Jail commands ────────────────────────────────────────

                        // /arcadia_adminpanel setjail
                        .then(Commands.literal("setjail")
                                .requires(require(AdminPermissions.SETJAIL))
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
                                    JailManager.getInstance().setJailLocation(sp);
                                    sp.sendSystemMessage(ArcadiaMessages.success(
                                            LanguageHelper.getText("jail.location.set", sp)));
                                    return 1;
                                }))

                        // /arcadia_adminpanel jail <player> <minutes> [reason]
                        // minutes = 0 for permanent
                        .then(Commands.literal("jail")
                                .requires(require(AdminPermissions.JAIL))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .then(Commands.argument("minutes", LongArgumentType.longArg(0))
                                                .executes(ctx -> executeJail(ctx, null))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> executeJail(ctx,
                                                                StringArgumentType.getString(ctx, "reason")))))))

                        // /arcadia_adminpanel unjail <target>
                        .then(Commands.literal("unjail")
                                .requires(require(AdminPermissions.JAIL))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeUnjail)))

                        // /arcadia_adminpanel jaillist
                        .then(Commands.literal("jaillist")
                                .requires(require(AdminPermissions.JAIL))
                                .executes(AdminPanelCommand::executeJailList))

                        // /arcadia_adminpanel givebaton — drops a Jail Baton into the staff member's
                        // inventory. Gated on JAIL since the item itself is gated on JAIL.
                        .then(Commands.literal("givebaton")
                                .requires(require(AdminPermissions.JAIL))
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
                                        ctx.getSource().sendFailure(ArcadiaMessages.error(
                                                LanguageHelper.getText("error.player_only", (ServerPlayer) null)));
                                        return 0;
                                    }
                                    var stack = new net.minecraft.world.item.ItemStack(
                                            com.arcadia.adminpanel.item.AdminPanelItems.JAIL_BATON.get());
                                    if (!sp.getInventory().add(stack)) sp.drop(stack, false);
                                    sp.sendSystemMessage(ArcadiaMessages.success(
                                            LanguageHelper.getText("baton.given", sp)));
                                    return 1;
                                }))
        );
    }

    private static int executeMute(CommandContext<CommandSourceStack> ctx, String reason) {
        try {
            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
            if (!StaffService.requireRole(ctx.getSource(), StaffRole.MOD)) return 0;
            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
            long mins = LongArgumentType.getLong(ctx, "minutes");
            StaffActions.mute(target.getUUID(), sp, reason, mins * 60_000L);
            return 1;
        } catch (Exception e) {
            return 0;
        }
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
            source.sendFailure(ArcadiaMessages.error(
                    String.format(LanguageHelper.getText("error.open_panel", player), e.getMessage())));
            return 0;
        }
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

        source.sendSuccess(() -> ArcadiaMessages.info(LanguageHelper.getText("reload.start", admin)), true);

        com.arcadia.adminpanel.util.AdminConfig.reload();
        OfflinePlayerManager.getInstance().reload(source.getServer());
        FTBDataReader.clearCache();
        FTBTeamsReader.clearCache();
        com.arcadia.adminpanel.util.FTBChunksReader.clearCache();
        AdminPermissions.invalidateAll();
        WarnManager.getInstance().reload();

        source.sendSuccess(() -> ArcadiaMessages.success(LanguageHelper.getText("reload.done", admin)), true);
        return 1;
    }

    /**
     * Offline-capable warn. Resolves the target name against (a) the online player list, (b) the
     * scanned offline-player cache, then adds the warn. Online targets get the same chat / title /
     * sound treatment as {@link #executeWarn}; offline targets just get the row written, and the
     * notification fires on their next login through {@link WarnPolicy#notifyOnJoin}.
     *
     * <p>Why a separate command instead of overloading {@code warn}? Brigadier doesn't allow two
     * sibling argument types under the same literal, and {@code EntityArgument.players()} only
     * resolves entities that exist right now. Splitting keeps both ergonomics: selectors stay on
     * {@code warn @a[...]}, and offline targets get a dedicated entry point.</p>
     */
    private static int executeWarnOffline(CommandContext<CommandSourceStack> context) {
        try {
            String targetName = StringArgumentType.getString(context, "target");
            String reason = StringArgumentType.getString(context, "reason");
            CommandSourceStack source = context.getSource();
            String by = source.getTextName();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(ArcadiaMessages.error(
                        LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }
            // Preserve the cache spelling — it's what shows up in the warn list afterwards.
            String resolvedName = resolveName(source, targetUUID, targetName);

            WarnManager.getInstance().addWarn(targetUUID, reason, by);

            source.sendSuccess(() -> ArcadiaMessages.success(
                    LanguageHelper.getText("warn.success", admin) + " §7(" + resolvedName + ")"), true);

            // If the player happens to be online right now, give them the full treatment.
            ServerPlayer onlineTarget = source.getServer().getPlayerList().getPlayer(targetUUID);
            if (onlineTarget != null) {
                onlineTarget.sendSystemMessage(ArcadiaMessages.error(
                        String.format(LanguageHelper.getText("warn.notification", onlineTarget), by)));
                onlineTarget.sendSystemMessage(Component.literal("§c" +
                        LanguageHelper.getText("warn.reason_prefix", onlineTarget) + " §f" + reason));
                com.arcadia.lib.text.MessageHelper.sendTitle(onlineTarget,
                        Component.literal("§c§l" + LanguageHelper.getText("warn.title", onlineTarget)),
                        Component.literal("§e" + reason),
                        10, 70, 20);
                com.arcadia.lib.util.SoundHelper.error(onlineTarget);
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /** Pretty-prints a name from the offline cache if the provided string was case-shifted. */
    private static String resolveName(CommandSourceStack source, UUID uuid, String fallback) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) return online.getName().getString();
        var cached = OfflinePlayerManager.getInstance().getCache().get(uuid);
        return cached != null ? cached.name() : fallback;
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
                target.sendSystemMessage(Component.literal("§c" +
                        LanguageHelper.getText("warn.reason_prefix", target) + " §f" + reason));

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
                source.sendSuccess(() -> ArcadiaMessages.info(
                        String.format(LanguageHelper.getText("warn.list_console", admin), targetName, warns.size())), false);
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

    private static int executeJail(CommandContext<CommandSourceStack> ctx, String reason) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getEntity() instanceof ServerPlayer sp)) return 0;
            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
            long mins = LongArgumentType.getLong(ctx, "minutes");
            String r = reason != null ? reason : LanguageHelper.getText("misc.admin_action", sp);
            ServerPlayer admin = sp;

            if (!JailManager.getInstance().hasJailLocation()) {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("jail.no_location", admin)));
                return 0;
            }

            JailManager.getInstance().jail(target, r, sp.getName().getString(),
                    mins > 0 ? mins * 60_000L : 0, source.getServer());

            // Notify target
            if (mins > 0) {
                target.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("jail.notify", target)
                                .replace("%time%", TextFormatter.formatMs(mins * 60_000L))
                                .replace("%reason%", r)));
            } else {
                target.sendSystemMessage(ArcadiaMessages.error(
                        LanguageHelper.getText("jail.notify.permanent", target)
                                .replace("%reason%", r)));
            }

            // Notify admin
            source.sendSuccess(() -> ArcadiaMessages.success(
                    LanguageHelper.getText("jail.success", admin)
                            .replace("%player%", target.getName().getString())
                            .replace("%time%", mins > 0 ? TextFormatter.formatMs(mins * 60_000L)
                                    : LanguageHelper.getText("jail.permanent", admin))), true);
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeUnjail(CommandContext<CommandSourceStack> context) {
        try {
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;

            UUID targetUUID = resolveUUID(source, targetName);
            if (targetUUID == null) {
                source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
                return 0;
            }

            boolean success = JailManager.getInstance().unjail(targetUUID, source.getServer());
            if (success) {
                source.sendSuccess(() -> ArcadiaMessages.success(
                        LanguageHelper.getText("jail.unjail.success", admin)
                                .replace("%player%", targetName)), true);

                // Notify target if online
                ServerPlayer target = source.getServer().getPlayerList().getPlayer(targetUUID);
                if (target != null) {
                    target.sendSystemMessage(ArcadiaMessages.success(
                            LanguageHelper.getText("jail.released", target)));
                }
            } else {
                source.sendFailure(ArcadiaMessages.error(
                        LanguageHelper.getText("jail.not_jailed", admin)
                                .replace("%player%", targetName)));
            }
            return 1;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    private static int executeJailList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        var jailed = JailManager.getInstance().getAllJailed();

        if (jailed.isEmpty()) {
            source.sendSuccess(() -> ArcadiaMessages.info(
                    LanguageHelper.getText("jail.list.empty", admin)), false);
            return 1;
        }

        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("jail.list.header", admin)
                        .replace("%count%", String.valueOf(jailed.size()))), false);

        for (var entry : jailed.entrySet()) {
            UUID uuid = entry.getKey();
            JailManager.JailEntry jail = entry.getValue();
            // Resolve name
            String name = uuid.toString().substring(0, 8);
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
            if (online != null) name = online.getName().getString();
            else {
                var cached = OfflinePlayerManager.getInstance().getCache().get(uuid);
                if (cached != null) name = cached.name();
            }

            String remaining = jail.durationMs() > 0
                    ? TextFormatter.formatMs(jail.getRemainingMs())
                    : LanguageHelper.getText("jail.permanent", admin);
            String finalName = name;
            source.sendSuccess(() -> Component.literal(
                    " §8- §e" + finalName + " §7(" + remaining + ") §8by §7" + jail.jailedBy()
                            + " §8— §7" + jail.reason()), false);
        }
        return 1;
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
