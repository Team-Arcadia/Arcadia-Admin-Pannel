package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Centralises the "what does a warn's lifetime look like?" policy:
 * <ul>
 *   <li><b>Expiry</b> — warns older than {@link AdminConfig.Data#warnExpiryDays} are filtered out
 *       on read. Configurable; {@code 0} = never expires (legacy behaviour).</li>
 *   <li><b>On-join notification</b> — when a player connects we list their still-active warns in
 *       chat with the remaining time until each one expires.</li>
 * </ul>
 *
 * <p>Expiry is computed at read-time rather than physically deleting rows; the underlying storage
 * keeps the history for audit purposes. {@link #purgeExpired()} can be called for an actual delete
 * if the operator wants to reclaim DB rows.</p>
 *
 * @author vyrriox
 */
public final class WarnPolicy {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DAY_MS = 86_400_000L;

    private WarnPolicy() {}

    /** Returns the configured expiry window in ms, or {@code 0} if disabled. */
    public static long expiryMs() {
        int days = AdminConfig.get().warnExpiryDays;
        return days <= 0 ? 0L : days * DAY_MS;
    }

    /** True if the warn is still within the active window (or expiry is disabled). */
    public static boolean isActive(WarnManager.WarnEntry w) {
        long ttl = expiryMs();
        if (ttl <= 0) return true;
        return (System.currentTimeMillis() - w.timestamp()) < ttl;
    }

    /** Remaining ms before this warn expires, or {@code -1} if expiry is disabled. */
    public static long remainingMs(WarnManager.WarnEntry w) {
        long ttl = expiryMs();
        if (ttl <= 0) return -1L;
        return Math.max(0L, (w.timestamp() + ttl) - System.currentTimeMillis());
    }

    /**
     * Filters a warn list to keep only active entries, and always returns an immutable snapshot —
     * callers may stash the result across thread boundaries (scheduler lambdas, etc.) and must not
     * be exposed to concurrent mutation of {@code WarnManager}'s backing list.
     */
    public static List<WarnManager.WarnEntry> filterActive(List<WarnManager.WarnEntry> warns) {
        if (expiryMs() <= 0) return List.copyOf(warns);
        return warns.stream().filter(WarnPolicy::isActive).toList();
    }

    /**
     * Run at the start of every {@link WarnManager#init()} and on {@code /reload} — physically
     * removes warns whose age exceeds the configured TTL. Safe no-op if expiry is disabled.
     */
    public static void purgeExpired() {
        long ttl = expiryMs();
        if (ttl <= 0) return;
        long cutoff = System.currentTimeMillis() - ttl;
        int removed = WarnManager.getInstance().purgeOlderThan(cutoff);
        if (removed > 0) {
            LOGGER.info("[AdminPanel] Auto-expired {} warn(s) older than {} days",
                    removed, AdminConfig.get().warnExpiryDays);
        }
    }

    /**
     * Send the player their currently-active warns in chat, with remaining lifetime. Called from
     * {@code ChatListener.onJoin} after the cross-server jail check.
     */
    public static void notifyOnJoin(ServerPlayer player) {
        if (!AdminConfig.get().warnNotifyOnJoin) return;
        UUID uuid = player.getUUID();
        List<WarnManager.WarnEntry> active = filterActive(WarnManager.getInstance().getWarns(uuid));
        if (active.isEmpty()) return;

        // Defer ~2 seconds so the join message isn't drowned by other mods' join spam (Quark welcome
        // text, FTB Quests progress, vanilla "joined the game" — they all hit immediately). 40 ticks
        // is long enough to clear most of that without feeling laggy.
        com.arcadia.lib.scheduler.SchedulerService.delayed(40, () -> deliverNotification(player, active));
    }

    private static void deliverNotification(ServerPlayer player, List<WarnManager.WarnEntry> active) {
        // Skip if the player left during the 2-second delay — no point sending to a closed channel.
        if (player.hasDisconnected()) return;
        boolean french = isFrench(player);

        player.sendSystemMessage(ArcadiaMessages.warning(
                LanguageHelper.getText("warn.join.header", player)
                        .replace("%count%", String.valueOf(active.size()))));

        // Newest first — match what WarnListMenu shows.
        active.stream()
                .sorted((a, b) -> Long.compare(b.timestamp(), a.timestamp()))
                .limit(5) // cap chat spam; the GUI shows the full list
                .forEach(w -> {
                    long remaining = remainingMs(w);
                    String line = " §c• §f" + truncate(w.reason(), 60)
                            + " §8(" + TimeFormat.relative(w.timestamp(), french) + ")";
                    if (remaining > 0) {
                        line += " §7— " + LanguageHelper.getText("warn.join.expires", player)
                                + " §e" + formatRemaining(remaining, french);
                    }
                    player.sendSystemMessage(Component.literal(line));
                });

        if (active.size() > 5) {
            player.sendSystemMessage(Component.literal("§8…" + (active.size() - 5)
                    + " " + LanguageHelper.getText("warn.join.more", player)));
        }

        // Clickable command hint — the command name is rendered in highlighted color and clicking
        // it fills the chat box (SUGGEST_COMMAND, not RUN_COMMAND) so the player sees what they're
        // about to execute. The plain text version was easy to miss in a busy chat window.
        String cmd = "/arcadia_adminpanel checkwarn";
        net.minecraft.network.chat.MutableComponent hint =
                net.minecraft.network.chat.Component.literal("§7" + LanguageHelper.getText("warn.join.cmd_hint", player) + " ")
                        .append(net.minecraft.network.chat.Component.literal("§b§n" + cmd)
                                .withStyle(s -> s
                                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, cmd))
                                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                                net.minecraft.network.chat.Component.literal(
                                                        LanguageHelper.getText("warn.join.cmd_hover", player))))));
        player.sendSystemMessage(ArcadiaMessages.info(hint));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Coarse human-readable countdown — days/hours, not seconds. */
    private static String formatRemaining(long ms, boolean french) {
        long days = ms / DAY_MS;
        long hours = (ms % DAY_MS) / 3_600_000L;
        if (french) {
            if (days >= 1) return days + (days == 1 ? " jour" : " jours");
            if (hours >= 1) return hours + " h";
            return "< 1 h";
        }
        if (days >= 1) return days + (days == 1 ? " day" : " days");
        if (hours >= 1) return hours + "h";
        return "< 1h";
    }

    private static boolean isFrench(ServerPlayer p) {
        try {
            String lang = p.clientInformation() != null ? p.clientInformation().language() : null;
            return lang != null && lang.toLowerCase().startsWith("fr");
        } catch (Exception e) {
            return false;
        }
    }
}
