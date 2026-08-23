package com.arcadia.adminpanel.util;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Private staff notes attached to a player.
 *
 * <p>Deliberately not a warning. A warn is a sanction the player is told about and that counts
 * toward escalation; a note is an observation for the next moderator on shift ("claims the chest was
 * already empty", "suspected alt of X, unconfirmed"). Notes are never shown to the subject, never
 * expire on their own, and never affect the ladder.</p>
 *
 * @author vyrriox
 */
public final class NotesManager {

    /** One note. {@code pinned} floats it to the top of the player sheet. */
    public record Note(String text, String authorName, String authorUuid, boolean pinned) {}

    private static final RecordStore<Note> STORE = new RecordStore<>("note", Note.class, 4000);

    private NotesManager() {}

    /** Adds a note and audits the fact that one was written (never its content). */
    public static void add(ServerPlayer author, UUID target, String targetName, String text, boolean pinned) {
        STORE.append(target, new Note(text, author.getName().getString(),
                author.getUUID().toString(), pinned));
        AuditManager.record(author, AdminAction.NOTE_ADD, target, targetName, "");
    }

    /** Newest-first notes for a player, pinned ones first. */
    public static List<RecordStore.Entry<Note>> forPlayer(UUID target) {
        List<RecordStore.Entry<Note>> list = STORE.forSubject(target);
        list.sort((a, b) -> {
            if (a.payload().pinned() != b.payload().pinned()) return a.payload().pinned() ? -1 : 1;
            return Long.compare(b.createdAt(), a.createdAt());
        });
        return list;
    }

    public static int count(UUID target) {
        return STORE.count(target);
    }

    public static boolean delete(ServerPlayer actor, UUID target, String targetName, long id) {
        boolean ok = STORE.remove(id);
        if (ok) AuditManager.record(actor, AdminAction.NOTE_DELETE, target, targetName, "");
        return ok;
    }

    /** True when the player has at least one pinned note, used to flag their head in the grid. */
    public static boolean hasPinned(UUID target) {
        for (var e : STORE.forSubject(target)) if (e.payload().pinned()) return true;
        return false;
    }
}
