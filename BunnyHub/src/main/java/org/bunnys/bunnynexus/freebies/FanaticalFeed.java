package org.bunnys.bunnynexus.freebies;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Fanatical's own store as a source ({@link FanaticalIntake}). Each check reads the two listings "only if changed"
 * (about 25 KB compressed when they changed, a few hundred bytes when not). Game pages are read only for games the
 * listings name, and again only when a listing changed or a known giveaway's window has passed. Blocking: call from
 * the discovery thread only.
 */
final class FanaticalFeed implements FreebieFeed {
    private final Clock clock;
    private final ConsoleTips.Http http;
    private final FreebieHttp.Calls calls;
    private Set<String> promoted = Set.of(), bannered = Set.of();
    /** The last verdict per named game: its candidate while it is a live giveaway. */
    private final Map<String, Optional<Candidate>> verdicts = new HashMap<>();

    FanaticalFeed(Clock clock) {
        this(clock, new FreebieHttp.Calls());
    }

    private FanaticalFeed(Clock clock, FreebieHttp.Calls calls) {
        this(clock, StoreFetch.create(Set.of(FanaticalIntake.HOST), FanaticalIntake.MAX_BYTES, calls), calls);
    }

    FanaticalFeed(Clock clock, ConsoleTips.Http http, FreebieHttp.Calls calls) {
        this.clock = clock; this.http = http; this.calls = calls;
    }

    @Override public Provider provider() { return Provider.FANATICAL; }

    @Override public Result poll() {
        Instant now = clock.instant();
        try {
            boolean changed = false;
            var promotions = http.ifChanged(FanaticalIntake.PROMOTIONS);
            if (promotions.isPresent()) {
                var slugs = FanaticalIntake.promoted(promotions.get());
                if (slugs.isEmpty()) { http.rollback(); return failed("invalid Fanatical promotions response"); }
                changed |= !slugs.get().equals(promoted);
                promoted = Set.copyOf(slugs.get());
            }
            var site = http.ifChanged(FanaticalIntake.SITE);
            if (site.isPresent()) {
                var slugs = FanaticalIntake.bannered(site.get());
                if (slugs.isEmpty()) { http.rollback(); return failed("invalid Fanatical site response"); }
                changed |= !slugs.get().equals(bannered);
                bannered = Set.copyOf(slugs.get());
            }
            Set<String> named = new LinkedHashSet<>(bannered);
            named.addAll(promoted);
            boolean complete = named.size() <= FanaticalIntake.MAX_GAMES;
            List<String> games = named.stream().limit(FanaticalIntake.MAX_GAMES).toList();
            verdicts.keySet().retainAll(games);
            List<Candidate> candidates = new ArrayList<>();
            int unreadable = 0;
            for (String slug : games) {
                var known = verdicts.get(slug);
                boolean stale = known == null || changed || known.flatMap(Candidate::endsAt).map(end -> !now.isBefore(end)).orElse(false);
                if (stale) {
                    try { known = FanaticalIntake.candidate(http.get(FanaticalIntake.product(slug)), slug, now); }
                    catch (IllegalArgumentException | IOException unreadablePage) { verdicts.remove(slug); unreadable++; continue; }
                    verdicts.put(slug, known);
                }
                known.ifPresent(candidates::add);
            }
            http.commit();
            if (unreadable > 0 || !complete)
                return new Result(State.PARTIAL, candidates, Optional.empty(), unreadable > 0
                        ? "partial feed: " + unreadable + " Fanatical game page(s) unreadable" : "partial feed: more Fanatical games than one check reads");
            return new Result(State.COMPLETE, candidates, Optional.empty(), "");
        } catch (IOException failure) {
            http.rollback();
            return failed("network: " + failure.getClass().getSimpleName());
        } catch (RuntimeException invalid) {
            http.rollback();
            return failed("invalid Fanatical response");
        }
    }

    private static Result failed(String problem) { return new Result(State.FAILED, List.of(), Optional.empty(), problem); }

    @Override public void close() { calls.close(); }
}
