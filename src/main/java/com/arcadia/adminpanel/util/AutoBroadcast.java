package com.arcadia.adminpanel.util;

import com.arcadia.lib.text.LegacyColorFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Rotating server announcements.
 *
 * <p>Rules, the Discord link, the vote reminder: the messages every server repeats and every staff
 * member forgets to. Messages cycle in the order written, so an operator can pace a sequence rather
 * than getting the same random line twice.</p>
 *
 * <p><b>Cost.</b> A counter compare per tick and a broadcast every {@code autoBroadcastIntervalMinutes}.
 * Disabled by default, and skipped entirely when the message list is empty or nobody is online:
 * announcing to an empty server is pure noise in the log.</p>
 *
 * @author vyrriox
 */
public final class AutoBroadcast {

    private static int tickCounter = 0;
    private static int nextIndex = 0;

    private AutoBroadcast() {}

    public static void reset() {
        tickCounter = 0;
        nextIndex = 0;
    }

    /** Index of the message that will go out next, for the status line. */
    public static int nextIndex() { return nextIndex; }

    public static void onServerTick(MinecraftServer server) {
        AdminConfig.Data cfg = AdminConfig.get();
        if (!cfg.autoBroadcastEnabled) return;
        List<String> messages = cfg.autoBroadcastMessages;
        if (messages == null || messages.isEmpty()) return;

        int period = Math.max(1, cfg.autoBroadcastIntervalMinutes) * 60 * 20;
        if (++tickCounter < period) return;
        tickCounter = 0;

        if (server.getPlayerList().getPlayerCount() == 0) return;

        if (nextIndex >= messages.size()) nextIndex = 0;
        String raw = messages.get(nextIndex);
        nextIndex = (nextIndex + 1) % messages.size();
        if (raw == null || raw.isBlank()) return;

        send(server, raw);
    }

    /** Sends one line to everyone, honouring the § colour codes operators write in the config. */
    public static void send(MinecraftServer server, String raw) {
        Component line = LegacyColorFormatter.parse(raw);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
    }

    /** Fires the next message immediately, for the panel's "send now" button. */
    public static boolean sendNow(MinecraftServer server) {
        List<String> messages = AdminConfig.get().autoBroadcastMessages;
        if (messages == null || messages.isEmpty()) return false;
        if (nextIndex >= messages.size()) nextIndex = 0;
        String raw = messages.get(nextIndex);
        nextIndex = (nextIndex + 1) % messages.size();
        if (raw == null || raw.isBlank()) return false;
        send(server, raw);
        tickCounter = 0;
        return true;
    }
}
