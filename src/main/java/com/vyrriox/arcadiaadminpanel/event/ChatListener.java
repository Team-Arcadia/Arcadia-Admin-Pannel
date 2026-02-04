package com.vyrriox.arcadiaadminpanel.event;

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
 * Listens for chat input when an admin is in "Warn Mode"
 * 
 * @author vyrriox
 */
public class ChatListener {

    private static final Map<UUID, WarnSession> warnSessions = new ConcurrentHashMap<>();

    // Record to hold session data: who we are warning and their name
    public record WarnSession(UUID targetUUID, String targetName) {
    }

    public static void startWarnSession(ServerPlayer admin, UUID targetUUID, String targetName) {
        warnSessions.put(admin.getUUID(), new WarnSession(targetUUID, targetName));
        admin.sendSystemMessage(LanguageHelper.getComponent("warn.prompt", admin));
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (warnSessions.containsKey(player.getUUID())) {
            WarnSession session = warnSessions.remove(player.getUUID());
            event.setCanceled(true); // Don't show message in chat

            String reason = event.getMessage().getString();

            // Execute Warn
            WarnManager.getInstance().addWarn(session.targetUUID, reason, player.getName().getString());

            // Success Message
            player.sendSystemMessage(Component.literal(
                    "§a" + LanguageHelper.getText("warn.success", player) + " §7(" + session.targetName + ")"));

            // Notify Target if online
            ServerPlayer target = player.getServer().getPlayerList().getPlayer(session.targetUUID);
            if (target != null) {
                target.sendSystemMessage(
                        Component.literal("§c[WARNING] §eYou have been warned by " + player.getName().getString()));
                target.sendSystemMessage(Component.literal("§cReason: §f" + reason));
            }

            // Re-open menu?
            // We can't easily re-open menu from chat thread safely sometimes, but usually
            // ok on server thread.
            // Let's offer a link/text to go back or just leave them.
            // Better UX: Just re-open the detail menu.
            player.getServer().execute(() -> {
                boolean isOnline = target != null;
                PlayerDetailMenu.open(player, session.targetUUID, session.targetName, isOnline);
            });
        }
    }
}
