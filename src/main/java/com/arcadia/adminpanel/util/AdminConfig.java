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
