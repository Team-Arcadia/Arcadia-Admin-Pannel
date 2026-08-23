package com.arcadia.adminpanel.util;

import com.arcadia.lib.text.MessageHelper;
import com.arcadia.lib.text.TextFormatter;
import com.arcadia.lib.util.SoundHelper;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * Restarts the server on a schedule, and tells people first.
 *
 * <p>A restart nobody announced is how players lose an hour of building. This warns at the operator's
 * chosen minute marks, counts down out loud in the final seconds, and then halts cleanly so vanilla
 * saves the world the normal way. An admin can also trigger one by hand, and cancel a pending one.</p>
 *
 * <p><b>Cost.</b> The tick hook does an integer compare and returns; the real check runs once per
 * second. Daily times are resolved to the next matching instant when the schedule is armed, not
 * recomputed on every tick.</p>
 *
 * @author vyrriox
 */
public final class RestartScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile long restartAt = 0L;
    private static volatile String reason = "";
    private static volatile String triggeredBy = "";
    /** Minute marks already announced for the current countdown, so each fires once. */
    private static final Set<Integer> announcedMinutes = new HashSet<>();
    private static volatile int lastAnnouncedSecond = -1;
    private static int tickCounter = 0;

    private RestartScheduler() {}

    // -- State ---------------------------------------------------------------

    public static boolean isPending() { return restartAt > 0L; }

    public static long remainingMs() {
        return restartAt <= 0L ? -1L : Math.max(0L, restartAt - System.currentTimeMillis());
    }

    public static String reason() { return reason; }

    public static String triggeredBy() { return triggeredBy; }

    // -- Control -------------------------------------------------------------

    /** Arms a restart {@code minutes} from now. */
    public static void schedule(@Nullable ServerPlayer actor, MinecraftServer server,
                                long minutes, @Nullable String why) {
        restartAt = System.currentTimeMillis() + Math.max(0L, minutes) * 60_000L;
        reason = why == null || why.isBlank() ? AdminConfig.get().restartReason : why;
        triggeredBy = actor != null ? actor.getName().getString() : "SCHEDULE";
        announcedMinutes.clear();
        lastAnnouncedSecond = -1;

        broadcast(server, LanguageHelper.getText("restart.scheduled", (ServerPlayer) null)
                .replace("%time%", TextFormatter.formatMs(remainingMs())));
        AuditManager.recordServer(actor, AdminAction.RESTART, "in " + minutes + " min: " + reason);
        DiscordWebhook.send("Restart scheduled in " + minutes + " min (" + reason + ")");
    }

    /** Cancels a pending restart. Returns false when none was armed. */
    public static boolean cancel(@Nullable ServerPlayer actor, MinecraftServer server) {
        if (restartAt <= 0L) return false;
        restartAt = 0L;
        announcedMinutes.clear();
        lastAnnouncedSecond = -1;
        broadcast(server, LanguageHelper.getText("restart.cancelled", (ServerPlayer) null));
        AuditManager.recordServer(actor, AdminAction.RESTART, "cancelled");
        DiscordWebhook.send("Restart cancelled");
        return true;
    }

    /** Arms the next daily restart from the config, if any is configured. */
    public static void armFromConfig() {
        var times = AdminConfig.get().restartScheduleTimes;
        if (times == null || times.isEmpty()) {
            restartAt = 0L;
            return;
        }
        long best = Long.MAX_VALUE;
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        for (String raw : times) {
            LocalTime time = parseTime(raw);
            if (time == null) continue;
            LocalDateTime candidate = LocalDateTime.of(LocalDate.now(zone), time);
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
            long at = candidate.atZone(zone).toInstant().toEpochMilli();
            if (at < best) best = at;
        }
        if (best == Long.MAX_VALUE) {
            restartAt = 0L;
            return;
        }
        restartAt = best;
        reason = AdminConfig.get().restartReason;
        triggeredBy = "SCHEDULE";
        announcedMinutes.clear();
        lastAnnouncedSecond = -1;
        LOGGER.info("[AdminPanel] Next scheduled restart in {}", TextFormatter.formatMs(remainingMs()));
    }

    @Nullable
    private static LocalTime parseTime(String raw) {
        try {
            String[] parts = raw.trim().split(":");
            if (parts.length != 2) return null;
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            LOGGER.warn("[AdminPanel] Ignoring malformed restart time '{}'", raw);
            return null;
        }
    }

    // -- Tick ----------------------------------------------------------------

    /** Called every server tick; does real work once a second. */
    public static void onServerTick(MinecraftServer server) {
        if (restartAt <= 0L) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;

        long remaining = restartAt - System.currentTimeMillis();
        if (remaining <= 0L) {
            fire(server);
            return;
        }

        int secondsLeft = (int) (remaining / 1000L);
        int countdown = Math.max(0, AdminConfig.get().restartCountdownSeconds);

        if (secondsLeft <= countdown) {
            if (secondsLeft != lastAnnouncedSecond) {
                lastAnnouncedSecond = secondsLeft;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    MessageHelper.sendTitle(p,
                            Component.literal("§c§l" + secondsLeft),
                            Component.literal("§e" + LanguageHelper.getText("restart.title", p)),
                            0, 25, 5);
                    SoundHelper.playAt(p, SoundHelper.CLICK, 0.6f, 1.4f);
                }
            }
            return;
        }

        int minutesLeft = (int) Math.ceil(secondsLeft / 60.0D);
        var marks = AdminConfig.get().restartWarnMinutes;
        if (marks == null || !marks.contains(minutesLeft)) return;
        if (!announcedMinutes.add(minutesLeft)) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.warning(
                    LanguageHelper.getText("restart.warning", p)
                            .replace("%minutes%", String.valueOf(minutesLeft))
                            .replace("%reason%", reason)));
            SoundHelper.playAt(p, SoundHelper.ERROR, 0.5f, 1.0f);
        }
    }

    private static void fire(MinecraftServer server) {
        restartAt = 0L;
        announcedMinutes.clear();
        lastAnnouncedSecond = -1;
        LOGGER.info("[AdminPanel] Scheduled restart firing: {}", reason);
        DiscordWebhook.send("Server restarting now (" + reason + ")");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.disconnect(Component.literal("§e" + reason));
        }
        // halt(false) runs the normal shutdown: worlds save, then the process exits and whatever
        // supervises the server brings it back.
        server.halt(false);
    }

    // -- Helpers -------------------------------------------------------------

    private static void broadcast(MinecraftServer server, String messageKeyResolved) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(com.arcadia.lib.ArcadiaMessages.warning(messageKeyResolved));
        }
    }

    public static void reset() {
        restartAt = 0L;
        announcedMinutes.clear();
        lastAnnouncedSecond = -1;
        tickCounter = 0;
    }
}
