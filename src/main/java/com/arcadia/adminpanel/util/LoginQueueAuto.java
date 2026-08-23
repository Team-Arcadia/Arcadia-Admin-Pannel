package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Turns the login throttle on by itself for the first minutes after a boot, then turns it back off.
 *
 * <p>The queue exists for exactly one situation: forty people reconnecting the instant a heavy
 * modpack finishes loading. Leaving it on permanently penalises every quiet evening; leaving it off
 * means somebody has to be awake at restart time to flip it. Arming it automatically for a window
 * after boot covers the case it was written for and nothing else.</p>
 *
 * <p>The window is only armed when the operator opted in, and it never overrides a queue an admin
 * turned on by hand: if the throttle was already on at boot, the auto window leaves it alone and
 * does not switch it off at the end.</p>
 *
 * @author vyrriox
 */
public final class LoginQueueAuto {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile long disableAt = 0L;
    private static volatile boolean armed = false;
    private static int tickCounter = 0;

    private LoginQueueAuto() {}

    /** Called once the server has finished starting. */
    public static void onServerStarted() {
        AdminConfig.Data cfg = AdminConfig.get();
        if (!cfg.loginQueueAutoAfterBoot) return;
        if (cfg.loginQueueEnabled) {
            // Already on by operator choice; the window has nothing to add and must not turn it off.
            LOGGER.info("[AdminPanel] Login queue already enabled; skipping the automatic window");
            return;
        }
        int minutes = Math.max(1, cfg.loginQueueAutoMinutes);
        cfg.loginQueueEnabled = true;
        armed = true;
        disableAt = System.currentTimeMillis() + minutes * 60_000L;
        LOGGER.info("[AdminPanel] Login queue armed automatically for {} min after boot", minutes);
    }

    public static boolean isArmed() { return armed; }

    /** Milliseconds until the automatic window closes, or -1 when it is not armed. */
    public static long remainingMs() {
        return armed ? Math.max(0L, disableAt - System.currentTimeMillis()) : -1L;
    }

    /** Called every server tick; checks once a second. */
    public static void onServerTick(MinecraftServer server) {
        if (!armed) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;
        if (System.currentTimeMillis() < disableAt) return;

        armed = false;
        disableAt = 0L;
        AdminConfig.get().loginQueueEnabled = false;
        LOGGER.info("[AdminPanel] Automatic login-queue window closed; throttle off");
    }

    /** Cancels the window early, e.g. when an admin flips the queue by hand. */
    public static void disarm() {
        armed = false;
        disableAt = 0L;
        tickCounter = 0;
    }

    public static void reset() {
        disarm();
    }
}
