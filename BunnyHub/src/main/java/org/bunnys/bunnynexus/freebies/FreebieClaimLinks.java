package org.bunnys.bunnynexus.freebies;

import org.bunnys.utils.BunnyLog;

import java.time.Clock;

/**
 * After each GamerPower poll, swaps GamerPower claim links for the store links behind them (see
 * {@link GamerPowerLinks}), a few offers at a time. New giveaways are handled right after they are found, normally
 * before their review message is even posted. An offer whose link can't be resolved keeps GamerPower's link, so its
 * claim button always works; it is tried again on the next polls, up to
 * {@link FreebieRepository#MAX_CLAIM_LINK_ATTEMPTS} times.
 */
final class FreebieClaimLinks implements Runnable {
    /** Bounds the work per poll, so a first start with a long backlog never stalls discovery. */
    static final int PER_POLL = 10;

    private final FreebieRepository repository;
    private final GamerPowerLinks links;
    private final Clock clock;

    FreebieClaimLinks(FreebieRepository repository, GamerPowerLinks links, Clock clock) {
        this.repository = repository; this.links = links; this.clock = clock;
    }

    @Override public void run() {
        int resolved = 0, kept = 0;
        for (var offer : repository.unresolvedClaimLinks(PER_POLL)) {
            var store = links.resolve(offer.claimUrl());
            if (store.isPresent()) { repository.claimLinkResolved(offer.id(), store.get(), clock.instant()); resolved++; }
            else { repository.claimLinkUnresolved(offer.id()); kept++; }
        }
        if (resolved + kept > 0)
            BunnyLog.info("[Freebies] Claim links: " + resolved + " now open the store directly, " + kept + " kept GamerPower's link.");
    }
}
