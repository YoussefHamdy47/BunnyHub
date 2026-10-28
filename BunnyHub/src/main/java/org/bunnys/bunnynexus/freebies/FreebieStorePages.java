package org.bunnys.bunnynexus.freebies;

import java.util.function.UnaryOperator;

/**
 * Optional (FREEBIE_RESOLVE_GAMERPOWER_LINKS): before a new GamerPower report of an Epic giveaway is matched, its
 * claim redirect is resolved with {@link GamerPowerLinks}; an official Epic product page gives the report a verified
 * storefront identity, so it can merge with Epic's report instead of being held. Any other answer, or none, leaves
 * the report unchanged (it can then only be held for an owner decision, never merged).
 */
final class FreebieStorePages implements UnaryOperator<FreebieFeed.Candidate> {
    private final GamerPowerLinks links;
    FreebieStorePages(GamerPowerLinks links) { this.links = links; }

    @Override public FreebieFeed.Candidate apply(FreebieFeed.Candidate c) {
        if (c.provider() != FreebieFeed.Provider.GAMERPOWER || c.store() != FreebieStore.EPIC || c.storePage().isPresent()) return c;
        return links.resolve(c.claimPage()).filter(page -> EpicLinks.productSlug(page).isPresent()).map(c::withStorePage).orElse(c);
    }
}
