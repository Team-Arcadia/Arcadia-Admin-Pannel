package com.arcadia.adminpanel.util;

import com.arcadia.lib.staff.StaffActions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pre-written reasons with an automatic escalation ladder.
 *
 * <p>The problem this solves is consistency, not typing. Two moderators handling the same offence a
 * week apart will pick different durations, and the player who got the harsher one will say so. A
 * template pins the wording and the progression: first offence a warn, second a thirty-minute mute,
 * third a day-long ban, and the panel picks the rung by counting what the player already collected
 * under that same template.</p>
 *
 * <p>The ladder is per template, not global, so a first offence for advertising does not inherit the
 * step count from three grief warnings. Operators can rewrite the whole set in
 * {@code config/arcadia/arcadiaadminpanel/templates.json}; a missing file is written with the
 * defaults below on first boot.</p>
 *
 * @author vyrriox
 */
public final class SanctionTemplates {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One rung of a ladder. {@code minutes} is ignored by actions that have no duration. */
    public static final class Rung {
        public String action = "warn";
        public long minutes = 0L;

        public Rung() {}
        public Rung(AdminAction action, long minutes) {
            this.action = action.id();
            this.minutes = minutes;
        }

        public AdminAction resolve() {
            AdminAction a = AdminAction.byId(action);
            return a != null ? a : AdminAction.WARN;
        }
    }

    /** One offence type. */
    public static final class Template {
        public String id = "generic";
        public String icon = "minecraft:paper";
        public String labelEn = "Generic";
        public String labelFr = "Generique";
        public String reasonEn = "Rule violation";
        public String reasonFr = "Violation du reglement";
        public List<Rung> ladder = new ArrayList<>();

        public Item iconItem() {
            ResourceLocation id = ResourceLocation.tryParse(icon);
            if (id == null) return Items.PAPER;
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            return item == Items.AIR ? Items.PAPER : item;
        }

        public String label(ServerPlayer viewer) {
            return isFrench(viewer) ? labelFr : labelEn;
        }

        public String reason(ServerPlayer viewer) {
            return isFrench(viewer) ? reasonFr : reasonEn;
        }

        private static boolean isFrench(ServerPlayer viewer) {
            try {
                String lang = viewer == null ? null : viewer.clientInformation().language();
                return lang != null && lang.toLowerCase().startsWith("fr");
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Records which rung a player has already climbed, per template. */
    private record LadderStep(String templateId, int step, String byName) {}

    private static final RecordStore<LadderStep> LADDER =
            new RecordStore<>("ladder", LadderStep.class, 8000);

    private static volatile List<Template> templates = defaults();

    private SanctionTemplates() {}

    // -- Config --------------------------------------------------------------

    public static List<Template> all() { return templates; }

    @Nullable
    public static Template byId(String id) {
        for (Template t : templates) if (t.id.equals(id)) return t;
        return null;
    }

    public static void init() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("arcadia/arcadiaadminpanel");
        Path file = dir.resolve("templates.json");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (!Files.exists(file)) {
                try (FileWriter w = new FileWriter(file.toFile())) {
                    GSON.toJson(defaults(), w);
                }
                templates = defaults();
                LOGGER.info("[AdminPanel] Wrote default sanction templates at {}", file);
                return;
            }
            try (FileReader r = new FileReader(file.toFile())) {
                List<Template> loaded = GSON.fromJson(r, new TypeToken<List<Template>>() {}.getType());
                if (loaded != null && !loaded.isEmpty()) templates = loaded;
            }
            LOGGER.info("[AdminPanel] Loaded {} sanction templates", templates.size());
        } catch (Exception e) {
            LOGGER.error("[AdminPanel] Failed to load sanction templates; using defaults", e);
            templates = defaults();
        }
    }

    public static void reload() { init(); }

    // -- Escalation ----------------------------------------------------------

    /** How many times this template has already been applied to the player. */
    public static int stepFor(UUID target, String templateId) {
        int n = 0;
        for (var e : LADDER.forSubject(target)) {
            if (templateId.equals(e.payload().templateId())) n++;
        }
        return n;
    }

    /** The rung that would be applied next, or the last one once the ladder is exhausted. */
    public static Rung nextRung(UUID target, Template template) {
        if (template.ladder.isEmpty()) return new Rung(AdminAction.WARN, 0L);
        int step = AdminConfig.get().escalationEnabled ? stepFor(target, template.id) : 0;
        return template.ladder.get(Math.min(step, template.ladder.size() - 1));
    }

    /**
     * Applies a template to a player: picks the rung, carries out the sanction, and records the step
     * so the next application climbs.
     *
     * @return the rung that was applied, or {@code null} when the sanction could not be carried out
     */
    @Nullable
    public static Rung apply(ServerPlayer actor, MinecraftServer server, UUID targetId,
                             String targetName, Template template) {
        Rung rung = nextRung(targetId, template);
        AdminAction action = rung.resolve();
        String reason = template.reason(actor);
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);

        switch (action) {
            case WARN -> WarnManager.getInstance().addWarn(targetId, reason,
                    actor != null ? actor.getName().getString() : "CONSOLE");
            case MUTE -> {
                if (online == null) return null;
                StaffActions.mute(targetId, actor, reason, rung.minutes * 60_000L);
            }
            case KICK -> {
                if (online == null) return null;
                online.connection.disconnect(net.minecraft.network.chat.Component.literal("§c" + reason));
            }
            case JAIL -> {
                String by = actor != null ? actor.getName().getString() : "CONSOLE";
                if (online != null) {
                    JailManager.getInstance().jail(online, reason, by, rung.minutes * 60_000L, server);
                } else {
                    JailManager.getInstance().jail(targetId, reason, by, rung.minutes * 60_000L);
                }
            }
            case TEMPBAN, BAN -> BanManager.ban(actor, server, targetId, targetName, reason, rung.minutes);
            default -> WarnManager.getInstance().addWarn(targetId, reason,
                    actor != null ? actor.getName().getString() : "CONSOLE");
        }

        LADDER.append(targetId, new LadderStep(template.id, stepFor(targetId, template.id) + 1,
                actor != null ? actor.getName().getString() : "CONSOLE"));

        // BanManager and the jail path audit themselves; the rest are recorded here so every rung
        // shows up in the history with the reason that produced it.
        if (action != AdminAction.TEMPBAN && action != AdminAction.BAN) {
            AuditManager.record(actor, action, targetId, targetName, reason, rung.minutes * 60_000L);
        }
        return rung;
    }

    /** Wipes a player's ladder progress, e.g. after an appeal. */
    public static int resetLadder(UUID target) {
        return LADDER.removeAll(target);
    }

    // -- Defaults ------------------------------------------------------------

    private static List<Template> defaults() {
        List<Template> out = new ArrayList<>();
        out.add(make("chat", "minecraft:name_tag", "Chat abuse", "Abus dans le chat",
                "Inappropriate language in chat", "Langage inapproprie dans le chat",
                new Rung(AdminAction.WARN, 0), new Rung(AdminAction.MUTE, 30),
                new Rung(AdminAction.MUTE, 180), new Rung(AdminAction.TEMPBAN, 1440)));
        out.add(make("spam", "minecraft:repeater", "Spam", "Spam",
                "Flooding the chat", "Inondation du chat",
                new Rung(AdminAction.WARN, 0), new Rung(AdminAction.MUTE, 15),
                new Rung(AdminAction.MUTE, 120)));
        out.add(make("grief", "minecraft:tnt", "Griefing", "Grief",
                "Destroying or stealing from another player", "Destruction ou vol chez un autre joueur",
                new Rung(AdminAction.WARN, 0), new Rung(AdminAction.JAIL, 30),
                new Rung(AdminAction.TEMPBAN, 1440), new Rung(AdminAction.BAN, 0)));
        out.add(make("cheat", "minecraft:diamond_sword", "Cheating", "Triche",
                "Use of an unauthorised client or exploit", "Utilisation d'un client ou d'un exploit non autorise",
                new Rung(AdminAction.TEMPBAN, 4320), new Rung(AdminAction.BAN, 0)));
        out.add(make("dupe", "minecraft:diamond_block", "Duplication", "Duplication",
                "Item duplication", "Duplication d'objets",
                new Rung(AdminAction.TEMPBAN, 1440), new Rung(AdminAction.BAN, 0)));
        out.add(make("ads", "minecraft:oak_sign", "Advertising", "Publicite",
                "Advertising another server", "Publicite pour un autre serveur",
                new Rung(AdminAction.MUTE, 60), new Rung(AdminAction.TEMPBAN, 1440)));
        out.add(make("staff", "minecraft:shield", "Staff disrespect", "Manque de respect au staff",
                "Disrespecting a staff member", "Manque de respect envers un membre du staff",
                new Rung(AdminAction.WARN, 0), new Rung(AdminAction.MUTE, 60),
                new Rung(AdminAction.TEMPBAN, 720)));
        return out;
    }

    private static Template make(String id, String icon, String labelEn, String labelFr,
                                 String reasonEn, String reasonFr, Rung... ladder) {
        Template t = new Template();
        t.id = id;
        t.icon = icon;
        t.labelEn = labelEn;
        t.labelFr = labelFr;
        t.reasonEn = reasonEn;
        t.reasonFr = reasonFr;
        t.ladder = new ArrayList<>(List.of(ladder));
        return t;
    }
}
