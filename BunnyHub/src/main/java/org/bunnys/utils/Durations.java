package org.bunnys.utils;

import java.util.ArrayList;
import java.util.List;

/** Human-readable durations such as {@code 1 hour 30 minutes}. */
public final class Durations {
    private Durations() {}

    private enum Unit {
        YEAR(31_557_600_000L, "year"),
        DAY(86_400_000L, "day"),
        HOUR(3_600_000L, "hour"),
        MINUTE(60_000L, "minute"),
        SECOND(1_000L, "second");

        final long millis;
        final String name;
        Unit(long millis, String name) { this.millis = millis; this.name = name; }
    }

    /** Every non-zero unit, largest first; {@code 0s} for anything under one second (including negatives). */
    public static String format(long millis) {
        List<String> parts = new ArrayList<>();
        long remaining = millis;
        for (Unit unit : Unit.values()) {
            long count = remaining / unit.millis;
            if (count < 1) continue;
            parts.add(count + " " + unit.name + (count == 1 ? "" : "s"));
            remaining -= count * unit.millis;
        }
        return parts.isEmpty() ? "0s" : String.join(" ", parts);
    }

    public static String formatSeconds(double seconds) {
        return format((long) (seconds * 1000));
    }
}
