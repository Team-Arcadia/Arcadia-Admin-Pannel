package com.arcadia.adminpanel.command;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.adminpanel.gui.AfkListMenu;
import com.arcadia.adminpanel.gui.AltGroupsMenu;
import com.arcadia.adminpanel.gui.AuditLogMenu;
import com.arcadia.adminpanel.gui.BanListMenu;
import com.arcadia.adminpanel.gui.ChunkBrowserMenu;
import com.arcadia.adminpanel.gui.ClientModsMenu;
import com.arcadia.adminpanel.gui.DeathSnapshotMenu;
import com.arcadia.adminpanel.gui.HistoryMenu;
import com.arcadia.adminpanel.gui.InventoryBackupMenu;
import com.arcadia.adminpanel.gui.InventoryEditMenu;
import com.arcadia.adminpanel.gui.LagPanelMenu;
import com.arcadia.adminpanel.gui.NotesMenu;
import com.arcadia.adminpanel.gui.RadarMenu;
import com.arcadia.adminpanel.gui.SessionsMenu;
import com.arcadia.adminpanel.gui.StaffToolsMenu;
import com.arcadia.adminpanel.gui.TemplatesMenu;
import com.arcadia.adminpanel.gui.WatchlistMenu;
import com.arcadia.adminpanel.gui.WorldControlMenu;
import com.arcadia.adminpanel.util.AdminConfig;
import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.AutoBroadcast;
import com.arcadia.adminpanel.util.BackManager;
import com.arcadia.adminpanel.util.BanManager;
import com.arcadia.adminpanel.util.ChatControl;
import com.arcadia.adminpanel.util.FreezeManager;
import com.arcadia.adminpanel.util.InventoryBackupManager;
import com.arcadia.adminpanel.util.LagMonitor;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.MailManager;
import com.arcadia.adminpanel.util.NotesManager;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.RestartScheduler;
import com.arcadia.adminpanel.util.SanctionTemplates;
import com.arcadia.adminpanel.util.SelectionManager;
import com.arcadia.adminpanel.util.SilentMode;
import com.arcadia.adminpanel.util.SpectateManager;
import com.arcadia.adminpanel.util.SpyManager;
import com.arcadia.adminpanel.util.VanishManager;
import com.arcadia.adminpanel.util.WatchlistManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Command surface for the 1.3.0 tooling.
 *
 * <p>Everything reachable from the new menus also has a command, for three reasons: console and
 * automation cannot click, a keybind beats four clicks during an incident, and a command is what a
 * staff member types when they already know exactly what they want.</p>
 *
 * <p>Built as one subtree so it can be grafted onto the existing {@code /arcadia_adminpanel} root
 * without touching its registration. Each branch carries its own permission predicate; there is no
 * shared gate that could silently widen access to a node it was not meant to cover.</p>
 *
 * @author vyrriox
 */
public final class StaffOpsCommand {

    private StaffOpsCommand() {}

    /** Item ids from the live registry, so modded items are offered exactly like vanilla ones. */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>
            ITEM_SUGGESTIONS = (ctx, builder) -> SharedSuggestionProvider.suggestResource(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet(), builder);

    /** Builds the whole 1.3.0 subtree. Returns a list because Brigadier grafts one node at a time. */
    public static List<ArgumentBuilder<CommandSourceStack, ?>> build(
            java.util.function.Function<AdminPermissions, Predicate<CommandSourceStack>> gate) {

        return List.of(
                // -- Screens -----------------------------------------------------
                lit("tools", gate.apply(AdminPermissions.OPEN),
                        ctx -> withPlayer(ctx, StaffToolsMenu::open)),
                lit("lag", gate.apply(AdminPermissions.PERFORMANCE),
                        StaffOpsCommand::lagSummary),
                lit("lagpanel", gate.apply(AdminPermissions.PERFORMANCE),
                        ctx -> withPlayer(ctx, LagPanelMenu::open)),
                lit("chunks", gate.apply(AdminPermissions.CHUNKS),
                        ctx -> withPlayer(ctx, ChunkBrowserMenu::open)),
                lit("world", gate.apply(AdminPermissions.WORLD),
                        ctx -> withPlayer(ctx, WorldControlMenu::open)),
                lit("sessions", gate.apply(AdminPermissions.SESSIONS),
                        ctx -> withPlayer(ctx, SessionsMenu::open)),
                lit("afklist", gate.apply(AdminPermissions.AFK),
                        ctx -> withPlayer(ctx, AfkListMenu::open)),
                lit("banlist", gate.apply(AdminPermissions.BAN),
                        ctx -> withPlayer(ctx, BanListMenu::open)),
                lit("watchlist", gate.apply(AdminPermissions.WATCHLIST),
                        ctx -> withPlayer(ctx, WatchlistMenu::open)),
                lit("radar", gate.apply(AdminPermissions.RADAR),
                        ctx -> withPlayer(ctx, RadarMenu::open)),
                lit("alts", gate.apply(AdminPermissions.ALTS),
                        ctx -> withPlayer(ctx, AltGroupsMenu::open)),
                lit("clientmods", gate.apply(AdminPermissions.CLIENT_MODS),
                        ctx -> withPlayer(ctx, ClientModsMenu::openOverview)),

                // -- Personal toggles --------------------------------------------
                lit("vanish", gate.apply(AdminPermissions.VANISH), StaffOpsCommand::vanish),
                lit("silent", gate.apply(AdminPermissions.SILENT), StaffOpsCommand::silent),
                lit("cmdspy", gate.apply(AdminPermissions.SPY), StaffOpsCommand::cmdSpy),
                lit("socialspy", gate.apply(AdminPermissions.SPY), StaffOpsCommand::socialSpy),
                lit("back", gate.apply(AdminPermissions.BACK), StaffOpsCommand::back),

                // -- Chat control ------------------------------------------------
                lit("chatlock", gate.apply(AdminPermissions.CHAT_CONTROL), StaffOpsCommand::chatLock),
                lit("clearchat", gate.apply(AdminPermissions.CHAT_CONTROL), StaffOpsCommand::clearChat),

                // -- Per-player --------------------------------------------------
                target("freeze", gate.apply(AdminPermissions.FREEZE), StaffOpsCommand::freeze)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(StaffOpsCommand::freezeWithReason))),
                target("unfreeze", gate.apply(AdminPermissions.FREEZE), StaffOpsCommand::unfreeze),
                target("spectate", gate.apply(AdminPermissions.SPECTATE), StaffOpsCommand::spectate),
                lit("unspectate", gate.apply(AdminPermissions.SPECTATE), StaffOpsCommand::unspectate),
                target("history", gate.apply(AdminPermissions.HISTORY), StaffOpsCommand::history),
                target("notes", gate.apply(AdminPermissions.NOTES), StaffOpsCommand::notes),
                target("note", gate.apply(AdminPermissions.NOTES), null)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(StaffOpsCommand::note))),
                target("watch", gate.apply(AdminPermissions.WATCHLIST), StaffOpsCommand::watch)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(StaffOpsCommand::watchWithReason))),
                target("unwatch", gate.apply(AdminPermissions.WATCHLIST), StaffOpsCommand::unwatch),
                target("audit", gate.apply(AdminPermissions.AUDIT), StaffOpsCommand::audit),
                target("invedit", gate.apply(AdminPermissions.INV_EDIT), StaffOpsCommand::invEdit),
                target("deaths", gate.apply(AdminPermissions.DEATH_RESTORE), StaffOpsCommand::deaths),
                target("invbackup", gate.apply(AdminPermissions.INV_BACKUP), StaffOpsCommand::invBackup),
                lit("invbackupnow", gate.apply(AdminPermissions.INV_BACKUP), StaffOpsCommand::backupAll)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .executes(StaffOpsCommand::backupOne)),
                target("whereis", gate.apply(AdminPermissions.INFO), StaffOpsCommand::whereIs),
                lit("online", gate.apply(AdminPermissions.OPEN), StaffOpsCommand::onlineList),
                lit("help", gate.apply(AdminPermissions.HELP), ctx -> help(ctx, null))
                        .then(Commands.argument("section", StringArgumentType.word())
                                .suggests(SECTION_SUGGESTIONS)
                                .executes(ctx -> help(ctx,
                                        StringArgumentType.getString(ctx, "section")))),
                target("templates", gate.apply(AdminPermissions.TEMPLATES), StaffOpsCommand::templates),
                target("select", gate.apply(AdminPermissions.BULK), StaffOpsCommand::select),
                lit("selectclear", gate.apply(AdminPermissions.BULK), StaffOpsCommand::selectClear),

                target("mail", gate.apply(AdminPermissions.MAIL), null)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(StaffOpsCommand::mail))),

                Commands.literal("tempban")
                        .requires(gate.apply(AdminPermissions.BAN))
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(0))
                                        .executes(ctx -> tempban(ctx, null))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> tempban(ctx,
                                                        StringArgumentType.getString(ctx, "reason")))))),

                // The item is a plain string with registry suggestions rather than an ItemArgument:
                // the vanilla argument type needs a CommandBuildContext that this grafted subtree
                // does not receive, and the id is validated against the registry when it is used.
                Commands.literal("giveitem")
                        .requires(gate.apply(AdminPermissions.GIVE_ITEM))
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                                .then(Commands.argument("item", StringArgumentType.string())
                                        .suggests(ITEM_SUGGESTIONS)
                                        .executes(ctx -> giveItem(ctx, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 6400))
                                                .executes(ctx -> giveItem(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count")))))),

                Commands.literal("restart")
                        .requires(gate.apply(AdminPermissions.RESTART))
                        .then(Commands.literal("cancel").executes(StaffOpsCommand::restartCancel))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 1440))
                                .executes(ctx -> restart(ctx, null))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> restart(ctx,
                                                StringArgumentType.getString(ctx, "reason"))))),

                Commands.literal("broadcast")
                        .requires(gate.apply(AdminPermissions.BROADCAST))
                        .executes(StaffOpsCommand::broadcastNow)
                        .then(Commands.literal("toggle").executes(StaffOpsCommand::broadcastToggle))
        );
    }

    // -- Builders ------------------------------------------------------------

    private static LiteralArgumentBuilder<CommandSourceStack> lit(
            String name, Predicate<CommandSourceStack> gate,
            @Nullable com.mojang.brigadier.Command<CommandSourceStack> action) {
        LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(name).requires(gate);
        if (action != null) b.executes(action);
        return b;
    }

    /** A literal taking one player name. */
    private static LiteralArgumentBuilder<CommandSourceStack> target(
            String name, Predicate<CommandSourceStack> gate,
            @Nullable com.mojang.brigadier.Command<CommandSourceStack> action) {
        LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(name).requires(gate);
        if (action != null) {
            b.then(Commands.argument("target", StringArgumentType.string())
                    .suggests(AdminPanelCommand.PLAYER_SUGGESTIONS)
                    .executes(action));
        }
        return b;
    }

    // -- Helpers -------------------------------------------------------------

    private static int withPlayer(CommandContext<CommandSourceStack> ctx,
                                  java.util.function.Consumer<ServerPlayer> action) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("error.player_only", (ServerPlayer) null)));
            return 0;
        }
        action.accept(sp);
        return 1;
    }

    @Nullable
    private static ServerPlayer self(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getEntity() instanceof ServerPlayer sp ? sp : null;
    }

    private static String targetName(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "target");
    }

    @Nullable
    private static UUID targetUuid(CommandContext<CommandSourceStack> ctx) {
        return AdminPanelCommand.resolveUUID(ctx.getSource(), targetName(ctx));
    }

    private static void ok(CommandContext<CommandSourceStack> ctx, String key, String... pairs) {
        ServerPlayer admin = self(ctx);
        String text = LanguageHelper.getText(key, admin);
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace(pairs[i], pairs[i + 1]);
        final String out = text;
        ctx.getSource().sendSuccess(() -> ArcadiaMessages.success(out), false);
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String key, String... pairs) {
        ServerPlayer admin = self(ctx);
        String text = LanguageHelper.getText(key, admin);
        for (int i = 0; i + 1 < pairs.length; i += 2) text = text.replace(pairs[i], pairs[i + 1]);
        ctx.getSource().sendFailure(ArcadiaMessages.error(text));
    }

    // -- Personal toggles ----------------------------------------------------

    private static int vanish(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        VanishManager.toggle(sp, sp);
        return 1;
    }

    private static int silent(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        boolean on = SilentMode.toggle(sp.getUUID());
        ok(ctx, on ? "silent.on" : "silent.off");
        return 1;
    }

    private static int cmdSpy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        boolean on = SpyManager.toggleCommandSpy(sp.getUUID());
        ok(ctx, on ? "spy.cmd.on" : "spy.cmd.off");
        return 1;
    }

    private static int socialSpy(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        boolean on = SpyManager.toggleSocialSpy(sp.getUUID());
        ok(ctx, on ? "spy.social.on" : "spy.social.off");
        return 1;
    }

    private static int back(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        if (!BackManager.teleportBack(sp)) {
            fail(ctx, "back.none");
            return 0;
        }
        com.arcadia.lib.util.SoundHelper.playAt(sp, com.arcadia.lib.util.SoundHelper.TELEPORT);
        return 1;
    }

    // -- Chat ----------------------------------------------------------------

    private static int chatLock(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        ChatControl.toggleLock(sp);
        return 1;
    }

    private static int clearChat(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = self(ctx);
        if (sp == null) return 0;
        ChatControl.clearAll(sp);
        return 1;
    }

    // -- Per-player ----------------------------------------------------------

    private static int freeze(CommandContext<CommandSourceStack> ctx) {
        return doFreeze(ctx, "");
    }

    private static int freezeWithReason(CommandContext<CommandSourceStack> ctx) {
        return doFreeze(ctx, StringArgumentType.getString(ctx, "reason"));
    }

    private static int doFreeze(CommandContext<CommandSourceStack> ctx, String reason) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName(ctx));
        if (target == null) {
            fail(ctx, "error.player_offline");
            return 0;
        }
        if (FreezeManager.freeze(admin, target, reason)) {
            ok(ctx, "freeze.done", "%player%", target.getName().getString());
            return 1;
        }
        fail(ctx, "freeze.already", "%player%", target.getName().getString());
        return 0;
    }

    private static int unfreeze(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        String name = targetName(ctx);
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target != null) {
            if (FreezeManager.unfreeze(admin, target)) {
                ok(ctx, "freeze.lifted", "%player%", target.getName().getString());
                return 1;
            }
            fail(ctx, "freeze.not_frozen", "%player%", target.getName().getString());
            return 0;
        }

        // The freeze outlives a disconnect, so releasing has to work on someone who is not here.
        java.util.UUID offline = FreezeManager.findByName(name);
        if (offline != null && FreezeManager.unfreezeOffline(admin, offline, name)) {
            ok(ctx, "freeze.lifted", "%player%", name);
            return 1;
        }
        fail(ctx, "error.player_offline");
        return 0;
    }

    private static int spectate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        if (admin == null) return 0;
        ServerPlayer target = ctx.getSource().getServer().getPlayerList()
                .getPlayerByName(targetName(ctx));
        if (target == null) {
            fail(ctx, "error.player_offline");
            return 0;
        }
        return SpectateManager.start(admin, target) ? 1 : 0;
    }

    private static int unspectate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        if (admin == null) return 0;
        return SpectateManager.stop(admin) ? 1 : 0;
    }

    private static int history(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        HistoryMenu.open(admin, uuid, targetName(ctx));
        return 1;
    }

    private static int notes(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        NotesMenu.open(admin, uuid, targetName(ctx));
        return 1;
    }

    private static int note(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        boolean pinned = text.startsWith("!");
        NotesManager.add(admin, uuid, targetName(ctx), pinned ? text.substring(1).trim() : text, pinned);
        ok(ctx, "note.added", "%player%", targetName(ctx));
        return 1;
    }

    private static int watch(CommandContext<CommandSourceStack> ctx) {
        return doWatch(ctx, "");
    }

    private static int watchWithReason(CommandContext<CommandSourceStack> ctx) {
        return doWatch(ctx, StringArgumentType.getString(ctx, "reason"));
    }

    private static int doWatch(CommandContext<CommandSourceStack> ctx, String reason) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        WatchlistManager.add(admin, uuid, targetName(ctx), reason);
        ok(ctx, "watchlist.added", "%player%", targetName(ctx));
        return 1;
    }

    private static int unwatch(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        if (WatchlistManager.remove(admin, uuid, targetName(ctx))) {
            ok(ctx, "watchlist.removed", "%player%", targetName(ctx));
            return 1;
        }
        fail(ctx, "watchlist.not_watched", "%player%", targetName(ctx));
        return 0;
    }

    private static int audit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        AuditLogMenu.open(admin, uuid, null, targetName(ctx));
        return 1;
    }

    private static int invEdit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        if (!AdminConfig.get().inventoryEditEnabled) {
            fail(ctx, "invedit.disabled");
            return 0;
        }
        boolean online = ctx.getSource().getServer().getPlayerList().getPlayer(uuid) != null;
        InventoryEditMenu.open(admin, uuid, targetName(ctx), online);
        return 1;
    }

    private static int deaths(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        DeathSnapshotMenu.open(admin, uuid, targetName(ctx));
        return 1;
    }

    private static int templates(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        TemplatesMenu.open(admin, uuid, targetName(ctx));
        return 1;
    }

    private static int select(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        boolean added = SelectionManager.toggle(admin.getUUID(), uuid, targetName(ctx));
        ok(ctx, added ? "bulk.added" : "bulk.removed", "%player%", targetName(ctx));
        return 1;
    }

    private static int selectClear(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        if (admin == null) return 0;
        SelectionManager.clear(admin.getUUID());
        ok(ctx, "bulk.cleared");
        return 1;
    }

    private static int mail(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        boolean immediate = MailManager.send(admin, uuid, targetName(ctx),
                StringArgumentType.getString(ctx, "message"));
        ok(ctx, immediate ? "mail.delivered" : "mail.queued", "%player%", targetName(ctx));
        return 1;
    }

    private static int giveItem(CommandContext<CommandSourceStack> ctx, int count) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName(ctx));
        if (target == null) {
            fail(ctx, "error.player_offline");
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "item");
        var id = net.minecraft.resources.ResourceLocation.tryParse(
                raw.contains(":") ? raw : "minecraft:" + raw);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
            fail(ctx, "give.unknown_item", "%item%", raw);
            return 0;
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        int remaining = count;
        while (remaining > 0) {
            var stack = new net.minecraft.world.item.ItemStack(item,
                    Math.min(remaining, item.getDefaultMaxStackSize()));
            remaining -= stack.getCount();
            if (!target.getInventory().add(stack)) target.drop(stack, false);
        }
        target.containerMenu.broadcastChanges();
        com.arcadia.adminpanel.util.AuditManager.record(admin,
                com.arcadia.adminpanel.util.AdminAction.GIVE_ITEM,
                target.getUUID(), target.getName().getString(), count + "x " + id);
        ok(ctx, "give.done", "%count%", String.valueOf(count),
                "%item%", id.toString(), "%player%", target.getName().getString());
        return 1;
    }

    private static int tempban(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        BanManager.ban(admin, ctx.getSource().getServer(), uuid, targetName(ctx),
                reason == null ? "" : reason, minutes);
        ok(ctx, "ban.applied", "%player%", targetName(ctx));
        return 1;
    }

    // -- Inventory backups (1.3.2) -------------------------------------------

    private static int invBackup(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        UUID uuid = targetUuid(ctx);
        if (admin == null || uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        if (!AdminConfig.get().inventoryBackupEnabled) {
            fail(ctx, "backups.disabled");
            return 0;
        }
        InventoryBackupMenu.open(admin, uuid, targetName(ctx));
        return 1;
    }

    /** Captures one connected player on demand: the "before I touch anything" button. */
    private static int backupOne(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName(ctx));
        if (target == null) {
            fail(ctx, "error.player_offline");
            return 0;
        }
        if (!InventoryBackupManager.capture(target, InventoryBackupManager.REASON_MANUAL)) {
            fail(ctx, "backups.capture_failed", "%player%", target.getName().getString());
            return 0;
        }
        com.arcadia.adminpanel.util.AuditManager.record(admin,
                com.arcadia.adminpanel.util.AdminAction.BACKUP_CAPTURE,
                target.getUUID(), target.getName().getString(), "manual");
        ok(ctx, "backups.captured", "%player%", target.getName().getString());
        return 1;
    }

    /** Captures everyone connected. Worth running before a migration or a suspected dupe. */
    private static int backupAll(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        if (!AdminConfig.get().inventoryBackupEnabled) {
            fail(ctx, "backups.disabled");
            return 0;
        }
        int n = InventoryBackupManager.captureAll(server, InventoryBackupManager.REASON_MANUAL);
        com.arcadia.adminpanel.util.AuditManager.recordServer(admin,
                com.arcadia.adminpanel.util.AdminAction.BACKUP_CAPTURE, n + " players");
        ok(ctx, "backups.captured_all", "%count%", String.valueOf(n));
        return n;
    }

    // -- Locate and roster (1.3.2) -------------------------------------------

    /**
     * Where a player is, in one line. Connected: their live position, dimension and distance from
     * you. Disconnected: the last position their FTB data recorded.
     */
    private static int whereIs(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        String name = targetName(ctx);
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);

        if (target != null) {
            String dim = shortDim(target.level().dimension().location().toString());
            String distance = admin != null && admin.level() == target.level()
                    ? " §7" + LanguageHelper.getText("whereis.distance", admin) + " §f"
                            + (int) Math.sqrt(admin.distanceToSqr(target)) + "m"
                    : "";
            String flags = flagsOf(target);
            ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(
                    "§e" + target.getName().getString() + " §7" + dim + " §f"
                            + (int) target.getX() + ", " + (int) target.getY() + ", "
                            + (int) target.getZ() + distance + flags), false);
            return 1;
        }

        UUID uuid = AdminPanelCommand.resolveUUID(ctx.getSource(), name);
        if (uuid == null) {
            fail(ctx, "error.invalid_target");
            return 0;
        }
        com.arcadia.adminpanel.util.FTBDataReader.ensureLocated(server);
        var data = com.arcadia.adminpanel.util.FTBDataReader.readPlayerData(uuid);
        if (data == null || data.lastSeen == null) {
            fail(ctx, "whereis.unknown", "%player%", name);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(
                "§c" + name + " §8(" + LanguageHelper.getText("player.offline", admin)
                        + ") §7" + data.lastSeen.getShortDimension() + " §f"
                        + data.lastSeen.getFormattedCoords()), false);
        return 1;
    }

    /**
     * The online roster a moderator actually wants: who is here, who is idle, who is hiding, who is
     * frozen, who is carrying warns. Each name opens that player's sheet.
     */
    private static int onlineList(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        List<ServerPlayer> players = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
        players.sort((a, b) -> a.getName().getString().compareToIgnoreCase(b.getName().getString()));

        ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("online.header", admin)
                        .replace("%count%", String.valueOf(players.size()))
                        .replace("%max%", String.valueOf(server.getMaxPlayers()))), false);
        for (ServerPlayer p : players) {
            String name = p.getName().getString();
            int warns = com.arcadia.adminpanel.util.WarnManager.getInstance()
                    .getWarnCount(p.getUUID());
            String suffix = flagsOf(p)
                    + (warns > 0 ? " §e" + warns + "w" : "")
                    + " §8" + shortDim(p.level().dimension().location().toString());
            Component line = Component.literal("§8- §b" + name)
                    .withStyle(st -> st
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/arcadia_adminpanel panel " + name))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("§7"
                                            + LanguageHelper.getText("online.open", admin)))))
                    .append(Component.literal(suffix));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return players.size();
    }

    /** The one-glance state markers shared by {@code whereis} and {@code online}. */
    private static String flagsOf(ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        if (com.arcadia.adminpanel.util.AfkTracker.isAfk(p.getUUID())) sb.append(" §8[afk]");
        if (VanishManager.isVanished(p.getUUID())) sb.append(" §8[vanish]");
        if (FreezeManager.isFrozen(p.getUUID())) sb.append(" §b[freeze]");
        if (com.arcadia.lib.staff.StaffActions.isMuted(p.getUUID())) sb.append(" §c[mute]");
        if (com.arcadia.adminpanel.util.JailManager.getInstance().isJailed(p.getUUID())) {
            sb.append(" §c[jail]");
        }
        if (WatchlistManager.isWatched(p.getUUID())) sb.append(" §d[watch]");
        return sb.toString();
    }

    private static String shortDim(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // -- Command index (1.3.2) -----------------------------------------------

    /** One group of the in-game index. {@code perm} decides whether a viewer sees the group at all. */
    private record HelpSection(String id, String labelKey, AdminPermissions perm,
                               List<String> commands) {}

    /**
     * The index itself. It lists what each group holds rather than describing every argument: the
     * usage string is the documentation, and clicking one puts it in the chat box ready to complete.
     */
    private static final List<HelpSection> HELP_SECTIONS = List.of(
            new HelpSection("panel", "help.section.panel", AdminPermissions.OPEN, List.of(
                    "panel", "tools", "online", "whereis <player>", "help")),
            new HelpSection("investigate", "help.section.investigate", AdminPermissions.AUDIT, List.of(
                    "audit <player>", "history <player>", "notes <player>", "note <player> <text>",
                    "sessions", "afklist", "alts", "clientmods", "radar")),
            new HelpSection("moderate", "help.section.moderate", AdminPermissions.KICK, List.of(
                    "warnoffline <player> <reason>", "warnlist <player>", "mute <player> <minutes>",
                    "unmute <player>", "jail <player> <minutes>", "unjail <player>",
                    "tempban <player> <minutes>", "banlist", "templates <player>",
                    "freeze <player>", "unfreeze <player>", "spectate <player>",
                    "watch <player>", "unwatch <player>")),
            new HelpSection("inventory", "help.section.inventory", AdminPermissions.INV_BACKUP, List.of(
                    "invbackup <player>", "invbackupnow", "invbackupnow <player>",
                    "invedit <player>", "deaths <player>", "giveitem <player> <item>",
                    "mail <player> <message>")),
            new HelpSection("server", "help.section.server", AdminPermissions.WORLD, List.of(
                    "world", "lag", "lagpanel", "chunks", "restart <minutes>", "restart cancel",
                    "broadcast", "chatlock", "clearchat", "loginqueue", "announce <title>")),
            new HelpSection("self", "help.section.self", AdminPermissions.OPEN, List.of(
                    "vanish", "silent", "cmdspy", "socialspy", "back",
                    "select <player>", "selectclear", "stafflist", "stafftoggle")),
            new HelpSection("cosmetic", "help.section.cosmetic", AdminPermissions.NAMETAG_EDIT, List.of(
                    "nametag color <player> <colour>", "nametag name <player> <pseudo>",
                    "nametag effect <player> <effect>", "disguise <player> <mob>",
                    "disguise clear <player>")));

    /** Section ids for {@code help <section>}, so the index can be narrowed to one group. */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>
            SECTION_SUGGESTIONS = (ctx, builder) -> SharedSuggestionProvider.suggest(
                    HELP_SECTIONS.stream().map(HelpSection::id), builder);

    private static int help(CommandContext<CommandSourceStack> ctx, @Nullable String section) {
        ServerPlayer admin = self(ctx);
        if (admin == null) {
            // Console gets the same index without the click handlers, which do nothing there.
            for (HelpSection s : HELP_SECTIONS) {
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6" + LanguageHelper.getText(s.labelKey(), (ServerPlayer) null)
                                + " §7" + String.join(", ", s.commands())), false);
            }
            return 1;
        }
        sendHelp(admin, section);
        return 1;
    }

    /**
     * Prints the command index to a staff member, filtered to what their nodes actually allow and
     * with every command clickable. Also reachable from the Staff Tools screen.
     */
    public static void sendHelp(ServerPlayer admin, @Nullable String section) {
        admin.sendSystemMessage(Component.literal("§8§m" + "─".repeat(34)));
        admin.sendSystemMessage(Component.literal(
                "§6§l" + LanguageHelper.getText("help.title", admin)));

        boolean any = false;
        for (HelpSection s : HELP_SECTIONS) {
            if (section != null && !section.equalsIgnoreCase(s.id())) continue;
            if (!admin.hasPermissions(2) && !s.perm().check(admin)) continue;
            any = true;
            MutableComponent line = Component.literal("§6▸ §e"
                    + LanguageHelper.getText(s.labelKey(), admin) + " §8: ");
            boolean first = true;
            for (String command : s.commands()) {
                if (!first) line.append(Component.literal("§8, "));
                first = false;
                line.append(clickable(command));
            }
            admin.sendSystemMessage(line);
        }

        if (!any) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("help.unknown_section", admin)));
            return;
        }
        admin.sendSystemMessage(Component.literal(
                "§8" + LanguageHelper.getText("help.hint", admin)));
    }

    /**
     * One clickable entry. It suggests rather than runs: half of these take an argument, and a
     * command that fires the moment you touch it is a trap on a moderation tool.
     */
    private static Component clickable(String command) {
        String full = "/arcadia_adminpanel " + command;
        String label = command.split(" ")[0];
        String rest = command.length() > label.length()
                ? "§8" + command.substring(label.length()) : "";
        return Component.literal("§b" + label + rest)
                .withStyle(st -> st
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, full))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7" + full))));
    }

    // -- Server --------------------------------------------------------------

    private static int lagSummary(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        LagMonitor.Sample s = LagMonitor.sample(server);
        ctx.getSource().sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("perf.summary", admin)
                        + " §7TPS " + LagMonitor.tpsColor(s.tps()) + String.format("%.2f", s.tps())
                        + " §7MSPT §f" + String.format("%.2f", s.msptMean())
                        + " §7RAM §f" + s.usedMemoryMb() + "/" + s.maxMemoryMb() + "MB"
                        + " §7E §f" + s.totalEntities()
                        + " §7C §f" + s.totalChunks()), false);
        return 1;
    }

    private static int restart(CommandContext<CommandSourceStack> ctx, @Nullable String reason) {
        ServerPlayer admin = self(ctx);
        int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
        RestartScheduler.schedule(admin, ctx.getSource().getServer(), minutes, reason);
        return 1;
    }

    private static int restartCancel(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer admin = self(ctx);
        if (!RestartScheduler.cancel(admin, ctx.getSource().getServer())) {
            fail(ctx, "restart.none");
            return 0;
        }
        return 1;
    }

    private static int broadcastNow(CommandContext<CommandSourceStack> ctx) {
        if (!AutoBroadcast.sendNow(ctx.getSource().getServer())) {
            fail(ctx, "broadcast.empty");
            return 0;
        }
        return 1;
    }

    private static int broadcastToggle(CommandContext<CommandSourceStack> ctx) {
        AdminConfig.get().autoBroadcastEnabled = !AdminConfig.get().autoBroadcastEnabled;
        AdminConfig.save();
        ok(ctx, AdminConfig.get().autoBroadcastEnabled ? "broadcast.on" : "broadcast.off");
        return 1;
    }
}
