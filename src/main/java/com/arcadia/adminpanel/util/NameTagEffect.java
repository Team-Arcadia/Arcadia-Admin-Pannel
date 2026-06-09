package com.arcadia.adminpanel.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * The full catalogue of name-tag effects plus the renderer that turns a raw player name + a
 * {@link NameTagStyle} + an animation clock into a styled {@link Component}.
 *
 * <p>This is the single source of truth shared by both sides:</p>
 * <ul>
 *   <li><b>Client</b> ({@code NameTagRenderer}) calls {@link #render} every frame with the client
 *       animation tick + partial-tick, producing smooth rainbow / breathing / chase motion on the
 *       floating tag via {@code RenderNameTagEvent.setContent}.</li>
 *   <li><b>Server</b> ({@code NameTagManager}) may call {@link #render} with a static tick to format
 *       the same name into chat / tab-list / scoreboard contexts when desired.</li>
 * </ul>
 *
 * <p>Gradient and animated effects are built one sibling {@link Component} per character — vanilla
 * has no native gradient, so per-glyph colouring is the only way. Decoration flags (bold, italic,
 * …) from the style are applied to every glyph. The work is O(name length) per frame, which is
 * trivial (player names are ≤ 16 chars) and only runs for players that actually have a non-no-op
 * style.</p>
 *
 * @author vyrriox
 */
public enum NameTagEffect {

    /** No effect — caller should use the vanilla name. Present so a style can be cleared cleanly. */
    NONE("none"),
    /** Single flat colour (first colour in the list). */
    SOLID("solid"),
    /** Static left-to-right gradient across the configured colour stops. */
    GRADIENT("gradient"),
    /** Animated full-spectrum hue cycle scrolling along the name. */
    RAINBOW("rainbow"),
    /** Animated brightness pulse (sine on HSB value) of the first colour — "breathing". */
    BREATHING("breathing"),
    /** A bright highlight band that sweeps across an otherwise base-coloured name. */
    CHASE("chase"),
    /** Hue oscillates back and forth between the gradient stops like a wave. */
    WAVE("wave"),
    /** Whole name fades its alpha-ish brightness on/off (hard blink). */
    BLINK("blink"),
    /** Smooth fade of brightness between the first two colours (or first colour ↔ dim). */
    FADE("fade"),
    /** Characters are revealed left-to-right then reset, like a typewriter. */
    TYPEWRITER("typewriter"),
    /** Each glyph gets an independently flickering colour from the palette — chaotic glitch look. */
    RANDOM("random");

    private final String id;
    NameTagEffect(String id) { this.id = id; }

    public String id() { return id; }

    /** Parse a command argument to an effect (case-insensitive), or {@code null} if unknown. */
    public static NameTagEffect fromId(String s) {
        if (s == null) return null;
        for (NameTagEffect e : values()) if (e.id.equalsIgnoreCase(s)) return e;
        return null;
    }

    /** All effect ids except NONE (NONE is exposed through the dedicated "reset" command). */
    public static String[] selectableIds() {
        NameTagEffect[] all = values();
        String[] out = new String[all.length - 1];
        int j = 0;
        for (NameTagEffect e : all) if (e != NONE) out[j++] = e.id;
        return out;
    }

    // ── Renderer ──────────────────────────────────────────────────────────────

    /**
     * Builds the styled name. {@code animTick} is a monotonically-increasing clock (client ticks,
     * or 0 for a static render) and {@code partial} is the inter-tick fraction [0,1) for smoothness.
     *
     * @param name     the raw player name (already plain text, no formatting codes)
     * @param style    the player's style; if {@code null}/no-op the raw name is returned as-is
     * @param animTick animation clock in ticks
     * @param partial  partial-tick fraction for smooth interpolation
     */
    public static Component render(String name, NameTagStyle style, float animTick, float partial) {
        if (name == null || name.isEmpty()) return Component.literal(name == null ? "" : name);
        if (style == null || style.isNoOp()) return Component.literal(name);

        NameTagEffect effect = style.effect();
        List<Integer> colors = style.colors();
        // Higher speed = faster motion. Map 1..10 to a per-tick phase increment.
        float t = (animTick + partial) * (0.02f + style.speed() * 0.012f);

        return switch (effect) {
            case NONE     -> applyFlags(Component.literal(name), style); // decorations only
            case SOLID    -> applyFlags(Component.literal(name), style)
                                  .withStyle(s -> s.withColor(TextColor.fromRgb(colors.get(0))));
            case GRADIENT -> perGlyph(name, style, (i, n) -> gradientColor(colors, frac(i, n)));
            case RAINBOW  -> perGlyph(name, style, (i, n) -> {
                                  float hue = (t + i * 0.08f) % 1.0f;
                                  return hsb(hue, 1.0f, 1.0f);
                              });
            case BREATHING-> {
                                  float v = 0.45f + 0.55f * (0.5f + 0.5f * Mth.sin(t * 3.0f));
                                  int base = colors.get(0);
                                  int col = scaleBrightness(base, v);
                                  yield applyFlags(Component.literal(name), style)
                                          .withStyle(s -> s.withColor(TextColor.fromRgb(col)));
                              }
            case CHASE    -> {
                                  // Sweep a highlight position across the glyphs.
                                  float head = (t * 4.0f) % 1.0f;
                                  int base = colors.get(0);
                                  int hi = colors.size() > 1 ? colors.get(1) : 0xFFFFFF;
                                  yield perGlyph(name, style, (i, n) -> {
                                      float pos = frac(i, n);
                                      float d = Math.abs(pos - head);
                                      d = Math.min(d, 1.0f - d); // wrap-around distance
                                      float blend = Math.max(0f, 1.0f - d * 6.0f);
                                      return lerpColor(base, hi, blend);
                                  });
                              }
            case WAVE     -> perGlyph(name, style, (i, n) -> {
                                  float w = 0.5f + 0.5f * Mth.sin(t * 3.0f + i * 0.6f);
                                  return gradientColor(colors, w);
                              });
            case BLINK    -> {
                                  boolean on = ((int) (t * 4.0f)) % 2 == 0;
                                  int base = colors.get(0);
                                  int col = on ? base : scaleBrightness(base, 0.25f);
                                  yield applyFlags(Component.literal(name), style)
                                          .withStyle(s -> s.withColor(TextColor.fromRgb(col)));
                              }
            case FADE     -> {
                                  float f = 0.5f + 0.5f * Mth.sin(t * 2.0f);
                                  int a = colors.get(0);
                                  int b = colors.size() > 1 ? colors.get(1) : scaleBrightness(a, 0.2f);
                                  int col = lerpColor(a, b, f);
                                  yield applyFlags(Component.literal(name), style)
                                          .withStyle(s -> s.withColor(TextColor.fromRgb(col)));
                              }
            case TYPEWRITER -> {
                                  int n = name.length();
                                  int reveal = (int) ((t * 6.0f) % (n + 4)); // +4 = brief "full" pause
                                  int shown = Math.min(n, reveal);
                                  MutableComponent out = Component.empty();
                                  int baseCol = colors.get(0);
                                  for (int i = 0; i < n; i++) {
                                      char c = name.charAt(i);
                                      boolean visible = i < shown;
                                      MutableComponent g = Component.literal(String.valueOf(c));
                                      int col = visible ? gradientColor(colors, frac(i, n)) : scaleBrightness(baseCol, 0.12f);
                                      out.append(decorate(g, style).withStyle(s -> s.withColor(TextColor.fromRgb(col))));
                                  }
                                  yield out;
                              }
            case RANDOM   -> perGlyph(name, style, (i, n) -> {
                                  // Deterministic per-(glyph,frame) pseudo-random pick from the palette,
                                  // advancing a few frames at a time so it flickers rather than strobes.
                                  int frame = (int) (t * 5.0f);
                                  int h = (i * 73856093) ^ (frame * 19349663);
                                  int idx = Math.floorMod(h, colors.size());
                                  return colors.get(idx);
                              });
        };
    }

    // ── Per-glyph helper ────────────────────────────────────────────────────

    @FunctionalInterface
    private interface GlyphColor { int colorAt(int index, int total); }

    private static MutableComponent perGlyph(String name, NameTagStyle style, GlyphColor fn) {
        MutableComponent out = Component.empty();
        int n = name.length();
        for (int i = 0; i < n; i++) {
            int col = fn.colorAt(i, n) & 0xFFFFFF;
            MutableComponent g = Component.literal(String.valueOf(name.charAt(i)));
            out.append(decorate(g, style).withStyle(s -> s.withColor(TextColor.fromRgb(col))));
        }
        return out;
    }

    /** Applies decoration flags to a fresh per-glyph component (no colour — caller sets that). */
    private static MutableComponent decorate(MutableComponent c, NameTagStyle style) {
        return c.withStyle(s -> s
                .withBold(style.bold())
                .withItalic(style.italic())
                .withUnderlined(style.underline())
                .withStrikethrough(style.strikethrough())
                .withObfuscated(style.obfuscated()));
    }

    /** Applies decoration flags to a whole-name component (used by single-colour effects). */
    private static MutableComponent applyFlags(MutableComponent c, NameTagStyle style) {
        Style s = Style.EMPTY
                .withBold(style.bold())
                .withItalic(style.italic())
                .withUnderlined(style.underline())
                .withStrikethrough(style.strikethrough())
                .withObfuscated(style.obfuscated());
        return c.withStyle(s);
    }

    // ── Colour maths ──────────────────────────────────────────────────────────

    /** Fraction [0,1] for glyph i of n (so the last glyph hits exactly 1.0). */
    private static float frac(int i, int n) { return n <= 1 ? 0f : (float) i / (float) (n - 1); }

    /** Samples a multi-stop gradient at position f in [0,1]. */
    private static int gradientColor(List<Integer> stops, float f) {
        if (stops.size() == 1) return stops.get(0);
        f = Mth.clamp(f, 0f, 1f);
        float scaled = f * (stops.size() - 1);
        int idx = (int) Math.floor(scaled);
        if (idx >= stops.size() - 1) return stops.get(stops.size() - 1);
        float local = scaled - idx;
        return lerpColor(stops.get(idx), stops.get(idx + 1), local);
    }

    /** Linear interpolation in RGB space between two packed colours. */
    private static int lerpColor(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Scales a colour's brightness by factor v in [0,1]. */
    private static int scaleBrightness(int rgb, float v) {
        v = Mth.clamp(v, 0f, 1f);
        int r = Math.round(((rgb >> 16) & 0xFF) * v);
        int g = Math.round(((rgb >> 8) & 0xFF) * v);
        int b = Math.round((rgb & 0xFF) * v);
        return (r << 16) | (g << 8) | b;
    }

    /** HSB → packed RGB without allocating a java.awt.Color. */
    private static int hsb(float h, float s, float b) {
        h = h - (float) Math.floor(h);
        float r = 0, g = 0, bl = 0;
        int i = (int) (h * 6.0f);
        float f = h * 6.0f - i;
        float p = b * (1 - s);
        float q = b * (1 - f * s);
        float t = b * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = b;  g = t;  bl = p; }
            case 1 -> { r = q;  g = b;  bl = p; }
            case 2 -> { r = p;  g = b;  bl = t; }
            case 3 -> { r = p;  g = q;  bl = b; }
            case 4 -> { r = t;  g = p;  bl = b; }
            case 5 -> { r = b;  g = p;  bl = q; }
        }
        int ri = Math.round(r * 255), gi = Math.round(g * 255), bi = Math.round(bl * 255);
        return (ri << 16) | (gi << 8) | bi;
    }
}
