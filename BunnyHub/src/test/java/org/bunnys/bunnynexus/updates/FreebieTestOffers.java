package org.bunnys.bunnynexus.updates;

import org.bunnys.bunnynexus.freebies.FreebieOffer;
import org.bunnys.bunnynexus.freebies.FreebieStore;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/** Live approved offers for the ad tests. */
final class FreebieTestOffers {
    private FreebieTestOffers() {}

    static FreebieOffer offer(String id, FreebieStore store, String title, String worth) {
        URI page = URI.create("https://example.com/" + id.replace(':', '-'));
        return new FreebieOffer(id, id, store, "PC", title, "", "", Optional.of(worth), Optional.empty(), page, page,
                Optional.of(Instant.parse("2026-11-30T16:00:00Z")), Instant.parse("2026-11-23T12:00:00Z"), FreebieOffer.State.APPROVED,
                Optional.empty(), Optional.empty(), Optional.of("owner"), true, false, 1);
    }
}
