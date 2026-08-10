package com.arcadia.adminpanel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Keeps staff-chat messages out of the public chat pipeline entirely.
 *
 * <p>While staff-chat mode is on, an outgoing chat line is cancelled client-side and re-sent as
 * {@code /arcadia_adminpanel staffchat <message>}. The line therefore never becomes a chat message:
 * no {@code ServerChatEvent} is fired, {@code PlayerList.broadcastChatMessage} is never reached and
 * nothing is written to the chat log — so a Discord bridge has nothing to relay, whichever way it
 * hooks in (event listener that ignores cancellation, mixin ahead of the event, or log tailer).
 * Cancelling the message server-side only worked against bridges that respect the cancel flag,
 * which is what let staff-chat leak to Discord (#245).</p>
 *
 * <p>Lines that already start with {@code /} never reach this event (vanilla routes them through
 * the command path), so normal commands keep working while staff-chat mode is on.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class StaffChatClientHandler {

    private static final String STAFF_CHAT_COMMAND = "arcadia_adminpanel staffchat ";

    private StaffChatClientHandler() {}

    /**
     * Runs at {@link EventPriority#HIGHEST} so client-side chat decorators (nickname prefixes,
     * emote expanders…) never see a staff message they might mirror elsewhere.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientChat(ClientChatEvent event) {
        if (!ClientStaffChatState.isEnabled()) return;

        String message = event.getMessage();
        if (message == null || message.isBlank()) return;

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return; // no connection: let vanilla deal with it

        event.setCanceled(true);
        connection.sendCommand(STAFF_CHAT_COMMAND + message);
    }

    /** Wipe the toggle on disconnect so a relog / server switch never starts in staff-chat mode. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientStaffChatState.clear();
    }
}
