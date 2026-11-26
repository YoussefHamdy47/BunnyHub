package org.bunnys.bunnynexus.ratings;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Star ratings drawn with the bot's own star emojis, to the nearest quarter star: 4.3 stars shows four full stars and a
 * quarter star. The emojis are application emojis the bot uploads itself ({@link StarEmojis}); until they are known
 * (the first seconds after a start, or if the upload failed) {@link #of} gives an empty string and callers show the
 * numbers alone. Pure apart from that one map.
 */
public final class Stars {
    /** Emoji names by quarters filled: empty, a quarter, half, three quarters, full. */
    public static final List<String> NAMES = List.of("star_empty", "star_quarter", "star_half", "star_three_quarter", "star_full");
    private static volatile Map<String, String> mentions = Map.of();

    private Stars() {}

    /** Called once the emojis exist: name to {@code <:name:id>}. */
    public static void use(Map<String, String> byName) {
        if (byName.isEmpty()) mentions = Map.of(); // Back to numbers only.
        else if (byName.keySet().containsAll(NAMES)) mentions = Map.copyOf(byName);
    }

    public static boolean ready() { return !mentions.isEmpty(); }

    /** Quarters filled per star, left to right, for {@code stars} out of 5 rounded to the nearest quarter. */
    static int[] quarters(double stars) {
        int total = (int) Math.round(Math.clamp(stars, 0, 5) * 4);
        int[] each = new int[5];
        for (int i = 0; i < 5; i++) each[i] = Math.clamp(total - i * 4, 0, 4);
        return each;
    }

    /** Five star emojis for {@code stars} out of 5, or "" while the emojis are not available. */
    public static String of(double stars) {
        var known = mentions;
        if (known.isEmpty()) return "";
        var text = new StringBuilder();
        for (int q : quarters(stars)) text.append(known.get(NAMES.get(q)));
        return text.toString();
    }

    /** Steam's percent positive on the same five-star scale: 96% is 4.8 stars. */
    public static double fromPercent(int percent) { return percent / 20.0; }

    /** For tests: pretend the emojis were uploaded with these IDs. */
    public static Map<String, String> fake() {
        Map<String, String> byName = new HashMap<>();
        for (int i = 0; i < NAMES.size(); i++) byName.put(NAMES.get(i), "<:" + NAMES.get(i) + ":" + (100000000000000000L + i) + ">");
        return byName;
    }

    /** 950, 1.2K, 12K, 120K, 1.3M: short enough for a rating line. */
    public static String count(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 10_000) return trim(n / 100 / 10.0) + "K";
        if (n < 1_000_000) return n / 1000 + "K";
        return trim(n / 100_000 / 10.0) + "M";
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
