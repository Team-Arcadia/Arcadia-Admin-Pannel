package com.arcadia.adminpanel.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Date/time formatting helpers — bilingual EN/FR, suitable for GUI lore lines.
 *
 * @author vyrriox
 */
public final class TimeFormat {

    private static final ThreadLocal<SimpleDateFormat> ABS_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT));

    private TimeFormat() {}

    /** Absolute date/time stamp, e.g. "18/05/2026 14:32". */
    public static String absolute(long epochMs) {
        if (epochMs <= 0L) return "—";
        return ABS_FORMAT.get().format(new Date(epochMs));
    }

    /**
     * Relative duration since {@code epochMs}, e.g. "5m ago" / "il y a 5min".
     * Returns {@code "—"} for zero/negative timestamps.
     */
    public static String relative(long epochMs, boolean french) {
        if (epochMs <= 0L) return "—";
        long delta = System.currentTimeMillis() - epochMs;
        if (delta < 0) delta = 0;
        long secs = delta / 1000L;
        long mins = secs / 60L;
        long hours = mins / 60L;
        long days = hours / 24L;

        if (french) {
            if (days >= 1) return "il y a " + days + (days == 1 ? " jour" : " jours");
            if (hours >= 1) return "il y a " + hours + "h";
            if (mins >= 1) return "il y a " + mins + " min";
            return "à l'instant";
        }
        if (days >= 1) return days + (days == 1 ? " day ago" : " days ago");
        if (hours >= 1) return hours + "h ago";
        if (mins >= 1) return mins + " min ago";
        return "just now";
    }
}
