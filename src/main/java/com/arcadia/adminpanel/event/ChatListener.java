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
import com.arcadia.adminpanel.util.JailManager;
import com.arcadia.adminpanel.util.LanguageHelper;
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

    public record WarnSession(UUID targetUUID, String targetName) {}

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

        // ── Staff chat toggle redirect ──────────────────────────────────────
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
                target.sendSystemMessage(Component.literal("§cReason: §f" + message));

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

    // ── Jail: block commands ────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer sp)) return;
        if (!JailManager.getInstance().isJailed(sp.getUUID())) return;

        String command = event.getParseResults().getReader().getString();
        if (!JailManager.getInstance().isCommandAllowed(command)) {
            event.setCanceled(true);
            sp.sendSystemMessage(ArcadiaMessages.error(LanguageHelper.getText("jail.blocked.command", sp)));
        }
    }

    // ── Jail: teleport to jail on login ──────────────────────────────────────

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (JailManager.getInstance().isJailed(sp.getUUID())) {
            sp.getServer().execute(() -> {
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
            });
        }
    }

    // ── Cleanup on disconnect ───────────────────────────────────────────────

    @SubscribeEvent
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID uuid = sp.getUUID();
        warnSessions.remove(uuid);
        searchSessions.remove(uuid);
        StaffChatService.onDisconnect(uuid);
    }
}
