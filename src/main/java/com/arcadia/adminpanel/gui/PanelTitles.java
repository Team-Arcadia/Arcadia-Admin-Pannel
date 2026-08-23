package com.arcadia.adminpanel.gui;

import net.minecraft.network.chat.Component;

/**
 * Marks a container title as belonging to the admin panel, so the client can theme it.
 *
 * <p>The client swaps a vanilla chest screen for the themed one by looking at the window title.
 * Before 1.3.0 that meant one hard-coded string per menu per language, which does not scale to a
 * dozen new screens and breaks the moment a translation is edited.</p>
 *
 * <p>Instead every 1.3.0 menu ends its title with a bare {@code §r}. The font renderer consumes
 * formatting codes, so nothing is drawn and no glyph box appears; the raw string still carries the
 * marker, so the client can recognise a panel screen in any language with one check and no table to
 * keep in sync.</p>
 *
 * @author vyrriox
 */
public final class PanelTitles {

    /** Invisible suffix identifying a panel screen. A reset code renders as nothing at all. */
    public static final String MARKER = "§r";

    private PanelTitles() {}

    /** Wraps a plain title string. */
    public static Component of(String title) {
        return Component.literal(title + MARKER);
    }

    /** True when a window title was produced by {@link #of}. */
    public static boolean isPanel(String rawTitle) {
        return rawTitle != null && rawTitle.endsWith(MARKER);
    }
}
