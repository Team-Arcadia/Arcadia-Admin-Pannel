package com.arcadia.adminpanel.util;

import com.arcadia.lib.ServerContext;
import com.arcadia.lib.data.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Append-only, dual-backend store for the record-shaped features added in 1.3.0 (audit log, staff
 * notes, sanction history, offline mail, bans, watchlist).
 *
 * <p><b>Why one generic store.</b> Each of those features has the same shape: a small immutable
 * payload attached to a subject UUID, written rarely, read often, and worth syncing across servers.
 * Six bespoke managers would have meant six schemas, six loaders and six sync paths. One store means
 * a new feature is a record class plus one line.</p>
 *
 * <p><b>Performance contract.</b> The server thread never touches disk, a socket, or a lock held by
 * IO:</p>
 * <ul>
 *   <li>Reads are served from an in-memory snapshot. No query, ever, on the tick thread.</li>
 *   <li>Writes append to memory and return; persistence happens on a shared daemon thread
 *       ({@code Arcadia-Records-IO}), coalesced so a burst of writes costs one file write.</li>
 *   <li>Database mode inserts through {@link DatabaseManager#executeAsync}, never inline.</li>
 *   <li>The cross-server pull is a single indexed {@code id > cursor} query on a background timer,
 *       not a table scan, and it is skipped entirely when the database is off.</li>
 *   <li>Every kind is capped ({@code maxEntries}), so memory and the JSON file stay bounded no
 *       matter how long the server runs.</li>
 * </ul>
 *
 * @param <T> payload type; must round-trip through Gson
 * @author vyrriox
 */
public final class RecordStore<T> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final Gson GSON_PRETTY = new GsonBuilder().setPrettyPrinting().create();

    /** Every live store, so init/shutdown/sync can iterate without a registry per feature. */
    private static final List<RecordStore<?>> STORES = new CopyOnWriteArrayList<>();

    /** Shared coalescing IO thread for JSON persistence and the cross-server pull. */
    private static volatile ScheduledExecutorService io;
    private static final long FLUSH_DELAY_SECONDS = 3L;
    private static final long SYNC_PERIOD_SECONDS = 30L;

    /** One stored record. {@code id} is unique per backend, monotonic, and used as the sync cursor. */
    public record Entry<T>(long id, UUID subject, T payload, String serverId, long createdAt) {}

    private final String kind;
    private final Class<T> payloadType;
    private final int maxEntries;
    private final Path file;
    private final Path tempFile;

    /** Every cached entry for this kind. Guarded by the list monitor. */
    private final List<Entry<T>> entries = Collections.synchronizedList(new ArrayList<>());
    /** Subject to entries index, so a per-player lookup never walks the whole kind. */
    private final Map<UUID, List<Entry<T>>> bySubject = new ConcurrentHashMap<>();

    private final AtomicLong localIdSeq = new AtomicLong(0L);
    private final AtomicLong syncCursor = new AtomicLong(0L);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile boolean loaded = false;

    public RecordStore(String kind, Class<T> payloadType, int maxEntries) {
        this.kind = kind;
        this.payloadType = payloadType;
        this.maxEntries = Math.max(16, maxEntries);
        Path dir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel/records");
        this.file = dir.resolve(kind + ".json");
        this.tempFile = dir.resolve(kind + ".json.tmp");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to create record directory for {}", kind, e);
        }
        STORES.add(this);
    }

    // -- Lifecycle -----------------------------------------------------------

    /** Boots the shared IO thread and loads every registered store. Called once on server start. */
    public static void initAll() {
        ScheduledExecutorService current = io;
        if (current == null || current.isShutdown()) {
            io = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Arcadia-Records-IO");
                t.setDaemon(true);
                return t;
            });
        }
        for (RecordStore<?> s : STORES) s.load();
        // Cross-server convergence. Only meaningful with a shared database; the poll short-circuits
        // on the first check otherwise, so a standalone server pays nothing for it.
        try {
            io.scheduleWithFixedDelay(RecordStore::pullRemoteAll,
                    SYNC_PERIOD_SECONDS, SYNC_PERIOD_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Executor torn down between the check above and here; nothing to schedule.
        }
    }

    /** Final flush + IO thread teardown. Called on server stop so no append is lost. */
    public static void shutdownAll() {
        for (RecordStore<?> s : STORES) {
            if (s.dirty.getAndSet(false) && !s.isDatabaseMode()) s.saveJson();
        }
        ScheduledExecutorService current = io;
        if (current != null) current.shutdownNow();
        io = null;
    }

    /** Drops caches and re-reads every store from its backend. Called by the reload command. */
    public static void reloadAll() {
        for (RecordStore<?> s : STORES) {
            synchronized (s.entries) { s.entries.clear(); }
            s.bySubject.clear();
            s.syncCursor.set(0L);
            s.loaded = false;
            s.load();
        }
    }

    private boolean isDatabaseMode() {
        return DatabaseManager.isDatabaseActive();
    }

    private void load() {
        if (isDatabaseMode()) {
            loadFromDatabase();
        } else {
            loadFromJson();
            loaded = true;
        }
    }

    // -- Write ---------------------------------------------------------------

    /**
     * Appends a record. Returns immediately; persistence is asynchronous. Safe to call from the
     * server thread.
     */
    public Entry<T> append(UUID subject, T payload) {
        long now = System.currentTimeMillis();
        long id = nextLocalId();
        Entry<T> e = new Entry<>(id, subject, payload, ServerContext.SERVER_ID, now);
        insertCached(e);
        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> insertDb(e));
        } else {
            markDirty();
        }
        return e;
    }

    /** Removes one record by id. Returns true when something was removed. */
    public boolean remove(long id) {
        boolean removed;
        synchronized (entries) {
            removed = entries.removeIf(e -> e.id() == id);
        }
        if (!removed) return false;
        for (List<Entry<T>> list : bySubject.values()) {
            synchronized (list) { list.removeIf(e -> e.id() == id); }
        }
        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> deleteDb(id));
        } else {
            markDirty();
        }
        return true;
    }

    /** Removes every record attached to {@code subject}. Returns how many went away. */
    public int removeAll(UUID subject) {
        List<Entry<T>> list = bySubject.remove(subject);
        int count = list == null ? 0 : list.size();
        if (count == 0) return 0;
        synchronized (entries) {
            entries.removeIf(e -> subject.equals(e.subject()));
        }
        if (isDatabaseMode()) {
            DatabaseManager.executeAsync(() -> deleteSubjectDb(subject));
        } else {
            markDirty();
        }
        return count;
    }

    /** Removes records matching {@code filter}. Used by expiry sweeps. Returns how many went away. */
    public int removeIf(Predicate<Entry<T>> filter) {
        List<Long> ids = new ArrayList<>();
        synchronized (entries) {
            for (Entry<T> e : entries) if (filter.test(e)) ids.add(e.id());
        }
        int removed = 0;
        for (long id : ids) if (remove(id)) removed++;
        return removed;
    }

    // -- Read (memory only) --------------------------------------------------

    /** Every record for one subject, newest first. Never touches disk or the database. */
    public List<Entry<T>> forSubject(UUID subject) {
        List<Entry<T>> list = bySubject.get(subject);
        if (list == null) return List.of();
        List<Entry<T>> copy;
        synchronized (list) { copy = new ArrayList<>(list); }
        copy.sort(Comparator.comparingLong(Entry<T>::createdAt).reversed());
        return copy;
    }

    /** The most recent {@code limit} records across every subject, newest first. */
    public List<Entry<T>> recent(int limit) {
        List<Entry<T>> copy;
        synchronized (entries) { copy = new ArrayList<>(entries); }
        copy.sort(Comparator.comparingLong(Entry<T>::createdAt).reversed());
        return copy.size() > limit ? new ArrayList<>(copy.subList(0, limit)) : copy;
    }

    /** The most recent {@code limit} records matching {@code filter}, newest first. */
    public List<Entry<T>> recentMatching(Predicate<Entry<T>> filter, int limit) {
        List<Entry<T>> copy;
        synchronized (entries) { copy = new ArrayList<>(entries); }
        copy.removeIf(filter.negate());
        copy.sort(Comparator.comparingLong(Entry<T>::createdAt).reversed());
        return copy.size() > limit ? new ArrayList<>(copy.subList(0, limit)) : copy;
    }

    /** Newest record for a subject, or {@code null}. */
    @Nullable
    public Entry<T> latest(UUID subject) {
        List<Entry<T>> list = forSubject(subject);
        return list.isEmpty() ? null : list.get(0);
    }

    public int count(UUID subject) {
        List<Entry<T>> list = bySubject.get(subject);
        return list == null ? 0 : list.size();
    }

    public int total() {
        synchronized (entries) { return entries.size(); }
    }

    public boolean isLoaded() { return loaded; }

    // -- Internals -----------------------------------------------------------

    private long nextLocalId() {
        // In database mode the authoritative id is assigned by AUTO_INCREMENT; the local value only
        // has to be unique inside this JVM until the row round-trips, so a high-water counter works
        // for both backends.
        return localIdSeq.incrementAndGet();
    }

    private void insertCached(Entry<T> e) {
        synchronized (entries) {
            entries.add(e);
            if (entries.size() > maxEntries) {
                // Trim oldest first so both memory and the JSON file stay bounded.
                entries.sort(Comparator.comparingLong(Entry<T>::createdAt));
                while (entries.size() > maxEntries) {
                    Entry<T> gone = entries.remove(0);
                    List<Entry<T>> list = bySubject.get(gone.subject());
                    if (list != null) {
                        synchronized (list) { list.remove(gone); }
                    }
                }
            }
        }
        bySubject.computeIfAbsent(e.subject(), k -> Collections.synchronizedList(new ArrayList<>()))
                 .add(e);
    }

    private void markDirty() {
        if (!dirty.compareAndSet(false, true)) return;
        ScheduledExecutorService current = io;
        if (current == null || current.isShutdown()) {
            dirty.set(false);
            saveJson();
            return;
        }
        try {
            current.schedule(() -> { dirty.set(false); saveJson(); },
                    FLUSH_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            dirty.set(false);
            saveJson();
        }
    }

    // -- JSON backend --------------------------------------------------------

    /** On-disk shape. Kept separate from {@code Entry} so the payload stays a raw JSON string. */
    private record StoredRow(long id, String subject, String payload, String serverId, long createdAt) {}

    private void loadFromJson() {
        if (!Files.exists(file)) return;
        try (FileReader r = new FileReader(file.toFile())) {
            List<StoredRow> rows = GSON.fromJson(r, new TypeToken<List<StoredRow>>() {}.getType());
            if (rows == null) return;
            long maxId = 0L;
            for (StoredRow row : rows) {
                T payload = decode(row.payload());
                if (payload == null) continue;
                UUID subject;
                try { subject = UUID.fromString(row.subject()); } catch (Exception ex) { continue; }
                Entry<T> e = new Entry<>(row.id(), subject, payload, row.serverId(), row.createdAt());
                insertCached(e);
                maxId = Math.max(maxId, row.id());
            }
            localIdSeq.set(maxId);
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load records {}", kind, e);
        }
    }

    private void saveJson() {
        List<StoredRow> rows = new ArrayList<>();
        synchronized (entries) {
            for (Entry<T> e : entries) {
                rows.add(new StoredRow(e.id(), e.subject().toString(),
                        GSON.toJson(e.payload()), e.serverId(), e.createdAt()));
            }
        }
        try (FileWriter w = new FileWriter(tempFile.toFile())) {
            GSON_PRETTY.toJson(rows, w);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to write records temp file {}", kind, e);
            return;
        }
        try {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to save records {}", kind, e);
        }
    }

    @Nullable
    private T decode(String json) {
        try {
            return GSON.fromJson(json, payloadType);
        } catch (Exception e) {
            return null;
        }
    }

    // -- Database backend ----------------------------------------------------

    private void loadFromDatabase() {
        DatabaseManager.supplyAsync(() -> {
            List<Entry<T>> out = new ArrayList<>();
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id, subject, payload, server_id, created_at FROM arcadia_admin_records "
                       + "WHERE kind = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, kind);
                ps.setInt(2, maxEntries);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        T payload = decode(rs.getString("payload"));
                        if (payload == null) continue;
                        UUID subject;
                        try { subject = UUID.fromString(rs.getString("subject")); }
                        catch (Exception ex) { continue; }
                        out.add(new Entry<>(rs.getLong("id"), subject, payload,
                                rs.getString("server_id"), rs.getLong("created_at")));
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[AdminPanel] Failed to load records {} from database", kind, e);
                return null;
            }
            return out;
        }).thenAccept(rows -> {
            if (rows == null) { loaded = true; return; }
            long maxId = 0L;
            for (Entry<T> e : rows) {
                insertCached(e);
                maxId = Math.max(maxId, e.id());
            }
            syncCursor.set(maxId);
            localIdSeq.set(maxId);
            loaded = true;
            LOGGER.info("[AdminPanel] Record store {} loaded ({} rows, database mode)", kind, rows.size());
        });
    }

    private void insertDb(Entry<T> e) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO arcadia_admin_records (kind, subject, payload, server_id, created_at) "
                   + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, e.subject().toString());
            ps.setString(3, GSON.toJson(e.payload()));
            ps.setString(4, e.serverId());
            ps.setLong(5, e.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long assigned = keys.getLong(1);
                    // Advance the cursor past our own row so the pull never re-imports it.
                    syncCursor.accumulateAndGet(assigned, Math::max);
                    // Adopt the id the database actually assigned. The cached entry was created with
                    // a local counter value, and on a second server sharing this table that value
                    // can belong to somebody else's row: deleting by it would delete the wrong
                    // record. After this swap the cached id and the row id are the same number.
                    adoptId(e, assigned);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[AdminPanel] Failed to insert record {}", kind, ex);
        }
    }

    /** Replaces a cached entry with an identical one carrying the database-assigned id. */
    private void adoptId(Entry<T> original, long assignedId) {
        if (original.id() == assignedId) return;
        Entry<T> replacement = new Entry<>(assignedId, original.subject(), original.payload(),
                original.serverId(), original.createdAt());
        synchronized (entries) {
            int index = entries.indexOf(original);
            if (index >= 0) entries.set(index, replacement);
        }
        List<Entry<T>> list = bySubject.get(original.subject());
        if (list != null) {
            synchronized (list) {
                int index = list.indexOf(original);
                if (index >= 0) list.set(index, replacement);
            }
        }
    }

    private void deleteDb(long id) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM arcadia_admin_records WHERE kind = ? AND id = ?")) {
            ps.setString(1, kind);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to delete record {}", kind, e);
        }
    }

    private void deleteSubjectDb(UUID subject) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM arcadia_admin_records WHERE kind = ? AND subject = ?")) {
            ps.setString(1, kind);
            ps.setString(2, subject.toString());
            ps.executeUpdate();
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to delete subject records for {}", kind, e);
        }
    }

    /** Background: import rows written by the other servers sharing this database. */
    private static void pullRemoteAll() {
        if (!DatabaseManager.isDatabaseActive()) return;
        for (RecordStore<?> s : STORES) {
            if (s.loaded) s.pullRemote();
        }
    }

    private void pullRemote() {
        long cursor = syncCursor.get();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, subject, payload, server_id, created_at FROM arcadia_admin_records "
                   + "WHERE kind = ? AND id > ? AND server_id <> ? ORDER BY id ASC LIMIT 200")) {
            ps.setString(1, kind);
            ps.setLong(2, cursor);
            ps.setString(3, ServerContext.SERVER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                long maxId = cursor;
                while (rs.next()) {
                    long id = rs.getLong("id");
                    maxId = Math.max(maxId, id);
                    T payload = decode(rs.getString("payload"));
                    if (payload == null) continue;
                    UUID subject;
                    try { subject = UUID.fromString(rs.getString("subject")); }
                    catch (Exception ex) { continue; }
                    insertCached(new Entry<>(id, subject, payload,
                            rs.getString("server_id"), rs.getLong("created_at")));
                }
                syncCursor.accumulateAndGet(maxId, Math::max);
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] Record pull failed for {}", kind, e);
        }
    }
}
