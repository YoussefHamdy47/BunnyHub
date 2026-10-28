package org.bunnys.bunnynexus.freebies;

import org.bunnys.utils.BunnyLog;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Owner approvals. A game of a verified Epic release (it has a {@code groupKey} and grouped announcements are on) is
 * approved only through a release snapshot ({@link FreebieReleases}): "Approve" commits a snapshot of that one game,
 * "Approve release" a snapshot of every pending game shown. Other games keep the single conditional approval.
 *
 * <p>After a commit the snapshot is reconciled: each member still pending at the reviewed revision becomes APPROVED,
 * its route is reserved under the release key and its deliveries are planned. A member that changed after review is
 * withdrawn from the approval instead. Housekeeping repeats reconciliation after a crash; every step is conditional
 * and idempotent.
 */
final class FreebieApprovals {
    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final FreebieReviewChannel reviews;
    private final FreebieFeatures features;
    private final Consumer<FreebieOffer> fanOut;
    private final Clock clock;

    FreebieApprovals(FreebieRepository repository, FreebieSubscriptions subscriptions, FreebieReviewChannel reviews,
                     FreebieFeatures features, Consumer<FreebieOffer> fanOut, Clock clock) {
        this.repository = repository; this.subscriptions = subscriptions; this.reviews = reviews;
        this.features = features; this.fanOut = fanOut; this.clock = clock;
    }

    /** The release key that governs this offer's approval, if any. */
    Optional<String> releaseOf(FreebieOffer offer) {
        if (!features.grouped() || repository.releases() == null) return Optional.empty();
        var facts = repository.facts(offer.id());
        return facts == null ? Optional.empty() : facts.groupKey();
    }

    boolean isRelease(String offerId) {
        return repository.offer(offerId).flatMap(this::releaseOf).isPresent();
    }

    /**
     * Auto-send launchers: a game of an Epic release is approved by the system through a one-game release snapshot,
     * so it posts under the release key and later games of the release join that post silently. False when the game
     * is not part of a release (the caller then uses the normal automatic approval).
     */
    boolean autoApprove(FreebieOffer offer) {
        var release = releaseOf(offer);
        if (release.isEmpty()) return false;
        Instant now = clock.instant();
        if (offer.state() != FreebieOffer.State.PENDING || offer.ended(now)) return true;
        var token = repository.releases().preview(release.get(), List.of(offer), FreebieSystem.AUTO_ACTOR, now);
        if (token.isEmpty()) return true; // Another writer moved the release; the next housekeeping run tries again.
        var commit = repository.releases().commit(release.get(), token.get(), FreebieSystem.AUTO_ACTOR, List.of(offer), now);
        commit.snapshot().ifPresent(this::reconcile);
        return true;
    }

    // ------------------------------------------------------------------ first click

    FreebieSystem.Reply request(String actor, String offerId, boolean wholeRelease) {
        Instant now = clock.instant();
        var offer = repository.offer(offerId).orElse(null);
        if (offer == null) return new FreebieSystem.Text("That offer no longer exists.");
        if (offer.state() != FreebieOffer.State.PENDING) return new FreebieSystem.Text("Already handled: " + offer.state().name().toLowerCase(Locale.ROOT) + ".");
        if (offer.ended(now)) return new FreebieSystem.Text("This giveaway has already ended.");
        var audience = subscriptions.audience(offer.store());
        var release = releaseOf(offer);
        if (release.isEmpty()) return new FreebieSystem.Prompt(FreebieMessages.confirm(offer, audience.channels(), audience.guilds()));
        var members = members(offer, wholeRelease, now);
        var token = repository.releases().preview(release.get(), members, actor, now);
        if (token.isEmpty()) return new FreebieSystem.Text("Another approval of this release is being saved. Try again.");
        return new FreebieSystem.Prompt(FreebieGroupApproval.confirm(offer, members, wholeRelease, token.get(), audience.channels(), audience.guilds()));
    }

    /** The games a release confirmation covers: this game alone, or every pending, live game of its release. */
    List<FreebieOffer> members(FreebieOffer offer, boolean wholeRelease, Instant now) {
        List<FreebieOffer> members = new ArrayList<>(List.of(offer));
        if (wholeRelease) members.addAll(repository.routes().groupMembers(offer, now));
        return members;
    }

    // ------------------------------------------------------------------ confirmation

    /** Legacy single confirmation; refused for release games, which must be confirmed through their snapshot. */
    String confirmSingle(String actor, String offerId) {
        Instant now = clock.instant();
        var current = repository.offer(offerId).orElse(null);
        if (current == null) return "That offer no longer exists.";
        if (current.ended(now)) return "This giveaway has already ended; nothing was sent.";
        if (releaseOf(current).isPresent()) return "This game belongs to an Epic release. Press Approve again to review it; nothing changed.";
        var approved = repository.transition(offerId, FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, actor, now);
        if (approved.isEmpty()) return "Already handled by someone else (or expired). Nothing changed.";
        fanOut.accept(approved.get());
        var refreshed = repository.offer(offerId).orElse(approved.get());
        var counts = repository.counts(offerId);
        reviews.edit(refreshed, "Approved by <@" + actor + "> — sending to " + counts.open() + " channel(s).", FreebieMessages.stopControls(refreshed));
        return "Approved. Sending to " + counts.open() + " channel(s); a summary will be posted here when it finishes.";
    }

    /** Commits the reviewed snapshot if nothing changed, then reconciles it. */
    String confirmRelease(String actor, String offerId, boolean wholeRelease, String token) {
        return confirmRelease(actor, offerId, wholeRelease, token, false);
    }

    /** {@code quiet}: "Send without ping" for every game of the snapshot. */
    String confirmRelease(String actor, String offerId, boolean wholeRelease, String token, boolean quiet) {
        Instant now = clock.instant();
        var offer = repository.offer(offerId).orElse(null);
        if (offer == null) return "That offer no longer exists.";
        var release = releaseOf(offer);
        if (release.isEmpty()) return "This approval is no longer valid. Press Approve again.";
        // Recomputed with the preview's rule; a handled or ended game yields a different list, so the commit is refused.
        var current = offer.state() == FreebieOffer.State.PENDING && !offer.ended(now) ? members(offer, wholeRelease, now) : List.<FreebieOffer>of();
        var commit = repository.releases().commit(release.get(), token, actor, current, now, quiet);
        return switch (commit.outcome()) {
            case STALE -> "The release changed since you opened this (a game was added, changed, handled or ended). Nothing was approved; open it again.";
            case EXPIRED -> "This confirmation expired. Nothing was approved; open it again.";
            case NOT_YOURS -> "Only the owner who opened this confirmation can use it. Nothing changed.";
            case RACED -> "Another approval of this release was saved first, or this confirmation was replaced. Nothing changed by this click.";
            case COMMITTED -> {
                var snapshot = commit.snapshot().orElseThrow();
                var result = reconcile(snapshot);
                yield FreebieGroupApproval.result(result.approved(), result.withdrawn(), result.channels());
            }
        };
    }

    record Reconciled(List<FreebieOffer> approved, int withdrawn, long channels) {}

    /**
     * Applies a committed snapshot to per-game state: reviewed-and-unchanged pending members become APPROVED; changed
     * ones are withdrawn from the approval; already approved ones are only planned. Safe to repeat.
     */
    Reconciled reconcile(FreebieReleases.Snapshot snapshot) {
        Instant now = clock.instant();
        List<FreebieOffer> approved = new ArrayList<>();
        int withdrawn = 0;
        for (var member : snapshot.members()) {
            var offer = repository.offer(member.offerId()).orElse(null);
            if (offer == null) continue;
            // The release route is reserved before the state changes, so a crash in between can never let the per-game
            // APPROVED state plan this game under its own key.
            repository.routes().route(offer.id(), true, Optional.of(snapshot.key()));
            if (offer.state() == FreebieOffer.State.PENDING) {
                var changed = offer.ended(now) ? Optional.<FreebieOffer>empty()
                        : repository.approveReviewed(offer.id(), member.revision(), snapshot.actor(), snapshot.at(), snapshot.quiet());
                if (changed.isEmpty()) {
                    repository.releases().withdraw(snapshot.key(), offer.id(), now);
                    withdrawn++;
                    continue;
                }
                offer = changed.get();
            }
            if (offer.state() == FreebieOffer.State.APPROVED) {
                fanOut.accept(offer);
                approved.add(offer);
            }
        }
        repository.releases().reconciled(snapshot);
        long channels = 0;
        for (var offer : approved) {
            var refreshed = repository.offer(offer.id()).orElse(offer);
            var counts = repository.counts(offer.id());
            channels = Math.max(channels, counts.open());
            reviews.edit(refreshed, "Approved by <@" + snapshot.actor() + "> with its release" + (snapshot.quiet() ? " without a ping" : "")
                            + " — sending to " + counts.open() + " channel(s).",
                    FreebieMessages.stopControls(refreshed));
        }
        return new Reconciled(approved, withdrawn, channels);
    }

    /** Housekeeping: finishes snapshots a crash left unreconciled. */
    void recover() {
        if (repository.releases() == null) return;
        for (var snapshot : repository.releases().unreconciled(20)) {
            var result = reconcile(snapshot);
            if (!result.approved().isEmpty() || result.withdrawn() > 0)
                BunnyLog.info("[Freebies] Recovered release approval " + snapshot.key() + " v" + snapshot.version() + ": "
                        + result.approved().size() + " planned, " + result.withdrawn() + " withdrawn.");
        }
    }

    /**
     * Whether an approved offer may be planned: a release-keyed route needs the release snapshot; any other offer is
     * governed by its own approval.
     */
    boolean authorized(FreebieOffer offer) {
        var key = repository.routes() == null ? Optional.<String>empty() : repository.routes().announcementKey(offer.id());
        return key.filter(k -> k.startsWith("epic|")).map(k -> repository.releases() != null && repository.releases().approved(k, offer.id()))
                .orElse(true);
    }
}
