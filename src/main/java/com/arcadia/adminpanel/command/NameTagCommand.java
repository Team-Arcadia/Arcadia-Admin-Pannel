package com.arcadia.adminpanel.command;

import com.arcadia.adminpanel.util.AdminPermissions;
import com.arcadia.adminpanel.util.LanguageHelper;
import com.arcadia.adminpanel.util.NameTagEffect;
import com.arcadia.adminpanel.util.NameTagManager;
import com.arcadia.adminpanel.util.NameTagStyle;
import com.arcadia.adminpanel.util.OfflinePlayerManager;
import com.arcadia.lib.ArcadiaMessages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The {@code /arcadia_adminpanel nametag …} sub-command tree — the admin-facing control surface for
 * the name-tag system. Mutations are all permission-checked server-side (never trust a client), set
 * state on {@link NameTagManager}, persist, and broadcast a live update to every client.
 *
 * <p>Branches:</p>
 * <ul>
 *   <li>{@code color <player> <named>} — solid named colour (16 vanilla colours)</li>
 *   <li>{@code rgb <player> <#hex>} — solid true-colour RGB</li>
 *   <li>{@code gradient <player> <#hex> <#hex> [#hex] [#hex]} — static multi-stop gradient</li>
 *   <li>{@code effect <player> <effect>} — animated effect (rainbow, breathing, chase, …)</li>
 *   <li>{@code style <player> <flag> <on|off>} — bold / italic / underline / strikethrough / obfuscated</li>
 *   <li>{@code speed <player> <1-10>} — animation speed</li>
 *   <li>{@code reset <player>} — clear styling (back to vanilla)</li>
 *   <li>{@code show <player>} — print a player's current styling</li>
 *   <li>{@code hide <on|off>} — global "hide names behind walls" master switch</li>
 *   <li>{@code exempt <player>} — toggle a player's exemption from hiding</li>
 * </ul>
 *
 * @author vyrriox
 */
public final class NameTagCommand {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private NameTagCommand() {}

    /** Player-name suggestions (online + offline cache) — mirrors {@code AdminPanelCommand}. */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        Stream<String> online = ctx.getSource().getOnlinePlayerNames().stream();
        Stream<String> offline = OfflinePlayerManager.getInstance().getCache().values().stream()
                .map(OfflinePlayerManager.CachedPlayerSummary::name);
        return SharedSuggestionProvider.suggest(Stream.concat(online, offline).distinct(), builder);
    };

    /** Named vanilla colours that are actually colours (exclude formatting codes like BOLD). */
    private static final List<String> COLOR_NAMES = buildColorNames();

    private static List<String> buildColorNames() {
        List<String> out = new ArrayList<>();
        for (ChatFormatting cf : ChatFormatting.values()) {
            if (cf.isColor()) out.add(cf.getName());
        }
        return out;
    }

    private static final String[] STYLE_FLAGS =
            {"bold", "italic", "underline", "strikethrough", "obfuscated"};

    /**
     * Builds the {@code nametag} literal node. Plugged into the root by {@code AdminPanelCommand}.
     * The {@code require} predicate is supplied by the caller so this tree shares the exact same
     * OP / node / legacy-staff gating logic as every other sub-command.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> build(
            Predicate<CommandSourceStack> editGate,
            Predicate<CommandSourceStack> hideGate) {

        return Commands.literal("nametag")
                .requires(editGate)

                // color <player> <named>
                .then(Commands.literal("color")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(COLOR_NAMES, b))
                                        .executes(NameTagCommand::execColor))))

                // rgb <player> <#hex>
                .then(Commands.literal("rgb")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("hex", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                new String[]{"#FF0000", "#00FF00", "#0080FF", "#FF8800", "#FF00FF"}, b))
                                        .executes(NameTagCommand::execRgb))))

                // gradient <player> <#hex> <#hex> [#hex] [#hex]
                .then(Commands.literal("gradient")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("c1", StringArgumentType.word())
                                        .then(Commands.argument("c2", StringArgumentType.word())
                                                .executes(ctx -> execGradient(ctx, 2))
                                                .then(Commands.argument("c3", StringArgumentType.word())
                                                        .executes(ctx -> execGradient(ctx, 3))
                                                        .then(Commands.argument("c4", StringArgumentType.word())
                                                                .executes(ctx -> execGradient(ctx, 4))))))))

                // effect <player> <effect>
                .then(Commands.literal("effect")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("effect", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                NameTagEffect.selectableIds(), b))
                                        .executes(NameTagCommand::execEffect))))

                // style <player> <flag> <on|off>
                .then(Commands.literal("style")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("flag", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(STYLE_FLAGS, b))
                                        .then(Commands.argument("state", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        new String[]{"on", "off"}, b))
                                                .executes(NameTagCommand::execStyle)))))

                // speed <player> <1-10>
                .then(Commands.literal("speed")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(Commands.argument("value",
                                                IntegerArgumentType.integer(NameTagStyle.MIN_SPEED, NameTagStyle.MAX_SPEED))
                                        .executes(NameTagCommand::execSpeed))))

                // reset <player>
                .then(Commands.literal("reset")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(NameTagCommand::execReset)))

                // show <player>
                .then(Commands.literal("show")
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(NameTagCommand::execShow)))

                // exempt <player> — toggle hide-exemption
                .then(Commands.literal("exempt")
                        .requires(hideGate)
                        .then(Commands.argument("target", StringArgumentType.string())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(NameTagCommand::execExempt)))

                // hide <on|off> — global master switch
                .then(Commands.literal("hide")
                        .requires(hideGate)
                        .executes(NameTagCommand::execHideShow)
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"on", "off"}, b))
                                .executes(NameTagCommand::execHideSet)));
    }

    // ── Style mutators ──────────────────────────────────────────────────────

    private static int execColor(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        String colorName = StringArgumentType.getString(ctx, "color");
        ChatFormatting cf = ChatFormatting.getByName(colorName);
        if (cf == null || !cf.isColor()) {
            t.source.sendFailure(ArcadiaMessages.error(
                    LanguageHelper.getText("nametag.invalid_color", t.admin)));
            return 0;
        }
        int rgb = NameTagStyle.rgbOf(cf);
        NameTagStyle style = baseOf(t.uuid).withColorsAndEffect(List.of(rgb), NameTagEffect.SOLID);
        apply(t, style, "nametag.set.color", colorName.toUpperCase());
        return 1;
    }

    private static int execRgb(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        Integer rgb = parseHex(StringArgumentType.getString(ctx, "hex"));
        if (rgb == null) {
            t.source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("nametag.invalid_hex", t.admin)));
            return 0;
        }
        NameTagStyle style = baseOf(t.uuid).withColorsAndEffect(List.of(rgb), NameTagEffect.SOLID);
        apply(t, style, "nametag.set.rgb", String.format("#%06X", rgb));
        return 1;
    }

    private static int execGradient(CommandContext<CommandSourceStack> ctx, int count) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        List<Integer> colors = new ArrayList<>(count);
        String[] keys = {"c1", "c2", "c3", "c4"};
        for (int i = 0; i < count; i++) {
            Integer rgb = parseHex(StringArgumentType.getString(ctx, keys[i]));
            if (rgb == null) {
                t.source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("nametag.invalid_hex", t.admin)));
                return 0;
            }
            colors.add(rgb);
        }
        NameTagStyle style = baseOf(t.uuid).withColorsAndEffect(colors, NameTagEffect.GRADIENT);
        apply(t, style, "nametag.set.gradient", String.valueOf(count));
        return 1;
    }

    private static int execEffect(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        String id = StringArgumentType.getString(ctx, "effect");
        NameTagEffect effect = NameTagEffect.fromId(id);
        if (effect == null || effect == NameTagEffect.NONE) {
            t.source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("nametag.invalid_effect", t.admin)));
            return 0;
        }
        // Keep the player's current colours; if they had none, seed a rainbow-friendly default.
        NameTagStyle base = baseOf(t.uuid);
        if (base.colors().size() == 1 && base.colors().get(0) == 0xFFFFFF
                && (effect == NameTagEffect.GRADIENT || effect == NameTagEffect.WAVE)) {
            base = base.withColorsAndEffect(List.of(0xFF0000, 0x0080FF), effect);
        } else {
            base = base.withEffect(effect);
        }
        apply(t, base, "nametag.set.effect", id.toUpperCase());
        return 1;
    }

    private static int execStyle(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        String flag = StringArgumentType.getString(ctx, "flag").toLowerCase();
        boolean valid = false;
        for (String f : STYLE_FLAGS) if (f.equals(flag)) { valid = true; break; }
        if (!valid) {
            t.source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("nametag.invalid_flag", t.admin)));
            return 0;
        }
        boolean on = parseOnOff(StringArgumentType.getString(ctx, "state"));
        // A bare decoration with no effect still needs an effect to render — default to SOLID white.
        NameTagStyle base = baseOf(t.uuid);
        if (base.effect() == NameTagEffect.NONE) base = base.withEffect(NameTagEffect.SOLID);
        NameTagStyle style = base.withFlag(flag, on);
        apply(t, style, on ? "nametag.set.style_on" : "nametag.set.style_off", flag.toUpperCase());
        return 1;
    }

    private static int execSpeed(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        int value = IntegerArgumentType.getInteger(ctx, "value");
        NameTagStyle style = baseOf(t.uuid).withSpeed(value);
        apply(t, style, "nametag.set.speed", String.valueOf(value));
        return 1;
    }

    private static int execReset(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        boolean had = NameTagManager.getInstance().clearStyle(t.uuid);
        NameTagManager.getInstance().broadcastUpdate(t.source.getServer(), t.uuid);
        t.source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText(had ? "nametag.reset" : "nametag.reset_none", t.admin)
                        .replace("%player%", t.name)), true);
        return 1;
    }

    private static int execShow(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        NameTagStyle style = NameTagManager.getInstance().getStyle(t.uuid);
        if (style == null) {
            t.source.sendSuccess(() -> ArcadiaMessages.info(
                    LanguageHelper.getText("nametag.show_none", t.admin).replace("%player%", t.name)), false);
            return 1;
        }
        StringBuilder flags = new StringBuilder();
        if (style.bold()) flags.append("bold ");
        if (style.italic()) flags.append("italic ");
        if (style.underline()) flags.append("underline ");
        if (style.strikethrough()) flags.append("strikethrough ");
        if (style.obfuscated()) flags.append("obfuscated ");
        StringBuilder cols = new StringBuilder();
        for (int c : style.colors()) cols.append(String.format("#%06X ", c));
        final String summary = LanguageHelper.getText("nametag.show", t.admin)
                .replace("%player%", t.name)
                .replace("%effect%", style.effect().id())
                .replace("%colors%", cols.toString().trim())
                .replace("%speed%", String.valueOf(style.speed()))
                .replace("%flags%", flags.length() == 0 ? "-" : flags.toString().trim());
        t.source.sendSuccess(() -> ArcadiaMessages.info(summary), false);
        return 1;
    }

    // ── Hide / exempt ─────────────────────────────────────────────────────────

    private static int execHideShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        boolean on = NameTagManager.getInstance().isHideEnabled();
        source.sendSuccess(() -> ArcadiaMessages.info(
                LanguageHelper.getText("nametag.hide.state", admin).replace("%state%", on ? "ON" : "OFF")), false);
        return 1;
    }

    private static int execHideSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        boolean on = parseOnOff(StringArgumentType.getString(ctx, "state"));
        NameTagManager.getInstance().setHideEnabled(on);
        // Re-sync everyone so the new master switch takes effect immediately.
        NameTagManager.getInstance().syncAll(source.getServer());
        source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText("nametag.hide.set", admin).replace("%state%", on ? "ON" : "OFF")), true);
        return 1;
    }

    private static int execExempt(CommandContext<CommandSourceStack> ctx) {
        ResolvedTarget t = resolve(ctx);
        if (t == null) return 0;
        boolean nowExempt = NameTagManager.getInstance().toggleExempt(t.uuid);
        NameTagManager.getInstance().broadcastUpdate(t.source.getServer(), t.uuid);
        t.source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText(nowExempt ? "nametag.exempt_on" : "nametag.exempt_off", t.admin)
                        .replace("%player%", t.name)), true);
        return 1;
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    /** The current style for a uuid, or a fresh solid-white base so mutators can build on it. */
    private static NameTagStyle baseOf(UUID uuid) {
        NameTagStyle s = NameTagManager.getInstance().getStyle(uuid);
        return s != null ? s : NameTagStyle.solid(0xFFFFFF);
    }

    /** Persist + broadcast + admin feedback for a mutation. */
    private static void apply(ResolvedTarget t, NameTagStyle style, String langKey, String value) {
        NameTagManager.getInstance().setStyle(t.uuid, style);
        NameTagManager.getInstance().broadcastUpdate(t.source.getServer(), t.uuid);
        t.source.sendSuccess(() -> ArcadiaMessages.success(
                LanguageHelper.getText(langKey, t.admin)
                        .replace("%player%", t.name)
                        .replace("%value%", value)), true);
    }

    private record ResolvedTarget(CommandSourceStack source, ServerPlayer admin, UUID uuid, String name) {}

    /** Resolves the {@code target} argument to a UUID + display name, or sends a failure + null. */
    private static ResolvedTarget resolve(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer admin = source.getEntity() instanceof ServerPlayer sp ? sp : null;
        String targetName = StringArgumentType.getString(ctx, "target");
        UUID uuid = resolveUUID(source, targetName);
        if (uuid == null) {
            source.sendFailure(ArcadiaMessages.error(LanguageHelper.getText("error.invalid_target", admin)));
            return null;
        }
        String name = resolveName(source, uuid, targetName);
        return new ResolvedTarget(source, admin, uuid, name);
    }

    /** Online → offline-cache UUID resolution (mirrors {@code AdminPanelCommand.resolveUUID}). */
    private static UUID resolveUUID(CommandSourceStack source, String targetName) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(targetName);
        if (online != null) return online.getUUID();
        UUID exact = null;
        for (var entry : OfflinePlayerManager.getInstance().getCache().entrySet()) {
            String n = entry.getValue().name();
            if (n.equals(targetName)) { exact = entry.getKey(); break; }
            if (n.equalsIgnoreCase(targetName) && exact == null) exact = entry.getKey();
        }
        return exact;
    }

    private static String resolveName(CommandSourceStack source, UUID uuid, String fallback) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
        if (online != null) return online.getName().getString();
        var cached = OfflinePlayerManager.getInstance().getCache().get(uuid);
        return cached != null ? cached.name() : fallback;
    }

    /** Parses {@code #RRGGBB} / {@code RRGGBB} to a packed int, or null if malformed. */
    private static Integer parseHex(String s) {
        if (s == null) return null;
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.length() != 6) return null;
        try {
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseOnOff(String s) {
        String v = s == null ? "" : s.toLowerCase();
        return v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
    }
}
