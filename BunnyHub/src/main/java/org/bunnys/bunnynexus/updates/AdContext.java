package org.bunnys.bunnynexus.updates;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * What a live ad may talk about at the moment it is shown ({@link AdTemplate}): a deal worth mentioning, another
 * game free right now that this channel does not get, the most valuable game in this very alert, and how much was
 * given away in the last 30 days. Built per alert, only for a server that is due an ad.
 */
public record AdContext(Optional<Deal> deal, Optional<Free> free, Optional<Game> game, int monthCount, long monthWorthCents,
                        Optional<String> invite, String server, String vote) {
    /** Fewer free games than this in a month is not worth bragging about. */
    static final int MONTH_MIN = 3;

    /** A deal: its store's emoji and name, prices as shown ("$14.99"), and the sale end when the store gives one. */
    public record Deal(String title, String url, String store, String emoji, String price, String was, int percentOff,
                       Optional<Instant> endsAt) {}

    public record Free(String title, String url, String store, String emoji, Optional<String> worth, Optional<Instant> endsAt) {}

    /** The alert's own most valuable game, and what it is worth ("$29.99"). */
    public record Game(String title, String url, String worth, long worthCents) {}

    public static final AdContext NONE = new AdContext(Optional.empty(), Optional.empty(), Optional.empty(), 0, 0,
            Optional.empty(), "https://discord.gg/gGsdHYhTFv", "https://top.gg/bot/795361755223556116/vote");

    static String dollars(long cents) {
        return cents % 100 == 0 ? String.format(Locale.ROOT, "$%,d", cents / 100) : String.format(Locale.ROOT, "$%,d.%02d", cents / 100, cents % 100);
    }

    /** "$19.99" or "19.99" → 1999; anything else → empty. */
    static Optional<Long> cents(String worth) {
        if (worth == null) return Optional.empty();
        var m = java.util.regex.Pattern.compile("\\$?([0-9]{1,5})(?:\\.([0-9]{2}))?").matcher(worth.strip());
        if (!m.matches()) return Optional.empty();
        return Optional.of(Long.parseLong(m.group(1)) * 100 + (m.group(2) == null ? 0 : Long.parseLong(m.group(2))));
    }
}
