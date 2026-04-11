package com.vyrriox.arcadiaadminpanel.event;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.util.SoundHelper;
import com.arcadia.lib.text.MessageHelper;
import com.vyrriox.arcadiaadminpanel.gui.AdminPanelMenu;
import com.vyrriox.arcadiaadminpanel.gui.PlayerDetailMenu;
import com.vyrriox.arcadiaadminpanel.util.LanguageHelper;
import com.vyrriox.arcadiaadminpanel.util.WarnManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

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

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        UUID playerUUID = player.getUUID();
        String message = event.getMessage().getString();

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
}
