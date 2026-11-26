package org.bunnys.bunnynexus.ratings;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * A game's Steam user rating: Steam's label ("Very Positive"), the share of positive reviews and how many there are.
 * Saved on a free-game offer as {@code rating} so the lookup happens once per game.
 */
public record GameRating(long appId, int percent, long reviews, String label) {
    /** Fewer reviews than this say little; Steam itself shows no score below it. */
    public static final int MIN_REVIEWS = 10;

    public double stars() { return Stars.fromPercent(percent); }

    /** "★★★★¾ **Very Positive** · 92% of 12K Steam reviews" (stars only once the emojis exist). */
    public String line() {
        String stars = Stars.of(stars());
        return (stars.isEmpty() ? "" : stars + " ") + "**" + label + "** · " + percent + "% of " + Stars.count(reviews) + " Steam reviews";
    }

    public Document toDocument(Instant checkedAt) {
        return new Document("source", "steam").append("appId", appId).append("percent", percent).append("reviews", reviews)
                .append("label", label).append("checkedAt", Date.from(checkedAt));
    }

    /** A saved rating, or empty for "looked up, no trustworthy match" and for anything unreadable. */
    public static Optional<GameRating> read(Document d) {
        if (d == null || !"steam".equals(d.getString("source"))) return Optional.empty();
        try {
            var rating = new GameRating(((Number) d.get("appId")).longValue(), ((Number) d.get("percent")).intValue(),
                    ((Number) d.get("reviews")).longValue(), d.getString("label"));
            return rating.label() == null || rating.reviews() < MIN_REVIEWS || rating.percent() < 0 || rating.percent() > 100
                    ? Optional.empty() : Optional.of(rating);
        } catch (RuntimeException unreadable) { return Optional.empty(); }
    }
}
