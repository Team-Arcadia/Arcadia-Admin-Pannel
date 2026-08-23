package com.arcadia.adminpanel.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted configuration for the admin panel — single JSON file at
 * {@code config/arcadia/arcadiaadminpanel/config.json}. Loaded once at server start, reloadable.
 *
 * <p>Why JSON and not a NeoForge config spec? The lib already standardises on Gson everywhere
 * (jail, warns, logins) and operators editing this file by hand benefit from a single mental
 * model. Defaults are inlined so a missing file is equivalent to "everything off / safe defaults".</p>
 *
 * @author vyrriox
 */
public final class AdminConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Data DATA = Data.defaults();

    /** All tunables. Public fields = easy Gson round-trip. */
    public static final class Data {
        // ── Warn lifecycle ──────────────────────────────────────────────────
        /** Auto-expire warns older than this many days. 0 disables expiry. Default: 180 (≈6 months). */
        public int warnExpiryDays = 180;
        /** On player join, send a chat message listing their active warns. */
        public boolean warnNotifyOnJoin = true;

        // ── Login queue ─────────────────────────────────────────────────────
        /** Master switch for the queue. When false, players connect normally regardless of load. */
        public boolean loginQueueEnabled = false;
        /** Maximum concurrent logins inside the rolling window. */
        public int loginQueueMaxPerWindow = 4;
        /** Rolling window in seconds (4 logins per 10 s = ~24/min, gentle on heavy modpacks). */
        public int loginQueueWindowSeconds = 10;
        /** Cap on how long a queued player may wait (ms) before we reject them with a "try again" kick. */
        public long loginQueueMaxWaitMs = 60_000L;

        // ── Jail anti-glitch ────────────────────────────────────────────────
        /** Run a periodic check that yanks jailed players who drifted outside the jail box back in. */
        public boolean jailEnforceProximity = true;
        /** Radius (blocks) from the jail spawn beyond which the player gets teleported back. */
        public int jailProximityRadius = 32;
        /** Tick interval for the anti-glitch sweep (20 ticks = 1 second). */
        public int jailEnforceTickInterval = 20;

        // ── Name tags ───────────────────────────────────────────────────────
        /**
         * Master switch for "hide names behind walls". When ON (default), a player's floating
         * name tag is suppressed client-side whenever a block sits on the line of sight between the
         * observer's camera and that player — you can't read who is hiding behind a wall. Enforced
         * by the client renderer; the server only broadcasts this flag. Operator-requested default.
         */
        public boolean nameTagHideBehindWalls = true;
        /**
         * When true, transparent/non-solid blocks (glass, water, leaves…) also occlude the name.
         * Default false: only fully opaque blocks hide a name, matching the "behind a wall" intent.
         */
        public boolean nameTagOccludeTransparent = false;
        /**
         * Event "blackout" switch (1.2.9). When ON, <em>every</em> player's floating name is hidden
         * for everyone — the hide-and-seek mode. Exempt players (see the per-player exemption set)
         * still show, so staff can be made visible during a game. OFF by default. Persisted here so a
         * running event survives a restart; flipped live by {@code /arcadia_adminpanel nametag hideall}.
         */
        public boolean nameTagHideAll = false;
        /**
         * Max distance (blocks) at which the wall-occlusion raytrace runs (1.2.9). Beyond it the name
         * is left visible — raycasting every frame for very distant players isn't worth the cost. The
         * old hard-coded 64-block gate was too small: vanilla draws player names well past 64 blocks,
         * so a distant player behind a wall stayed readable. 128 covers any realistic "read the name"
         * range while bounding the per-frame work. Squared on the client.
         */
        public int nameTagHideMaxDistance = 128;

        // ── Disguise ─────────────────────────────────────────────────────────
        /**
         * Play the disguise mob's sounds (1.2.9): periodic ambient calls (e.g. a pig oinks), plus the
         * mob's hurt and death sounds when the disguised player is damaged / dies. Server-broadcast so
         * everyone nearby hears them in sync. ON by default; flip OFF to keep events fully silent.
         */
        public boolean disguiseSounds = true;
        /** Min / max ticks between a disguise's ambient sounds (random in range). Default 100–300 (5–15 s). */
        public int disguiseAmbientMinTicks = 100;
        public int disguiseAmbientMaxTicks = 300;
        /**
         * Drop a player's disguise when they die (1.3.0). OFF by default so an event survives a
         * mistake; operators running hide-and-seek where death means elimination will want it ON.
         */
        public boolean disguiseClearOnDeath = false;

        // ── Audit log & sanction visibility (1.3.0) ─────────────────────────
        /**
         * Announce sanctions to the whole server. OFF by default: most communities prefer public
         * shaming to be a deliberate choice. Staff always see the sanction regardless, and silent
         * mode suppresses this broadcast even when the switch is ON.
         */
        public boolean broadcastSanctions = false;
        /** Drop audit rows older than this many days on the periodic sweep. 0 disables the sweep. */
        public int auditRetentionDays = 180;

        // ── Discord sanction webhook (1.3.0) ────────────────────────────────
        /**
         * Incoming-webhook URL for the sanction mirror. This is a credential: keep it in this local
         * file only, never in git. Empty (the default) disables the feature and every outbound call
         * with it. The staff chat is deliberately never sent here.
         */
        public String discordWebhookUrl = "";
        /** Display name the webhook posts under. */
        public String discordWebhookName = "Arcadia Moderation";
        /** Skip actions performed in silent mode. ON by default: silent means silent everywhere. */
        public boolean discordSkipSilent = true;

        // ── Vanish (1.3.0) ──────────────────────────────────────────────────
        /** Remove a vanished staff member from the TAB player list. */
        public boolean vanishHideFromTab = true;
        /** Print a fake leave/join line when toggling, so the disappearance looks like a disconnect. */
        public boolean vanishFakeJoinLeave = true;
        /** A vanished staff member walks over items without picking them up. */
        public boolean vanishNoPickup = true;
        /** Mobs forget a vanished staff member instead of tracking them. */
        public boolean vanishNoMobTarget = true;
        /**
         * Restore vanish on the next login. OFF by default: a staff member who reconnects invisible
         * without realising it is a support incident waiting to happen, and the vanilla join message
         * cannot be suppressed without a mixin, so the disguise would be broken anyway.
         */
        public boolean vanishPersist = false;

        // ── Freeze / screenshare (1.3.0) ────────────────────────────────────
        /** A frozen player takes no damage. Prevents "died during the screenshare" disputes. */
        public boolean freezeDamageImmunity = true;
        /** Seconds between the reminder lines a frozen player receives. 0 disables the reminder. */
        public int freezeReminderSeconds = 10;

        // ── Bans (1.3.0) ────────────────────────────────────────────────────
        /** Default duration (minutes) used by the GUI temp-ban button. */
        public int defaultTempbanMinutes = 1440;
        /** Replicate bans to the other servers sharing the database. */
        public boolean banSyncEnabled = true;

        // ── Sanction templates & escalation (1.3.0) ─────────────────────────
        /** Apply the escalation ladder when a template is used from the GUI. */
        public boolean escalationEnabled = true;

        // ── Chat control (1.3.0) ────────────────────────────────────────────
        /** Staff can still talk while the chat is locked. */
        public boolean chatLockAllowStaff = true;
        /** Blank lines pushed by the clear-chat action. */
        public int clearChatLines = 100;

        // ── Death snapshots (1.3.0) ─────────────────────────────────────────
        /** Capture a player's full inventory on death so it can be restored later. */
        public boolean deathSnapshotsEnabled = true;
        /** How many deaths to keep per player. Oldest is dropped past this. */
        public int deathSnapshotsPerPlayer = 5;

        // ── Inventory editor (1.3.0) ────────────────────────────────────────
        /** Allow editing an online player's inventory from the panel. */
        public boolean inventoryEditEnabled = true;
        /** Allow editing a disconnected player's inventory by rewriting their playerdata. */
        public boolean offlineInventoryEditEnabled = true;

        // ── Offline mail (1.3.0) ────────────────────────────────────────────
        /** Cap on undelivered messages per player. */
        public int mailMaxPerPlayer = 20;

        // ── AFK tracking (1.3.0) ────────────────────────────────────────────
        public boolean afkEnabled = true;
        /** Minutes of no movement, no chat and no interaction before a player counts as AFK. */
        public int afkMinutes = 5;
        /** Ticks between AFK sweeps. 100 = every 5 s; the sweep is a position compare per player. */
        public int afkCheckIntervalTicks = 100;

        // ── Alt detection (1.3.0) ───────────────────────────────────────────
        /**
         * Group accounts by connection fingerprint. The fingerprint is a salted SHA-256 of the
         * address: the panel can tell you two accounts connect from the same place, and can never
         * tell you (or anyone reading the data files) where that is.
         */
        public boolean altDetectionEnabled = true;
        /** Ping staff when an account logs in sharing a fingerprint with a banned account. */
        public boolean altAlertStaff = true;
        /**
         * Keep writing the plain-text address in {@code logins.json}. OFF since 1.3.0: the hashed
         * fingerprint covers every panel feature that used it, and a plain-text address in a file is
         * personal data nobody asked to store. Turning it back ON is an operator decision.
         */
        public boolean storePlainIp = false;

        // ── Client mod inventory (1.3.0) ────────────────────────────────────
        /** Collect the mod list reported by clients that run this mod. */
        public boolean clientModsEnabled = true;
        /** Mod ids that raise a staff alert when reported. Matched case-insensitively. */
        public java.util.List<String> clientModBlacklist = new java.util.ArrayList<>();
        /** Ping staff when a blacklisted mod is reported. */
        public boolean clientModAlertStaff = true;

        // ── Performance panel (1.3.0) ───────────────────────────────────────
        /** Seconds a computed lag sample is reused before being recomputed. */
        public int lagSampleCacheSeconds = 10;
        /** How many hot chunks the panel lists. */
        public int lagTopChunks = 10;
        /** Radius (blocks) used when attributing nearby entities to a player. */
        public int lagEntityRadius = 64;

        // ── Scheduled restart (1.3.0) ───────────────────────────────────────
        /** Daily restart times in 24 h {@code HH:mm} local time. Empty disables the schedule. */
        public java.util.List<String> restartScheduleTimes = new java.util.ArrayList<>();
        /** Minute marks at which a warning is broadcast before a restart. */
        public java.util.List<Integer> restartWarnMinutes =
                new java.util.ArrayList<>(java.util.List.of(15, 10, 5, 3, 1));
        /** Seconds of per-second countdown at the very end. */
        public int restartCountdownSeconds = 10;
        /** Kick message shown when the restart fires. */
        public String restartReason = "Scheduled restart";

        // ── Auto broadcast (1.3.0) ──────────────────────────────────────────
        public boolean autoBroadcastEnabled = false;
        /** Minutes between two rotating messages. */
        public int autoBroadcastIntervalMinutes = 15;
        /** Messages, broadcast in order then looped. Supports the § colour codes. */
        public java.util.List<String> autoBroadcastMessages = new java.util.ArrayList<>();

        // ── Automatic post-reboot login queue (1.3.0) ───────────────────────
        /** Turn the login queue on by itself when the server finishes booting. */
        public boolean loginQueueAutoAfterBoot = false;
        /** Minutes the automatic queue stays on before switching itself back off. */
        public int loginQueueAutoMinutes = 10;

        // ── Watchlist & radar (1.3.0) ───────────────────────────────────────
        /** Ping staff when a watched player connects. */
        public boolean watchlistAlertOnJoin = true;
        /** Radius (blocks) scanned by the proximity radar. */
        public int radarRadius = 128;

        static Data defaults() { return new Data(); }
    }

    private AdminConfig() {}

    public static Data get() { return DATA; }

    public static void init() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        Path file = dir.resolve("config.json");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (!Files.exists(file)) {
                writeDefaults(file);
                LOGGER.info("[AdminPanel] Wrote default config at {}", file);
            } else {
                try (FileReader r = new FileReader(file.toFile())) {
                    Data loaded = GSON.fromJson(r, Data.class);
                    if (loaded != null) DATA = loaded;
                }
                LOGGER.info("[AdminPanel] Loaded config from {}", file);
            }
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load config; using defaults", e);
            DATA = Data.defaults();
        }
    }

    private static void writeDefaults(Path file) throws IOException {
        try (FileWriter w = new FileWriter(file.toFile())) {
            GSON.toJson(Data.defaults(), w);
        }
    }

    /** Persist the current in-memory config to disk (used by runtime setters like /jailradius). */
    public static synchronized void save() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        Path file = dir.resolve("config.json");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (FileWriter w = new FileWriter(file.toFile())) {
                GSON.toJson(DATA, w);
            }
        } catch (IOException e) {
            LOGGER.error("[AdminPanel] Failed to save config", e);
        }
    }

    /** Re-read from disk. Called by {@code /arcadia_adminpanel reload}. */
    public static void reload() { init(); }
}
