package com.arcadia.adminpanel.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of this player's staff-chat toggle, populated by {@code S2CStaffChatState}.
 * Read by {@link StaffChatClientHandler} to decide whether an outgoing chat line must be rewritten
 * into the staff-chat command. The server owns the truth; the client only mirrors it.
 *
 * @author vyrriox
 */
@OnlyIn(Dist.CLIENT)
public final class ClientStaffChatState {

    private static volatile boolean enabled;

    private ClientStaffChatState() {}

    public static void set(boolean value) { enabled = value; }

    public static boolean isEnabled() { return enabled; }

    /** Called on disconnect so a relog / server-switch starts clean. */
    public static void clear() { enabled = false; }
}
