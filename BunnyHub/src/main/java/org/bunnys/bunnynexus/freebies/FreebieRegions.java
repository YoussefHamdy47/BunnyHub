package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Country restrictions for one exact offer. Pure: no I/O.
 *
 * <p>Only explicit allow/deny lists from exact-offer evidence can block a country. Missing, null, malformed or stale
 * evidence, failed requests and unknown countries stay {@link Status#UNKNOWN}: never an invented ban. An empty list
 * means no exclusions were recorded in that field, not worldwide availability.
 */
final class FreebieRegions {
    private FreebieRegions() {}

    enum Status { CONFIRMED_FREE, BLOCKED, UNKNOWN, CONFLICT, STALE }
    /** Restrictions older than this are history, not current confirmation. */
    static final Duration MAX_AGE = Duration.ofHours(6);
    private static final Set<String> ISO = Set.of(Locale.getISOCountries());

    /**
     * Country lists observed for one offer. {@code blacklist}/{@code whitelist} are empty when the field was absent or
     * null; {@code malformed} when it held anything other than a list of ISO 3166 alpha-2 codes.
     */
    record Evidence(String provider, Instant observedAt, Optional<List<String>> blacklist,
                    Optional<List<String>> whitelist, boolean malformed) {
        Evidence {
            Objects.requireNonNull(provider); Objects.requireNonNull(observedAt);
            blacklist = blacklist.map(List::copyOf); whitelist = whitelist.map(List::copyOf);
        }
        boolean fresh(Instant now) { return !malformed && Duration.between(observedAt, now).compareTo(MAX_AGE) <= 0; }

        Document toDocument() {
            return new Document("provider", provider).append("observedAt", Date.from(observedAt))
                    .append("blacklistPresent", blacklist.isPresent()).append("blacklist", blacklist.orElse(null))
                    .append("whitelistPresent", whitelist.isPresent()).append("whitelist", whitelist.orElse(null))
                    .append("malformed", malformed);
        }

        static Optional<Evidence> read(Document d) {
            if (d == null || d.getString("provider") == null || d.getDate("observedAt") == null) return Optional.empty();
            try {
                return Optional.of(new Evidence(d.getString("provider"), d.getDate("observedAt").toInstant(),
                        d.getBoolean("blacklistPresent", false) ? Optional.of(codes(d.get("blacklist"))) : Optional.empty(),
                        d.getBoolean("whitelistPresent", false) ? Optional.of(codes(d.get("whitelist"))) : Optional.empty(),
                        d.getBoolean("malformed", false)));
            } catch (IllegalArgumentException invalid) { return Optional.empty(); }
        }
    }

    /**
     * A provider field: absent or null is "not stated" (empty), a list of ISO codes is the list. Anything else
     * (a string, numbers, platform tokens such as {@code IOS}) throws.
     */
    static Optional<List<String>> field(Document row, String name) {
        if (!row.containsKey(name) || row.get(name) == null) return Optional.empty();
        return Optional.of(codes(row.get(name)));
    }

    static List<String> codes(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() > 300) throw new IllegalArgumentException("not a country list");
        var codes = new TreeSet<String>();
        for (Object value : list)
            if (!(value instanceof String code) || !ISO.contains(code)) throw new IllegalArgumentException("not an ISO country code");
            else codes.add(code);
        return List.copyOf(codes);
    }

    /** Parses both lists of a provider row; a bad value makes the evidence malformed (unknown), never a ban. */
    static Evidence observe(String provider, Instant now, Document row) {
        try { return new Evidence(provider, now, field(row, "countriesBlacklist"), field(row, "countriesWhitelist"), false); }
        catch (IllegalArgumentException invalid) { return new Evidence(provider, now, Optional.empty(), Optional.empty(), true); }
    }

    /**
     * One country under one provider's evidence. {@code checkedFree}: this country's storefront was observed with the
     * active zero-price promotion (today only US, by the Epic promotions feed).
     */
    static Status status(Optional<Evidence> evidence, String country, boolean checkedFree, Instant now) {
        if (evidence.isPresent() && !evidence.get().malformed() && !evidence.get().fresh(now)) return Status.STALE;
        var rules = evidence.filter(e -> e.fresh(now));
        boolean blocked = rules.map(e -> e.blacklist().map(l -> l.contains(country)).orElse(false)
                || e.whitelist().map(l -> !l.isEmpty() && !l.contains(country)).orElse(false)).orElse(false);
        if (blocked && checkedFree) return Status.CONFLICT;
        if (blocked) return Status.BLOCKED;
        return checkedFree ? Status.CONFIRMED_FREE : Status.UNKNOWN;
    }

    /** Countries the fresh evidence explicitly blocks (blacklist wins); empty when nothing is known. */
    static List<String> blocked(Optional<Evidence> evidence, Instant now) {
        return evidence.filter(e -> e.fresh(now)).flatMap(Evidence::blacklist).orElse(List.of());
    }

    /**
     * Card text. Names only countries the evidence names; "not fully verified" whenever coverage is incomplete. The
     * Epic feed checks the US storefront only.
     */
    static String describe(Optional<Evidence> evidence, boolean usChecked, Instant now) {
        var lines = new ArrayList<String>();
        var fresh = evidence.filter(e -> e.fresh(now));
        var blocked = blocked(evidence, now);
        if (!blocked.isEmpty()) lines.add("Not claimable in: " + names(blocked) + ".");
        fresh.flatMap(Evidence::whitelist).filter(l -> !l.isEmpty()).ifPresent(l -> lines.add("Only claimable in: " + names(l) + "."));
        if (usChecked) lines.add("Free in the checked US storefront.");
        lines.add(fresh.isPresent() ? "Other regions: only the restrictions listed by the source are known."
                : "Regional availability not fully verified.");
        return String.join(" ", lines);
    }

    static String names(List<String> codes) {
        var names = new ArrayList<String>();
        for (String code : codes) names.add(Locale.of("", code).getDisplayCountry(Locale.ENGLISH));
        Collections.sort(names);
        if (names.size() <= 8) return String.join(", ", names);
        return String.join(", ", names.subList(0, 8)) + " and " + (names.size() - 8) + " more";
    }
}
