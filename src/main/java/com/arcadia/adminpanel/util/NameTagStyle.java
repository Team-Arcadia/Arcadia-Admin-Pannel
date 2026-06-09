package com.arcadia.adminpanel.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable description of how a player's name should be rendered: a list of base colours
 * (one = solid, two+ = gradient stops), a set of text-decoration flags, an animated effect mode,
 * and an animation speed. Consumed by {@link NameTagEffect} (the renderer) and persisted by
 * {@link NameTagManager}.
 *
 * <p>Designed to be cheap to (de)serialise three ways: Gson (disk, via public fields on the
 * builder-friendly {@code Mutable} mirror is avoided — the record fields are Gson-friendly already),
 * and {@link FriendlyByteBuf} for the network sync. The colour list holds packed 0xRRGGBB ints so a
 * true 24-bit RGB name is representable; named {@link ChatFormatting} colours are stored as their
 * RGB value too (see {@link #rgbOf}).</p>
 *
 * <p>{@code DEFAULT} ({@code null} effect data via {@link #none()}) means "no styling" — the
 * renderer leaves the vanilla name untouched. This keeps the common case (almost every player)
 * free of per-frame work.</p>
 *
 * @author vyrriox
 */
public record NameTagStyle(
        List<Integer> colors,   // packed 0xRRGGBB; size 1 = solid, 2+ = gradient stops
        boolean bold,
        boolean italic,
        boolean underline,
        boolean strikethrough,
        boolean obfuscated,
        NameTagEffect effect,
        int speed               // animation speed 1..10 (higher = faster); ignored for static effects
) {

    /** Hard cap on gradient stops to bound packet size and per-char work. */
    public static final int MAX_COLORS = 8;
    public static final int MIN_SPEED = 1;
    public static final int MAX_SPEED = 10;
    public static final int DEFAULT_SPEED = 5;

    /** A style that means "render the vanilla name unchanged". */
    private static final NameTagStyle NONE =
            new NameTagStyle(List.of(0xFFFFFF), false, false, false, false, false, NameTagEffect.NONE, DEFAULT_SPEED);

    public static NameTagStyle none() { return NONE; }

    /** True when this style would leave the name visually identical to vanilla (skip the work). */
    public boolean isNoOp() {
        return effect == NameTagEffect.NONE
                && !bold && !italic && !underline && !strikethrough && !obfuscated;
    }

    // ── Builders ────────────────────────────────────────────────────────────

    /** Solid single colour, no decorations, no animation. */
    public static NameTagStyle solid(int rgb) {
        return new NameTagStyle(List.of(rgb & 0xFFFFFF),
                false, false, false, false, false, NameTagEffect.SOLID, DEFAULT_SPEED);
    }

    /** Returns a copy with the given colour list and effect, preserving decoration flags + speed. */
    public NameTagStyle withColorsAndEffect(List<Integer> newColors, NameTagEffect newEffect) {
        List<Integer> capped = clampColors(newColors);
        return new NameTagStyle(capped, bold, italic, underline, strikethrough, obfuscated, newEffect, speed);
    }

    public NameTagStyle withEffect(NameTagEffect newEffect) {
        return new NameTagStyle(colors, bold, italic, underline, strikethrough, obfuscated, newEffect,
                clampSpeed(speed));
    }

    public NameTagStyle withSpeed(int newSpeed) {
        return new NameTagStyle(colors, bold, italic, underline, strikethrough, obfuscated, effect,
                clampSpeed(newSpeed));
    }

    /** Toggles a single decoration flag, returning a new style. */
    public NameTagStyle withFlag(String flag, boolean on) {
        return switch (flag) {
            case "bold"          -> new NameTagStyle(colors, on, italic, underline, strikethrough, obfuscated, effect, speed);
            case "italic"        -> new NameTagStyle(colors, bold, on, underline, strikethrough, obfuscated, effect, speed);
            case "underline"     -> new NameTagStyle(colors, bold, italic, on, strikethrough, obfuscated, effect, speed);
            case "strikethrough" -> new NameTagStyle(colors, bold, italic, underline, on, obfuscated, effect, speed);
            case "obfuscated"    -> new NameTagStyle(colors, bold, italic, underline, strikethrough, on, effect, speed);
            default              -> this;
        };
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    /** Defensive copy + clamp on read-back from disk (Gson can produce nulls / oversize lists). */
    public NameTagStyle normalised() {
        List<Integer> c = clampColors(colors);
        NameTagEffect e = effect == null ? NameTagEffect.NONE : effect;
        return new NameTagStyle(c, bold, italic, underline, strikethrough, obfuscated, e, clampSpeed(speed));
    }

    private static List<Integer> clampColors(List<Integer> in) {
        if (in == null || in.isEmpty()) return List.of(0xFFFFFF);
        List<Integer> out = new ArrayList<>(Math.min(in.size(), MAX_COLORS));
        for (int i = 0; i < in.size() && out.size() < MAX_COLORS; i++) {
            Integer v = in.get(i);
            if (v != null) out.add(v & 0xFFFFFF);
        }
        return out.isEmpty() ? List.of(0xFFFFFF) : List.copyOf(out);
    }

    public static int clampSpeed(int s) { return Math.max(MIN_SPEED, Math.min(MAX_SPEED, s)); }

    /** Packs a named {@link ChatFormatting} colour to its 0xRRGGBB value (white if not a colour). */
    public static int rgbOf(ChatFormatting cf) {
        Integer v = cf.getColor();
        return v != null ? (v & 0xFFFFFF) : 0xFFFFFF;
    }

    // ── Network (de)serialisation ──────────────────────────────────────────────

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(colors.size());
        for (int c : colors) buf.writeInt(c);
        // Decoration flags packed into one byte.
        int flags = (bold ? 1 : 0) | (italic ? 2 : 0) | (underline ? 4 : 0)
                | (strikethrough ? 8 : 0) | (obfuscated ? 16 : 0);
        buf.writeByte(flags);
        buf.writeVarInt(effect.ordinal());
        buf.writeByte(clampSpeed(speed));
    }

    public static NameTagStyle read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_COLORS);
        List<Integer> colors = new ArrayList<>(Math.max(1, n));
        for (int i = 0; i < n; i++) colors.add(buf.readInt() & 0xFFFFFF);
        if (colors.isEmpty()) colors.add(0xFFFFFF);
        int flags = buf.readByte() & 0xFF;
        int effOrdinal = buf.readVarInt();
        NameTagEffect[] all = NameTagEffect.values();
        NameTagEffect eff = (effOrdinal >= 0 && effOrdinal < all.length) ? all[effOrdinal] : NameTagEffect.NONE;
        int speed = clampSpeed(buf.readByte());
        return new NameTagStyle(List.copyOf(colors),
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                (flags & 8) != 0, (flags & 16) != 0, eff, speed);
    }
}
