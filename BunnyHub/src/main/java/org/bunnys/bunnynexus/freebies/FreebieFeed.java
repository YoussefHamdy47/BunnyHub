package org.bunnys.bunnynexus.freebies;

import java.net.URI;
import java.time.*;
import java.util.*;

/** Provider-neutral discovery. Provider adapters own validation; no candidate grants approval. */
interface FreebieFeed extends AutoCloseable {
    enum Provider {
        GAMERPOWER("gamerpower", "GamerPower"), EPIC("epic", "Epic Games");
        final String id, label;
        Provider(String id, String label) { this.id = id; this.label = label; }
    }
    enum State { COMPLETE, PARTIAL, FAILED }
    record Candidate(Provider provider, String sourceId, FreebieStore store, String platforms, String title,
                     String description, String instructions, Optional<String> worth, Optional<URI> image,
                     URI claimPage, URI sourcePage, Optional<Instant> startsAt, Optional<Instant> endsAt,
                     String region) {
        public Candidate {
            Objects.requireNonNull(provider); Objects.requireNonNull(sourceId); Objects.requireNonNull(store);
            Objects.requireNonNull(startsAt); Objects.requireNonNull(endsAt); Objects.requireNonNull(region);
        }
        String identity() { return provider.id + ":" + sourceId; }
        static Candidate from(GamerPowerIntake.Candidate c) {
            return new Candidate(Provider.GAMERPOWER, c.sourceItemId(), FreebieStore.fromPlatforms(c.platforms()),
                    c.platforms(), c.title(), c.description(), c.instructions(), c.worth(), c.image(), c.claimLink(),
                    c.sourcePage(), Optional.empty(), FreebieOffer.endInstant(c), "Not specified by source");
        }
    }
    record Result(State state, List<Candidate> candidates, Optional<Duration> retryAfter, String problem) {
        public Result { candidates = List.copyOf(candidates); }
        boolean complete() { return state == State.COMPLETE; }
    }
    Provider provider();
    Result poll();
    @Override void close();
}

