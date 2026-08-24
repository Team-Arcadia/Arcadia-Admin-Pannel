package com.arcadia.adminpanel.event;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.staff.StaffActions;
import com.arcadia.lib.staff.StaffChatService;
import com.arcadia.lib.staff.StaffService;
import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.adminpanel.gui.AdminPanelMenu;
import com.arcadia.adminpanel.gui.PlayerDetailMenu;
import com.arcadia.adminpanel.gui.TeamDetailMenu;
import com.arcadia.adminpanel.util.FTBTeamsReader;
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.NextSpawnManager;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.adminpanel.util.SkullCache;
import com.arcadia.adminpanel.util.WarnManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for chat input when an admin is in "Warn Mode" or "Search Mode".
 *
 * @author vyrriox
 */
public class ChatListener {

    private static final Map<UUID, WarnSession> warnSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> searchSessions = new ConcurrentHashMap<>();
    /** Admin UUID → the team they're composing a message for (team moderation). */
    private static final Map<UUID, TeamMessageSession> teamMessageSessions = new ConcurrentHashMap<>();

    public record WarnSession(UUID targetUUID, String targetName) {}
    public record TeamMessageSession(UUID teamId, String teamName) {}

    // ── Generic chat prompts (1.3.0) ────────────────────────────────────────

    /**
     * The 1.3.0 features that need a line of free text all take it the same way: the menu closes, the
     * admin types, the panel reopens. Rather than a session map and a handler branch per feature,
     * they share one map keyed by {@link PromptKind}.
     */
    public enum PromptKind {
        NOTE, MAIL, BAN_REASON, FREEZE_REASON, WATCH_REASON, GIVE_ITEM, BULK_MESSAGE, BULK_WARN
    }

    /** {@code extra} carries whatever the kind needs: a duration for a ban, unused elsewhere. */
    public record PromptSession(PromptKind kind, UUID targetUUID, String targetName, long extra) {}

    private static final Map<UUID, PromptSession> promptSessions = new ConcurrentHashMap<>();

    /** Opens a typed prompt and tells the admin what is expected. */
    public static void startPrompt(ServerPlayer admin, PromptKind kind,
                                   UUID targetUUID, String targetName, long extra) {
        promptSessions.put(admin.getUUID(), new PromptSession(kind, targetUUID, targetName, extra));
        admin.sendSystemMessage(ArcadiaMessages.info(
                LanguageHelper.getText("prompt." + kind.name().toLowerCase(), admin)
                        .replace("%player%", targetName == null ? "" : targetName)));
        admin.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("warn.prompt.cancel", admin)));
    }

    public static void startNoteSession(ServerPlayer admin, UUID target, String name) {
        startPrompt(admin, PromptKind.NOTE, target, name, 0L);
    }

    public static void startMailSession(ServerPlayer admin, UUID target, String name) {
        startPrompt(admin, PromptKind.MAIL, target, name, 0L);
    }

    public static void startBanReasonSession(ServerPlayer admin, UUID target, String name, long minutes) {
        startPrompt(admin, PromptKind.BAN_REASON, target, name, minutes);
    }

    public static void startFreezeReasonSession(ServerPlayer admin, UUID target, String name) {
        startPrompt(admin, PromptKind.FREEZE_REASON, target, name, 0L);
    }

    public static void startWatchReasonSession(ServerPlayer admin, UUID target, String name) {
        startPrompt(admin, PromptKind.WATCH_REASON, target, name, 0L);
    }

    public static void startGiveItemSession(ServerPlayer admin, UUID target, String name) {
        startPrompt(admin, PromptKind.GIVE_ITEM, target, name, 0L);
    }

    public static void startBulkMessageSession(ServerPlayer admin) {
        startPrompt(admin, PromptKind.BULK_MESSAGE, null, "", 0L);
    }

    public static void startBulkWarnSession(ServerPlayer admin) {
        startPrompt(admin, PromptKind.BULK_WARN, null, "", 0L);
    }

    // ── Warn session ────────────────────────────────────────────────────────

    public static void startWarnSession(ServerPlayer admin, UUID targetUUID, String targetName) {
        warnSessions.put(admin.getUUID(), new WarnSession(targetUUID, targetName));
        admin.sendSystemMessage(ArcadiaMessages.warning(LanguageHelper.getText("warn.prompt", admin)));
        admin.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("warn.prompt.cancel", admin)));
    }

    // ── Search session ──────────────────────────────────────────────────────

    public static void startSearchSession(ServerPlayer admin) {
        searchSessions.put(admin.getUUID(), true);
        admin.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("action.search.prompt", admin)));
        admin.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("warn.prompt.cancel", admin)));
    }

    // ── Team message session ────────────────────────────────────────────────

    public static void startTeamMessageSession(ServerPlayer admin, UUID teamId, String teamName) {
        teamMessageSessions.put(admin.getUUID(), new TeamMessageSession(teamId, teamName));
        admin.sendSystemMessage(ArcadiaMessages.info(
                LanguageHelper.getText("team.message.prompt", admin).replace("%team%", teamName)));
        admin.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("warn.prompt.cancel", admin)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        UUID playerUUID = player.getUUID();
        String message = event.getMessage().getString();

        // ── Mute enforcement (highest priority) ─────────────────────────────
        if (StaffActions.isMuted(playerUUID)) {
            long remaining = StaffActions.getMuteRemaining(playerUUID);
            String reason = StaffActions.getMuteReason(playerUUID);
            player.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("mute.feedback", player)
                            .replace("%time%", TextFormatter.formatMs(remaining))
                            .replace("%reason%", reason != null ? reason : "N/A")));
            event.setCanceled(true);
            return;
        }

        // ── Chat lock ───────────────────────────────────────────────────────
        // Runs after the mute check (a muted player must still be told they are muted) and before
        // everything else, so a locked chat cannot be worked around through a panel session.
        if (com.arcadia.adminpanel.util.ChatControl.shouldBlock(player)) {
            player.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("chat.locked.blocked", player)));
            event.setCanceled(true);
            return;
        }

        // Typing counts as being at the keyboard.
        com.arcadia.adminpanel.util.AfkTracker.markActive(player);

        // ── Staff chat toggle redirect ──────────────────────────────────────
        // Safety net only. A toggled staff member's client rewrites the line into
        // /arcadia_adminpanel staffchat before it is ever sent (see StaffChatClientHandler), so this
        // branch normally never runs. It stays for the window between the toggle command and the
        // state packet landing — cancelling here is not enough on its own, because a Discord bridge
        // that hooks the chat pipeline ahead of us still relays the message (#245).
        if (StaffChatService.isToggled(playerUUID) && StaffService.isStaff(player)) {
            StaffChatService.broadcast(player, message);
            event.setCanceled(true);
            return;
        }

        // Cancel keyword
        if (message.equalsIgnoreCase("cancel")) {
            if (warnSessions.remove(playerUUID) != null) {
                event.setCanceled(true);
                player.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("action.cancelled", player)));
                return;
            }
            if (searchSessions.remove(playerUUID) != null) {
                event.setCanceled(true);
                player.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("action.cancelled", player)));
                return;
            }
            if (teamMessageSessions.remove(playerUUID) != null) {
                event.setCanceled(true);
                player.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("action.cancelled", player)));
                return;
            }
            if (promptSessions.remove(playerUUID) != null) {
                event.setCanceled(true);
                player.sendSystemMessage(ArcadiaMessages.info(LanguageHelper.getText("action.cancelled", player)));
                return;
            }
        }

        // Generic 1.3.0 prompt (note, mail, ban reason, bulk message…).
        if (promptSessions.containsKey(playerUUID)) {
            PromptSession session = promptSessions.remove(playerUUID);
            event.setCanceled(true);
            handlePrompt(player, session, message.trim());
            return;
        }

        // Team message session — broadcast the typed line to every online member of the team.
        if (teamMessageSessions.containsKey(playerUUID)) {
            TeamMessageSession session = teamMessageSessions.remove(playerUUID);
            event.setCanceled(true);
            handleTeamMessage(player, session, message.trim());
            return;
        }

        // Warn session
        if (warnSessions.containsKey(playerUUID)) {
            WarnSession session = warnSessions.remove(playerUUID);
            event.setCanceled(true);

            WarnManager.getInstance().addWarn(session.targetUUID, message, player.getName().getString());

            player.sendSystemMessage(ArcadiaMessages.success(
                    LanguageHelper.getText("warn.success", player) + " §7(" + session.targetName + ")"));

            // Notify target if online
            ServerPlayer target = player.getServer().getPlayerList().getPlayer(session.targetUUID);
            if (target != null) {
                target.sendSystemMessage(ArcadiaMessages.error(
                        String.format(LanguageHelper.getText("warn.notification", target),
                                player.getName().getString())));
                target.sendSystemMessage(Component.literal("§c" +
                        LanguageHelper.getText("warn.reason_prefix", target) + " §f" + message));

                MessageHelper.sendTitle(target,
                        Component.literal("§c§l" + LanguageHelper.getText("warn.title", target)),
                        Component.literal("§e" + message),
                        10, 70, 20);
                SoundHelper.error(target);
            }

            // Reopen detail menu
            player.getServer().execute(() -> {
                boolean isOnline = target != null;
                PlayerDetailMenu.open(player, session.targetUUID, session.targetName, isOnline);
            });
            return;
        }

        // Search session
        if (searchSessions.containsKey(playerUUID)) {
            searchSessions.remove(playerUUID);
            event.setCanceled(true);

            String searchQuery = message.trim();
            player.getServer().execute(() -> AdminPanelMenu.open(player, searchQuery));
            return;
        }
    }

    /**
     * Carries out whatever the admin was prompted for, then puts them back where they came from.
     *
     * <p>Each branch re-checks its permission node. The prompt was opened from a button that was
     * already gated, but a session survives the menu closing, so the check has to happen again at the
     * point the action actually runs.</p>
     */
    private void handlePrompt(ServerPlayer admin, PromptSession session, String message) {
        if (message.isEmpty()) {
            reopenAfterPrompt(admin, session);
            return;
        }
        var server = admin.getServer();
        if (server == null) return;

        switch (session.kind()) {
            case NOTE -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.NOTES.check(admin)) return;
                boolean pinned = message.startsWith("!");
                String text = pinned ? message.substring(1).trim() : message;
                com.arcadia.adminpanel.util.NotesManager.add(admin, session.targetUUID(),
                        session.targetName(), text, pinned);
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("note.added", admin)
                                .replace("%player%", session.targetName())));
            }
            case MAIL -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.MAIL.check(admin)) return;
                boolean immediate = com.arcadia.adminpanel.util.MailManager.send(
                        admin, session.targetUUID(), session.targetName(), message);
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText(immediate ? "mail.delivered" : "mail.queued", admin)
                                .replace("%player%", session.targetName())));
            }
            case BAN_REASON -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.BAN.check(admin)) return;
                com.arcadia.adminpanel.util.BanManager.ban(admin, server, session.targetUUID(),
                        session.targetName(), message, session.extra());
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("ban.applied", admin)
                                .replace("%player%", session.targetName())));
            }
            case FREEZE_REASON -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.FREEZE.check(admin)) return;
                ServerPlayer target = server.getPlayerList().getPlayer(session.targetUUID());
                if (target == null) {
                    admin.sendSystemMessage(ArcadiaMessages.error(
                            LanguageHelper.getText("error.player_offline", admin)));
                } else {
                    com.arcadia.adminpanel.util.FreezeManager.freeze(admin, target, message);
                }
            }
            case WATCH_REASON -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.WATCHLIST.check(admin)) return;
                com.arcadia.adminpanel.util.WatchlistManager.add(admin, session.targetUUID(),
                        session.targetName(), message);
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("watchlist.added", admin)
                                .replace("%player%", session.targetName())));
            }
            case GIVE_ITEM -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.GIVE_ITEM.check(admin)) return;
                giveByName(admin, server, session, message);
            }
            case BULK_MESSAGE -> {
                int sent = 0;
                Component line = Component.literal("§b[" + admin.getName().getString() + "§b] §f" + message);
                for (ServerPlayer target : com.arcadia.adminpanel.util.SelectionManager.onlineTargets(admin)) {
                    target.sendSystemMessage(line);
                    SoundHelper.playAt(target, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    sent++;
                }
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("bulk.messaged", admin)
                                .replace("%count%", String.valueOf(sent))));
                com.arcadia.adminpanel.util.AuditManager.recordServer(admin,
                        com.arcadia.adminpanel.util.AdminAction.BULK, "message " + sent);
            }
            case BULK_WARN -> {
                if (!com.arcadia.adminpanel.util.AdminPermissions.WARN_EDIT.check(admin)) return;
                int warned = 0;
                for (var entry : com.arcadia.adminpanel.util.SelectionManager.entries(admin.getUUID())) {
                    WarnManager.getInstance().addWarn(entry.getKey(), message,
                            admin.getName().getString());
                    com.arcadia.adminpanel.util.AuditManager.record(admin,
                            com.arcadia.adminpanel.util.AdminAction.WARN,
                            entry.getKey(), entry.getValue(), message);
                    ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
                    if (target != null) {
                        target.sendSystemMessage(ArcadiaMessages.error(
                                String.format(LanguageHelper.getText("warn.notification", target),
                                        admin.getName().getString())));
                        SoundHelper.error(target);
                    }
                    warned++;
                }
                admin.sendSystemMessage(ArcadiaMessages.success(
                        LanguageHelper.getText("bulk.warned", admin)
                                .replace("%count%", String.valueOf(warned))));
            }
        }
        reopenAfterPrompt(admin, session);
    }

    /** Parses {@code <item id> [count]} and hands the stack over. */
    private static void giveByName(ServerPlayer admin, net.minecraft.server.MinecraftServer server,
                                   PromptSession session, String message) {
        String[] parts = message.split("\\s+");
        var id = net.minecraft.resources.ResourceLocation.tryParse(
                parts[0].contains(":") ? parts[0] : "minecraft:" + parts[0]);
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("give.unknown_item", admin).replace("%item%", parts[0])));
            return;
        }
        int count = 1;
        if (parts.length > 1) {
            try { count = Math.max(1, Math.min(6400, Integer.parseInt(parts[1]))); }
            catch (NumberFormatException ignored) { count = 1; }
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        ServerPlayer target = server.getPlayerList().getPlayer(session.targetUUID());
        if (target == null) {
            admin.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("error.player_offline", admin)));
            return;
        }
        int remaining = count;
        while (remaining > 0) {
            var stack = new net.minecraft.world.item.ItemStack(item,
                    Math.min(remaining, item.getDefaultMaxStackSize()));
            remaining -= stack.getCount();
            if (!target.getInventory().add(stack)) target.drop(stack, false);
        }
        target.containerMenu.broadcastChanges();
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("give.done", admin)
                        .replace("%count%", String.valueOf(count))
                        .replace("%item%", id.toString())
                        .replace("%player%", session.targetName())));
        com.arcadia.adminpanel.util.AuditManager.record(admin,
                com.arcadia.adminpanel.util.AdminAction.GIVE_ITEM,
                session.targetUUID(), session.targetName(), count + "x " + id);
    }

    /** Bulk prompts return to the bulk screen; per-player prompts return to that player's sheet. */
    private static void reopenAfterPrompt(ServerPlayer admin, PromptSession session) {
        var server = admin.getServer();
        if (server == null) return;
        server.execute(() -> {
            if (session.kind() == PromptKind.BULK_MESSAGE || session.kind() == PromptKind.BULK_WARN) {
                com.arcadia.adminpanel.gui.BulkActionsMenu.open(admin);
                return;
            }
            if (session.targetUUID() == null) return;
            boolean online = server.getPlayerList().getPlayer(session.targetUUID()) != null;
            PlayerDetailMenu.open(admin, session.targetUUID(), session.targetName(), online);
        });
    }

    /** Delivers the admin's typed message to every online member of the team, then reopens the menu. */
    private void handleTeamMessage(ServerPlayer admin, TeamMessageSession session, String message) {
        if (message.isEmpty()) {
            admin.getServer().execute(() -> TeamDetailMenu.open(admin, session.teamId()));
            return;
        }
        FTBTeamsReader.Team team = findTeam(session.teamId());
        int delivered = 0;
        if (team != null) {
            Component line = Component.literal("§b[" + session.teamName() + "] §f"
                    + LanguageHelper.getText("team.message.prefix", admin) + " §7" + message);
            for (FTBTeamsReader.Member m : team.members) {
                if (!m.rank().isInTeam()) continue; // owner/officer/member only — not allies/invites
                ServerPlayer target = admin.getServer().getPlayerList().getPlayer(m.uuid());
                if (target != null) {
                    target.sendSystemMessage(line);
                    SoundHelper.playAt(target, SoundHelper.SUCCESS, 0.5f, 1.2f);
                    delivered++;
                }
            }
        }
        final int count = delivered;
        admin.sendSystemMessage(ArcadiaMessages.success(
                LanguageHelper.getText("team.message.sent", admin)
                        .replace("%count%", String.valueOf(count))
                        .replace("%team%", session.teamName())));
        admin.getServer().execute(() -> TeamDetailMenu.open(admin, session.teamId()));
    }

    private static FTBTeamsReader.Team findTeam(UUID teamId) {
        for (var t : FTBTeamsReader.getParties())     if (t.id.equals(teamId)) return t;
        for (var t : FTBTeamsReader.getServerTeams()) if (t.id.equals(teamId)) return t;
        for (var t : FTBTeamsReader.getPlayerTeams()) if (t.id.equals(teamId)) return t;
        return null;
    }

    // ── Jail: block commands ────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer sp)) return;
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return;

        // Staff bypass — an admin who jailed themselves (or got jailed by another admin as a prank)
        // must be able to escape via /arcadia_adminpanel unjail or any other staff command.
        // Without this, the only way out was a manual DB edit or restart.
        if (com.arcadia.adminpanel.AdminPanelMod.canOpenAdminPanel(sp)) return;

        String command = event.getParseResults().getReader().getString();
        if (!JailManager.getInstance().isCommandAllowed(command)) {
            event.setCanceled(true);
            sp.sendSystemMessage(ArcadiaMessages.error(LanguageHelper.getText("jail.blocked.command", sp)));
        }
    }

    // ── Jail: teleport to jail on login + record login timestamp ────────────

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;

        // Record connection time for the admin-panel "last login" display.
        com.arcadia.adminpanel.util.LoginTracker.getInstance().recordLogin(sp);

        // Repair the offline-cache name with the authoritative profile name (fixes the
        // "Unknown-xxxx" / UUID-instead-of-pseudo regression for anyone scanned before usercache or
        // FTB Teams knew their name) and warm their head texture so the real skin shows in the GUI.
        OfflinePlayerManager.getInstance().upsertName(sp.getUUID(), sp.getGameProfile().getName());
        SkullCache.warmTextures(sp.getServer(), sp.getUUID());

        // Cross-server jail sync (DB mode only): pull the freshest jail row for this UUID before
        // deciding whether to teleport. Covers the "jailed on server A, reconnect to B" case where
        // B's in-memory cache was populated at startup and doesn't know about the new jail yet.
        // Falls through to the local cache check for JSON-mode (single-server) installs.
        if (JailManager.getInstance().isDatabaseMode()) {
            JailManager.getInstance().refreshFromDatabaseAsync(sp.getUUID(),
                    () -> sp.getServer().execute(() -> {
                        if (!applyJailIfNeeded(sp)) applyNextSpawnIfNeeded(sp);
                    }));
        } else {
            if (!applyJailIfNeeded(sp)) applyNextSpawnIfNeeded(sp);
        }

        // Surface active warns on join (configurable via WarnPolicy).
        com.arcadia.adminpanel.util.WarnPolicy.notifyOnJoin(sp);

        // ── 1.3.0 join hooks ────────────────────────────────────────────────
        com.arcadia.adminpanel.util.AfkTracker.onJoin(sp);
        com.arcadia.adminpanel.util.VanishManager.onJoin(sp);
        com.arcadia.adminpanel.util.WatchlistManager.onJoin(sp);
        com.arcadia.adminpanel.util.AltDetector.onJoin(sp);
        com.arcadia.adminpanel.util.MailManager.onJoin(sp);
        // A player who was frozen when they disconnected — or when the server went down — comes
        // back frozen: the hold is re-applied and the anchor re-pinned to where they spawned.
        // The overlay state is pushed either way so a stale client flag cannot survive a relog.
        com.arcadia.adminpanel.util.FreezeManager.onJoin(sp);
        com.arcadia.adminpanel.network.AdminPanelNet.sendFreezeState(
                sp, com.arcadia.adminpanel.util.FreezeManager.isFrozen(sp.getUUID()));

        // Push the full name-tag state (hide switch + every styled player + exemptions) so this
        // client renders colours/effects and wall-occlusion correctly from the first frame. Deferred
        // one tick so the player's connection is fully established before the payload is sent.
        com.arcadia.lib.scheduler.SchedulerService.delayed(1, () -> {
            if (!sp.hasDisconnected()) {
                com.arcadia.adminpanel.util.NameTagManager.getInstance().syncTo(sp);
                com.arcadia.adminpanel.util.DisguiseManager.getInstance().syncTo(sp);
                // Staff chat is cleared server-side on disconnect, so a fresh session always starts
                // off. Pushing it explicitly keeps the client from carrying a stale toggle over from
                // a previous server or session and silently swallowing public chat.
                com.arcadia.adminpanel.network.AdminPanelNet.sendStaffChatState(
                        sp, StaffChatService.isToggled(sp.getUUID()));
            }
        });
    }

    /**
     * Apply a pending next-login spawn override (jail takes priority and is handled first). Deferred
     * a few ticks so we override AFTER vanilla / other mods finish positioning the freshly-spawned
     * player, then consumed (one-shot).
     */
    private static void applyNextSpawnIfNeeded(ServerPlayer sp) {
        if (!NextSpawnManager.getInstance().has(sp.getUUID())) return;
        com.arcadia.lib.scheduler.SchedulerService.delayed(10, () -> {
            if (sp.hasDisconnected()) return;
            NextSpawnManager.SpawnPoint point = NextSpawnManager.getInstance().consumeAndApply(sp);
            if (point != null) {
                sp.sendSystemMessage(ArcadiaMessages.info(
                        LanguageHelper.getText("nextspawn.applied", sp)));
            }
        });
    }

    /** @return {@code true} if the player was jailed (and thus teleported to jail). */
    private static boolean applyJailIfNeeded(ServerPlayer sp) {
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return false;
        JailManager.getInstance().teleportToJail(sp, sp.getServer());
        JailManager.JailEntry entry = JailManager.getInstance().getJailEntry(sp.getUUID());
        if (entry != null) {
            String remaining = entry.durationMs() > 0
                    ? TextFormatter.formatMs(entry.getRemainingMs())
                    : LanguageHelper.getText("jail.permanent", sp);
            sp.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("jail.login.reminder", sp)
                            .replace("%time%", remaining)
                            .replace("%reason%", entry.reason())));
        }
        return true;
    }

    // ── Cleanup on disconnect ───────────────────────────────────────────────

    @SubscribeEvent
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID uuid = sp.getUUID();
        warnSessions.remove(uuid);
        searchSessions.remove(uuid);
        teamMessageSessions.remove(uuid);
        promptSessions.remove(uuid);
        StaffChatService.onDisconnect(uuid);
        com.arcadia.adminpanel.util.LoginTracker.getInstance().recordLogout(sp);
        com.arcadia.adminpanel.util.AdminPermissions.invalidate(uuid);

        // ── 1.3.0 disconnect cleanup ────────────────────────────────────────
        // Order matters: the spectate sessions are restored before the flags they depend on are
        // dropped, so a staff member never persists as a spectator locked to a camera.
        com.arcadia.adminpanel.util.SpectateManager.onStaffQuit(sp);
        com.arcadia.adminpanel.util.SpectateManager.onTargetQuit(sp);
        com.arcadia.adminpanel.util.VanishManager.onQuit(sp);
        // Not cleared: a freeze that ends at the disconnect makes alt-F4 a valid answer to a
        // screenshare. Only the message pacing is dropped here.
        com.arcadia.adminpanel.util.FreezeManager.onQuit(uuid);
        com.arcadia.adminpanel.util.AfkTracker.onQuit(uuid);
        com.arcadia.adminpanel.util.SpyManager.clear(uuid);
        com.arcadia.adminpanel.util.SilentMode.clear(uuid);
        com.arcadia.adminpanel.util.SelectionManager.clear(uuid);
        com.arcadia.adminpanel.util.BackManager.clear(uuid);
        com.arcadia.adminpanel.util.ClientModsRegistry.onQuit(uuid);
    }
}
