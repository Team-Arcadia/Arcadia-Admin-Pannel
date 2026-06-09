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
import com.arcadia.adminpanel.util.LoginTracker;
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

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

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

                        // /arcadia_adminpanel checkwarn — self-serve "see my own warns". Gated on the
                        // base OPEN node for auditability; the menu is read-only for self-views anyway.
                        .then(Commands.literal("checkwarn")
                                .requires(require(AdminPermissions.OPEN))
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
                                            if (!AdminPermissions.MUTE.check(sp)) return 0;
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

                        // /arcadia_adminpanel announce <title> [| <subtitle>] — server-wide flash
                        // announcement. Title and optional subtitle are split on a single literal
                        // pipe character so the whole message fits in one greedy argument.
                        .then(Commands.literal("announce")
                                .requires(require(AdminPermissions.ANNOUNCE))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(AdminPanelCommand::executeAnnounce)))

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

                        // /arcadia_adminpanel setnextspawn <target> — pin the admin's current
                        // position as the player's one-shot next-login spawn (debug teleport).
                        .then(Commands.literal("setnextspawn")
                                .requires(require(AdminPermissions.NEXT_SPAWN))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeSetNextSpawn)))

                        // /arcadia_adminpanel clearnextspawn <target>
                        .then(Commands.literal("clearnextspawn")
                                .requires(require(AdminPermissions.NEXT_SPAWN))
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(AdminPanelCommand::executeClearNextSpawn)))

                        // /arcadia_adminpanel nextspawnlist
                        .then(Commands.literal("nextspawnlist")
                                .requires(require(AdminPermissions.NEXT_SPAWN))
                                .executes(AdminPanelCommand::executeNextSpawnList))

                        // /arcadia_adminpanel jailradius [blocks] — show or set the max jail zone
                        // radius. Setting it also (re-)enables the anti-escape proximity sweep that
                        // teleports a jailed player back inside the zone if they get out.
                        .then(Commands.literal("jailradius")
                                .requires(require(AdminPermissions.SETJAIL))
                                .executes(AdminPanelCommand::executeJailRadiusShow)
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 1000))
                                        .executes(AdminPanelCommand::executeJailRadiusSet)))

                        // /arcadia_adminpanel loginqueue [on|off] — show or toggle the login-throttle
                        // queue at runtime (was config-only; the LOGIN_QUEUE node now actually gates it).
                        .then(Commands.literal("loginqueue")
                                .requires(require(AdminPermissions.LOGIN_QUEUE))
                                .executes(AdminPanelCommand::executeLoginQueueShow)
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"on", "off"}, b))
                                        .executes(AdminPanelCommand::executeLoginQueueSet)))

                        // /arcadia_adminpanel nametag … — colour / effect / style + hide-behind-walls.
                        // The whole tree gates on NAMETAG_EDIT; the global hide switch + exemptions
                        // additionally gate on NAMETAG_HIDE (a higher-impact, server-wide toggle).
                        .then(com.arcadia.adminpanel.command.NameTagCommand.build(
                                require(AdminPermissions.NAMETAG_EDIT),
                                require(AdminPermissions.NAMETAG_HIDE)))
        );
    }

    private static int executeLoginQueueShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        boolean on = com.arcadia.adminpanel.util.AdminConfig.get().loginQueueEnabled;
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("loginqueue.state", admin)
                        .replace("%state%", on ? "ON" : "OFF")), false);
        return 1;
    }

    private static int executeLoginQueueSet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String state = StringArgumentType.getString(context, "state").toLowerCase();
        boolean on;
        if (state.equals("on") || state.equals("true")) on = true;
        else if (state.equals("off") || state.equals("false")) on = false;
        else {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("loginqueue.invalid", admin)));
            return 0;
        }
        com.arcadia.adminpanel.util.AdminConfig.get().loginQueueEnabled = on;
        com.arcadia.adminpanel.util.AdminConfig.save();
        final boolean fOn = on;
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("loginqueue.set", admin)
                        .replace("%state%", fOn ? "ON" : "OFF")), true);
        return 1;
    }

    private static int executeJailRadiusShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        com.arcadia.adminpanel.util.AdminConfig.Data cfg = com.arcadia.adminpanel.util.AdminConfig.get();
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("jail.radius.current", admin)
                        .replace("%radius%", String.valueOf(cfg.jailProximityRadius))
                        .replace("%state%", cfg.jailEnforceProximity ? "ON" : "OFF")), false);
        return 1;
    }

    private static int executeJailRadiusSet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        int blocks = IntegerArgumentType.getInteger(context, "blocks");
        com.arcadia.adminpanel.util.AdminConfig.Data cfg = com.arcadia.adminpanel.util.AdminConfig.get();
        cfg.jailProximityRadius = blocks;
        cfg.jailEnforceProximity = true; // configuring a zone implies enabling anti-escape
        com.arcadia.adminpanel.util.AdminConfig.save();
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("jail.radius.set", admin)
                        .replace("%radius%", String.valueOf(blocks))), true);
        return 1;
    }

    private static int executeSetNextSpawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer admin)) {
            source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("error.player_only", (ServerPlayer) null)));
            return 0;
        }
        String targetName = StringArgumentType.getString(context, "target");
        UUID targetUUID = resolveUUID(source, targetName);
        if (targetUUID == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        String resolved = resolveName(source, targetUUID, targetName);
        com.arcadia.adminpanel.util.NextSpawnManager.getInstance().setFromAdmin(targetUUID, admin);
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("nextspawn.set", admin).replace("%player%", resolved)));
        return 1;
    }

    private static int executeClearNextSpawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(context, "target");
        UUID targetUUID = resolveUUID(source, targetName);
        if (targetUUID == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return 0;
        }
        boolean cleared = com.arcadia.adminpanel.util.NextSpawnManager.getInstance().clear(targetUUID);
        String resolved = resolveName(source, targetUUID, targetName);
        source.sendSuccess(() -> cleared
                ? ArcadiaMessages.success(LanguageHelper.getText("nextspawn.cleared", admin)
                        .replace("%player%", resolved))
                : ArcadiaMessages.info(LanguageHelper.getText("nextspawn.none", admin)
                        .replace("%player%", resolved)), false);
        return 1;
    }

    private static int executeNextSpawnList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        var all = com.arcadia.adminpanel.util.NextSpawnManager.getInstance().getAll();
        if (all.isEmpty()) {
            source.sendSuccess(() -> ArcadiaMessages.info(
                    LanguageHelper.getText("nextspawn.list.empty", admin)), false);
            return 1;
        }
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("nextspawn.list.header", admin)
                        .replace("%count%", String.valueOf(all.size()))), false);
        for (var entry : all.entrySet()) {
            UUID uuid = entry.getKey();
            var point = entry.getValue();
            String name = resolveName(source, uuid, uuid.toString().substring(0, 8));
            source.sendSuccess(() -> Component.literal(
                    " §8- §e" + name + " §7→ §f" + point.getShortDimension()
                            + " §7(" + point.getFormattedCoords() + ") §8by §7" + point.setBy()), false);
        }
        return 1;
    }

    /**
     * Server-wide announcement: pushes a vanilla title + optional subtitle to every online player
     * and plays a chime so people actually notice. Syntax:
     * {@code /arcadia_adminpanel announce <title>[| <subtitle>]} — the single pipe character is
     * the separator. Color codes (§a, &amp;c, …) are honoured in both title and subtitle.
     *
     * <p>Sound: vanilla {@code BLOCK_NOTE_BLOCK_BELL} at every player's position with a slight
     * pitch boost — close enough to a PA chime that players look up, gentle enough that it
     * doesn't blow earpieces. Title timings: 10t fade-in, 60t hold (3 s), 20t fade-out — feels
     * snappy without being annoying.</p>
     */
    private static int executeAnnounce(CommandContext<CommandSourceStack> ctx) {
        try {
            String raw = StringArgumentType.getString(ctx, "message").trim();
            if (raw.isEmpty()) return 0;

            String titleText;
            String subtitleText;
            int pipe = raw.indexOf('|');
            if (pipe < 0) {
                titleText = raw;
                subtitleText = null;
            } else {
                titleText = raw.substring(0, pipe).trim();
                String after = raw.substring(pipe + 1).trim();
                subtitleText = after.isEmpty() ? null : after;
            }
            if (titleText.isEmpty()) {
                ctx.getSource().sendFailure(ArcadiaMessages.error(
                        LanguageHelper.getText("announce.empty",
                                ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null)));
                return 0;
            }

            // Honour vanilla & color codes (&a -> §a) so staff can colour titles inline.
            Component title = Component.literal(net.minecraft.util.StringUtil.filterText(
                    titleText.replace('&', '§')));
            Component subtitle = subtitleText == null ? null : Component.literal(
                    net.minecraft.util.StringUtil.filterText(subtitleText.replace('&', '§')));

            var server = ctx.getSource().getServer();
            int count = 0;
            for (ServerPlayer target : server.getPlayerList().getPlayers()) {
                com.arcadia.lib.text.MessageHelper.sendTitle(target, title, subtitle, 10, 60, 20);
                com.arcadia.lib.util.SoundHelper.playAt(target,
                        net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 1.2f);
                count++;
            }

            ServerPlayer admin = ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
            final int delivered = count;
            ctx.getSource().sendSuccess(() -> ArcadiaMessages.success(
                    LanguageHelper.getText("announce.success", admin)
                            .replace("%count%", String.valueOf(delivered))), true);
            return delivered;
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Command execution failed", e);
            return 0;
        }
    }

    private static int executeMute(CommandContext<CommandSourceStack> ctx, String reason) {
        try {
            if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return 0;
            if (!StaffService.requireRole(ctx.getSource(), StaffRole.MOD)) return 0;
            // Granular node on top of the staff grade, mirroring the GUI's dual gate so the same
            // role config governs both the command and the Mute button.
            if (!AdminPermissions.MUTE.check(sp)) return 0;
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
        com.arcadia.adminpanel.util.NextSpawnManager.getInstance().reload();
        com.arcadia.adminpanel.util.NameTagManager.getInstance().reload();
        // Push the freshly-loaded name-tag state back to every online client.
        com.arcadia.adminpanel.util.NameTagManager.getInstance().syncAll(source.getServer());

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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
            LOGGER.error("[AdminPanel] Command execution failed", e);
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
        // Prefer an exact-case match; collect case-insensitive matches for a deterministic tie-break
        // (ConcurrentHashMap iteration order is undefined, so "first match wins" could act on a
        // different account across restarts when names collide via Mojang name reuse).
        UUID exact = null;
        java.util.List<UUID> ci = new java.util.ArrayList<>();
        for (var entry : OfflinePlayerManager.getInstance().getCache().entrySet()) {
            String n = entry.getValue().name();
            if (n.equals(targetName)) exact = entry.getKey();
            else if (n.equalsIgnoreCase(targetName)) ci.add(entry.getKey());
        }
        if (exact != null) return exact;
        if (ci.isEmpty()) return null;
        if (ci.size() == 1) return ci.get(0);
        // Ambiguous: most-recently-seen wins (stable across restarts).
        ci.sort(java.util.Comparator.<UUID>comparingLong(u -> {
            LoginTracker.LoginRecord r = LoginTracker.getInstance().get(u);
            return r == null ? 0L : Math.max(r.lastLoginMs(), r.firstSeenMs());
        }).reversed());
        return ci.get(0);
    }

    private AdminPanelCommand() {}
}
