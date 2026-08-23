package com.arcadia.adminpanel.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of this player's freeze state, populated by {@code S2CFreezeState}.
 * Read by {@link FreezeOverlay} to decide whether to draw the screenshare overlay. The server owns
 * the truth and enforces the freeze; the client only knows enough to say so on screen.
 *
 * @author vyrriox
 */
@OnlyIn(Dist.CLIENT)
public final class ClientFreezeState {

    private static volatile boolean frozen;
    private static volatile long since;

    private ClientFreezeState() {}

    public static void set(boolean value) {
        if (value && !frozen) since = System.currentTimeMillis();
        frozen = value;
        if (!value) since = 0L;
    }

    public static boolean isFrozen() { return frozen; }

    public static long frozenSince() { return since; }

    /** Called on disconnect so a relog or server switch starts clean. */
    public static void clear() {
        frozen = false;
        since = 0L;
    }
}
