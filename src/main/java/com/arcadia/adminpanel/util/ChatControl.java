package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.staff.StaffService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-wide chat controls: the lock, and the wipe.
 *
 * <p><b>Lock.</b> Freezes public chat while an incident is being handled, so a flood of speculation
 * does not bury the moderator's questions. Staff keep talking by default; the exemption is a config
 * switch, not a hard rule, because some teams want a total freeze during an event.</p>
 *
 * <p><b>Clear.</b> Pushes blank lines so the visible history scrolls away. This is the only thing a
 * server can do to a client's chat buffer: there is no protocol message that erases what a player
 * already received, so anyone with chat logging on still has it. Presented honestly rather than as a
 * privacy feature.</p>
 *
 * @author vyrriox
 */
public final class ChatControl {

    private static volatile boolean locked = false;
    private static volatile String lockedBy = "";

    private ChatControl() {}

    // -- Lock ----------------------------------------------------------------

    public static boolean isLocked() { return locked; }

    public static String lockedBy() { return lockedBy; }

    /** Flips the lock and announces it. Returns the new state. */
    public static boolean toggleLock(ServerPlayer actor) {
        locked = !locked;
        lockedBy = locked ? actor.getName().getString() : "";
        MinecraftServer srv = actor.getServer();
        if (srv != null) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                p.sendSystemMessage(ArcadiaMessages.warning(
                        LanguageHelper.getText(locked ? "chat.locked" : "chat.unlocked", p)
                                .replace("%by%", actor.getName().getString())));
            }
        }
        AuditManager.recordServer(actor, AdminAction.CHAT_LOCK, locked ? "on" : "off");
        return locked;
    }

    /** Clears the lock without announcing. Used on server stop and by the reload path. */
    public static void reset() {
        locked = false;
        lockedBy = "";
    }

    /**
     * Decides whether a chat line survives the lock. Called from the chat listener, which already
     * runs per message, so this adds one boolean read on the common path.
     *
     * @return true when the message must be blocked
     */
    public static boolean shouldBlock(ServerPlayer sender) {
        if (!locked) return false;
        if (AdminConfig.get().chatLockAllowStaff && StaffService.isStaff(sender)) return false;
        return true;
    }

    // -- Clear ---------------------------------------------------------------

    /** Scrolls every player's chat away with blank lines. */
    public static void clearAll(ServerPlayer actor) {
        MinecraftServer srv = actor.getServer();
        if (srv == null) return;
        int lines = Math.max(1, Math.min(200, AdminConfig.get().clearChatLines));
        Component blank = Component.literal(" ");
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            for (int i = 0; i < lines; i++) p.sendSystemMessage(blank);
            p.sendSystemMessage(ArcadiaMessages.info(
                    LanguageHelper.getText("chat.cleared", p)
                            .replace("%by%", actor.getName().getString())));
        }
        AuditManager.recordServer(actor, AdminAction.CHAT_CLEAR, String.valueOf(lines));
    }
}
