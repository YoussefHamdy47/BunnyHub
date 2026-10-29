package org.bunnys.bunnynexus.freebies;

import org.bson.Document;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * One discovered giveaway and where it is in the review/delivery lifecycle.
 *
 * <pre>
 * PENDING --approve--> APPROVED --all deliveries finished--> COMPLETED
 *    |                     \--owner stop / offer ended-----> STOPPED
 *    |--reject--> REJECTED
 *    \--ended before review--> EXPIRED
 * </pre>
 */
public record FreebieOffer(String id, String sourceId, FreebieStore store, String platforms, String title,
                           String description, String instructions, Optional<String> worth, Optional<URI> image,
                           URI claimUrl, URI pageUrl, Optional<Instant> endsAt, Instant discoveredAt,
                           State state, Optional<String> reviewMessageId, Optional<String> reviewChannelId, Optional<String> decidedBy,
                           boolean fanoutDone, boolean gone, long revision, boolean endEstimated) {
    public enum State { PENDING, APPROVED, REJECTED, EXPIRED, STOPPED, COMPLETED;
        public boolean open() { return this == PENDING || this == APPROVED; }
    }

    public FreebieOffer {
        Objects.requireNonNull(id); Objects.requireNonNull(store); Objects.requireNonNull(state);
        Objects.requireNonNull(claimUrl); Objects.requireNonNull(pageUrl); Objects.requireNonNull(endsAt);
        Objects.requireNonNull(worth); Objects.requireNonNull(image);
        Objects.requireNonNull(reviewMessageId); Objects.requireNonNull(reviewChannelId); Objects.requireNonNull(decidedBy);
    }

    /** An offer whose end date came from the feed ({@code endEstimated=false}); the usual case. */
    public FreebieOffer(String id, String sourceId, FreebieStore store, String platforms, String title,
                        String description, String instructions, Optional<String> worth, Optional<URI> image,
                        URI claimUrl, URI pageUrl, Optional<Instant> endsAt, Instant discoveredAt,
                        State state, Optional<String> reviewMessageId, Optional<String> reviewChannelId, Optional<String> decidedBy,
                        boolean fanoutDone, boolean gone, long revision) {
        this(id, sourceId, store, platforms, title, description, instructions, worth, image, claimUrl, pageUrl, endsAt,
                discoveredAt, state, reviewMessageId, reviewChannelId, decidedBy, fanoutDone, gone, revision, false);
    }

    public String sourceLabel() { return id.startsWith("epic:") ? "Epic Games" : id.startsWith("steam:") ? "Steam" : "GamerPower"; }
    public String region() {
        return id.startsWith("epic:") ? "US (en-US); availability elsewhere may differ"
                : id.startsWith("steam:") ? "US store; availability elsewhere may differ" : "Not specified by source";
    }

    public static String idFor(String sourceItemId) { return "gamerpower:" + sourceItemId; }

    /**
     * GamerPower end dates carry no timezone. Treat them as UTC and add a small grace so an offer is never cut
     * off early; the "disappeared from the active feed" check stops sends promptly when it really ends.
     */
    static Optional<Instant> endInstant(GamerPowerIntake.Candidate candidate) {
        return candidate.endsLocal().map(local -> local.toInstant(ZoneOffset.UTC).plus(Duration.ofHours(2)));
    }

    /**
     * The end date when it is a real one. GamerPower's {@code end_date} is a real date (read as UTC+2); a record
     * flagged {@code endsAtEstimated} (none are written today) only has a guess, which never ends a card or
     * schedules a reminder by itself.
     */
    public Optional<Instant> realEnd() { return endEstimated ? Optional.empty() : endsAt; }

    /** Ended by date, or no longer listed as active by GamerPower ('gone'). */
    public boolean ended(Instant now) { return gone || endsAt.map(end -> !now.isBefore(end)).orElse(false); }

    static Document newDocument(GamerPowerIntake.Candidate c, Instant now) {
        var doc = new Document("_id", idFor(c.sourceItemId())).append("schemaVersion", 1)
                .append("source", "gamerpower").append("sourceId", c.sourceItemId())
                .append("store", FreebieStore.fromPlatforms(c.platforms()).id()).append("platforms", c.platforms())
                .append("title", c.title()).append("description", c.description()).append("instructions", c.instructions())
                .append("worth", c.worth().orElse(null)).append("image", c.image().map(URI::toString).orElse(null))
                .append("claimUrl", c.claimLink().toString()).append("pageUrl", c.sourcePage().toString())
                .append("endsAt", endInstant(c).map(Date::from).orElse(null))
                .append("discoveredAt", Date.from(now)).append("lastSeenAt", Date.from(now)).append("missingPolls", 0)
                .append("state", State.PENDING.name()).append("reviewMessageId", null)
                .append("fanoutDone", false).append("revision", 1L);
        return doc;
    }

    static FreebieOffer read(Document d) {
        if (d.getInteger("schemaVersion", 0) != 1) throw new IllegalStateException("Unsupported freebie offer schema.");
        return new FreebieOffer(d.getString("_id"), d.getString("sourceId"),
                FreebieStore.byId(d.getString("store")).orElse(FreebieStore.OTHER), d.getString("platforms"),
                d.getString("title"), d.getString("description"), d.getString("instructions"),
                Optional.ofNullable(d.getString("worth")), Optional.ofNullable(d.getString("image")).map(URI::create),
                URI.create(d.getString("claimUrl")), URI.create(d.getString("pageUrl")),
                Optional.ofNullable(d.getDate("endsAt")).map(Date::toInstant), d.getDate("discoveredAt").toInstant(),
                State.valueOf(d.getString("state")), Optional.ofNullable(d.getString("reviewMessageId")),
                Optional.ofNullable(d.getString("reviewChannelId")),
                Optional.ofNullable(d.getString("decidedBy")), d.getBoolean("fanoutDone", false), d.getBoolean("gone", false),
                ((Number) d.get("revision")).longValue(), d.getBoolean("endsAtEstimated", false));
    }
}

