package com.arcadia.adminpanel.util;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Staff action log: who did what, to whom, when, and on which server.
 *
 * <p>Two stores back it. {@code audit} keeps every action for the recent-activity view and is capped
 * tightly, because it fills fast on a busy server. {@code sanction} keeps only the punishments, with
 * a much larger cap, because a player history that silently forgets a six-month-old ban is worse
 * than useless. Both live in {@link RecordStore}, so both sync across servers and neither ever
 * touches disk on the tick thread.</p>
 *
 * <p>One {@link #record} call is the single entry point: it writes the audit row, mirrors sanctions
 * into the history store, pings the staff channel, and hands the line to the Discord webhook. Call
 * sites never have to remember the other three.</p>
 *
 * @author vyrriox
 */
public final class AuditManager {

    /**
     * One logged action. {@code actorUuid} is stored as a string because the store subject is the
     * <em>target</em>: keeping the actor as a payload field lets the same row answer both
     * "what happened to this player" and "what did this staff member do".
     */
    public record AuditEntry(String action, String actorName, String actorUuid,
                             String targetName, String detail, long durationMs, boolean silent) {}

    private static final RecordStore<AuditEntry> AUDIT =
            new RecordStore<>("audit", AuditEntry.class, 4000);
    private static final RecordStore<AuditEntry> SANCTIONS =
            new RecordStore<>("sanction", AuditEntry.class, 8000);

    private AuditManager() {}

    // -- Write ---------------------------------------------------------------

    /**
     * Records a staff action against a target player.
     *
     * @param actor    the staff member, or {@code null} for a console/automatic action
     * @param targetId the affected player, or {@code null} for a server-wide action
     * @param detail   free-form context (a reason, a value, an item id); may be empty
     */
    public static void record(@Nullable ServerPlayer actor, AdminAction action,
                              @Nullable UUID targetId, @Nullable String targetName,
                              @Nullable String detail, long durationMs) {
        boolean silent = actor != null && SilentMode.isSilent(actor.getUUID());
        String actorName = actor != null ? actor.getName().getString() : "CONSOLE";
        String actorUuid = actor != null ? actor.getUUID().toString() : "";
        // A server-wide action has no target, so it is filed under the actor. That keeps every row
        // addressable by a UUID and lets "what did this admin do" find them too.
        UUID subject = targetId != null ? targetId
                     : (actor != null ? actor.getUUID() : new UUID(0L, 0L));

        AuditEntry entry = new AuditEntry(action.id(), actorName, actorUuid,
                targetName != null ? targetName : "", detail != null ? detail : "",
                durationMs, silent);

        AUDIT.append(subject, entry);
        if (action.isSanction()) SANCTIONS.append(subject, entry);

        StaffFeed.onAudit(action, entry, silent);
        DiscordWebhook.onAudit(action, entry);
    }

    /** Convenience overload for actions with no duration. */
    public static void record(@Nullable ServerPlayer actor, AdminAction action,
                              @Nullable UUID targetId, @Nullable String targetName,
                              @Nullable String detail) {
        record(actor, action, targetId, targetName, detail, 0L);
    }

    /** Convenience overload for server-wide actions with no target. */
    public static void recordServer(@Nullable ServerPlayer actor, AdminAction action,
                                    @Nullable String detail) {
        record(actor, action, null, null, detail, 0L);
    }

    // -- Read ----------------------------------------------------------------

    /** Newest-first audit rows, across every player. */
    public static List<RecordStore.Entry<AuditEntry>> recent(int limit) {
        return AUDIT.recent(limit);
    }

    /** Newest-first audit rows affecting one player. */
    public static List<RecordStore.Entry<AuditEntry>> forTarget(UUID target) {
        return AUDIT.forSubject(target);
    }

    /** Newest-first audit rows produced by one staff member, whoever they targeted. */
    public static List<RecordStore.Entry<AuditEntry>> byActor(UUID actor, int limit) {
        String needle = actor.toString();
        return AUDIT.recentMatching(e -> needle.equals(e.payload().actorUuid()), limit);
    }

    /** Newest-first audit rows for one action type. */
    public static List<RecordStore.Entry<AuditEntry>> byAction(AdminAction action, int limit) {
        return AUDIT.recentMatching(e -> action.id().equals(e.payload().action()), limit);
    }

    /** Newest-first sanction history for one player: warns, mutes, jails, kicks and bans. */
    public static List<RecordStore.Entry<AuditEntry>> sanctionsFor(UUID target) {
        return SANCTIONS.forSubject(target);
    }

    /** How many sanctions of one type this player has collected. Feeds the escalation ladder. */
    public static int sanctionCount(UUID target, AdminAction action) {
        int n = 0;
        for (var e : SANCTIONS.forSubject(target)) {
            if (action.id().equals(e.payload().action())) n++;
        }
        return n;
    }

    /** Total sanctions on record for a player, all types. */
    public static int sanctionCount(UUID target) {
        return SANCTIONS.count(target);
    }

    /** Every staff member who appears in the recent audit window, newest activity first. */
    public static List<String> recentActors(int scanLimit) {
        List<String> out = new ArrayList<>();
        for (var e : AUDIT.recent(scanLimit)) {
            String name = e.payload().actorName();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /** Deletes one audit row (staff correction). The sanction copy, if any, is left alone. */
    public static boolean delete(long id) {
        return AUDIT.remove(id);
    }

    public static int totalRecorded() {
        return AUDIT.total();
    }

    /**
     * Drops audit rows past the configured retention window. Sanctions are deliberately left alone:
     * a player history that quietly forgets a ban is worse than no history, and the sanction store
     * has its own much larger cap.
     */
    public static int purgeExpired() {
        int days = AdminConfig.get().auditRetentionDays;
        if (days <= 0) return 0;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        return AUDIT.removeIf(e -> e.createdAt() < cutoff);
    }
}
