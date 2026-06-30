package com.arcadia.adminpanel.util;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Reads FTB Teams data from the live world directory.
 *
 * <p>Layout for NeoForge 1.21.1:</p>
 * <pre>
 * &lt;world&gt;/ftbteams/
 *   ftbteams.snbt
 *   player/&lt;team-uuid&gt;.snbt   (PlayerTeam — one per player, id == player uuid)
 *   party/&lt;team-uuid&gt;.snbt    (PartyTeam — player-created)
 *   server/&lt;team-uuid&gt;.snbt   (ServerTeam — admin-created)
 * </pre>
 *
 * <p>We do not depend on the FTB Teams runtime API to keep this mod usable on servers where FTB
 * Teams is missing — instead we parse the SNBT files directly. Cached with a 30s TTL so the admin
 * GUI doesn't hit disk on every redraw.</p>
 *
 * @author vyrriox
 */
public final class FTBTeamsReader {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CACHE_TTL_MS = 30_000L;
    private static volatile Path basePath = null;

    private static final Map<String, CachedTeams> cache = new ConcurrentHashMap<>();

    private record CachedTeams(List<Team> teams, long timestamp) {}

    /** Set once the world's {@code ftbteams} directory is located. */
    public static void setBasePath(Path teamsDir) {
        basePath = teamsDir;
        cache.clear();
        logContents(teamsDir);
    }

    /**
     * Lazily (re)locate the {@code ftbteams} directory from the running server's world path, in case
     * the async startup scan ran before FTB Teams created the directory (it's written lazily on the
     * first team op), missed it, or got cleared. Called from the Teams browser when it's about to read,
     * so opening the menu is self-healing rather than depending solely on the boot-time scan. No-op if
     * already located.
     */
    public static void ensureLocated(@Nullable MinecraftServer server) {
        if (isAvailable() || server == null) return;
        try {
            Path world = server.getWorldPath(LevelResource.ROOT);
            if (world == null) return;
            Path teams = world.resolve("ftbteams");
            if (Files.isDirectory(teams)) {
                setBasePath(teams);
                LOGGER.info("[AdminPanel] Located FTB Teams data on demand at: {}", teams);
            }
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] ensureLocated failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        Path p = basePath;
        return p != null && Files.isDirectory(p);
    }

    /** One-shot diagnostic: list the children of the located ftbteams dir so an unexpected on-disk
     *  layout (e.g. team files nested under a {@code teams/} folder) shows up in the log. */
    private static void logContents(Path teamsDir) {
        if (teamsDir == null) return;
        try (Stream<Path> s = Files.list(teamsDir)) {
            String children = s.map(p -> p.getFileName().toString()).sorted().limit(20)
                    .reduce((a, b) -> a + ", " + b).orElse("(empty)");
            LOGGER.info("[AdminPanel] ftbteams dir contents: {}", children);
        } catch (IOException ignored) {}
    }

    @Nullable
    public static Path getBasePath() { return basePath; }

    public static void clearCache() { cache.clear(); }

    // ── Public read API ─────────────────────────────────────────────────────

    /** All parties (player-created teams). Cached. */
    public static List<Team> getParties() {
        return getTeams(TeamType.PARTY);
    }

    /** All player teams (auto-created on join). Cached. */
    public static List<Team> getPlayerTeams() {
        return getTeams(TeamType.PLAYER);
    }

    /** All server teams. Cached. */
    public static List<Team> getServerTeams() {
        return getTeams(TeamType.SERVER);
    }

    /**
     * Returns the effective team for a player UUID: their party if joined, otherwise their
     * personal {@code PlayerTeam}. Returns {@code null} if no record exists at all.
     */
    @Nullable
    public static Team getEffectiveTeamFor(UUID playerUuid) {
        for (Team party : getParties()) {
            Member m = party.member(playerUuid);
            if (m != null && m.rank.isInTeam()) return party;
        }
        for (Team pt : getPlayerTeams()) {
            if (pt.id.equals(playerUuid)) return pt;
        }
        return null;
    }

    /**
     * Best-effort lookup of the cached player name FTB Teams stores in the per-player team file
     * ({@code player/<uuid>.snbt}, top-level {@code player_name}). Returns {@code null} if FTB Teams
     * data is unavailable or the file/field is missing. Used as one source in offline-name resolution
     * so the admin panel never has to fall back to a raw UUID when FTB Teams knows the name.
     */
    @Nullable
    public static String getPlayerName(UUID playerUuid) {
        Path base = basePath;
        if (base == null) return null;
        Path file = base.resolve(TeamType.PLAYER.dir).resolve(playerUuid + ".snbt");
        if (!Files.isRegularFile(file)) return null;
        CompoundTag tag = readSnbt(file);
        if (tag == null) return null;
        String name = tag.getString("player_name");
        return name != null && !name.isBlank() ? name : null;
    }

    private static List<Team> getTeams(TeamType type) {
        if (basePath == null) return List.of();
        CachedTeams cached = cache.get(type.dir);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.teams;
        }
        Path dir = resolveTypeDir(basePath, type);
        if (dir == null) {
            cache.put(type.dir, new CachedTeams(List.of(), System.currentTimeMillis()));
            return List.of();
        }
        List<Team> teams = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            // Filter on filename (not full path) — a parent dir containing ".snbt" would otherwise pass.
            stream.filter(p -> p.getFileName().toString().endsWith(".snbt")).forEach(file -> {
                Team t = parseTeam(file, type);
                if (t != null) teams.add(t);
            });
        } catch (IOException e) {
            LOGGER.warn("[AdminPanel] Failed to list FTB Teams dir {}: {}", dir, e.getMessage());
        }
        teams.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        cache.put(type.dir, new CachedTeams(teams, System.currentTimeMillis()));
        return teams;
    }

    /** Resolves a team-type folder, tolerating both the flat ({@code ftbteams/<type>}) and the
     *  nested ({@code ftbteams/teams/<type>}) layouts FTB Teams has used. Null if neither exists. */
    @Nullable
    private static Path resolveTypeDir(Path base, TeamType type) {
        Path flat = base.resolve(type.dir);
        if (Files.isDirectory(flat)) return flat;
        Path nested = base.resolve("teams").resolve(type.dir);
        if (Files.isDirectory(nested)) return nested;
        return null;
    }

    @Nullable
    private static Team parseTeam(Path file, TeamType type) {
        try {
            CompoundTag tag = readSnbt(file);
            if (tag == null) return null;

            String idStr = tag.getString("id");
            UUID id;
            try { id = UUID.fromString(idStr); }
            catch (IllegalArgumentException e) {
                // Some FTB versions omit "id" from disk (it's the filename).
                String fn = file.getFileName().toString();
                if (fn.endsWith(".snbt")) fn = fn.substring(0, fn.length() - 5);
                try { id = UUID.fromString(fn); }
                catch (IllegalArgumentException e2) { return null; }
            }

            String displayName = id.toString().substring(0, 8);
            String color = "#FFFFFF";
            String description = "";

            if (tag.contains("properties", Tag.TAG_COMPOUND)) {
                CompoundTag props = tag.getCompound("properties");
                if (props.contains("ftbteams:display_name")) {
                    displayName = stripFormatting(props.getString("ftbteams:display_name"));
                }
                if (props.contains("ftbteams:color")) {
                    color = props.getString("ftbteams:color");
                }
                if (props.contains("ftbteams:description")) {
                    description = props.getString("ftbteams:description");
                }
            }

            // PlayerTeam stores cached name directly.
            if (type == TeamType.PLAYER && tag.contains("player_name") && displayName.length() < 3) {
                displayName = tag.getString("player_name");
            }

            UUID owner = null;
            if (tag.contains("owner")) {
                try { owner = UUID.fromString(tag.getString("owner")); }
                catch (IllegalArgumentException ignored) {}
            }
            if (owner == null && type == TeamType.PLAYER) {
                owner = id; // PlayerTeam id == player uuid.
            }

            List<Member> members = new ArrayList<>();
            if (tag.contains("ranks", Tag.TAG_COMPOUND)) {
                CompoundTag ranks = tag.getCompound("ranks");
                for (String key : ranks.getAllKeys()) {
                    try {
                        UUID memberId = UUID.fromString(key);
                        Rank rank = Rank.fromString(ranks.getString(key));
                        members.add(new Member(memberId, rank));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            // FTB Teams never writes the owner into the "ranks" map: for a PartyTeam the owner lives
            // only in the top-level "owner" string, and for a PlayerTeam the owner is the team id
            // itself (injected as `owner` above). FTB re-applies the OWNER rank at runtime via
            // getRankForPlayer(). Without mirroring that here, every solo team (owner only, no other
            // ranks) parses to memberCount()==0 and getEffectiveTeamFor() can't match the owner —
            // which is the core "teams show empty / aren't found" bug. Verified against FTB Teams
            // 2101.x AbstractTeam.serializeNBT + PartyTeam/PlayerTeam.getRankForPlayer.
            if (owner != null) {
                boolean ownerListed = false;
                for (Member m : members) {
                    if (m.uuid.equals(owner)) { ownerListed = true; break; }
                }
                if (!ownerListed) members.add(new Member(owner, Rank.OWNER));
            }

            return new Team(id, type, displayName, color, description, owner, members);
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] Failed to parse team file {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Reads a textual SNBT file. FTB Teams emits human-readable SNBT (not the gzipped binary NBT
     * format), so we read the file as UTF-8 and feed it to {@link TagParser}.
     */
    @Nullable
    private static CompoundTag readSnbt(Path file) {
        try {
            String content = Files.readString(file);
            return TagParser.parseTag(content);
        } catch (Exception e) {
            LOGGER.debug("[AdminPanel] Failed to read SNBT {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
    }

    // ── Data shapes ─────────────────────────────────────────────────────────

    public enum TeamType {
        PLAYER("player"),
        PARTY("party"),
        SERVER("server");

        public final String dir;
        TeamType(String dir) { this.dir = dir; }
    }

    public enum Rank {
        OWNER, OFFICER, MEMBER, ALLY, INVITED, NONE, ENEMY;

        public boolean isInTeam() {
            return this == OWNER || this == OFFICER || this == MEMBER;
        }

        public static Rank fromString(String s) {
            if (s == null) return NONE;
            try { return Rank.valueOf(s.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return NONE; }
        }
    }

    public record Member(UUID uuid, Rank rank) {}

    public static final class Team {
        public final UUID id;
        public final TeamType type;
        public final String displayName;
        public final String color;
        public final String description;
        @Nullable public final UUID owner;
        public final List<Member> members;

        public Team(UUID id, TeamType type, String displayName, String color, String description,
                    @Nullable UUID owner, List<Member> members) {
            this.id = id;
            this.type = type;
            this.displayName = displayName;
            this.color = color;
            this.description = description;
            this.owner = owner;
            this.members = members;
        }

        @Nullable
        public Member member(UUID uuid) {
            for (Member m : members) if (m.uuid.equals(uuid)) return m;
            return null;
        }

        public int memberCount() {
            int c = 0;
            for (Member m : members) if (m.rank.isInTeam()) c++;
            return c;
        }
    }

    private FTBTeamsReader() {}
}
