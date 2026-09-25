package org.bunnys.bunnynexus.alerts.domain;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Inactive pure review rules. Durable CAS/audit and a transactional publication guard are still required. */
public final class PublicationReviewPolicy {
    public static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(15);
    public static final Duration MAX_APPROVAL_LIFETIME = Duration.ofDays(7);
    public enum State { PENDING, APPROVED, REJECTED }
    public enum Gate { ALLOWED, REVIEW_REQUIRED, REJECTED, CHANGED, EXPIRED, INELIGIBLE }
    public record Decision(String operatorId, Instant at, Instant validUntil) {
        public Decision {
            operatorId = AlertIdentity.snowflake(operatorId);
            Objects.requireNonNull(at); Objects.requireNonNull(validUntil);
            if (!at.isBefore(validUntil) || Duration.between(at, validUntil).compareTo(MAX_APPROVAL_LIFETIME) > 0)
                throw new IllegalArgumentException("Invalid review decision window.");
        }
    }
    public record Review(String id, long revision, AlertIdentity.OfferKey offerKey, String materialHash,
                         Instant createdAt, Instant previewValidUntil, State state, Optional<Decision> decision) {
        public Review {
            id = AlertIdentity.token(id); Objects.requireNonNull(offerKey); Objects.requireNonNull(createdAt);
            Objects.requireNonNull(previewValidUntil); Objects.requireNonNull(state); Objects.requireNonNull(decision);
            if (revision < 1 || materialHash == null || !materialHash.matches("[a-f0-9]{64}")
                    || !createdAt.isBefore(previewValidUntil)
                    || Duration.between(createdAt, previewValidUntil).compareTo(PREVIEW_LIFETIME) > 0
                    || (state == State.PENDING) != decision.isEmpty())
                throw new IllegalArgumentException("Invalid publication review.");
            decision.ifPresent(d -> {
                if (d.at().isBefore(createdAt) || !d.at().isBefore(previewValidUntil))
                    throw new IllegalArgumentException("Decision outside preview window.");
            });
        }
    }
    private final Set<String> operators;
    private final FreeGamePolicy eligibility;
    public PublicationReviewPolicy(Set<String> operators, FreeGamePolicy eligibility) {
        this.operators = Set.copyOf(operators); this.eligibility = Objects.requireNonNull(eligibility);
        if (this.operators.isEmpty() || this.operators.size() > 100 || eligibility.allowUnknownEnd())
            throw new IllegalArgumentException("Explicit operators and known-end eligibility required.");
        this.operators.forEach(AlertIdentity::snowflake);
    }
    public Review preview(String id, Offer offer, AlertDisplayContent content, Instant now) {
        Objects.requireNonNull(now);
        return new Review(id, 1, offer.key(), materialHash(offer, content), now, now.plus(PREVIEW_LIFETIME), State.PENDING, Optional.empty());
    }
    /** Actor ID must come from a verified interaction; a server administrator is not automatically an operator. */
    public Review decide(Review review, long expectedRevision, String operatorId, boolean approve,
                         Offer offer, AlertDisplayContent content, Instant now) {
        if (!operators.contains(AlertIdentity.snowflake(operatorId))) throw new SecurityException("Review operator required.");
        Objects.requireNonNull(now);
        if (review.revision() != expectedRevision || review.state() != State.PENDING)
            throw new IllegalStateException("Review already decided or changed.");
        if (now.isBefore(review.createdAt()) || !now.isBefore(review.previewValidUntil()))
            throw new IllegalStateException("Review preview expired.");
        if (!review.offerKey().equals(offer.key()) || !review.materialHash().equals(materialHash(offer, content)))
            throw new IllegalStateException("Reviewed material changed.");
        if (approve && !eligibility.evaluate(offer, now).eligible())
            throw new IllegalStateException("Approval cannot override unverified or expired evidence.");
        Instant until = now.plus(MAX_APPROVAL_LIFETIME);
        if (approve && offer.endsAt().orElseThrow().isBefore(until)) until = offer.endsAt().orElseThrow();
        return new Review(review.id(), Math.incrementExact(review.revision()), review.offerKey(), review.materialHash(),
                review.createdAt(), review.previewValidUntil(), approve ? State.APPROVED : State.REJECTED,
                Optional.of(new Decision(operatorId, now, until)));
    }
    /** Re-run against current records before publication AND send; approval never replaces fresh eligibility. */
    public Gate evaluate(Optional<Review> review, Offer offer, AlertDisplayContent content, Instant now) {
        Objects.requireNonNull(review); Objects.requireNonNull(now);
        String hash = materialHash(offer, content);
        if (!eligibility.evaluate(offer, now).eligible()) return Gate.INELIGIBLE;
        if (review.isEmpty()) return Gate.REVIEW_REQUIRED;
        Review current = review.get();
        if (!current.offerKey().equals(offer.key()) || !current.materialHash().equals(hash)) return Gate.CHANGED;
        if (current.state() == State.REJECTED) return Gate.REJECTED;
        if (current.state() != State.APPROVED) return Gate.REVIEW_REQUIRED;
        var decision = current.decision().orElseThrow();
        if (!operators.contains(decision.operatorId())) return Gate.REVIEW_REQUIRED;
        if (now.isBefore(decision.at()) || !now.isBefore(decision.validUntil())) return Gate.EXPIRED;
        return Gate.ALLOWED;
    }
    /** Material fingerprint, NOT promotion identity. Freshness-only revision changes preserve human approval. */
    public static String materialHash(Offer offer, AlertDisplayContent content) {
        Objects.requireNonNull(offer); Objects.requireNonNull(content);
        if (!content.offerKey().equals(offer.key()) || content.contentRevision() != offer.contentRevision())
            throw new IllegalArgumentException("Display revision/offer mismatch.");
        var e = offer.evidence(); var k = offer.key();
        return StableIdentity.hash("alert-review-material-v1", k.store().value(), k.campaignKey(), k.market().value(),
                offer.editionId(), offer.kind().name(), e.market().name(), e.entitlement().name(), e.claimConditions().name(),
                e.approvedLink().name(), e.source().value(), e.sourceItemId(), e.identityRuleVersion(),
                offer.price().map(p -> p.minorUnits() + ":" + p.currency().getCurrencyCode()).orElse("absent"),
                offer.startsAt().map(Instant::toString).orElse("absent"), offer.endsAt().map(Instant::toString).orElse("absent"),
                digest(content.title()), digest(content.description()), digest(content.claimUrl().toASCIIString()),
                digest(content.sourceName()), digest(content.attributionUrl().toASCIIString()));
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
