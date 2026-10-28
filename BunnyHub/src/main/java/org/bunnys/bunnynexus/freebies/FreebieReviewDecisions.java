package org.bunnys.bunnynexus.freebies;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Review decisions on one offer: an owner's approve (two clicks), reject, stop and launcher fix, and the
 * automatic approval of launchers on the auto-send list. Each is a guarded state transition in the
 * repository, so a double click or two owners acting at once change it only once. Called through
 * {@link FreebieSystem}, which supplies the fan-out and the ended-card follow-up.
 */
final class FreebieReviewDecisions {
    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final FreebieSubscriptions subscriptions;
    private final FreebieReviewChannel reviews;
    private final Clock clock;
    /** Plans one delivery per subscribed channel (and the DMs). */
    private final Consumer<FreebieOffer> fanOut;
    /** Queues the "withdrawn" edit of cards already posted for a stopped offer. */
    private final Consumer<String> withdrawn;

    FreebieReviewDecisions(FreebieConfig config, FreebieRepository repository, FreebieSubscriptions subscriptions,
                           FreebieReviewChannel reviews, Clock clock, Consumer<FreebieOffer> fanOut, Consumer<String> withdrawn) {
        this.config = config; this.repository = repository; this.subscriptions = subscriptions;
        this.reviews = reviews; this.clock = clock; this.fanOut = fanOut; this.withdrawn = withdrawn;
    }

    /** First click: show exactly how many channels will be messaged and ask for a second confirmation. */
    FreebieSystem.Reply requestApproval(String actorId, String offerId) {
        requireOwner(actorId);
        var offer = repository.offer(offerId).orElse(null);
        if (offer == null) return new FreebieSystem.Text("That offer no longer exists.");
        if (offer.state() != FreebieOffer.State.PENDING) return new FreebieSystem.Text("Already handled: " + offer.state().name().toLowerCase(Locale.ROOT) + ".");
        if (offer.ended(clock.instant())) return new FreebieSystem.Text("This giveaway has already ended.");
        var audience = subscriptions.audience(offer.store());
        return new FreebieSystem.Prompt(FreebieMessages.confirm(offer, audience.channels(), audience.guilds()));
    }

    String confirmApproval(String actorId, String offerId) { return confirmApproval(actorId, offerId, false); }

    /** {@code quiet}: "Send without ping"; the role mention is left out of every post of this game. */
    String confirmApproval(String actorId, String offerId, boolean quiet) {
        requireOwner(actorId);
        Instant now = clock.instant();
        var current = repository.offer(offerId).orElse(null);
        if (current == null) return "That offer no longer exists.";
        if (current.ended(now)) return "This giveaway has already ended; nothing was sent.";
        var approved = repository.approve(offerId, actorId, now, quiet);
        if (approved.isEmpty()) return "Already handled by someone else (or expired). Nothing changed.";
        fanOut.accept(approved.get());
        var refreshed = repository.offer(offerId).orElse(approved.get());
        var counts = repository.counts(offerId);
        String how = quiet ? " without a ping" : "";
        reviews.edit(refreshed, "Approved by <@" + actorId + ">" + how + " — sending to " + counts.open() + " channel(s).",
                FreebieMessages.stopControls(refreshed));
        return "Approved" + how + ". Sending to " + counts.open() + " channel(s); a summary will be posted here when it finishes.";
    }

    String reject(String actorId, String offerId) {
        requireOwner(actorId);
        var rejected = repository.transition(offerId, FreebieOffer.State.PENDING, FreebieOffer.State.REJECTED, actorId, clock.instant());
        if (rejected.isEmpty()) return "Already handled. Nothing changed.";
        reviews.edit(rejected.get(), "Rejected by <@" + actorId + ">. Nothing was sent.", List.of());
        return "Rejected. Nothing was sent.";
    }

    /** Kill switch for one offer: messages already sent stay, everything not yet sent is cancelled. */
    String stop(String actorId, String offerId) {
        requireOwner(actorId);
        Instant now = clock.instant();
        var stopped = repository.stopByOwner(offerId, FreebieOffer.State.APPROVED, actorId, now);
        if (stopped.isEmpty()) stopped = repository.stopByOwner(offerId, FreebieOffer.State.COMPLETED, actorId, now);
        if (stopped.isEmpty()) return "This offer is not currently sending.";
        long cancelled = repository.cancelPending(offerId, "stopped by owner", now);
        repository.dmJobs().cancelForOffer(offerId, now);
        withdrawn.accept(offerId);
        reviews.edit(stopped.get(), "Stopped by <@" + actorId + ">. " + cancelled + " pending message(s) cancelled; summary follows.",
                List.of());
        return "Stopped. " + cancelled + " pending message(s) cancelled. Up to " + FreebieSender.MAX_IN_FLIGHT
                + " already in flight may still arrive.";
    }

    /** Owner fixes a wrongly detected launcher before approving; the confirmation then counts the new audience. */
    String changeStore(String actorId, String offerId, String storeId) {
        requireOwner(actorId);
        var store = FreebieStore.byId(storeId).orElse(null);
        if (store == null) return "Unknown launcher.";
        var changed = repository.changeStore(offerId, store, clock.instant());
        if (changed.isEmpty()) return "Only offers still waiting for review can change launcher.";
        reviews.edit(changed.get(), "Waiting for review (launcher set to " + store.label() + " by <@" + actorId + ">)",
                FreebieMessages.reviewControls(changed.get()));
        return "Launcher changed to **" + store.label() + "**.";
    }

    /** Approval without an owner, for launchers on the auto-send list. Same path as a confirmed approval. */
    void autoApprove(FreebieOffer offer) {
        Instant now = clock.instant();
        var approved = repository.transition(offer.id(), FreebieOffer.State.PENDING, FreebieOffer.State.APPROVED, FreebieSystem.AUTO_ACTOR, now);
        if (approved.isEmpty()) return;
        fanOut.accept(approved.get());
        var refreshed = repository.offer(offer.id()).orElse(approved.get());
        var counts = repository.counts(offer.id());
        reviews.edit(refreshed, "Sent automatically (" + offer.store().label() + " is on the auto-send list) — sending to "
                + counts.open() + " channel(s).", FreebieMessages.stopControls(refreshed));
    }

    private void requireOwner(String actorId) {
        if (!config.isOwner(actorId)) throw new SecurityException("Not a freebie owner.");
    }
}
