package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import org.bson.conversions.Bson;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Field authority when another source reports a giveaway that is already saved. Pure: returns the updates.
 *
 * <ul>
 *   <li>Epic's exact offer is authoritative for title, description, artwork, product page and claim window. While the
 *       giveaway awaits review those fields are replaced (the review is refreshed). After approval the approved text stays
 *       (a difference is recorded as {@code officialTextDiffers}); the official end may only shorten the saved window,
 *       which edits posted cards silently, never lengthen it.</li>
 *   <li>A secondary report (GamerPower) only fills fields that are empty before approval, and records the platforms it
 *       reported. It never changes titles, dates or approval.</li>
 * </ul>
 * Every applied change is kept with its provider and observation time ({@code official}, {@code reported}).
 */
final class FreebieFields {
    private FreebieFields() {}

    static final String EPIC_PLATFORMS = "PC, Epic Games Store";

    /** The official snapshot an Epic report carries; compared without its observation time. */
    static Document official(FreebieFeed.Candidate c) {
        return new Document("identity", c.identity()).append("title", c.title()).append("description", c.description())
                .append("image", c.image().map(URI::toString).orElse(null)).append("pageUrl", c.claimPage().toString())
                .append("startsAt", c.startsAt().map(Date::from).orElse(null)).append("endsAt", c.endsAt().map(Date::from).orElse(null));
    }

    static Optional<String> groupKey(FreebieStore store, Optional<Instant> start) {
        return start.map(s -> "epic|" + store.section().code() + "|" + s.getEpochSecond());
    }

    static List<Bson> changes(Document existing, FreebieFeed.Candidate c, Instant now) {
        List<Bson> changes = new ArrayList<>();
        String state = existing.getString("state");
        if (c.provider() == FreebieFeed.Provider.EPIC) {
            Document official = official(c);
            Document saved = existing.get("official", Document.class);
            Document comparable = saved == null ? null : new Document(saved);
            if (comparable != null) comparable.remove("observedAt");
            boolean identityKnown = existing.get("epicOffer") != null;
            if (official.equals(comparable) && identityKnown) return changes;
            changes.add(set("official", new Document(official).append("observedAt", Date.from(now))));
            c.epicOffer().filter(o -> !identityKnown).ifPresent(o -> changes.add(set("epicOffer",
                    new Document("namespace", o.namespace()).append("offerId", o.offerId()))));
            c.productSlug().filter(s -> existing.getString("productSlug") == null).ifPresent(s -> changes.add(set("productSlug", s)));
            if (existing.getString("groupKey") == null)
                groupKey(FreebieStore.byId(existing.getString("store")).orElse(FreebieStore.OTHER), c.startsAt())
                        .ifPresent(key -> changes.add(set("groupKey", key)));
            switch (state == null ? "" : state) {
                case "PENDING" -> {
                    changes.add(set("title", c.title())); changes.add(set("description", c.description()));
                    if (c.image().isPresent()) changes.add(set("image", c.image().get().toString()));
                    changes.add(set("claimUrl", c.claimPage().toString())); changes.add(set("pageUrl", c.claimPage().toString()));
                    changes.add(set("platforms", EPIC_PLATFORMS)); changes.add(set("region", c.region()));
                    c.startsAt().ifPresent(s -> changes.add(set("startsAt", Date.from(s))));
                    c.endsAt().ifPresent(e -> changes.add(set("endsAt", Date.from(e))));
                    changes.add(set("endsAtEstimated", false));
                    changes.add(inc("revision", 1L)); changes.add(set("reviewRefresh", true));
                }
                case "APPROVED", "COMPLETED" -> {
                    // Approved text, artwork and links stay as reviewed; a difference is only recorded for the owner.
                    boolean differs = !Objects.equals(existing.getString("title"), c.title())
                            || !Objects.equals(existing.getString("description"), c.description());
                    if (differs != existing.getBoolean("officialTextDiffers", false)) changes.add(set("officialTextDiffers", differs));
                    Date savedEnd = existing.getDate("endsAt");
                    boolean shorter = c.endsAt().isPresent() && (savedEnd == null || c.endsAt().get().isBefore(savedEnd.toInstant()));
                    if (shorter) {
                        // Restrictive and automatic: the official end may only shorten the window.
                        changes.add(set("endsAt", Date.from(c.endsAt().get()))); changes.add(set("endsAtEstimated", false));
                        changes.add(inc("cardRevision", 1L));
                    } else if (c.endsAt().isPresent() && savedEnd != null && c.endsAt().get().isAfter(savedEnd.toInstant()))
                        changes.add(set("officialLaterEnd", true)); // A longer window needs a new review; never applied silently.
                }
                default -> { /* Provenance only: a source can never reopen a finished or rejected giveaway. */ }
            }
            return changes;
        }
        // Secondary report: fill empty fields before review, record what it claimed.
        Document reported = existing.get("reported", Document.class);
        Document mine = reported == null ? null : reported.get(c.provider().id, Document.class);
        if (mine == null || !Objects.equals(mine.getString("platforms"), c.platforms()))
            changes.add(set("reported." + c.provider().id, new Document("platforms", c.platforms()).append("observedAt", Date.from(now))
                    .append("identity", c.identity())));
        if ("PENDING".equals(state)) {
            boolean filled = false;
            if (existing.getString("worth") == null && c.worth().isPresent()) { changes.add(set("worth", c.worth().get())); filled = true; }
            if ((existing.getString("instructions") == null || existing.getString("instructions").isBlank())
                    && c.instructions() != null && !c.instructions().isBlank()) { changes.add(set("instructions", c.instructions())); filled = true; }
            if (filled) changes.add(set("reviewRefresh", true));
        }
        return changes;
    }
}
