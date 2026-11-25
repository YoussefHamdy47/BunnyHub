package org.bunnys.bunnynexus.deals;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One game on sale at one store, read from that store's own data. Money in whole US cents.
 *
 * @param id      {@code steam:<appid>} or {@code gog:<product id>}: the game at that store
 * @param link    the store's own page for the game (never a third-party redirect)
 * @param rating  Steam: percent positive; GOG: stars in tenths (47 = 4.7 of 5)
 * @param reviews how many reviews the rating is based on
 * @param endsAt  when the sale ends, when the store says
 */
public record Deal(String id, DealStore store, String title, URI link, long saleCents, long normalCents, int percentOff,
                   int rating, int reviews, Optional<Instant> endsAt) {
    public Deal {
        Objects.requireNonNull(id); Objects.requireNonNull(store); Objects.requireNonNull(title);
        Objects.requireNonNull(link); Objects.requireNonNull(endsAt);
    }

    /** "86% positive" on Steam, "rated 4.7/5" on GOG. */
    public String ratingText() {
        return store == DealStore.GOG ? "rated " + rating / 10 + "." + rating % 10 + "/5" : rating + "% positive";
    }
}
