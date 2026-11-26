package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bunnys.bunnynexus.ratings.SteamRatings;
import org.bunnys.utils.BunnyLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Steam user ratings for PC free games, looked up once per game while it waits for review or its first post and saved
 * on the offer ({@code rating}, {@code ratingCheckedAt}). A Steam game is read by its app ID; any other PC game is
 * matched by exact title and publisher ({@link SteamRatings}). No match is saved too, so a game is never looked up
 * twice; a failed request is retried after {@link #RETRY}. Runs on the discovery thread, a few games per run.
 */
final class FreebieRatings {
    /** Lookups per housekeeping run: each is one or two Steam requests. */
    static final int PER_RUN = 3;
    static final Duration RETRY = Duration.ofMinutes(30);
    static final List<String> PC_STORES = Arrays.stream(FreebieStore.values())
            .filter(s -> s.section() == FreebieSection.MAJOR_PC || s.section() == FreebieSection.OTHER_PC).map(FreebieStore::id).toList();
    private static final Pattern STEAM_APP = Pattern.compile("^https://store\\.steampowered\\.com/app/([1-9][0-9]{0,9})(?:[/?#].*)?$");

    /** {@link SteamRatings} in production. */
    interface Source {
        SteamRatings.Lookup forApp(long appId);
        SteamRatings.Lookup forTitle(String title, Optional<String> publisher);
    }

    private final MongoCollection<Document> offers;
    private final Source source;
    private final Clock clock;
    private final Map<String, Instant> failedUntil = new HashMap<>(); // Discovery thread only.

    FreebieRatings(MongoCollection<Document> offers, Source source, Clock clock) {
        this.offers = offers; this.source = source; this.clock = clock;
    }

    /** @return how many games were looked up */
    int run() {
        Instant now = clock.instant();
        failedUntil.values().removeIf(until -> !until.isAfter(now));
        int looked = 0;
        // PC games only: a Steam rating says little about a console or mobile release.
        for (Document d : offers.find(and(in("state", "PENDING", "APPROVED"), in("store", PC_STORES), exists("ratingCheckedAt", false),
                        nin("_id", failedUntil.keySet())))
                .projection(Projections.include("_id", "store", "title", "claimUrl", "pageUrl", "seller")).sort(new Document("discoveredAt", -1))
                .limit(PER_RUN)) {
            looked++;
            var lookup = lookup(d);
            switch (lookup) {
                case SteamRatings.Found found -> offers.updateOne(eq("_id", d.get("_id")),
                        combine(set("rating", found.rating().toDocument(now)), set("ratingCheckedAt", Date.from(now))));
                case SteamRatings.NoMatch none -> offers.updateOne(eq("_id", d.get("_id")),
                        combine(set("ratingCheckedAt", Date.from(now)), set("ratingMiss", none.why())));
                case SteamRatings.Failed failed -> {
                    failedUntil.put(d.getString("_id"), now.plus(RETRY));
                    BunnyLog.warning("[Freebies] Steam rating lookup failed for " + d.getString("_id") + ": " + failed.why());
                }
            }
        }
        return looked;
    }

    private SteamRatings.Lookup lookup(Document d) {
        for (String field : List.of("claimUrl", "pageUrl")) {
            var app = steamApp(d.getString(field));
            if (app.isPresent()) return source.forApp(app.getAsLong());
        }
        // GamerPower titles carry the store and "Giveaway": "Bread & Fred (Epic Games) Giveaway" is "Bread & Fred".
        String title = FreebieMessages.title(Objects.requireNonNullElse(d.getString("title"), "")).replaceAll("\\s*\\([^()]*\\)$", "").strip();
        return source.forTitle(title, Optional.ofNullable(d.getString("seller")));
    }

    static OptionalLong steamApp(String url) {
        if (url == null) return OptionalLong.empty();
        var m = STEAM_APP.matcher(url);
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
