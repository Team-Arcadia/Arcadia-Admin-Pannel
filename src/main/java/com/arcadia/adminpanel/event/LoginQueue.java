package com.arcadia.adminpanel.event;

import com.arcadia.adminpanel.util.AdminConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerNegotiationEvent;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Throttles concurrent player logins to avoid the TPS dip when N people reconnect simultaneously
 * after a server restart (the chunk-load + entity-load + mod-init burst that murders heavy
 * modpacks).
 *
 * <p>Hooks {@link PlayerNegotiationEvent} which fires <i>before</i> the player slot is acquired
 * and before chunks are loaded — exactly the right insertion point. We attach a
 * {@link CompletableFuture} to the event; NeoForge keeps the connection in "connecting" state
 * until the future completes. Players don't see "Logging in" stuck — they see "Connecting to the
 * server" until their turn.</p>
 *
 * <p>Algorithm: token-bucket rolling window. A window admits at most
 * {@link AdminConfig.Data#loginQueueMaxPerWindow} logins per
 * {@link AdminConfig.Data#loginQueueWindowSeconds} seconds. Excess logins are deferred via a
 * single-thread scheduler and complete in FIFO order. If a player has been queued for longer than
 * {@link AdminConfig.Data#loginQueueMaxWaitMs}, we let them through anyway (better to lag a bit
 * than to lose them to a connection timeout).</p>
 *
 * <p>The feature is OFF by default — operators flip it in {@code config.json}.</p>
 *
 * @author vyrriox
 */
public final class LoginQueue {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-LoginQueue");
                t.setDaemon(true);
                return t;
            });

    /** Timestamps (ms) of recently admitted logins — head=oldest. Trimmed on every admission. */
    private final Deque<Long> admitWindow = new ArrayDeque<>();

    @SubscribeEvent
    public void onNegotiation(PlayerNegotiationEvent event) {
        AdminConfig.Data cfg = AdminConfig.get();
        if (!cfg.loginQueueEnabled) return;

        long now = System.currentTimeMillis();
        long delayMs = computeDelayMs(now, cfg);
        if (delayMs <= 0) {
            recordAdmit(now);
            return;
        }
        if (delayMs > cfg.loginQueueMaxWaitMs) {
            // Don't make players wait forever — admit with a polite warning rather than drop them.
            LOGGER.warn("[AdminPanel] LoginQueue: wait would exceed cap ({} ms); admitting anyway", delayMs);
            recordAdmit(now);
            return;
        }
        LOGGER.debug("[AdminPanel] LoginQueue: holding negotiation for {} ms", delayMs);

        // Each negotiation gets its own future; once it completes NeoForge resumes the login.
        // We hand the Future directly to the event so no extra thread blocks on join().
        CompletableFuture<Void> gate = new CompletableFuture<>();
        SCHED.schedule(() -> {
            recordAdmit(System.currentTimeMillis());
            gate.complete(null);
        }, delayMs, TimeUnit.MILLISECONDS);

        event.enqueueWork(gate);
    }

    /** Returns how long this login should wait before being admitted, in ms. */
    private synchronized long computeDelayMs(long now, AdminConfig.Data cfg) {
        long windowMs = cfg.loginQueueWindowSeconds * 1000L;
        // Drop entries that fell out of the window.
        while (!admitWindow.isEmpty() && now - admitWindow.peekFirst() > windowMs) {
            admitWindow.pollFirst();
        }
        if (admitWindow.size() < cfg.loginQueueMaxPerWindow) return 0L;
        // The oldest in-window entry tells us when the next slot opens.
        long oldest = admitWindow.peekFirst();
        long openAt = oldest + windowMs;
        return Math.max(0, openAt - now);
    }

    private synchronized void recordAdmit(long ts) {
        admitWindow.addLast(ts);
    }

    /** Disconnect-time courtesy message for the player (optional kick reason). */
    public static Component kickReason() {
        return Component.literal("Server is throttling logins, please try again.");
    }
}
