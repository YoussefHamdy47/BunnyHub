package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.bunnys.bunnynexus.alerts.AlertFixtures.*;
import static org.bunnys.bunnynexus.alerts.domain.PublicationReviewPolicy.*;

class PublicationReviewPolicyTest {
    final PublicationReviewPolicy policy = new PublicationReviewPolicy(Set.of("900"), POLICY);
    AlertDisplayContent content(Offer offer) {
        return new AlertDisplayContent(offer.key(), offer.contentRevision(), "Game", "Description",
                URI.create("https://store.example.com/game"), "Source", URI.create("https://source.example.com/"));
    }
    Review pending() { return policy.preview("review", offer(), content(offer()), NOW); }
    Review approved() { return policy.decide(pending(), 1, "900", true, offer(), content(offer()), NOW); }
    @Test void launchRequiresAnExplicitDecisionFromAnOperator() {
        assertEquals(Gate.REVIEW_REQUIRED, policy.evaluate(Optional.empty(), offer(), content(offer()), NOW));
        assertEquals(Gate.REVIEW_REQUIRED, policy.evaluate(Optional.of(pending()), offer(), content(offer()), NOW));
        assertThrows(SecurityException.class, () -> policy.decide(pending(), 1, "100", true, offer(), content(offer()), NOW));
        assertEquals(Gate.ALLOWED, policy.evaluate(Optional.of(approved()), offer(), content(offer()), NOW));
        assertEquals(offer().endsAt().orElseThrow(), approved().decision().orElseThrow().validUntil());
    }
    @Test void stalePreviewRevisionRepeatedDecisionAndExpiredConfirmationFail() {
        assertThrows(IllegalStateException.class, () -> policy.decide(pending(), 2, "900", true, offer(), content(offer()), NOW));
        assertThrows(IllegalStateException.class, () -> policy.decide(approved(), 2, "900", true, offer(), content(offer()), NOW));
        assertThrows(IllegalStateException.class, () -> policy.decide(pending(), 1, "900", true, offer(), content(offer()), NOW.plus(PREVIEW_LIFETIME)));
        assertThrows(IllegalStateException.class, () -> policy.decide(pending(), 1, "900", true, offer(), content(offer()), NOW.minusSeconds(1)));
    }
    @Test void titleAndClaimLinkChangesRequireAnotherHumanReview() {
        var c = content(offer());
        for (var changed : List.of(
                new AlertDisplayContent(c.offerKey(), 1, "Another title", c.description(), c.claimUrl(), c.sourceName(), c.attributionUrl()),
                new AlertDisplayContent(c.offerKey(), 1, c.title(), c.description(), URI.create("https://store.example.com/other"), c.sourceName(), c.attributionUrl()))) {
            assertEquals(Gate.CHANGED, policy.evaluate(Optional.of(approved()), offer(), changed, NOW));
            assertThrows(IllegalStateException.class, () -> policy.decide(pending(), 1, "900", true, offer(), changed, NOW));
        }
    }
    @Test void freshnessRefreshPreservesApprovalButStaleEvidenceStillBlocks() {
        var old = offer();
        var refreshed = new Offer(old.key(), old.editionId(), old.kind(), old.evidence(), old.price(), old.startsAt(), old.endsAt(), NOW.plusSeconds(600), 2);
        assertEquals(Gate.INELIGIBLE, policy.evaluate(Optional.of(approved()), old, content(old), NOW.plusSeconds(600)));
        assertEquals(Gate.ALLOWED, policy.evaluate(Optional.of(approved()), refreshed, content(refreshed), NOW.plusSeconds(600)));
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(Optional.of(approved()), refreshed, content(old), NOW.plusSeconds(600)));
    }
    @Test void approvalCannotOverridePriceUnknownProofOrUnknownEnd() {
        var o = offer(); var e = o.evidence();
        var unknown = new Offer.Evidence(Offer.Proof.UNKNOWN, e.entitlement(), e.claimConditions(), e.approvedLink(), e.source(), e.sourceItemId(), e.identityRuleVersion());
        for (var invalid : List.of(
                with(o.kind(), unknown, o.price(), o.startsAt(), o.endsAt(), NOW),
                with(o.kind(), e, Optional.of(new Offer.Money(100, Currency.getInstance("USD"))), o.startsAt(), o.endsAt(), NOW),
                with(o.kind(), e, o.price(), o.startsAt(), Optional.empty(), NOW))) {
            var preview = policy.preview("review", invalid, content(invalid), NOW);
            assertThrows(IllegalStateException.class, () -> policy.decide(preview, 1, "900", true, invalid, content(invalid), NOW));
            assertEquals(Gate.INELIGIBLE, policy.evaluate(Optional.of(approved()), invalid, content(invalid), NOW));
        }
    }
    @Test void changedDeadlineOrCampaignCannotReuseApproval() {
        var o = offer();
        var extended = with(o.kind(), o.evidence(), o.price(), o.startsAt(), Optional.of(NOW.plusSeconds(7200)), NOW);
        var foreign = new Offer(new AlertIdentity.OfferKey(o.key().store(), "different-campaign", o.key().market()), o.editionId(),
                o.kind(), o.evidence(), o.price(), o.startsAt(), o.endsAt(), NOW, 1);
        for (var changed : List.of(extended, foreign))
            assertEquals(Gate.CHANGED, policy.evaluate(Optional.of(approved()), changed, content(changed), NOW));
    }
    @Test void rejectionAndRemovedOperatorBlockPublication() {
        var rejected = policy.decide(pending(), 1, "900", false, offer(), content(offer()), NOW);
        assertEquals(Gate.REJECTED, policy.evaluate(Optional.of(rejected), offer(), content(offer()), NOW));
        var replacedOperator = new PublicationReviewPolicy(Set.of("901"), POLICY);
        assertEquals(Gate.REVIEW_REQUIRED, replacedOperator.evaluate(Optional.of(approved()), offer(), content(offer()), NOW));
    }
    @Test void approvalExpiresAfterSevenDaysEvenWithFreshEvidence() {
        var o = offer();
        var longOffer = with(o.kind(), o.evidence(), o.price(), o.startsAt(), Optional.of(NOW.plus(Duration.ofDays(10))), NOW);
        var approval = policy.decide(policy.preview("long", longOffer, content(longOffer), NOW), 1, "900", true, longOffer, content(longOffer), NOW);
        var later = new Offer(longOffer.key(), longOffer.editionId(), longOffer.kind(), longOffer.evidence(), longOffer.price(),
                longOffer.startsAt(), longOffer.endsAt(), NOW.plus(MAX_APPROVAL_LIFETIME), 2);
        assertEquals(Gate.EXPIRED, policy.evaluate(Optional.of(approval), later, content(later), NOW.plus(MAX_APPROVAL_LIFETIME)));
    }
}
