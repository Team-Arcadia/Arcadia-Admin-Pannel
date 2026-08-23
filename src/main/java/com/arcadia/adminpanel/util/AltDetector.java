package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups accounts that connect from the same place, without ever storing where that is.
 *
 * <p><b>The design constraint.</b> Alt detection is genuinely useful: a banned player coming back on
 * a second account is the most common evasion there is. But the usual implementation, keeping a
 * column of IP addresses, means every backup, every support request and every accidental screenshot
 * now carries the personal data of everyone who ever joined.</p>
 *
 * <p><b>What this does instead.</b> The address is hashed with SHA-256 and a per-installation random
 * salt, and only the digest is kept. Two accounts with the same digest connected from the same
 * address; that is the entire question the feature needs answered. The digest cannot be turned back
 * into an address, the salt never leaves the server, and the panel shows "these accounts share a
 * connection fingerprint" rather than an address anyone could act on directly.</p>
 *
 * <p>The salt lives in {@code config/arcadia/arcadiaadminpanel/fingerprint.salt}, is generated on
 * first run, and must not be committed or shared. Losing it only means old fingerprints stop
 * matching new ones.</p>
 *
 * @author vyrriox
 */
public final class AltDetector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile byte[] salt;

    private AltDetector() {}

    // -- Salt ----------------------------------------------------------------

    public static void init() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        Path file = dir.resolve("fingerprint.salt");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (Files.exists(file)) {
                byte[] read = Files.readAllBytes(file);
                if (read.length >= 16) {
                    salt = read;
                    return;
                }
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Files.write(file, fresh);
            salt = fresh;
            LOGGER.info("[AdminPanel] Generated a new connection-fingerprint salt");
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Could not prepare the fingerprint salt; alt detection is off", e);
            salt = null;
        }
    }

    // -- Fingerprint ---------------------------------------------------------

    /**
     * Salted digest of an address, or {@code null} when the feature is off, the salt is missing, or
     * the address could not be read.
     */
    @Nullable
    public static String fingerprint(@Nullable String address) {
        if (address == null || address.isBlank()) return null;
        if (!AdminConfig.get().altDetectionEnabled) return null;
        byte[] s = salt;
        if (s == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(s);
            digest.update(address.getBytes(StandardCharsets.UTF_8));
            byte[] out = digest.digest();
            // 12 bytes is far past the point where a collision is plausible on a player list, and
            // short enough to render in a tooltip.
            byte[] truncated = new byte[12];
            System.arraycopy(out, 0, truncated, 0, 12);
            return HexFormat.of().formatHex(truncated);
        } catch (Exception e) {
            return null;
        }
    }

    // -- Queries -------------------------------------------------------------

    /** One account sharing a fingerprint. */
    public record Alt(UUID uuid, String name, long lastLoginMs, boolean online, boolean banned) {}

    /**
     * Every other account that shares {@code subject}'s fingerprint, most recently seen first.
     * Reads only the in-memory login cache: no disk, no network.
     */
    public static List<Alt> altsOf(MinecraftServer server, UUID subject) {
        LoginTracker.LoginRecord own = LoginTracker.getInstance().get(subject);
        if (own == null || own.ipHash() == null) return List.of();
        String needle = own.ipHash();

        List<Alt> out = new ArrayList<>();
        for (var e : LoginTracker.getInstance().snapshot().entrySet()) {
            if (e.getKey().equals(subject)) continue;
            if (!needle.equals(e.getValue().ipHash())) continue;
            String name = OfflinePlayerManager.getInstance().getName(e.getKey());
            if (name == null) name = e.getKey().toString().substring(0, 8);
            boolean online = server.getPlayerList().getPlayer(e.getKey()) != null;
            out.add(new Alt(e.getKey(), name, e.getValue().lastLoginMs(), online,
                    BanManager.isBanned(server, e.getKey(), name)));
        }
        out.sort(Comparator.comparingLong(Alt::lastLoginMs).reversed());
        return out;
    }

    /** Every fingerprint that covers more than one account, largest group first. */
    public static List<List<Alt>> groups(MinecraftServer server) {
        Map<String, List<UUID>> byHash = new HashMap<>();
        for (var e : LoginTracker.getInstance().snapshot().entrySet()) {
            String hash = e.getValue().ipHash();
            if (hash == null) continue;
            byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(e.getKey());
        }
        List<List<Alt>> out = new ArrayList<>();
        for (var e : byHash.entrySet()) {
            if (e.getValue().size() < 2) continue;
            List<Alt> group = new ArrayList<>();
            for (UUID id : e.getValue()) {
                String name = OfflinePlayerManager.getInstance().getName(id);
                if (name == null) name = id.toString().substring(0, 8);
                LoginTracker.LoginRecord rec = LoginTracker.getInstance().get(id);
                group.add(new Alt(id, name, rec == null ? 0L : rec.lastLoginMs(),
                        server.getPlayerList().getPlayer(id) != null,
                        BanManager.isBanned(server, id, name)));
            }
            group.sort(Comparator.comparingLong(Alt::lastLoginMs).reversed());
            out.add(group);
        }
        out.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return out;
    }

    /** How many other accounts share this player's fingerprint. */
    public static int altCount(MinecraftServer server, UUID subject) {
        return altsOf(server, subject).size();
    }

    /**
     * How many fingerprints cover more than one account.
     *
     * <p>Separate from {@link #groups} because the tools menu only wants the number for a tile, and
     * building the full group list means a ban-list lookup and a name resolution per member. This is
     * one pass over the login cache counting strings.</p>
     */
    public static int groupCount() {
        Map<String, Integer> counts = new HashMap<>();
        for (var e : LoginTracker.getInstance().snapshot().values()) {
            String hash = e.ipHash();
            if (hash != null) counts.merge(hash, 1, Integer::sum);
        }
        int groups = 0;
        for (int n : counts.values()) if (n >= 2) groups++;
        return groups;
    }

    // -- Login hook ----------------------------------------------------------

    /**
     * Warns staff when a connecting account shares a fingerprint with a banned one. Runs once per
     * login over the in-memory cache, so the cost is a map scan of the known player set.
     */
    public static void onJoin(ServerPlayer player) {
        if (!AdminConfig.get().altDetectionEnabled || !AdminConfig.get().altAlertStaff) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        List<Alt> alts = altsOf(server, player.getUUID());
        if (alts.isEmpty()) return;

        List<String> bannedNames = new ArrayList<>();
        for (Alt alt : alts) if (alt.banned()) bannedNames.add(alt.name());
        if (bannedNames.isEmpty()) return;

        String joined = String.join(", ", bannedNames);
        StaffFeed.alertStaffKey("alt.ban_evasion", staff ->
                LanguageHelper.getText("alt.ban_evasion", staff)
                        .replace("%player%", player.getName().getString())
                        .replace("%accounts%", joined));
    }
}
