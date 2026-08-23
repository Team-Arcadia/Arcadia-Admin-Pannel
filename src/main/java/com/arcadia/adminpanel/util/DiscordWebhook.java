package com.arcadia.adminpanel.util;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors sanctions to a Discord channel through an incoming webhook.
 *
 * <p><b>Scope.</b> This is the sanction log, not the staff chat. Staff chat is deliberately kept off
 * every bridge (see the 1.2.12 fix): a private channel that leaks is worse than no private channel.
 * What goes out here is the same information a public sanction broadcast would carry, plus the
 * moderator name, and nothing else.</p>
 *
 * <p><b>Safety.</b> The webhook URL is a credential. It lives only in the operator's local config,
 * is never logged (failures report a status code, never the target), and an empty value disables the
 * feature entirely. Nothing is sent when the URL is unset, so the default install makes no outbound
 * connection at all.</p>
 *
 * <p><b>Performance.</b> Posts are queued and drained by one daemon thread at a fixed minimum
 * spacing, so a mass-ban never turns into a burst of blocking HTTP calls on the server thread. The
 * queue is bounded; if Discord is down the overflow is dropped rather than allowed to grow.</p>
 *
 * @author vyrriox
 */
public final class DiscordWebhook {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_QUEUE = 200;
    private static final long DRAIN_INTERVAL_MS = 1200L;

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static volatile ScheduledExecutorService io;
    private static volatile HttpClient client;

    private DiscordWebhook() {}

    // -- Lifecycle -----------------------------------------------------------

    public static void init() {
        if (!isEnabled()) return;
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        ScheduledExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-DiscordWebhook");
                t.setDaemon(true);
                return t;
            });
            io.scheduleWithFixedDelay(DiscordWebhook::drainOne,
                    DRAIN_INTERVAL_MS, DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        LOGGER.info("[AdminPanel] Discord sanction webhook enabled");
    }

    public static void shutdown() {
        ScheduledExecutorService current = io;
        if (current != null) current.shutdownNow();
        io = null;
        synchronized (QUEUE) { QUEUE.clear(); }
    }

    public static boolean isEnabled() {
        String url = AdminConfig.get().discordWebhookUrl;
        return url != null && !url.isBlank();
    }

    // -- Entry points --------------------------------------------------------

    /** Called by {@link AuditManager} for every recorded action. Only sanctions go out. */
    static void onAudit(AdminAction action, AuditManager.AuditEntry entry) {
        if (!isEnabled() || !action.isSanction()) return;
        if (AdminConfig.get().discordSkipSilent && entry.silent()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(sanitize(entry.actorName())).append("** ")
          .append(LanguageHelper.getText(action.labelKey(), "en")).append(" **")
          .append(sanitize(entry.targetName())).append("**");
        if (entry.durationMs() > 0) {
            sb.append(" (").append(com.arcadia.lib.text.TextFormatter.formatMs(entry.durationMs())).append(')');
        }
        if (entry.detail() != null && !entry.detail().isBlank()) {
            sb.append("\n> ").append(sanitize(entry.detail()));
        }
        sb.append("\n`").append(com.arcadia.lib.ServerContext.SERVER_ID).append('`');
        enqueue(sb.toString());
    }

    /** Queues a free-form line (used by the scheduled-restart and alert paths). */
    public static void send(String content) {
        if (!isEnabled()) return;
        enqueue(sanitize(content));
    }

    // -- Internals -----------------------------------------------------------

    private static void enqueue(String content) {
        synchronized (QUEUE) {
            if (QUEUE.size() >= MAX_QUEUE) {
                // Drop the oldest: a backlog means Discord is unreachable, and the freshest sanction
                // is the one worth delivering when it comes back.
                QUEUE.pollFirst();
            }
            QUEUE.addLast(content);
        }
    }

    private static void drainOne() {
        String content;
        synchronized (QUEUE) { content = QUEUE.pollFirst(); }
        if (content == null) return;

        String url = AdminConfig.get().discordWebhookUrl;
        HttpClient c = client;
        if (url == null || url.isBlank() || c == null) return;

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        body.addProperty("username", AdminConfig.get().discordWebhookName);
        // Belt and braces: even though the content is escaped, tell Discord not to resolve pings.
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new com.google.gson.JsonArray());
        body.add("allowed_mentions", allowed);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<Void> res = c.send(req, HttpResponse.BodyHandlers.discarding());
            int code = res.statusCode();
            if (code == 429) {
                // Rate limited: put it back at the front and let the next tick retry.
                synchronized (QUEUE) { QUEUE.addFirst(content); }
            } else if (code >= 400) {
                // Never log the URL itself: it is the credential.
                LOGGER.warn("[AdminPanel] Discord webhook rejected the post (HTTP {})", code);
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] Discord webhook post failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Strips Minecraft colour codes and neutralises Discord markdown and mentions. */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        s = s.replace("@everyone", "@​everyone").replace("@here", "@​here");
        s = s.replace("`", "'").replace("*", "\\*").replace("_", "\\_").replace("~", "\\~");
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
