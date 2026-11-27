package org.bunnys.bunnynexus.freebies;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * GOG's own store as a source ({@link GogIntake}): the home page's section list on every check (about 1 KB), the
 * giveaway section only while there is one, and the catalog once per game. Blocking: call from the discovery thread
 * only.
 */
final class GogFeed implements FreebieFeed {
    private final Clock clock;
    private final ConsoleTips.Http http;
    private final FreebieHttp.Calls calls;
    /** Catalog details per game slug, read once. */
    private final Map<String, Optional<GogIntake.Listing>> listings = new HashMap<>();

    GogFeed(Clock clock) { this(clock, new FreebieHttp.Calls()); }

    private GogFeed(Clock clock, FreebieHttp.Calls calls) {
        this(clock, StoreFetch.create(Set.of(GogIntake.SECTIONS_HOST, GogIntake.CATALOG_HOST), GogIntake.MAX_BYTES, calls), calls);
    }

    GogFeed(Clock clock, ConsoleTips.Http http, FreebieHttp.Calls calls) {
        this.clock = clock; this.http = http; this.calls = calls;
    }

    @Override public Provider provider() { return Provider.GOG; }

    @Override public Result poll() {
        Instant now = clock.instant();
        try {
            var sections = GogIntake.giveaways(http.get(GogIntake.HOME_SECTIONS));
            if (sections.isEmpty()) return failed("invalid GOG home page response");
            List<Candidate> candidates = new ArrayList<>();
            Set<String> live = new HashSet<>();
            for (String id : sections.get()) {
                var giveaway = GogIntake.giveaway(http.get(GogIntake.section(id)), now);
                if (giveaway.isEmpty()) continue;
                var g = giveaway.get();
                live.add(g.slug());
                var listing = listings.get(g.slug());
                if (listing == null) {
                    try {
                        listing = GogIntake.listing(http.get(GogIntake.catalog(g.title())), g.slug());
                        listings.put(g.slug(), listing);
                    } catch (IOException catalogDown) { listing = Optional.empty(); } // Price and art are extras; asked again next check.
                }
                candidates.add(GogIntake.candidate(g, listing));
            }
            listings.keySet().retainAll(live);
            return new Result(State.COMPLETE, candidates, Optional.empty(), "");
        } catch (IOException failure) {
            return failed("network: " + failure.getClass().getSimpleName());
        } catch (RuntimeException invalid) {
            return failed("invalid GOG giveaway response");
        }
    }

    private static Result failed(String problem) { return new Result(State.FAILED, List.of(), Optional.empty(), problem); }

    @Override public void close() { calls.close(); }
}
