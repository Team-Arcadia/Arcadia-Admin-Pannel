package com.arcadia.adminpanel.util;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;

/**
 * Parses the SNBT dialect the FTB mods write (FTB Essentials, FTB Teams, FTB Chunks).
 *
 * <p>FTB serialises through FTB Library's own SNBT writer, which is <b>not</b> what vanilla's
 * {@link TagParser} accepts: it separates compound entries and list elements with <b>line breaks
 * instead of commas</b>, and its config files carry {@code #} line comments. A real FTB Essentials
 * player file looks like this:</p>
 *
 * <pre>
 * {
 *     muted: false
 *     last_seen: { dim: "minecraft:overworld", x: -157, y: 15, z: -1597 }
 *     homes: {
 *         base: { dim: "minecraft:overworld", x: -99, y: 78, z: -316 }
 *     }
 * }
 * </pre>
 *
 * <p>Vanilla {@code TagParser.readStruct()} stops reading entries as soon as
 * {@code hasElementSeparator()} finds no {@code ,}, then fails on {@code expect('}')} — so feeding
 * such a file straight to {@link TagParser} throws on <b>every</b> FTB file, not just odd ones. That
 * silently blanked homes, last-seen positions, teleport history and claim counts across the panel.</p>
 *
 * <p>{@link #parse(String)} therefore tries the strict vanilla parse first (so a file that is already
 * vanilla-compliant is never touched by the heuristic) and only then re-parses a normalised copy in
 * which the implicit separators are made explicit.</p>
 *
 * @author vyrriox
 */
public final class SnbtCompat {

    private SnbtCompat() {
    }

    /**
     * Parses SNBT text, tolerating FTB Library's dialect.
     *
     * @throws CommandSyntaxException if the text is unparseable even after normalisation. The
     *                                exception carries the normalised parse failure, which is the
     *                                more informative of the two.
     */
    public static CompoundTag parse(String content) throws CommandSyntaxException {
        try {
            return TagParser.parseTag(content);
        } catch (CommandSyntaxException strict) {
            return TagParser.parseTag(normalize(content));
        }
    }

    /** {@link #parse(String)} that returns {@code null} instead of throwing. */
    @Nullable
    public static CompoundTag parseOrNull(String content) {
        try {
            return parse(content);
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Rewrites FTB-flavoured SNBT into the vanilla-compliant form: inserts the element separators
     * FTB leaves implicit at line breaks, strips {@code #} and {@code //} line comments, and drops
     * trailing commas. Quoted strings are copied verbatim, so a {@code #} or a line break inside a
     * value is never mistaken for syntax.
     *
     * <p>A separator is inserted only when a line break sits between two significant characters that
     * can legally be separated — never after an opener ({@code &#123;}, {@code [}, {@code :},
     * {@code ;}, {@code ,}) and never before a closer ({@code &#125;}, {@code ]}). Text that already
     * carries its commas is returned unchanged.</p>
     */
    static String normalize(String in) {
        StringBuilder out = new StringBuilder(in.length() + 64);
        final int len = in.length();
        char lastSignificant = 0;
        boolean lineBreakPending = false;

        for (int i = 0; i < len; i++) {
            char c = in.charAt(i);

            // Line comments. Neither '#' nor '/' is allowed in an unquoted SNBT token, so outside a
            // string they can only start a comment.
            if (c == '#' || (c == '/' && i + 1 < len && in.charAt(i + 1) == '/')) {
                while (i < len && in.charAt(i) != '\n') i++;
                i--; // hand the newline back to the loop so it still counts as a line break
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (c == '\n' || c == '\r') lineBreakPending = true;
                out.append(c);
                continue;
            }

            // Trailing comma before a closer — vanilla rejects it.
            if (c == ',') {
                char next = nextSignificant(in, i + 1);
                if (next == '}' || next == ']') continue;
            }

            if (lineBreakPending && lastSignificant != 0 && needsSeparator(lastSignificant, c)) {
                out.append(',');
            }
            out.append(c);
            lastSignificant = c;
            lineBreakPending = false;

            // Copy a quoted string verbatim (both quote styles vanilla accepts).
            if (c == '"' || c == '\'') {
                boolean escaped = false;
                while (++i < len) {
                    char s = in.charAt(i);
                    out.append(s);
                    if (escaped) escaped = false;
                    else if (s == '\\') escaped = true;
                    else if (s == c) break;
                }
                lastSignificant = c; // the closing quote ends a value
            }
        }
        return out.toString();
    }

    /** True when {@code prev} and {@code next} are two elements that FTB separated with a newline. */
    private static boolean needsSeparator(char prev, char next) {
        if (next == '}' || next == ']' || next == ',') return false;
        return prev != '{' && prev != '[' && prev != ',' && prev != ':' && prev != ';';
    }

    /** Next non-whitespace character from {@code from}, or {@code 0} at end of input. */
    private static char nextSignificant(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) return c;
        }
        return 0;
    }
}
