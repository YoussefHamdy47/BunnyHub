package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pure cross-source matching rules. A verified storefront identity (the same normalized Epic product page) plus an
 * overlapping promotion window may join a report to an existing giveaway automatically. Titles alone never join:
 * related titles with close end dates only reserve an owner question, so the second report cannot open a review.
 */
final class FreebieIdentity {
    private FreebieIdentity() {}

    /** GamerPower's dates are approximate (no timezone, often end-of-day); Epic's are exact. */
    static final Duration END_TOLERANCE = Duration.ofHours(36);
    private static final Duration GAMERPOWER_GRACE = Duration.ofHours(2);
    private static final Pattern EDITION = Pattern.compile(
            "\\b(deluxe|gold|ultimate|complete|definitive|premium|collector'?s|goty|game of the year|digital|enhanced|"
                    + "remastered|director'?s cut|bundle|pack|season pass|dlc|expansion|edition|soundtrack|demo)\\b");
    private static final Pattern SUFFIX = Pattern.compile("(?i)\\s*\\((?:epic games(?: store)?|steam|gog|android|ios|mobile)\\)$");

    static String normalized(String raw) {
        String title = FreebieMessages.title(raw == null ? "" : raw);
        title = SUFFIX.matcher(title).replaceFirst("").strip();
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Edition, bundle and add-on words present in one title but not the other: a different entitlement may be meant. */
    static boolean editionConflict(String a, String b) { return !editionWords(normalized(a)).equals(editionWords(normalized(b))); }

    private static Set<String> editionWords(String title) {
        Set<String> words = new TreeSet<>();
        var matcher = EDITION.matcher(title);
        while (matcher.find()) words.add(matcher.group(1));
        return words;
    }

    /** Equal, or one is the other followed by a subtitle ("Home" / "Home - A Unique Horror Adventure"). */
    static boolean titlesRelated(String a, String b) {
        String x = normalized(a), y = normalized(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String shorter = x.length() < y.length() ? x : y, longer = x.length() < y.length() ? y : x;
        if (!longer.startsWith(shorter)) return false;
        String rest = longer.substring(shorter.length());
        return rest.startsWith(" - ") || rest.startsWith(": ") || rest.startsWith(" – ") || rest.startsWith(" — ");
    }

    /** The end used for comparison: the official one when recorded, else the provider's (GamerPower grace removed). */
    static Optional<Instant> comparableEnd(Document d) {
        Document official = d.get("official", Document.class);
        if (official != null && official.getDate("endsAt") != null) return Optional.of(official.getDate("endsAt").toInstant());
        Date end = d.getDate("endsAt");
        if (end == null) return Optional.empty();
        Instant instant = end.toInstant();
        return Optional.of("gamerpower".equals(d.getString("source")) ? instant.minus(GAMERPOWER_GRACE) : instant);
    }

    static Optional<Instant> comparableEnd(FreebieFeed.Candidate c) {
        return c.endsAt().map(end -> c.provider() == FreebieFeed.Provider.GAMERPOWER ? end.minus(GAMERPOWER_GRACE) : end);
    }

    static Optional<Instant> start(Document d) {
        Document official = d.get("official", Document.class);
        Date start = official != null && official.getDate("startsAt") != null ? official.getDate("startsAt") : d.getDate("startsAt");
        return Optional.ofNullable(start).map(Date::toInstant);
    }

    /**
     * Whether a report can describe the same promotion occurrence as a saved giveaway. Exact windows must overlap;
     * approximate end dates must be within {@link #END_TOLERANCE}. Without an end date the saved one must be live now.
     * A repeat giveaway of the same product (a later window) never matches an earlier occurrence.
     */
    static boolean sameOccurrence(Document existing, FreebieFeed.Candidate c, Instant now) {
        var existingEnd = comparableEnd(existing);
        var candidateEnd = comparableEnd(c);
        var existingStart = start(existing);
        if (existingStart.isPresent() && c.startsAt().isPresent() && existingEnd.isPresent() && candidateEnd.isPresent()) {
            boolean overlap = existingStart.get().isBefore(candidateEnd.get()) && c.startsAt().get().isBefore(existingEnd.get());
            if (!overlap) return false;
        }
        if (existingEnd.isPresent() && candidateEnd.isPresent())
            return Duration.between(existingEnd.get(), candidateEnd.get()).abs().compareTo(END_TOLERANCE) <= 0;
        if (existingEnd.isPresent()) return now.isBefore(existingEnd.get()) && existingStart.map(s -> !now.isBefore(s)).orElse(true);
        return false;
    }

    /** The saved giveaway whose end is closest to the report's, for an owner question. */
    static Optional<Document> closest(List<Document> rows, FreebieFeed.Candidate c) {
        Instant target = comparableEnd(c).orElse(Instant.EPOCH);
        return rows.stream().min(Comparator.comparing((Document d) -> comparableEnd(d)
                .map(end -> Duration.between(end, target).abs()).orElse(Duration.ofDays(36500))).thenComparing(d -> d.getString("_id")));
    }
}
