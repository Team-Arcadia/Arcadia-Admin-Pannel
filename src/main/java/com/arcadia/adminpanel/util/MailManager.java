package com.arcadia.adminpanel.util;

import com.arcadia.lib.ArcadiaMessages;
import com.arcadia.lib.util.SoundHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Leaves a message for a player who is not connected, delivered the next time they log in.
 *
 * <p>The alternative was telling staff to "remember to tell them", which never survives a shift
 * change. Messages are stored as records so they sync across servers: a note left on the survival
 * server reaches the player even if they next log into the creative one, and delivery marks the row
 * read rather than deleting it, so the trail stays auditable.</p>
 *
 * @author vyrriox
 */
public final class MailManager {

    /** One message. {@code delivered} flips when the recipient has actually seen it. */
    public record MailEntry(String message, String fromName, String fromUuid,
                            String toName, boolean delivered) {}

    private static final RecordStore<MailEntry> STORE = new RecordStore<>("mail", MailEntry.class, 4000);

    private MailManager() {}

    // -- Send ----------------------------------------------------------------

    /**
     * Queues a message. When the recipient is already online it is delivered immediately (and still
     * recorded), so staff never have to decide which command to use.
     *
     * @return true when the message was delivered on the spot
     */
    public static boolean send(ServerPlayer from, UUID target, String targetName, String message) {
        int pending = pendingCount(target);
        if (pending >= AdminConfig.get().mailMaxPerPlayer) {
            from.sendSystemMessage(ArcadiaMessages.error(
                    LanguageHelper.getText("mail.full", from).replace("%player%", targetName)));
            return false;
        }

        ServerPlayer online = from.getServer().getPlayerList().getPlayer(target);
        boolean immediate = online != null;

        STORE.append(target, new MailEntry(message, from.getName().getString(),
                from.getUUID().toString(), targetName, immediate));
        AuditManager.record(from, AdminAction.MAIL_SEND, target, targetName, message);

        if (immediate) deliverOne(online, from.getName().getString(), message);
        return immediate;
    }

    // -- Delivery ------------------------------------------------------------

    /** Called on login. Delivers every pending message, then marks them read. */
    public static void onJoin(ServerPlayer player) {
        List<RecordStore.Entry<MailEntry>> pending = STORE.forSubject(player.getUUID());
        if (pending.isEmpty()) return;

        int delivered = 0;
        for (var e : pending) {
            if (e.payload().delivered()) continue;
            deliverOne(player, e.payload().fromName(), e.payload().message());
            // Append-only store: re-append the row as delivered and drop the pending one, so the
            // history keeps the message but the next login does not repeat it.
            STORE.append(player.getUUID(), new MailEntry(e.payload().message(), e.payload().fromName(),
                    e.payload().fromUuid(), e.payload().toName(), true));
            STORE.remove(e.id());
            delivered++;
        }
        if (delivered > 0) SoundHelper.playAt(player, SoundHelper.REWARD, 0.7f, 1.0f);
    }

    private static void deliverOne(ServerPlayer to, String fromName, String message) {
        to.sendSystemMessage(ArcadiaMessages.info(
                LanguageHelper.getText("mail.received", to).replace("%from%", fromName)));
        to.sendSystemMessage(Component.literal("§7> §f" + message));
    }

    // -- Reads ---------------------------------------------------------------

    public static int pendingCount(UUID target) {
        int n = 0;
        for (var e : STORE.forSubject(target)) if (!e.payload().delivered()) n++;
        return n;
    }

    /** Full mail history for a player, newest first. */
    public static List<RecordStore.Entry<MailEntry>> history(UUID target) {
        return STORE.forSubject(target);
    }

    public static boolean delete(long id) {
        return STORE.remove(id);
    }
}
